#!/usr/bin/env bash
#
# 打 KOMGP 的四平台发行包：
#   Windows(zip，内含 KOMGP.exe) / Debian(deb) / Fedora(rpm) / Arch(pkg.tar.zst)
#   + 源码包
#
#   ./packaging/build-packages.sh
#   VERSION=v0.2-test ./packaging/build-packages.sh
#
# 发行包只包含游戏本体，不含任何自测/基准/截图工具（那些在 src/dev 下）。
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

VERSION="${VERSION:-v0.1-test}"
DEB_VER="${DEB_VER:-0.1-test}"
RPM_VER="${RPM_VER:-0.1}"
RPM_REL="${RPM_REL:-test}"
ARCH_VER="${ARCH_VER:-0.1.test}"
ARCH_REL="${ARCH_REL:-1}"
NAME="komgp"
SUMMARY_EN="KitsuneOS Music Game Player: 4K fall-down rhythm game with Malody support"
SUMMARY_ZH="KitsuneOS 音乐游戏播放器：支持 Malody 与 KOMGP 明文谱面的 4K 下落式音游"
DESC_EN="$(cat <<'EOF'
KOMGP (KitsuneOS Music Game Player) is a lightweight 4K fall-down rhythm game
written in pure Java, designed for the KitsuneOS desktop.

* Plays Malody .mc / .mcz charts and KOMGP plain-text .kmt charts
* Formats are detected by file content, not by extension
* Built-in chart editor with plain-text and Malody export
* Five judgement tiers, health bar, practice mode, per-chart timing offset
* 2D flat UI, software rendered with Java2D - no GPU required
EOF
)"
URL="https://github.com/Skywindfox/KOMGP"
EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"

DIST="$ROOT/dist/$VERSION"
STAGE="$ROOT/build/pkg"
rm -rf "$STAGE" "$DIST"
mkdir -p "$DIST" "$STAGE"

echo "==> 编译（发行版：仅游戏本体）"
./build.sh >/dev/null

# ---------------------------------------------------------------- Windows exe
echo "==> 交叉编译 Windows 启动器 (KOMGP.exe)"
MINGW_BIN="${MINGW_BIN:-}"
if [ -z "$MINGW_BIN" ]; then
    for cand in /usr/bin /usr/x86_64-w64-mingw32/bin /tmp/mingw/usr/bin; do
        if [ -x "$cand/x86_64-w64-mingw32-gcc" ]; then MINGW_BIN="$cand"; break; fi
    done
fi
if [ -z "$MINGW_BIN" ] || [ ! -x "$MINGW_BIN/x86_64-w64-mingw32-gcc" ]; then
    echo "    !! 找不到 x86_64-w64-mingw32-gcc，无法生成 .exe" >&2
    echo "       安装 mingw64-gcc，或用 MINGW_BIN=/path/to/bin 指定" >&2
    exit 1
fi
"$MINGW_BIN/x86_64-w64-mingw32-windres" packaging/win/launcher.rc -o "$STAGE/launcher_res.o"
"$MINGW_BIN/x86_64-w64-mingw32-gcc" -O2 -s -mwindows -municode \
    -o build/KOMGP.exe packaging/win/launcher.c "$STAGE/launcher_res.o" -lshell32
echo "    build/KOMGP.exe ($(stat -c%s build/KOMGP.exe) 字节)"

# ---------------------------------------------------------------- Linux 文件树
echo "==> 准备通用文件树"
LIN="$STAGE/linux"
install -d "$LIN/usr/bin" "$LIN/usr/share/$NAME/lib" \
           "$LIN/usr/share/applications" "$LIN/usr/share/icons/hicolor/256x256/apps" \
           "$LIN/usr/share/doc/$NAME"
install -m 0755 packaging/launcher.sh "$LIN/usr/bin/$NAME"
install -m 0644 build/KOMGP.jar     "$LIN/usr/share/$NAME/KOMGP.jar"
for j in lib/*.jar; do install -m 0644 "$j" "$LIN/usr/share/$NAME/lib/"; done
install -m 0644 packaging/komgp.desktop "$LIN/usr/share/applications/$NAME.desktop"
install -m 0644 packaging/komgp.png     "$LIN/usr/share/icons/hicolor/256x256/apps/$NAME.png"
install -m 0644 README.md LICENSE "$LIN/usr/share/doc/$NAME/"

INSTALL_BYTES=$(du -sb --apparent-size "$LIN" | cut -f1)
INSTALL_KB=$(( (INSTALL_BYTES + 1023) / 1024 ))

# ---------------------------------------------------------------- Debian
echo "==> 生成 .deb"
DEB_ROOT="$STAGE/deb"
cp -a "$LIN" "$DEB_ROOT"
install -d "$DEB_ROOT/DEBIAN"
cat > "$DEB_ROOT/DEBIAN/control" <<EOF
Package: $NAME
Version: $DEB_VER
Architecture: all
Maintainer: KitsuneOS <dev@kitsuneos.example>
Installed-Size: $INSTALL_KB
Depends: default-jre | java17-runtime
Section: games
Priority: optional
Homepage: $URL
Description: $SUMMARY_EN
 $SUMMARY_ZH
 .
 KOMGP 是 KitsuneOS 的音乐游戏播放器：读取 Malody .mc/.mcz 与 KOMGP .kmt 明文谱面，
 内置谱面编辑器，五档判定、练习模式、单曲延时校准、血条，2D 扁平界面、纯 Java2D 渲染。
EOF
( cd "$DEB_ROOT" && find usr -type f -print0 | sort -z | xargs -0 md5sum > DEBIAN/md5sums )
( cd "$DEB_ROOT/DEBIAN" && tar --owner=0 --group=0 --numeric-owner --sort=name \
      --mtime="@$EPOCH" -cf - . | gzip -9n > "$STAGE/control.tar.gz" )
( cd "$DEB_ROOT" && tar --owner=0 --group=0 --numeric-owner --sort=name \
      --mtime="@$EPOCH" --exclude=./DEBIAN -cf - . | gzip -9n > "$STAGE/data.tar.gz" )
printf '2.0\n' > "$STAGE/debian-binary"
DEB_OUT="$DIST/${NAME}-${VERSION}-debian-all.deb"
( cd "$STAGE" && ar rc "$DEB_OUT" debian-binary control.tar.gz data.tar.gz )
echo "    $DEB_OUT"

# ---------------------------------------------------------------- Fedora
echo "==> 生成 .rpm"
RPM_OUT="$DIST/${NAME}-${VERSION}-fedora-noarch.rpm"
python3 packaging/mk_rpm.py "$LIN" \
    --out "$RPM_OUT" \
    --name "$NAME" --version "$RPM_VER" --release "$RPM_REL" \
    --summary "$SUMMARY_EN" \
    --description "$DESC_EN
$SUMMARY_ZH" \
    --url "$URL" --arch noarch \
    --packager "KitsuneOS <dev@kitsuneos.example>" \
    --requires 'java>=17' \
    --files usr/bin/$NAME \
            usr/share/$NAME/KOMGP.jar \
            usr/share/$NAME/lib/jlayer-1.0.1.4.jar \
            usr/share/$NAME/lib/jorbis-0.0.17.jar \
            usr/share/applications/$NAME.desktop \
            usr/share/icons/hicolor/256x256/apps/$NAME.png \
            usr/share/doc/$NAME/README.md \
            usr/share/doc/$NAME/LICENSE
echo "    $RPM_OUT"

# ---------------------------------------------------------------- Arch
echo "==> 生成 .pkg.tar.zst"
ARCH_ROOT="$STAGE/arch"
cp -a "$LIN" "$ARCH_ROOT"
cat > "$ARCH_ROOT/.PKGINFO" <<EOF
pkgname = $NAME
pkgbase = $NAME
pkgver = ${ARCH_VER}-${ARCH_REL}
pkgdesc = $SUMMARY_EN
url = $URL
builddate = $EPOCH
packager = KitsuneOS <dev@kitsuneos.example>
size = $INSTALL_BYTES
arch = any
license = MIT
depend = java-runtime>=17
EOF
cat > "$ARCH_ROOT/.INSTALL" <<'EOF'
post_install() {
    update-desktop-database -q 2>/dev/null || true
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor 2>/dev/null || true
}
post_upgrade() {
    post_install
}
EOF
ARCH_OUT="$DIST/${NAME}-${VERSION}-arch-any.pkg.tar.zst"
( cd "$ARCH_ROOT" && tar --owner=0 --group=0 --numeric-owner --sort=name \
      --mtime="@$EPOCH" -cf - .PKGINFO .INSTALL usr | zstd -19 -T0 -q -o "$ARCH_OUT" -f )
echo "    $ARCH_OUT"

# ---------------------------------------------------------------- Windows
echo "==> 生成 Windows 包"
WIN="$STAGE/win/${NAME}-${VERSION}"
install -d "$WIN/lib" "$WIN/charts"
install -m 0644 build/KOMGP.jar "$WIN/KOMGP.jar"
install -m 0755 build/KOMGP.exe "$WIN/KOMGP.exe"
for j in lib/*.jar; do install -m 0644 "$j" "$WIN/lib/"; done
install -m 0644 README.md "$WIN/README.md"
install -m 0644 LICENSE "$WIN/LICENSE"
install -m 0644 packaging/README-windows.txt "$WIN/README.txt"
install -m 0644 packaging/komgp.ico "$WIN/komgp.ico"
install -m 0644 packaging/win/debug-console.bat "$WIN/debug-console.bat"
cat > "$WIN/charts/PUT-CHARTS-HERE.txt" <<'EOF'
把 Malody 的 .mc / .mcz，或 KOMGP 的 .kmt 明文谱面放进这个目录
（可以建子文件夹），然后在游戏里的选曲页按 R 刷新。
首次启动如果这里为空，游戏会自动生成 3 首演示曲。
EOF
WIN_OUT="$DIST/${NAME}-${VERSION}-windows.zip"
( cd "$STAGE/win" && zip -r -9 -q "$WIN_OUT" "$(basename "$WIN")" )
echo "    $WIN_OUT"

# ---------------------------------------------------------------- 源码包
echo "==> 生成源码包"
SRCROOT="$STAGE/$NAME-$VERSION"
rm -rf "$SRCROOT"
mkdir -p "$SRCROOT"
tar --exclude=./build --exclude=./dist --exclude=./charts --exclude=./shots \
    --exclude=./config.json --exclude='*.log' -cf - . | tar -xf - -C "$SRCROOT"
( cd "$STAGE" && tar --owner=0 --group=0 --numeric-owner --sort=name --mtime="@$EPOCH" \
      -czf "$DIST/${NAME}-${VERSION}-src.tar.gz" "$(basename "$SRCROOT")" )
echo "    $DIST/${NAME}-${VERSION}-src.tar.gz"

# ---------------------------------------------------------------- 发布说明
DEB_BASE=$(basename "$DEB_OUT")
RPM_BASE=$(basename "$RPM_OUT")
ARCH_BASE=$(basename "$ARCH_OUT")
WIN_BASE=$(basename "$WIN_OUT")
cat > "$DIST/RELEASE-NOTES.md" <<NOTES
# KOMGP $VERSION

**KitsuneOS Music Game Player** —— 用纯 Java 写的 4K 下落式音游。
支持 Malody 的 \`.mc\` / \`.mcz\` 与 KOMGP 自己的明文谱面 \`.kmt\`，
自带谱面编辑器，界面是 2D 扁平风格。

## 包一览

| 文件 | 平台 | 安装方式 |
|---|---|---|
| \`$WIN_BASE\` | Windows 10/11 | 解压后双击 \`KOMGP.exe\` |
| \`$DEB_BASE\` | Debian / Ubuntu / Mint | \`sudo apt install ./$DEB_BASE\` |
| \`$RPM_BASE\` | Fedora / RHEL / openSUSE | \`sudo dnf install ./$RPM_BASE\` |
| \`$ARCH_BASE\` | Arch / Manjaro | \`sudo pacman -U $ARCH_BASE\` |
| \`${NAME}-${VERSION}-src.tar.gz\` | 任意 | 源码包（含 PKGBUILD，可自行编译） |

运行只要求 **Java 17+（带图形界面的 JRE）**，不要求独立显卡。
发行包里不含任何测试代码。

## 安装后怎么用

Linux 上安装命令行入口 \`komgp\` 和桌面图标（应用菜单里搜 "KOMGP"）。

* 曲库与配置在 \`\$HOME/.local/share/komgp/\`（可用 \`KOMGP_HOME\` 覆盖）
* 首次启动若曲库为空，会自动生成 3 首演示曲
* 自己的谱面：\`.mc\` / \`.mcz\` / \`.kmt\` 放进 \`~/.local/share/komgp/charts/\`，选曲页按 \`R\` 刷新
* 选曲页按 \`E\` 打开谱面编辑器

Windows 版是绿色版，解压即用：\`KOMGP.exe\` + \`KOMGP.jar\` + \`lib/\` + \`charts/\`。

## 校验

    sha256sum -c SHA256SUMS.txt

## 已知限制

* 音频输出未在真实声卡上验证（构建机没有 /dev/snd）；解码链路与时钟已自测通过
* 编辑器目前只支持固定 BPM 网格编辑（多段变速谱可以打开、播放、导出，但不改速度点）
* 不支持键音、谱面内置视频、皮肤
NOTES
echo "    $DIST/RELEASE-NOTES.md"

# ---------------------------------------------------------------- 校验和
( cd "$DIST" && find . -maxdepth 1 -type f ! -name SHA256SUMS.txt -printf '%f\n' \
    | sort | xargs -r sha256sum > "$STAGE/sums.txt" && mv "$STAGE/sums.txt" SHA256SUMS.txt )

# ---------------------------------------------------------------- 校验
echo
echo "==> 校验"
echo "-- rpm 摘要 --"
rpm -K "$RPM_OUT" 2>&1 | tail -1
echo "-- rpm 信息 --"
rpm -qip "$RPM_OUT" 2>/dev/null | sed -n '1,6p'
echo "-- rpm 文件 --"
rpm -qp --qf '[%{FILENAMES} %{FILESIZES}\n]' "$RPM_OUT" 2>/dev/null | grep -v ' 0$'
echo "-- deb 成员 --"
ar t "$DEB_OUT"
echo "-- Windows exe --"
file build/KOMGP.exe
echo
echo "==> 产物"
ls -lh "$DIST"
