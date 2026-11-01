#!/usr/bin/env bash
# 开发构建：游戏本体 + 自测/基准/截图工具（不用于发行）
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/dev-classes
rm -rf "$OUT"
mkdir -p "$OUT"

CP=""
for j in lib/*.jar; do CP="$CP:$j"; done
CP="${CP#:}"

shopt -s globstar nullglob
SRC=(src/main/java/**/*.java src/dev/java/**/*.java)
echo "[devbuild] javac ${#SRC[@]} 个源文件 ..."
javac --release 17 -encoding UTF-8 -Xlint:all,-serial,-this-escape \
      -cp "$CP" -d "$OUT" "${SRC[@]}"
echo "[devbuild] 完成 -> $OUT"
