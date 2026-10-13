# Shared evidence boundary for standalone checks and rendering.
readonly ATLAS_EVIDENCE_ROOT=/Volumes/Lexar/Unciv/.superpowers/civ6-atlas

# Prefer the APFS volume whose sparse backing file lives on Lexar. A small
# local compiler workspace remains possible when this project volume is absent.
atlas_default_build_root() {
    local worktree_id=$1
    if [[ -f /Volumes/UncivAtlasBuild/.unciv-atlas-storage.json ]]; then
        printf '/Volumes/UncivAtlasBuild/compiler/%s\n' "$worktree_id"
    else
        printf '/Users/noahkenny/.unciv-atlas-build/%s\n' "$worktree_id"
    fi
}

atlas_default_gradle_home() {
    if [[ -f /Volumes/UncivAtlasBuild/.unciv-atlas-storage.json ]]; then
        printf '/Volumes/UncivAtlasBuild/gradle-user-home\n'
    else
        printf '/Users/noahkenny/.gradle-unciv\n'
    fi
}

# Resolve existing symlinks before creating any missing directories. Parent
# traversal is rejected by the caller so the missing suffix stays canonical.
atlas_resolve_directory() {
    local probe=$1 suffix= component physical
    while [[ ! -d $probe ]]; do
        [[ ! -e $probe && ! -L $probe ]] || return 2
        component=$(basename "$probe")
        suffix="/$component$suffix"
        probe=$(dirname "$probe")
    done
    physical=$(cd -P "$probe" && pwd -P) || return 2
    printf '%s%s\n' "$physical" "$suffix"
}

atlas_evidence_path() {
    local candidate=$1 resolved boundary
    if [[ $candidate != /* || $candidate/ == */../* ]]; then
        echo "Atlas evidence requires an absolute directory without '..' within the approved Lexar root: $ATLAS_EVIDENCE_ROOT" >&2
        return 2
    fi
    boundary=$(atlas_resolve_directory "$ATLAS_EVIDENCE_ROOT") || {
        echo "The approved Lexar evidence root is not a valid directory: $ATLAS_EVIDENCE_ROOT" >&2
        return 2
    }
    resolved=$(atlas_resolve_directory "$candidate") || {
        echo "Atlas evidence requires a directory within the approved Lexar root: $ATLAS_EVIDENCE_ROOT (received $candidate)" >&2
        return 2
    }
    case "$resolved" in
        "$boundary"|"$boundary"/*) printf '%s\n' "$resolved" ;;
        *) echo "Atlas evidence resolves outside the approved Lexar root: $ATLAS_EVIDENCE_ROOT (received $candidate)" >&2; return 2 ;;
    esac
}
