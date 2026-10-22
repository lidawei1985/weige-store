# -*- coding: utf-8 -*-
"""APK 元信息解析（无第三方依赖）

优先用 aapt2 dump badging（最准，能拿到多语言 label 与各密度图标路径）；
找不到 aapt2 时回退到纯 Python 的二进制 AXML 解析（能拿包名/版本/字面量名称，
图标退化为「res/ 里最大的 png」启发式）。
"""
import glob
import io
import os
import re
import shutil
import struct
import subprocess
import zipfile


# ---------------------------------------------------------------- aapt2 定位
def find_aapt2():
    cands = []
    env = os.environ.get("AAPT2")
    if env:
        cands.append(env)
    here = os.path.dirname(os.path.abspath(__file__))
    cands += glob.glob(os.path.join(here, "tools", "aapt2.exe"))
    cands += glob.glob(r"C:\Users\*\Android\build-tools\*\aapt2.exe")
    cands += glob.glob(r"C:\Android\build-tools\*\aapt2.exe")
    cands += glob.glob(r"D:\Android\build-tools\*\aapt2.exe")
    cands += glob.glob(os.path.expanduser("~/Android/build-tools/*/aapt2.exe"))
    for c in cands:
        if c and os.path.isfile(c):
            return c
    # 同目录下 *-linux aapt2（NAS 场景）
    cands2 = glob.glob(os.path.join(here, "tools", "aapt2"))
    for c in cands2:
        if os.path.isfile(c):
            return c
    return shutil.which("aapt2")


AAPT2 = find_aapt2()


# ---------------------------------------------------------------- 纯 Python AXML
class _AXML(object):
    """Android 二进制 XML（AXML）的最小解析器，收集全部起始元素。"""

    def __init__(self, data):
        self.data = data
        self.strings = []
        self.elements = []
        self.manifest = None
        self._read()

    def _read(self):
        data = self.data
        if len(data) < 8:
            raise ValueError("axml too short")
        pos = 8
        n = len(data)
        while pos + 8 <= n:
            ctype, hsize, csize = struct.unpack_from("<HHI", data, pos)
            if csize < 8 or pos + csize > n:
                break
            if ctype == 0x0001:  # string pool
                self.strings = self._string_pool(pos, hsize, csize)
            elif ctype == 0x0102:  # start element
                el = self._start_element(pos, hsize, csize)
                if el:
                    self.elements.append(el)
                    if el[0] == "manifest" and self.manifest is None:
                        self.manifest = el[1]
            pos += csize

    def _string_pool(self, base, hsize, csize):
        data = self.data
        count, style_count, flags, str_start, _sty_start = struct.unpack_from(
            "<IIIII", data, base + 8
        )
        utf8 = bool(flags & 0x100)
        offs = struct.unpack_from("<%dI" % count, data, base + hsize)
        out = []
        for off in offs:
            p = base + str_start + off
            try:
                if utf8:
                    # 两段 varint：UTF-16 长度、UTF-8 字节数
                    p, _ = self._varint(data, p)
                    ln, p = self._varint(data, p)
                    out.append(data[p:p + ln].decode("utf-8", "replace"))
                else:
                    ln, p = self._varint(data, p)
                    out.append(data[p:p + ln * 2].decode("utf-16-le", "replace"))
            except Exception:
                out.append("")
        return out

    @staticmethod
    def _varint(data, p):
        v = data[p]
        if v & 0x80:
            return ((v & 0x7F) << 8) | data[p + 1], p + 2
        return v, p + 1

    def _start_element(self, base, hsize, csize):
        data = self.data
        # 节点头 16 字节后：ns(4) name(4) attrStart(2) attrSize(2) attrCount(2) ...
        p = base + 16
        ns_i, name_i = struct.unpack_from("<II", data, p)
        attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, p + 8)
        name = self.strings[name_i] if name_i < len(self.strings) else ""
        attrs = {}
        ap = base + 16 + attr_start
        for i in range(attr_count):
            q = ap + i * attr_size
            if q + 20 > len(data):
                break
            _ans, an, raw = struct.unpack_from("<III", data, q)
            _vsize, _res0, dtype, dval = struct.unpack_from("<HBBI", data, q + 12)
            key = self.strings[an] if an < len(self.strings) else ""
            key = key.split(":")[-1]
            if dtype == 0x03:  # 字符串引用
                val = self.strings[dval] if dval < len(self.strings) else ""
            elif dtype == 0x10:  # 十进制 int
                val = dval
            elif dtype == 0x12:  # 十六进制 int
                val = dval
            elif dtype == 0x01:  # 资源引用
                val = "@0x%08x" % dval
            elif raw != 0xFFFFFFFF and raw < len(self.strings):
                val = self.strings[raw]
            else:
                val = dval
            attrs[key] = val
        return name, attrs


def _fallback_parse(path):
    with zipfile.ZipFile(path) as z:
        axml = _AXML(z.read("AndroidManifest.xml"))
    a = getattr(axml, "manifest", {}) or {}
    pkg = a.get("package") or ""
    label = a.get("label") or ""
    if isinstance(label, str) and label.startswith("@0x"):
        label = ""  # 是资源引用，纯 Python 解不出来，交给上层用包名兜底
    info = {
        "pkg": pkg,
        "name": label or pkg,
        "versionName": str(a.get("versionName") or ""),
        "versionCode": a.get("versionCode") or 0,
        "minSdk": str(a.get("minSdkVersion") or ""),
        "targetSdk": str(a.get("targetSdkVersion") or ""),
        "icon_in_apk": "",
        "source": "axml",
    }
    try:
        info["versionCode"] = int(info["versionCode"])
    except Exception:
        info["versionCode"] = 0
    return info


# ---------------------------------------------------------------- aapt2 解析
_RE_KV = re.compile(r"(\w[\w-]*)=\'([^\']*)\'")


def _aapt2_parse(path):
    if not AAPT2:
        return None
    try:
        out = subprocess.run(
            [AAPT2, "dump", "badging", path],
            capture_output=True, timeout=120,
        ).stdout.decode("utf-8", "replace")
    except Exception:
        return None
    if "package: name=" not in out:
        return None

    info = {
        "pkg": "", "name": "", "versionName": "", "versionCode": 0,
        "minSdk": "", "targetSdk": "", "icon_in_apk": "", "source": "aapt2",
    }
    icons = {}
    labels = {}
    for line in out.splitlines():
        if line.startswith("package:"):
            kv = dict(_RE_KV.findall(line))
            info["pkg"] = kv.get("name", "")
            info["versionName"] = kv.get("versionName", "")
            try:
                info["versionCode"] = int(kv.get("versionCode", "0"))
            except Exception:
                info["versionCode"] = 0
        elif line.startswith("sdkVersion:"):
            info["minSdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("targetSdkVersion:"):
            info["targetSdk"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("application-label:"):
            info["name"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("application-label-"):
            lang = line.split(":", 1)[0][len("application-label-"):]
            labels[lang] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("application-icon-"):
            dpi = line.split(":", 1)[0][len("application-icon-"):]
            try:
                icons[int(dpi)] = line.split(":", 1)[1].strip().strip("'")
            except Exception:
                pass

    # 中文名优先
    for lang in ("zh-CN", "zh", "zh-Hans", "zh-TW"):
        if labels.get(lang):
            info["name"] = labels[lang]
            break
    if icons:
        best = max(icons)  # 取最高密度
        info["icon_in_apk"] = icons[best]
        info["icon_all"] = icons
    if not info["name"]:
        info["name"] = info["pkg"]
    return info


# ---------------------------------------------------------------- 图标抽取
_RE_RES = re.compile(r"^\s+resource (0x[0-9a-fA-F]+) (\S+)/(\S+)")
_RE_FILE = re.compile(r"\(([^)]*)\) \(file\) (\S+) type=(\w+)")


def _dump_resources(path):
    """{res_id: [(config, filepath), ...]}，解析失败返回 {}"""
    if not AAPT2:
        return {}
    try:
        out = subprocess.run(
            [AAPT2, "dump", "resources", path],
            capture_output=True, timeout=180,
        ).stdout.decode("utf-8", "replace")
    except Exception:
        return {}
    table = {}
    cur = None
    for line in out.splitlines():
        m = _RE_RES.match(line)
        if m:
            cur = m.group(1).lower()
            table.setdefault(cur, [])
            continue
        if cur:
            m2 = _RE_FILE.search(line)
            if m2:
                table[cur].append((m2.group(1), m2.group(2), m2.group(3)))
            elif line and not line.startswith(" "):
                cur = None
    return table


def _pick_png(entries):
    """从一个资源的多个配置里挑最像样的 png/webp。"""
    pngs = [(p, c) for c, p, t in entries if t in ("PNG", "WEBP")]
    if not pngs:
        return None
    # 密度越高的通常越靠后也越大，直接按路径里的 dpi 排序兜底
    order = {"ldpi": 1, "mdpi": 2, "hdpi": 3, "xhdpi": 4, "xxhdpi": 5, "xxxhdpi": 6}
    def score(p):
        for k, v in order.items():
            if k in p:
                return v
        return 0
    pngs.sort(key=lambda pc: score(pc[0]))
    return pngs[-1][0]


def _adaptive_to_png(z, xml_path, res_table, out_png):
    """把 adaptive-icon 的 xml 合成成一张 png。"""
    try:
        ax = _AXML(z.read(xml_path))
    except Exception:
        return False
    bg = fg = None
    bg_color = None
    for name, attrs in ax.elements:
        if name == "background":
            v = attrs.get("drawable")
            if isinstance(v, int) or (isinstance(v, str) and v.startswith("@0x")):
                rid = v if isinstance(v, int) else int(v[3:], 16)
                bg = _pick_png(res_table.get(("0x%08x" % rid), []))
            elif isinstance(v, str) and v.startswith("#"):
                bg_color = v
        elif name == "foreground":
            v = attrs.get("drawable")
            if isinstance(v, int) or (isinstance(v, str) and v.startswith("@0x")):
                rid = v if isinstance(v, int) else int(v[3:], 16)
                fg = _pick_png(res_table.get(("0x%08x" % rid), []))
    if not fg and not bg:
        return False

    try:
        from PIL import Image
    except Exception:
        # 没有 PIL：能拿到前景就直接当前景
        for cand in (fg, bg):
            if cand:
                try:
                    blob = z.read(cand)
                except KeyError:
                    continue
                if blob[:8] == b"\x89PNG\r\n\x1a\n":
                    with open(out_png, "wb") as f:
                        f.write(blob)
                    return True
        return False

    S = 432
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    if bg_color:
        try:
            from PIL import ImageColor
            canvas.paste(Image.new("RGBA", (S, S), ImageColor.getrgb(bg_color) + (255,)), (0, 0))
        except Exception:
            pass
    elif bg:
        try:
            im = Image.open(io.BytesIO(z.read(bg))).convert("RGBA").resize((S, S), Image.LANCZOS)
            canvas.paste(im, (0, 0), im)
        except Exception:
            pass
    if fg:
        try:
            im = Image.open(io.BytesIO(z.read(fg))).convert("RGBA").resize((S, S), Image.LANCZOS)
            canvas = Image.alpha_composite(canvas, im)
        except Exception:
            pass
    canvas.save(out_png, "PNG")
    return True


def _extract_icon(path, info, out_png):
    """把图标抽成 png，返回 True/False。"""
    cands = []
    all_icons = info.get("icon_all") or {}
    if info.get("icon_in_apk"):
        cands.append(info["icon_in_apk"])
    for dpi in sorted(all_icons, reverse=True):
        if all_icons[dpi] not in cands:
            cands.append(all_icons[dpi])

    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())

        # 1) 直接是图片
        for cn in cands:
            if cn not in names:
                continue
            try:
                blob = z.read(cn)
            except KeyError:
                continue
            if blob[:8] == b"\x89PNG\r\n\x1a\n" or (blob[:4] == b"RIFF" and blob[8:12] == b"WEBP"):
                with open(out_png, "wb") as f:
                    f.write(blob)
                return True

        # 2) adaptive icon（xml）→ 合成
        for cn in cands:
            if cn in names and cn.lower().endswith(".xml"):
                if _adaptive_to_png(z, cn, _dump_resources(path), out_png):
                    return True

        # 3) 启发式：res/mipmap* 里最大的 png
        pngs = [
            (z.getinfo(n).file_size, n) for n in names
            if n.lower().endswith((".png", ".webp"))
            and ("mipmap" in n or "drawable" in n)
        ]
        pngs.sort(reverse=True)
        for _s, n in pngs[:5]:
            blob = z.read(n)
            if blob[:8] == b"\x89PNG\r\n\x1a\n":
                with open(out_png, "wb") as f:
                    f.write(blob)
                return True
    return False


# ---------------------------------------------------------------- 对外入口
def parse_apk(path, icon_out=None):
    """解析 APK；icon_out 给出时顺便把图标写到该路径。"""
    info = _aapt2_parse(path)
    if not info:
        info = _fallback_parse(path)
    if not info.get("pkg"):
        raise ValueError("无法解析出包名，可能不是合法 APK")

    info["size"] = os.path.getsize(path)
    if icon_out:
        try:
            info["has_icon"] = _extract_icon(path, info, icon_out)
        except Exception:
            info["has_icon"] = False
    return info


if __name__ == "__main__":
    import json
    import sys
    for p in sys.argv[1:]:
        print(json.dumps(parse_apk(p), ensure_ascii=False, indent=2))
