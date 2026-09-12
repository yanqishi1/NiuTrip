#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_FILE="$REPO_ROOT/app/build.gradle.kts"
DRY_RUN=false
REQUESTED_VERSION=""

usage() {
    cat <<'EOF'
Usage:
  ./scripts/release.sh                 Automatically increment the patch version
  ./scripts/release.sh 0.3.0           Release the specified version
  ./scripts/release.sh v0.3.0          A leading "v" is also accepted
  ./scripts/release.sh --dry-run       Show the next automatic version only
  ./scripts/release.sh --dry-run 0.3.0 Validate and show a specified version

Before publishing, commit all feature changes and switch to the main branch.
The script updates the Android version, tests and builds the APK, creates a
release commit and tag, pushes both atomically, then waits for GitHub Release.
EOF
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

read_latest_tag() {
    local tag
    while IFS= read -r tag; do
        if [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
            printf '%s\n' "$tag"
            return
        fi
    done < <(git tag --list 'v*' --sort=-version:refname)
}

normalize_version() {
    local version="${1#v}"
    [[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
        fail "Invalid version '$1'; expected MAJOR.MINOR.PATCH without leading zeroes"
    printf '%s\n' "$version"
}

next_patch_version() {
    local latest="${1#v}"
    local major minor patch
    IFS=. read -r major minor patch <<< "$latest"
    printf '%s.%s.%s\n' "$major" "$minor" "$((patch + 1))"
}

version_is_greater() {
    local candidate="$1" current="$2"
    local c_major c_minor c_patch p_major p_minor p_patch
    IFS=. read -r c_major c_minor c_patch <<< "$candidate"
    IFS=. read -r p_major p_minor p_patch <<< "$current"
    ((c_major > p_major)) ||
        ((c_major == p_major && c_minor > p_minor)) ||
        ((c_major == p_major && c_minor == p_minor && c_patch > p_patch))
}

github_repo_slug() {
    local remote_url="$1" slug
    case "$remote_url" in
        git@github.com:*) slug="${remote_url#git@github.com:}" ;;
        ssh://git@github.com/*) slug="${remote_url#ssh://git@github.com/}" ;;
        https://github.com/*) slug="${remote_url#https://github.com/}" ;;
        *) return 1 ;;
    esac
    printf '%s\n' "${slug%.git}"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    shift
fi
(( $# <= 1 )) || { usage >&2; exit 2; }
REQUESTED_VERSION="${1:-}"

require_command git
require_command awk
require_command perl
cd "$REPO_ROOT"
[[ -f "$BUILD_FILE" ]] || fail "Android build file not found: $BUILD_FILE"

if [[ "$DRY_RUN" == false ]]; then
    require_command curl
    [[ "$(git branch --show-current)" == "main" ]] || fail "Releases must be created from the main branch"
    [[ -z "$(git status --porcelain)" ]] || fail "Working tree is not clean; commit or stash changes first"
    git remote get-url origin >/dev/null 2>&1 || fail "Git remote 'origin' is not configured"

    printf 'Fetching main and release tags...\n'
    git fetch origin main --tags
    git merge-base --is-ancestor origin/main HEAD || fail "Local main is behind or diverged from origin/main; update it first"
fi

LATEST_TAG="$(read_latest_tag)"
if [[ -z "$LATEST_TAG" ]]; then
    LATEST_TAG="v0.0.0"
fi
LATEST_VERSION="$(normalize_version "$LATEST_TAG")"

if [[ -n "$REQUESTED_VERSION" ]]; then
    VERSION="$(normalize_version "$REQUESTED_VERSION")"
    version_is_greater "$VERSION" "$LATEST_VERSION" || fail "Version $VERSION must be newer than $LATEST_VERSION"
else
    VERSION="$(next_patch_version "$LATEST_VERSION")"
fi
TAG="v$VERSION"

CURRENT_CODE="$(awk '/^[[:space:]]*versionCode = [0-9]+/ { print $3; exit }' "$BUILD_FILE")"
CURRENT_NAME="$(awk -F\" '/^[[:space:]]*versionName = / { print $2; exit }' "$BUILD_FILE")"
[[ "$CURRENT_CODE" =~ ^[0-9]+$ ]] || fail "Could not read versionCode from $BUILD_FILE"
[[ -n "$CURRENT_NAME" ]] || fail "Could not read versionName from $BUILD_FILE"
NEXT_CODE="$((CURRENT_CODE + 1))"

printf 'Latest tag:      %s\n' "$LATEST_TAG"
printf 'Android version: %s (%s) -> %s (%s)\n' "$CURRENT_NAME" "$CURRENT_CODE" "$VERSION" "$NEXT_CODE"
printf 'Release tag:     %s\n' "$TAG"

if [[ "$DRY_RUN" == true ]]; then
    printf 'Dry run complete; no files were changed.\n'
    exit 0
fi

git rev-parse --verify --quiet "refs/tags/$TAG" >/dev/null && fail "Tag $TAG already exists"

perl -0pi -e "s/versionCode = $CURRENT_CODE/versionCode = $NEXT_CODE/" "$BUILD_FILE"
perl -0pi -e "s/versionName = \"\Q$CURRENT_NAME\E\"/versionName = \"$VERSION\"/" "$BUILD_FILE"
grep -q "versionCode = $NEXT_CODE" "$BUILD_FILE" || fail "Failed to update versionCode"
grep -q "versionName = \"$VERSION\"" "$BUILD_FILE" || fail "Failed to update versionName"

printf '\nTesting and building APK...\n'
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK_PATH" ]] || fail "APK was not generated at $APK_PATH"

if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK_PATH"
else
    shasum -a 256 "$APK_PATH"
fi

git add "$BUILD_FILE"
git commit -m "chore: release $TAG"
git tag -a "$TAG" -m "NiuTrip $TAG"

printf '\nPushing main and %s...\n' "$TAG"
git push --atomic origin HEAD:refs/heads/main "refs/tags/$TAG"

REMOTE_URL="$(git remote get-url origin)"
REPO_SLUG="$(github_repo_slug "$REMOTE_URL")" || fail "Origin is not a supported GitHub URL: $REMOTE_URL"
RELEASE_URL="https://github.com/$REPO_SLUG/releases/tag/$TAG"
API_URL="https://api.github.com/repos/$REPO_SLUG/releases/tags/$TAG"
WAIT_ATTEMPTS="${RELEASE_WAIT_ATTEMPTS:-40}"
[[ "$WAIT_ATTEMPTS" =~ ^[0-9]+$ ]] || fail "RELEASE_WAIT_ATTEMPTS must be a non-negative integer"

printf '\nGitHub Actions is building the release APK.\n'
printf 'Actions: https://github.com/%s/actions/workflows/release.yml\n' "$REPO_SLUG"
for ((attempt = 1; attempt <= WAIT_ATTEMPTS; attempt++)); do
    if curl --silent --fail --output /dev/null "$API_URL"; then
        printf '\nRelease published: %s\n' "$RELEASE_URL"
        printf 'APK: https://github.com/%s/releases/download/%s/NiuTrip-%s.apk\n' "$REPO_SLUG" "$TAG" "$TAG"
        printf 'SHA-256: https://github.com/%s/releases/download/%s/NiuTrip-%s.apk.sha256\n' "$REPO_SLUG" "$TAG" "$TAG"
        exit 0
    fi
    if ((attempt % 4 == 0)); then
        printf 'Still waiting for GitHub Release (%s/%s)...\n' "$attempt" "$WAIT_ATTEMPTS"
    fi
    sleep 15
done

printf '\nThe tag was pushed, but the Release was not available before the wait timed out.\n' >&2
printf 'Check: https://github.com/%s/actions/workflows/release.yml\n' "$REPO_SLUG" >&2
exit 1
