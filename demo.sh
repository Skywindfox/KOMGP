#!/usr/bin/env bash
# 重新生成内置演示谱面（3 首歌 / 4 张谱面，含合成音频与曲绘）
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -d build/classes ]; then ./build.sh; fi
CP="build/classes"
for j in lib/*.jar; do CP="$CP:$j"; done
exec java -Dfile.encoding=UTF-8 -cp "$CP" komgp.tools.DemoGen charts
