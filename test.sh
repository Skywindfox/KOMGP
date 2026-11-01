#!/usr/bin/env bash
# 运行内置自测（解析 / 时间轴 / 判定 / 解码 / 明文谱面 / 曲库）
set -euo pipefail
cd "$(dirname "$0")"

./devbuild.sh >/dev/null

CP="build/dev-classes"
for j in lib/*.jar; do CP="$CP:$j"; done
exec java -Dfile.encoding=UTF-8 -cp "$CP" komgp.dev.SelfTest "$@"
