#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source "$repo/scripts/atlas/paths.sh"
worktree_id=$(printf '%s' "$repo" | shasum -a 256 | cut -c1-12)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$(atlas_default_gradle_home)}
export UNCIV_ATLAS_BUILD_ROOT=${UNCIV_ATLAS_BUILD_ROOT:-$(atlas_default_build_root "$worktree_id")}
evidence=$(atlas_evidence_path "${UNCIV_ATLAS_OUTPUT:-$ATLAS_EVIDENCE_ROOT/checks}")
args=()
if [[ ${1:-} == --gradle ]]; then
    shift
    [[ $# -gt 0 ]] || { echo 'Supply explicit Gradle tasks' >&2; exit 2; }
    for task in "$@"; do
        [[ $task != -* && $task != *run* && $task != *macApp* && $task != *install* && $task != *publish* ]] || {
            echo "Unsafe or unsupported verification task: $task" >&2; exit 2;
        }
    done
    args=("$@")
else
    [[ $# -gt 0 ]] || { echo 'Usage: check.sh <JUnit class glob>... | --gradle <task>...' >&2; exit 2; }
    args=(:tests:test)
    for pattern in "$@"; do args+=(--tests "$pattern"); done
fi
mkdir -p "$evidence"
cd "$repo"
./gradlew -I "$repo/scripts/atlas/build.gradle" --console=plain "${args[@]}" 2>&1 | tee "$evidence/gradle-$(date -u +%Y%m%dT%H%M%SZ)-$$.log"
