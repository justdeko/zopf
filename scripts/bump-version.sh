#!/bin/sh
# Moves the version in every file that spells it out. gradle.properties is the one
# that counts; the rest are manifests and examples that have to agree with it.

set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUMP=""
DRY_RUN=0

say() { printf '%s\n' "$*"; }
err() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Moves zopf's version everywhere it is written down.

  scripts/bump-version.sh patch      1.2.3 -> 1.2.4
  scripts/bump-version.sh minor      1.2.3 -> 1.3.0
  scripts/bump-version.sh major      1.2.3 -> 2.0.0
  scripts/bump-version.sh 2.5.0      that exact version

  --dry-run   say what would change and write nothing
  --help

It touches no git state. Commit the result, then cut the tag — the release build takes its
version from the tag, so a tag that disagrees with these files is what this exists to stop.
EOF
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --dry-run | -n)
                DRY_RUN=1
                shift
                ;;
            --help | -h)
                usage
                exit 0
                ;;
            -*) err "unknown option: $1" ;;
            *)
                [ -z "$BUMP" ] || err "give one bump, not both \"$BUMP\" and \"$1\"."
                BUMP="$1"
                shift
                ;;
        esac
    done
    [ -n "$BUMP" ] || {
        usage >&2
        exit 1
    }
}

current_version() {
    grep '^zopfVersion=' "$ROOT/gradle.properties" | cut -d= -f2 ||
        err "gradle.properties has no zopfVersion= line to read the current version from."
}

# macOS won't install a bundle whose CFBundleShortVersionString starts at 0.
check_shape() {
    case "$1" in
        [1-9]*.[0-9]*.[0-9]*) ;;
        0.*) err "macOS won't install a 0.x build — the major has to be 1 or more." ;;
        *) err "\"$1\" isn't a version. Write it as X.Y.Z, or say patch, minor or major." ;;
    esac
    printf '%s' "$1" | grep -Eq '^[1-9][0-9]*\.[0-9]+\.[0-9]+$' ||
        err "\"$1\" isn't a version. Write it as X.Y.Z, or say patch, minor or major."
}

next_version() {
    from="$1"
    major=${from%%.*}
    rest=${from#*.}
    minor=${rest%%.*}
    patch=${rest#*.}
    case "$BUMP" in
        major) printf '%s' "$((major + 1)).0.0" ;;
        minor) printf '%s' "$major.$((minor + 1)).0" ;;
        patch) printf '%s' "$major.$minor.$((patch + 1))" ;;
        *) printf '%s' "$BUMP" ;;
    esac
}

# Each edit is checked afterwards, so a file that has moved on fails the bump
# instead of keeping the old number.
replace() {
    file="$1"
    pattern="$2"
    what="$3"
    [ -f "$ROOT/$file" ] || err "$file isn't there any more, so $what can't be updated."
    grep -Eq "$4" "$ROOT/$file" || err "$file no longer has $what where this expected it."
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  $file — $what"
        return 0
    fi
    sed -i '' "$pattern" "$ROOT/$file"
    grep -q "$NEW" "$ROOT/$file" || err "$file came out of the edit without $NEW in it."
    say "  $file — $what"
}

changelog() {
    file="$ROOT/CHANGELOG.md"
    [ -f "$file" ] || err "CHANGELOG.md isn't there any more."
    if grep -q "^## $NEW\$" "$file"; then
        say "  CHANGELOG.md — already has a $NEW entry, left alone"
        return 0
    fi
    grep -q '^# Changelog$' "$file" || err "CHANGELOG.md has no \"# Changelog\" heading to file $NEW under."
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  CHANGELOG.md — a \"## $NEW\" heading for you to write under"
        return 0
    fi
    tmp=$(mktemp)
    awk -v version="$NEW" '
        /^# Changelog$/ && !done { print; print ""; print "## " version; done = 1; next }
        { print }
    ' "$file" > "$tmp"
    mv "$tmp" "$file"
    say "  CHANGELOG.md — a \"## $NEW\" heading for you to write under"
}

main() {
    parse_args "$@"

    OLD=$(current_version)
    check_shape "$OLD"
    NEW=$(next_version "$OLD")
    check_shape "$NEW"
    [ "$OLD" != "$NEW" ] || err "zopf is already $NEW."

    if [ "$DRY_RUN" -eq 1 ]; then
        say "$OLD -> $NEW, which would change:"
    else
        say "$OLD -> $NEW"
    fi

    replace gradle.properties \
        "s/^zopfVersion=.*\$/zopfVersion=$NEW/" \
        "the version the build stamps into the jar" \
        '^zopfVersion='
    replace plugins/zopf/.claude-plugin/plugin.json \
        "s/\"version\": \"[0-9][0-9.]*\"/\"version\": \"$NEW\"/" \
        "the plugin manifest's version" \
        '"version": "[0-9]'
    replace install.sh \
        "s/-- --version [0-9][0-9.]*/-- --version $NEW/" \
        "the --version example in the usage text" \
        '\-\- \-\-version [0-9]'
    replace CLAUDE.md \
        "s/-PpackageVersion=[0-9][0-9.]*/-PpackageVersion=$NEW/" \
        "the packageDmg example" \
        '\-PpackageVersion=[0-9]'
    changelog

    if [ "$DRY_RUN" -eq 0 ]; then
        say ""
        say "Write the $NEW entry in CHANGELOG.md, commit, then tag v$NEW — the release build"
        say "takes its version from the tag."
    fi
}

main "$@"
