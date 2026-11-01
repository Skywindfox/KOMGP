#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
不依赖 rpmbuild 直接产出 .rpm（RPM v3 头格式）。

本机是 Fedora，但没有装 rpm-build；为了能打出可在 Fedora/RHEL/openSUSE 上
`dnf install ./x.rpm` 的包，这里按 RPM 的二进制格式手工组装：
    lead(96B) + signature header + main header + gzip(cpio newc payload)

产出后可以用系统自带的 rpm 校验：
    rpm -qip pkg.rpm
    rpm -K   pkg.rpm
    rpm2cpio pkg.rpm | cpio -tv
"""

import argparse
import gzip
import hashlib
import io
import os
import stat
import struct
import sys
import time

INT16, INT32, INT64, STRING, BIN, STRING_ARRAY, I18N = 3, 4, 5, 6, 7, 8, 9

# 常用 tag
T_NAME, T_VERSION, T_RELEASE = 1000, 1001, 1002
T_SUMMARY, T_DESCRIPTION, T_BUILDTIME, T_BUILDHOST = 1004, 1005, 1006, 1007
T_SIZE, T_LICENSE, T_PACKAGER, T_GROUP, T_URL, T_OS, T_ARCH = 1009, 1014, 1015, 1016, 1020, 1021, 1022
T_FILESIZES, T_FILEMODES, T_FILERDEVS, T_FILEMTIMES = 1028, 1030, 1033, 1034
T_FILEDIGESTS, T_FILELINKTOS, T_FILEFLAGS = 1035, 1036, 1037
T_FILEUSERNAME, T_FILEGROUPNAME, T_SOURCERPM, T_FILEVERIFYFLAGS = 1039, 1040, 1044, 1045
T_ARCHIVESIZE, T_PROVIDENAME, T_REQUIREFLAGS = 1046, 1047, 1048
T_REQUIREVERSION, T_REQUIRENAME = 1049, 1050
T_DIRINDEXES, T_BASENAMES, T_DIRNAMES = 1116, 1117, 1118
T_PROVIDEFLAGS, T_PROVIDEVERSION = 1112, 1113
T_PAYLOADSHA256, T_PAYLOADSHA256ALGO = 5092, 5093
T_PAYLOADFORMAT, T_PAYLOADCOMPRESSOR, T_PAYLOADFLAGS = 1124, 1125, 1126
T_FILEDIGESTALGO = 5011

RPMSENSE_LESS = 0x02
RPMSENSE_GREATER = 0x04
RPMSENSE_EQUAL = 0x08


def cur(tag, typ, raw, count):
    return (tag, typ, raw, count)


def s(tag, value):
    return cur(tag, STRING, value.encode("utf-8") + b"\0", 1)


def i18n(tag, value):
    return cur(tag, I18N, value.encode("utf-8") + b"\0", 1)


def i32(tag, values):
    values = list(values)
    packed = struct.pack(">%dI" % len(values), *[v & 0xFFFFFFFF for v in values])
    return cur(tag, INT32, packed, len(values))


def i16(tag, values):
    values = list(values)
    packed = struct.pack(">%dH" % len(values), *[v & 0xFFFF for v in values])
    return cur(tag, INT16, packed, len(values))


def sa(tag, values):
    values = list(values)
    raw = b"".join((v if isinstance(v, bytes) else v.encode("utf-8")) + b"\0" for v in values)
    return cur(tag, STRING_ARRAY, raw, len(values))


def bin_(tag, data):
    return cur(tag, BIN, data, len(data))


def build_header(entries, region_tag=None):
    """按 rpm 的 header 结构组装：魔数 + 索引表 + 数据区。

    region_tag 指定的标签（主头是 63 HEADERIMMUTABLE）的数据要放在数据区末尾，
    索引项指向这段「区域描述符」，rpm 靠它识别 v4+ 头格式。
    """
    entries = sorted(entries, key=lambda e: e[0])
    idx = []
    store = bytearray()
    region = None
    for tag, typ, raw, count in entries:
        if region_tag is not None and tag == region_tag:
            region = (tag, typ, raw, count)
            continue
        align = 8 if typ == INT64 else (4 if typ == INT32 else (2 if typ == INT16 else 1))
        while len(store) % align:
            store.append(0)
        idx.append((tag, typ, len(store), count))
        store += raw
    if region is not None:
        idx.append((region[0], region[1], len(store), region[3]))
        store += region[2]
    idx.sort()
    index = b"".join(struct.pack(">IIII", *e) for e in idx)
    head = b"\x8e\xad\xe8\x01\x00\x00\x00\x00" + struct.pack(">II", len(idx), len(store))
    return head + index + bytes(store)


def cpio_newc(entries):
    """entries: [(path, mode, data_bytes, mtime)] —— 生成 newc 格式 cpio。"""
    out = io.BytesIO()
    ino = 1
    for path, mode, data, mtime in entries:
        name = path.encode("utf-8") + b"\0"
        fields = (
            ino, mode, 0, 0, 2 if stat.S_ISDIR(mode) else 1, mtime,
            len(data), 0, 0, 0, 0, len(name), 0,
        )
        out.write(b"070701" + "".join("%08X" % v for v in fields).encode())
        out.write(name)
        out.write(b"\0" * ((4 - (110 + len(name)) % 4) % 4))
        out.write(data)
        out.write(b"\0" * ((4 - len(data) % 4) % 4))
        ino += 1
    # 结尾条目
    name = b"TRAILER!!!\0"
    fields = (0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, len(name), 0)
    out.write(b"070701" + "".join("%08X" % v for v in fields).encode())
    out.write(name)
    while out.tell() % 512:
        out.write(b"\0")
    return out.getvalue()


def collect(root, paths):
    """把待打包文件展开成 (相对路径, 绝对路径, 是否目录)。"""
    items = []
    seen = set()
    for rel in paths:
        rel = rel.lstrip("/")
        full = os.path.join(root, rel)
        if os.path.isdir(full):
            items.append((rel, full, True))
            seen.add(rel)
        else:
            # 顺带补上父目录
            parts = rel.split("/")[:-1]
            for i in range(1, len(parts) + 1):
                d = "/".join(parts[:i])
                if d not in seen:
                    seen.add(d)
                    items.append((d, os.path.join(root, d), True))
            items.append((rel, full, False))
            seen.add(rel)
    uniq = {}
    for rel, full, isdir in items:
        uniq[rel] = (full, isdir)
    return sorted(uniq.items(), key=lambda kv: (kv[0].count("/"), kv[0]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", help="打包根目录（里面是 usr/ 等）")
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="malody4k")
    ap.add_argument("--version", required=True)
    ap.add_argument("--release", required=True)
    ap.add_argument("--summary", required=True)
    ap.add_argument("--description", required=True)
    ap.add_argument("--license", default="MIT")
    ap.add_argument("--packager", default="Malody4K <noreply@example.com>")
    ap.add_argument("--url", default="https://example.com/malody4k")
    ap.add_argument("--group", default="Amusements/Games")
    ap.add_argument("--arch", default="noarch")
    ap.add_argument("--requires", action="append", default=[],
                    help="例：java-headless>=17")
    ap.add_argument("--files", nargs="+", required=True, help="相对 root 的路径")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    mtime = int(time.time())

    items = collect(root, args.files)
    if not items:
        sys.exit("没有文件可打包")

    payload_entries = []
    sizes, modes, mtimes, digests, links, flags, users, groups = [], [], [], [], [], [], [], []
    basenames, dirnames, dirindexes = [], [], []
    dir_map = {}
    total_size = 0

    for rel, (full, isdir) in items:
        path = "./" + rel
        if isdir:
            st = os.stat(full)
            mode = st.st_mode
            data = b""
            size = 0
            dig = ""
        else:
            with open(full, "rb") as f:
                data = f.read()
            st = os.stat(full)
            mode = st.st_mode
            size = len(data)
            dig = hashlib.sha256(data).hexdigest()
            total_size += size
        payload_entries.append((path, mode, data, mtime))

        d, _, base = rel.rpartition("/")
        d = ("/" + d + "/") if d else "/"
        if d not in dir_map:
            dir_map[d] = len(dirnames)
            dirnames.append(d)
        dirindexes.append(dir_map[d])
        basenames.append(base)

        sizes.append(size)
        modes.append(mode)
        mtimes.append(mtime)
        digests.append(dig)
        links.append("")
        flags.append(0)
        users.append("root")
        groups.append("root")

    cpio = cpio_newc(payload_entries)
    payload = gzip.compress(cpio, 9, mtime=0)

    req_names, req_vers, req_flags = ["/bin/sh"], [""], [0]
    for spec in args.requires:
        if ">=" in spec:
            n, v = spec.split(">=", 1)
            req_names.append(n.strip())
            req_vers.append(v.strip())
            req_flags.append(RPMSENSE_GREATER | RPMSENSE_EQUAL)
        elif "=" in spec:
            n, v = spec.split("=", 1)
            req_names.append(n.strip())
            req_vers.append(v.strip())
            req_flags.append(RPMSENSE_EQUAL)
        else:
            req_names.append(spec.strip())
            req_vers.append("")
            req_flags.append(0)

    main_entries = [
        s(T_NAME, args.name),
        s(T_VERSION, args.version),
        s(T_RELEASE, args.release),
        i18n(T_SUMMARY, args.summary),
        i18n(T_DESCRIPTION, args.description),
        i32(T_BUILDTIME, [mtime]),
        s(T_BUILDHOST, os.uname().nodename),
        i32(T_SIZE, [total_size]),
        s(T_LICENSE, args.license),
        s(T_PACKAGER, args.packager),
        i18n(T_GROUP, args.group),
        s(T_URL, args.url),
        s(T_OS, "linux"),
        s(T_ARCH, args.arch),
        i32(T_FILESIZES, sizes),
        i16(T_FILEMODES, modes),
        i16(T_FILERDEVS, [0] * len(items)),
        i32(T_FILEMTIMES, mtimes),
        sa(T_FILEDIGESTS, digests),
        sa(T_FILELINKTOS, links),
        i32(T_FILEFLAGS, flags),
        sa(T_FILEUSERNAME, users),
        sa(T_FILEGROUPNAME, groups),
        s(T_SOURCERPM, "%s-%s-%s.src.rpm" % (args.name, args.version, args.release)),
        i32(T_FILEVERIFYFLAGS, [0xFFFFFFFF] * len(items)),
        # 注意：ARCHIVESIZE(1046) 属于签名头专用 tag，放进主头会被 rpm 判为非法
        sa(T_PROVIDENAME, [args.name]),
        i32(T_PROVIDEFLAGS, [RPMSENSE_EQUAL]),
        sa(T_PROVIDEVERSION, ["%s-%s" % (args.version, args.release)]),
        i32(T_REQUIREFLAGS, req_flags),
        sa(T_REQUIREVERSION, req_vers),
        sa(T_REQUIRENAME, req_names),
        i32(T_DIRINDEXES, dirindexes),
        sa(T_BASENAMES, basenames),
        sa(T_DIRNAMES, dirnames),
        s(T_PAYLOADFORMAT, "cpio"),
        s(T_PAYLOADCOMPRESSOR, "gzip"),
        s(T_PAYLOADFLAGS, "9"),
        i32(T_FILEDIGESTALGO, [8]),  # SHA-256
        # 现代 rpm 把「payload 摘要」放在主头里（签名头只放 header 摘要）
        sa(T_PAYLOADSHA256, [hashlib.sha256(payload).hexdigest()]),
        i32(T_PAYLOADSHA256ALGO, [8]),
    ]

    n_main = len(main_entries) + 2
    main_entries.append(sa(100, ["C"]))               # HEADERI18NTABLE
    main_entries.append(bin_(63, struct.pack(          # HEADERIMMUTABLE（区域描述符）
        ">IIII", 63, BIN, (-(n_main * 16)) & 0xFFFFFFFF, 16)))
    main_blob = build_header(main_entries, region_tag=63)
    # rpm 的约定：SHA1/SHA256 摘要只覆盖 header 区，MD5 覆盖 header+payload
    signed = main_blob + payload
    sha1 = hashlib.sha1(main_blob).hexdigest()
    sha256 = hashlib.sha256(main_blob).hexdigest()
    md5 = hashlib.md5(signed).digest()

    # 签名头：与真实 Fedora 包一致，用 269/273（header 摘要）+ 1000/1004/1007
    sig_items = [
        s(269, sha1),
        s(273, sha256),
        i32(1000, [len(main_blob) + len(payload)]),
        bin_(1004, md5),
        i32(1007, [len(cpio)]),
    ]
    n_sig = len(sig_items) + 1
    # tag 62 的 offset 是「负索引长度」，表示这块区域覆盖整个 header
    entry62 = bin_(62, struct.pack(">IIII", 62, BIN, (-(n_sig * 16)) & 0xFFFFFFFF, 16))
    sig_blob = build_header([entry62] + sig_items)
    # 主头必须落在 8 字节对齐的文件偏移上
    file_pad = (8 - ((96 + len(sig_blob)) % 8)) % 8

    archnum = 1 if args.arch in ("x86_64", "amd64") else 0
    # lead 固定 96 字节：magic[4] major[1] minor[1] type[2] archnum[2] name[66]
    #                     osnum[2] sigtype[2] reserved[16]
    lead = b"\xed\xab\xee\xdb" + bytes([3, 0]) + struct.pack(">HH", 0, archnum)
    name = ("%s-%s-%s" % (args.name, args.version, args.release)).encode()
    lead += name[:66].ljust(66, b"\0")
    lead += struct.pack(">H", 1)      # os = Linux
    lead += struct.pack(">H", 5)      # signature type
    lead += b"\0" * 16

    with open(args.out, "wb") as f:
        f.write(lead + sig_blob + b"\0" * file_pad + main_blob + payload)

    print("rpm: %s (%.1f KB, %d files)" % (args.out, os.path.getsize(args.out) / 1024.0, len(items)))


if __name__ == "__main__":
    main()
