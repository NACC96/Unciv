#!/usr/bin/env python3
"""Exercise the real Atlas wrappers with a synthetic Git tree and fake Gradle."""

import contextlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
EVIDENCE = Path("/Volumes/Lexar/Unciv/.superpowers/civ6-atlas")
HARNESS_SOURCES = (
    "tests/src/com/unciv/dev/AtlasFixtureCatalog.kt",
    "tests/src/com/unciv/dev/AtlasRenderCheck.kt",
    "tests/src/com/unciv/dev/AtlasPerformanceCheck.kt",
    "tests/src/com/unciv/ui/components/AtlasCoverageTest.kt",
)


class AtlasWrapperTest(unittest.TestCase):
    @contextlib.contextmanager
    def fixture(self):
        scratch = EVIDENCE / "wrapper-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="synthetic-", dir=scratch) as temp:
            root = Path(temp)
            repo = root / "repo"
            scripts = repo / "scripts/atlas"
            scripts.mkdir(parents=True)
            for name in ("check.sh", "render.sh", "build.gradle", "paths.sh"):
                source = REPO / "scripts/atlas" / name
                if source.exists():
                    shutil.copy2(source, scripts / name)
            inputs = (
                "core/src/SyntheticActor.kt", "desktop/src/SyntheticFont.kt",
                "android/assets/SyntheticMap.json", "build.gradle.kts",
                "tests/build.gradle.kts", "docs/atlas/screen-coverage.json",
                *HARNESS_SOURCES,
            )
            for name in inputs:
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic committed input\n")
            (repo / ".gitignore").write_text(".superpowers/\n")
            gradle = repo / "gradlew"
            gradle.write_text(
                '#!/usr/bin/env bash\n'
                'printf "%s\\n" "$@" > "$ATLAS_TEST_CALL"\n'
                'echo "FAKE_GRADLE boundary reached in $PWD; no compiler ran"\n'
                'echo "FAKE_WARNING must remain visible" >&2\n'
                'exit "${ATLAS_TEST_EXIT:-0}"\n'
            )
            gradle.chmod(0o755)
            self.git(repo, "init", "-q")
            self.git(repo, "add", ".")
            self.git(repo, "-c", "user.name=Atlas wrapper test",
                     "-c", "user.email=atlas-wrapper@example.invalid",
                     "-c", "commit.gpgsign=false", "commit", "-qm", "synthetic baseline")
            yield root, repo

    def git(self, repo, *args):
        return subprocess.run(
            ["git", "-c", "core.hooksPath=/dev/null", "-C", str(repo), *args],
            check=True, capture_output=True, text=True,
        )

    def run_wrapper(self, root, repo, name, *args, output=None, gradle_exit=0):
        env = os.environ.copy()
        env.pop("UNCIV_ATLAS_OUTPUT", None)
        env["ATLAS_TEST_CALL"] = str(root / "gradle-called.txt")
        env["ATLAS_TEST_EXIT"] = str(gradle_exit)
        if output is not None:
            env["UNCIV_ATLAS_OUTPUT"] = str(output)
        return subprocess.run(
            ["bash", str(repo / "scripts/atlas" / name), *map(str, args)],
            cwd=repo, env=env, capture_output=True, text=True,
        )

    def assert_rejected_before_gradle(self, result, root):
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertFalse((root / "gradle-called.txt").exists())

    def test_standalone_defaults_to_approved_lexar_log_root(self):
        logs = EVIDENCE / "checks"
        before = set(logs.glob("gradle-*.log"))
        with self.fixture() as (root, repo):
            result = self.run_wrapper(root, repo, "check.sh", "*AtlasCoverageTest")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["-I", str(repo / "scripts/atlas/build.gradle"), "--console=plain",
                 ":tests:test", "--tests", "*AtlasCoverageTest"],
                (root / "gradle-called.txt").read_text().splitlines(),
            )
            added = set(logs.glob("gradle-*.log")) - before
            self.assertEqual(1, len(added), "standalone log must use the approved default")
            log = added.pop()
            self.assertIn("FAKE_WARNING must remain visible", log.read_text())
            self.assertIn("FAKE_WARNING must remain visible", result.stdout)
            self.assertFalse((repo / ".superpowers/civ6-atlas/checks").exists())
            print(f"Verified standalone default log: {log}")

    def test_check_honors_approved_explicit_destination(self):
        with self.fixture() as (root, repo):
            output = root / "explicit-logs"
            result = self.run_wrapper(root, repo, "check.sh", "*AtlasCoverageTest", output=output)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(1, len(list(output.glob("gradle-*.log"))))

    def test_check_rejects_relative_and_external_destinations(self):
        for output in (".", "/dev/null"):
            with self.subTest(output=output), self.fixture() as (root, repo):
                result = self.run_wrapper(root, repo, "check.sh", "*AtlasCoverageTest", output=output)
                self.assert_rejected_before_gradle(result, root)
                self.assertIn("Lexar", result.stderr)

    def test_render_rejects_external_output(self):
        with self.fixture() as (root, repo):
            result = self.run_wrapper(root, repo, "render.sh", "world", "--output", "/dev/null")
            self.assert_rejected_before_gradle(result, root)
            self.assertIn("Lexar", result.stderr)

    def test_render_rejects_symlink_escape(self):
        with self.fixture() as (root, repo):
            link = root / "outside"
            link.symlink_to("/dev", target_is_directory=True)
            result = self.run_wrapper(root, repo, "render.sh", "world", "--output", link / "null")
            self.assert_rejected_before_gradle(result, root)
            self.assertIn("Lexar", result.stderr)

    def test_render_rejects_dirty_production_and_build_inputs(self):
        for name in ("core/src/SyntheticActor.kt", "desktop/src/SyntheticFont.kt",
                     "android/assets/SyntheticMap.json", "build.gradle.kts", "tests/build.gradle.kts"):
            with self.subTest(path=name), self.fixture() as (root, repo):
                (repo / name).write_text("uncommitted render input\n")
                output = root / "captures"
                result = self.run_wrapper(root, repo, "render.sh", "world", "--output", output, gradle_exit=77)
                self.assert_rejected_before_gradle(result, root)
                self.assertFalse(output.exists(), "preflight must precede output creation")
                self.assertIn(name, result.stderr)
                self.assertIn("commit", result.stderr.lower())

    def test_render_rejects_staged_input(self):
        with self.fixture() as (root, repo):
            (repo / "core/src/SyntheticActor.kt").write_text("staged render input\n")
            self.git(repo, "add", "core/src/SyntheticActor.kt")
            result = self.run_wrapper(root, repo, "render.sh", "performance", "--output", root / "captures", gradle_exit=77)
            self.assert_rejected_before_gradle(result, root)

    def test_render_rejects_untracked_asset(self):
        with self.fixture() as (root, repo):
            (repo / "android/assets/UntrackedMap.json").write_text("untracked render input\n")
            result = self.run_wrapper(root, repo, "render.sh", "world", "--output", root / "captures", gradle_exit=77)
            self.assert_rejected_before_gradle(result, root)
            self.assertIn("android/assets/UntrackedMap.json", result.stderr)

    def test_render_allows_clean_inputs_and_preserved_harness_changes(self):
        with self.fixture() as (root, repo):
            for dirty_harness in (False, True):
                with self.subTest(dirty_harness=dirty_harness):
                    if dirty_harness:
                        (repo / HARNESS_SOURCES[0]).write_text("fully hashed harness input\n")
                    result = self.run_wrapper(root, repo, "render.sh", "world", "--output", root / "captures", gradle_exit=77)
                    self.assertEqual(77, result.returncode, result.stdout + result.stderr)
                    self.assertTrue((root / "gradle-called.txt").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
