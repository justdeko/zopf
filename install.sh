#!/bin/sh
# curl -fsSL https://raw.githubusercontent.com/justdeko/zopf/main/install.sh | sh
#
# Everything lives in main(), called on the very last line, so a transfer that dies
# halfway leaves a partial script that has defined some functions and run none of them.

set -eu

REPO="justdeko/zopf"
PREFIX="${ZOPF_PREFIX:-$HOME/.local}"
VERSION="${ZOPF_VERSION:-}"
MODIFY_PATH=1

say() { printf '%s\n' "$*"; }
note() { printf '%s\n' "$*" >&2; }
err() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Installs the zopf command line into ~/.local (override with --prefix or ZOPF_PREFIX).

  --version <x.y.z>   a specific release, rather than the newest published one
  --prefix <dir>      install under <dir>/opt and link <dir>/bin/zopf
  --no-modify-path    never touch a shell rc file, just say what to add
  --help

Piping into sh takes arguments after -s --, e.g.
  curl -fsSL <url> | sh -s -- --version 1.0.0
EOF
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --version)
                [ $# -ge 2 ] || err "--version needs a value."
                VERSION="$2"
                shift 2
                ;;
            --prefix)
                [ $# -ge 2 ] || err "--prefix needs a value."
                PREFIX="$2"
                shift 2
                ;;
            --no-modify-path)
                MODIFY_PATH=0
                shift
                ;;
            --help | -h)
                usage
                exit 0
                ;;
            *) err "unknown option: $1" ;;
        esac
    done
    [ -n "$PREFIX" ] || err "--prefix cannot be empty."
}

check_platform() {
    [ "$(uname -s)" = "Darwin" ] || err "zopf is macOS only."
    command -v curl >/dev/null 2>&1 || err "curl is required and isn't on your PATH."
    command -v tar >/dev/null 2>&1 || err "tar is required and isn't on your PATH."
}

# /releases/latest redirects to /releases/tag/vX.Y.Z, so reading the version out of the
# redirect costs no API call and can't be rate limited. It also ignores drafts, which is
# what keeps a tagged-but-unpublished build invisible here.
resolve_version() {
    [ -z "$VERSION" ] || return 0
    url=$(curl -sSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" 2>/dev/null) ||
        err "couldn't reach GitHub to ask what the newest release is."
    case "$url" in
        */releases/tag/v*) VERSION="${url##*/releases/tag/v}" ;;
        *) err "github.com/$REPO has no published release to install. Pass --version to name one." ;;
    esac
}

download() {
    asset="zopf-cli-$VERSION.tar.gz"
    base="https://github.com/$REPO/releases/download/v$VERSION"
    say "Downloading zopf $VERSION"
    curl -fsSL "$base/$asset" -o "$TMP/$asset" ||
        err "couldn't download $asset — is $VERSION a published release?"
    if curl -fsSL "$base/$asset.sha256" -o "$TMP/$asset.sha256" 2>/dev/null; then
        expected=$(cut -d' ' -f1 <"$TMP/$asset.sha256")
        actual=$(shasum -a 256 "$TMP/$asset" | cut -d' ' -f1)
        [ "$expected" = "$actual" ] ||
            err "checksum mismatch: expected $expected, got $actual. Nothing was installed."
    else
        note "note: $VERSION publishes no .sha256, so the download wasn't verified."
    fi
}

unpack() {
    dest="$PREFIX/opt/zopf-cli-$VERSION"
    mkdir -p "$PREFIX/opt" "$PREFIX/bin"
    rm -rf "$dest"
    tar xzf "$TMP/$asset" -C "$PREFIX/opt"
    [ -x "$dest/bin/zopf" ] || err "the tarball holds no bin/zopf where one was expected."
    ln -sf "$dest/bin/zopf" "$PREFIX/bin/zopf"
}

rc_file() {
    case "$(basename "${SHELL:-/bin/sh}")" in
        zsh) printf '%s' "${ZDOTDIR:-$HOME}/.zshrc" ;;
        bash) printf '%s' "$HOME/.bash_profile" ;;
        fish) printf '%s' "$HOME/.config/fish/config.fish" ;;
        *) printf '%s' "$HOME/.profile" ;;
    esac
}

path_line() {
    case "$(basename "${SHELL:-/bin/sh}")" in
        fish) printf 'fish_add_path %s' "$PREFIX/bin" ;;
        *) printf 'export PATH="%s:$PATH"' "$PREFIX/bin" ;;
    esac
}

# A child process can't put anything on its parent shell's PATH, so the most an installer
# can do is edit the rc file and say which one it edited.
ensure_on_path() {
    case ":$PATH:" in
        *":$PREFIX/bin:"*) return 0 ;;
    esac
    line=$(path_line)
    if [ "$MODIFY_PATH" -eq 0 ]; then
        note "$PREFIX/bin is not on your PATH. Add this yourself:"
        note "  $line"
        return 0
    fi
    rc=$(rc_file)
    mkdir -p "$(dirname "$rc")"
    if [ -f "$rc" ] && grep -Fq "$PREFIX/bin" "$rc"; then
        RESTART_HINT="$rc already adds it — open a new terminal to pick it up."
        return 0
    fi
    printf '\n# added by the zopf installer\n%s\n' "$line" >>"$rc"
    RESTART_HINT="Added $PREFIX/bin to $rc — run 'exec \$SHELL' or open a new terminal."
}

# The tarball carries no runtime, unlike the .app, so a launcher installed next to no JDK
# would fail on first use with a message about java rather than about zopf.
check_java() {
    java_bin="java"
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_bin="$JAVA_HOME/bin/java"
    command -v "$java_bin" >/dev/null 2>&1 || {
        note ""
        note "zopf needs a JDK 17 or newer and there is no java on your PATH."
        note "  brew install temurin"
        return 0
    }
    major=$("$java_bin" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
    [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null && return 0
    note ""
    note "zopf needs a JDK 17 or newer; the java on your PATH reports ${major:-an unreadable version}."
    note "  brew install temurin"
}

main() {
    parse_args "$@"
    check_platform
    resolve_version

    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT INT TERM

    download
    unpack

    RESTART_HINT=""
    ensure_on_path

    say "Installed zopf $VERSION to $PREFIX/bin/zopf"
    [ -n "$RESTART_HINT" ] && say "$RESTART_HINT"
    check_java
    say ""
    say "  zopf --help          what it can do"
    say "  zopf list            the workflows in the workspace you're standing in"
}

main "$@"
