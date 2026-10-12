#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
command=${1:-}
case "$command" in validate|pack|coverage) ;; *) echo 'Usage: assets.sh validate|pack|coverage [--require-complete]' >&2; exit 2 ;; esac
shift
require_complete=false
if [[ ${1:-} == --require-complete ]]; then require_complete=true; shift; fi
[[ $# == 0 ]] || { echo 'Unsupported argument' >&2; exit 2; }
worktree_id=$(printf '%s' "$repo" | shasum -a 256 | cut -c1-12)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/Users/noahkenny/.gradle-unciv}
export UNCIV_ATLAS_BUILD_ROOT=${UNCIV_ATLAS_BUILD_ROOT:-/Users/noahkenny/.unciv-atlas-build/$worktree_id}
export UNCIV_ATLAS_ASSET_COMMAND=$command
export UNCIV_ATLAS_REQUIRE_COMPLETE=$require_complete
cd "$repo"
./gradlew -I "$repo/scripts/atlas/build.gradle" --console=plain atlasAssets
