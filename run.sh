#!/usr/bin/env bash
# 启动 KOMGP
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f build/KOMGP.jar ]; then
  ./build.sh
fi

CP="build/KOMGP.jar"
for j in lib/*.jar; do CP="$CP:$j"; done

# -XX:+UseSerialGC 在小堆、短命对象多的场景下停顿更短更可预测
exec java -Xms256m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=UTF-8 \
     -cp "$CP" komgp.Main "$@"
