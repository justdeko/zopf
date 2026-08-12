#!/usr/bin/env bash
# Regenerates the packaged app icons from the master SVG.
# Requires: rsvg-convert, magick (ImageMagick), iconutil (macOS).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
master="$here/../src/main/resources/icon.svg"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

render() { rsvg-convert -w "$1" -h "$1" "$master" -o "$2"; }

# Linux: full-bleed PNG.
render 512 "$here/icon.png"

# Windows: multi-resolution full-bleed ICO.
for s in 16 32 48 64 128 256; do render "$s" "$work/ico-$s.png"; done
magick "$work"/ico-16.png "$work"/ico-32.png "$work"/ico-48.png \
       "$work"/ico-64.png "$work"/ico-128.png "$work"/ico-256.png "$here/icon.ico"

# macOS: artwork sits in the 824/1024 safe area so it matches neighbouring
# dock icons, padded out to the full canvas with transparency.
set="$work/icon.iconset"
mkdir -p "$set"
emit() { # emit <canvas> <outfile>
  local art=$(( $1 * 824 / 1024 ))
  render "$art" "$work/art-$1.png"
  magick "$work/art-$1.png" -background none -gravity center -extent "$1x$1" "$set/$2"
}
emit 16   icon_16x16.png
emit 32   icon_16x16@2x.png
emit 32   icon_32x32.png
emit 64   icon_32x32@2x.png
emit 128  icon_128x128.png
emit 256  icon_128x128@2x.png
emit 256  icon_256x256.png
emit 512  icon_256x256@2x.png
emit 512  icon_512x512.png
emit 1024 icon_512x512@2x.png
iconutil -c icns "$set" -o "$here/icon.icns"

# The macos-notify connector posts from its own bundle
notifier="$here/../../.zopf/connectors/macos-notify/notifier"
also=""
if [ -d "$notifier" ]; then
  cp "$here/icon.icns" "$notifier/icon.icns"
  also=", and refreshed the macos-notify connector's copy"
fi

echo "wrote icon.png, icon.ico, icon.icns in $here$also"
