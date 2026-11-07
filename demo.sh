#!/usr/bin/env bash
# 重新生成内置演示谱面（Malody V 的 .mcz + 一份 KOMGP 明文 .kmt 示例）
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
CP="build/classes"
for j in lib/*.jar; do CP="$CP:$j"; done
exec java -Dfile.encoding=UTF-8 -cp "$CP" komgp.tools.DemoGen charts
