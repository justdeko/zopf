#!/usr/bin/env bash
# Rebuilds the three eval fixture repos that check.py grades against.
#
# Each is a small project in a different ecosystem with its own .zopf workspace,
# so the evals never lean on this repo's Kotlin/Gradle shape. eval-2 also carries
# a deliberately broken nightly.yaml — four defects that all parse: an
# unreachable exitCode branch, a missing when:false arm, a promtFile typo, and a
# ${...} in a title. Regenerate with:  ./make_fixtures.sh [dest]
set -euo pipefail

FX="${1:-$(cd "$(dirname "$0")" && pwd)/fixtures}"
CONNECTORS="${CONNECTORS:-$(cd "$(dirname "$0")/../../../../.zopf/connectors" && pwd)}"

rm -rf "$FX"; mkdir -p "$FX"

mk_ws () {
  mkdir -p "$1/.zopf/workflows" "$1/.zopf/connectors"
  printf 'name: %s\nversion: 1\n' "$(basename "$1")" > "$1/.zopf/zopf.yaml"
  cp -r "$CONNECTORS/macos-notify" "$CONNECTORS/github-issue" "$1/.zopf/connectors/"
  ( cd "$1" && git init -q . && git add -A \
      && git -c user.email=t@t -c user.name=t commit -qm "initial commit" )
}

mkdir -p "$FX/eval-0/pkg/src"
cat > "$FX/eval-0/pkg/package.json" <<'JSON'
{
  "name": "billing-api",
  "version": "0.3.1",
  "scripts": {
    "lint": "eslint src --max-warnings 0",
    "test": "vitest run"
  }
}
JSON
echo "export const rate = 0.07;" > "$FX/eval-0/pkg/src/index.ts"
mk_ws "$FX/eval-0/pkg"

mkdir -p "$FX/eval-1/etl/etl"
printf '[project]\nname = "warehouse-etl"\nversion = "1.2.0"\n' > "$FX/eval-1/etl/pyproject.toml"
echo "def load(): ..." > "$FX/eval-1/etl/etl/load.py"
mk_ws "$FX/eval-1/etl"

mkdir -p "$FX/eval-2/tool/cmd"
echo "module example.com/tool" > "$FX/eval-2/tool/go.mod"
echo "package main" > "$FX/eval-2/tool/cmd/main.go"
mk_ws "$FX/eval-2/tool"
mkdir -p "$FX/eval-2/tool/.zopf/prompts"
cat > "$FX/eval-2/tool/.zopf/workflows/nightly.yaml" <<'YAML'
name: nightly
description: Nightly build, then a written summary.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: build
    type: shell
    command: go build ./...
  - id: gate
    type: branch
    title: did it build
    expression: ${build.exitCode} == 0
  - id: report
    type: agent
    title: Summarise ${build.result}
    promtFile: prompts/nightly.md
  - id: cleanup
    type: shell
    command: rm -rf ./tmp
edges:
  - from: build
    to: gate
  - from: gate
    to: report
    when: true
  - from: report
    to: cleanup
    on: failure
YAML
echo "Summarise the nightly build below. Output at most five lines." \
  > "$FX/eval-2/tool/.zopf/prompts/nightly.md"
( cd "$FX/eval-2/tool" && git add -A \
    && git -c user.email=t@t -c user.name=t commit -qm "add nightly workflow" )

echo "fixtures rebuilt at $FX"
