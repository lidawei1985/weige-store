# -*- coding: utf-8 -*-
"""
从 APK 前缀（HTTP Range 只取前几百 KB）解析包名 / 版本 / 应用名
==============================================================
索引里没有包名，客户端就没法判断「已安装 / 可更新」。
整包下载几百 MB 不现实，但 AndroidManifest.xml 在 zip 里几乎总在最前面，
所以只取前 512KB 就够：扫 zip 本地文件头定位它，解压，读 package 属性。

坑：部分 APK（当贝这批就是）的 AXML 字符串池 flags 没标 0x100，
但内容其实是 UTF-8。所以编码要靠内容自检，不能信 flags。

    python pkgprobe.py <apk_url> [前多少字节]
"""
import struct
import sys
import urllib.request
import zlib

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")


# ---------------------------------------------------------------- HTTP 前缀
def fetch_prefix(url, nbytes=524288, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Range": "bytes=0-%d" % (nbytes - 1),
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def find_manifest(data):
    """扫 zip 本地文件头（PK\\x03\\x04），返回解压后的 AndroidManifest.xml"""
    pos, n = 0, len(data)
    while pos + 30 <= n:
        if data[pos:pos + 4] != b"PK\x03\x04":
            pos += 1
            continue
        (_sig, _ver, _flag, method, _t, _d, _crc,
         csize, usize, nlen, elen) = struct.unpack_from("<IHHHHHIIIHH", data, pos)
        name = data[pos + 30:pos + 30 + nlen].decode("utf-8", "replace")
        dstart = pos + 30 + nlen + elen
        if name == "AndroidManifest.xml":
            raw = data[dstart:dstart + csize] if csize else data[dstart:dstart + usize]
            if method == 0:
                return raw
            try:
                return zlib.decompressobj(-15).decompress(raw)
            except zlib.error:
                return None
        if csize == 0:
            return None       # 流式写（flag bit3），没法跳，放弃
        pos = dstart + csize
    return None


# ---------------------------------------------------------------- AXML 解析
def _varint(d, p):
    v = d[p]
    if v & 0x80:
        return ((v & 0x7F) << 8) | d[p + 1], p + 2
    return v, p + 1


def _read_pool(d, base, hsize, utf8):
    cnt, _sty, _flags, sstart = struct.unpack_from("<IIII", d, base + 8)
    if cnt > 20000:
        return []
    offs = struct.unpack_from("<%dI" % cnt, d, base + hsize)
    out = []
    for off in offs:
        try:
            p = base + sstart + off
            if utf8:
                _n, p = _varint(d, p)
                ln, p = _varint(d, p)
                out.append(d[p:p + ln].decode("utf-8", "replace"))
            else:
                # UTF-16 池的长度是固定 2 字节 u16，不是 varint（踩过）
                ln = struct.unpack_from("<H", d, p)[0]
                p += 2
                out.append(d[p:p + ln * 2].decode("utf-16-le", "replace"))
        except Exception:
            out.append("")
    return out


def _pool_score(strings):
    """哪种编码解出来更像真字符串"""
    if not strings:
        return -1
    keys = ("manifest", "package", "versionName", "application", "uses-sdk")
    hit = sum(1 for k in keys
              if any(isinstance(s, str) and s == k for s in strings))
    printable = 0
    for s in strings:
        if isinstance(s, str) and s:
            try:
                if all(ord(c) >= 32 for c in s):
                    printable += 1
            except TypeError:
                pass
    return hit * 10 + printable


def parse_axml(d):
    """返回 (包名, 版本名, 应用名)"""
    pos, n = 8, len(d)
    strings, elements = [], []
    while pos + 8 <= n:
        ctype, hsize, csize = struct.unpack_from("<HHI", d, pos)
        if csize < 8 or pos + csize > n:
            break
        if ctype == 0x0001:
            a = _read_pool(d, pos, hsize, False)   # 先按 UTF-16
            b = _read_pool(d, pos, hsize, True)    # 再按 UTF-8
            strings = a if _pool_score(a) >= _pool_score(b) else b
        elif ctype == 0x0102 and strings:
            p = pos + 16
            _ns, name_i = struct.unpack_from("<II", d, p)
            astart, asize, acount = struct.unpack_from("<HHH", d, p + 8)
            name = strings[name_i] if name_i < len(strings) else ""
            attrs = {}
            ap = pos + 16 + astart
            for i in range(min(acount, 64)):
                q = ap + i * asize
                if q + 20 > n:
                    break
                _ans, an, raw = struct.unpack_from("<III", d, q)
                _vs, _r0, dtype, dval = struct.unpack_from("<HBBI", d, q + 12)
                key = strings[an] if an < len(strings) else ""
                key = key.split(":")[-1]
                if dtype == 0x03:
                    val = strings[dval] if dval < len(strings) else ""
                elif raw != 0xFFFFFFFF and raw < len(strings) and dtype not in (0x10, 0x11, 0x12):
                    val = strings[raw]
                else:
                    val = dval
                attrs[key] = val
            elements.append((name, attrs))
        pos += csize

    pkg = ver = label = ""
    for name, attrs in elements:
        if name == "manifest" and not pkg:
            pkg = attrs.get("package") or ""
            ver = str(attrs.get("versionName") or "")
        if name == "application" and not label:
            label = attrs.get("label") or ""
    return pkg, ver, label if isinstance(label, str) else ""


def probe(url, nbytes=524288):
    """返回 (包名, 版本名)，失败返回 (None, None)"""
    try:
        mx = find_manifest(fetch_prefix(url, nbytes))
        if not mx:
            return None, None
        pkg, ver, _label = parse_axml(mx)
        return (pkg or None), (ver or None)
    except Exception:
        return None, None


if __name__ == "__main__":
    u = sys.argv[1]
    nb = int(sys.argv[2]) if len(sys.argv) > 2 else 524288
    print("包名=%s 版本=%s" % probe(u, nb))
