#!/bin/sh
# KOMGP 启动器（Linux 包内安装为 /usr/bin/komgp）
#
# 可用环境变量：
#   KOMGP_APP   程序所在目录（默认 /usr/share/komgp）
#   KOMGP_HOME  用户数据目录（曲库、config.json），默认 ~/.local/share/komgp
set -eu

APP="${KOMGP_APP:-/usr/share/komgp}"
DATA="${KOMGP_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/komgp}"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="$(command -v java || true)"
fi

if [ -z "${JAVA:-}" ] || [ ! -x "$JAVA" ]; then
    echo "找不到 Java。请先安装 Java 17 或更高版本，例如：" >&2
    echo "  Debian/Ubuntu: sudo apt install default-jre" >&2
    echo "  Fedora:        sudo dnf install java-17-openjdk-headless" >&2
    echo "  Arch:          sudo pacman -S jre-openjdk-headless" >&2
    exit 1
fi

mkdir -p "$DATA"
# 游戏以「当前目录」作为 config.json 与 charts/ 的位置
cd "$DATA"

exec "$JAVA" -Xms256m -Xmx512m -XX:+UseSerialGC -Dfile.encoding=UTF-8 \
     -cp "$APP/KOMGP.jar:$APP/lib/*" rhythm.Main "$@"
