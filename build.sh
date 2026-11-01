#!/usr/bin/env bash
# 编译 KOMGP 发行版（只含游戏本体，不含任何测试/开发工具）
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/classes
JAR=build/KOMGP.jar

rm -rf "$OUT"
mkdir -p "$OUT"

CP=""
for j in lib/*.jar; do CP="$CP:$j"; done
CP="${CP#:}"

shopt -s globstar nullglob
SRC=(src/main/java/**/*.java)
echo "[build] javac ${#SRC[@]} 个源文件 ..."
javac --release 17 -encoding UTF-8 -Xlint:all,-serial,-this-escape \
      -cp "$CP" -d "$OUT" "${SRC[@]}"

jar --create --file "$JAR" --main-class komgp.Main -C "$OUT" .
echo "[build] 完成 -> $JAR"
