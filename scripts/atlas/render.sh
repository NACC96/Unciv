#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source "$repo/scripts/atlas/paths.sh"
id=${1:-}; shift || true
output=; matrix=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output) output=${2:-}; shift 2 ;;
        --matrix) matrix=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
[[ -n $id && $output == /* ]] || { echo 'Usage: render.sh <id|performance> --output <absolute path> [--matrix]' >&2; exit 2; }
output=$(atlas_evidence_path "$output")
# HEAD identifies production/build inputs. Only the complete, separately hashed
# Atlas harness may be dirty; reused test fixtures must also match HEAD.
dirty_inputs=$(git -C "$repo" status --porcelain=v1 --untracked-files=all -- \
    core desktop android server buildSrc gradle tests agent-protocol agent-runner extraImages \
    ':(top)build.gradle*' ':(top)settings.gradle*' gradle.properties ':(top)gradlew*' \
    ':(exclude)tests/src/com/unciv/dev/AtlasFixtureCatalog.kt' \
    ':(exclude)tests/src/com/unciv/dev/AtlasRenderCheck.kt' \
    ':(exclude)tests/src/com/unciv/dev/AtlasPerformanceCheck.kt' \
    ':(exclude)tests/src/com/unciv/ui/components/AtlasCoverageTest.kt')
if [[ -n $dirty_inputs ]]; then
    printf 'Atlas rendering requires committed production/build inputs. Dirty paths:\n%s\n' "$dirty_inputs" >&2
    echo 'Commit the listed changes with git add <paths> and git commit, then rerun render.sh.' >&2
    exit 2
fi
mkdir -p "$output"
worktree_id=$(printf '%s' "$repo" | shasum -a 256 | cut -c1-12)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export UNCIV_ATLAS_BUILD_ROOT=${UNCIV_ATLAS_BUILD_ROOT:-/Users/noahkenny/.unciv-atlas-build/$worktree_id}
export UNCIV_ATLAS_OUTPUT=$output/logs
"$repo/scripts/atlas/check.sh" --gradle :tests:testClasses :tests:writeAtlasRuntimeClasspath
classpath=$(cat "$UNCIV_ATLAS_BUILD_ROOT/atlas-runtime-classpath.txt")
agent=$(printf '%s' "$classpath" | tr ':' '\n' | awk '/\/mockito-core\// { print; exit }')
[[ -f $agent ]] || { echo 'Mockito runtime agent is missing' >&2; exit 1; }
source_hash=$(git -C "$repo" rev-parse HEAD)
tree_hash=$(git -C "$repo" diff HEAD -- scripts/atlas tests/src/com/unciv/dev/Atlas* tests/src/com/unciv/ui/components/AtlasCoverageTest.kt docs/atlas | shasum -a 256 | cut -d' ' -f1)
harness_hash=$(cd "$repo" && git ls-files -co --exclude-standard scripts/atlas tests/src/com/unciv/dev/AtlasFixtureCatalog.kt tests/src/com/unciv/dev/AtlasTurnFixtures.kt tests/src/com/unciv/dev/AtlasRenderCheck.kt tests/src/com/unciv/dev/AtlasPerformanceCheck.kt tests/src/com/unciv/ui/components/AtlasCoverageTest.kt tests/build.gradle.kts docs/atlas/screen-coverage.json | sort -u | while IFS= read -r path; do shasum -a 256 "$path"; done | shasum -a 256 | cut -d' ' -f1)
cd "$repo/android/assets"
java_args=(-XstartOnFirstThread -Xmx3g "-javaagent:$agent" "-Dunciv.atlas.sourceHash=$source_hash" "-Dunciv.atlas.diffHash=$tree_hash" "-Dunciv.atlas.harnessHash=$harness_hash" -cp "$classpath")
if [[ $id == performance ]]; then
    [[ $matrix == false ]] || { echo 'Performance uses the fixed 1440x900, 1.0x trace' >&2; exit 2; }
    "$JAVA_HOME/bin/java" "${java_args[@]}" com.unciv.dev.AtlasPerformanceCheck "$output" 2>&1 | tee "$output/performance.log"
    for run in 1 2 3; do [[ -s $output/trace-$run.json && -s $output/trace-$run.csv && -s $output/performance-$run.png ]]; done
else
    sizes=(1440x900); scales=(1.0)
    if [[ $matrix == true ]]; then sizes=(1440x900 1920x1080 960x720 800x500 420x600); scales=(1.0 1.5); fi
    for size in "${sizes[@]}"; do
        for scale in "${scales[@]}"; do
            "$JAVA_HOME/bin/java" "${java_args[@]}" com.unciv.dev.AtlasRenderCheck "$id" "$output" "${size%x*}" "${size#*x}" "$scale" 2>&1 | tee "$output/$id-$size-$scale.log"
            [[ -s $output/$id-$size-$scale.png && -s $output/$id-$size-$scale.json ]]
        done
    done
fi
