# -*- coding: utf-8 -*-
"""当贝市场网页版数据源

已验证可用（2026-10）：
  分类列表  https://www.dangbei.com/app/tv/list-<id>.html
  新秀榜    https://www.dangbei.com/app/new/
  模糊搜索  https://www.dangbei.com/app/plus/search.php?q=<kw>&kwtype=1&searchtype=titlekeyword
  APK 直链  https://app.qingyingyong.net/down/<日期>/<文件名>.apk   （支持 Range）
  图标      https://pic.dangbei.net/...

注意：分类页没有分页（list-7-2.html 会 404），每类只有一页 ≈28 个；
      但模糊搜索能覆盖全库，所以「搜索」是主要入口。
"""
import gzip
import html as htmlmod
import re
import time
import urllib.parse
import urllib.request
import zlib

BASE = "https://www.dangbei.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 当贝分类 id → (显示名, 客户端大类)
CATS = [
    ("7",  "影音软件", "影音"),
    ("9",  "点播",     "影音"),
    ("22", "聚合",     "影音"),
    ("6",  "工具",     "工具"),
    ("1",  "电视软件", "工具"),
    ("18", "遥控",     "工具"),
    ("19", "鼠标",     "工具"),
    ("20", "手柄",     "工具"),
    ("4",  "生活",     "生活"),
    ("5",  "教育",     "生活"),
    ("3",  "娱乐",     "生活"),
    ("2",  "电视游戏", "游戏"),
    ("14", "休闲",     "游戏"),
    ("15", "桌游",     "游戏"),
    ("16", "动作",     "游戏"),
    ("17", "竞技",     "游戏"),
]
GROUPS = ["影音", "工具", "生活", "游戏"]


def fetch(url, timeout=25):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Encoding": "gzip, deflate",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": BASE + "/app/",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        enc = (r.headers.get("Content-Encoding") or "").lower()
        if "gzip" in enc:
            try:
                raw = gzip.decompress(raw)
            except OSError:
                pass
        elif "deflate" in enc:
            try:
                raw = zlib.decompress(raw, -zlib.MAX_WBITS)
            except zlib.error:
                pass
        return raw.decode("utf-8", "replace")


_TAG = re.compile(r"<[^>]+>")


def _clean(s):
    if not s:
        return ""
    s = _TAG.sub("", s)
    s = htmlmod.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


_SIZE_RE = re.compile(r"([\d.]+)\s*(KB|MB|GB)", re.I)
_VER_RE = re.compile(r"_([0-9][0-9A-Za-z.]*)_(?:dangbei|znds|db)(?:_|\.apk)", re.I)


def parse_size(txt):
    m = _SIZE_RE.search(txt or "")
    if not m:
        return 0
    v = float(m.group(1))
    unit = m.group(2).upper()
    return int(v * {"KB": 1024, "MB": 1048576, "GB": 1073741824}[unit])


def guess_ver(apk_url):
    fn = apk_url.rsplit("/", 1)[-1]
    m = _VER_RE.search(fn)
    return m.group(1) if m else ""


def parse_list(page):
    """从列表页 / 搜索结果页里抽出应用条目。"""
    out = []
    seen = set()
    # 每个卡片被 <div class="wrap"> 包裹
    blocks = re.split(r'<div class="wrap">', page)
    for blk in blocks[1:]:
        blk = blk[:4000]
        m = re.search(r'href="/app/tv/(\d{4})/(\d{4})/(\d+)\.html"', blk)
        if not m:
            continue
        app_id = m.group(3)
        if app_id in seen:
            continue
        seen.add(app_id)

        apk = re.search(r'href="(https?://[^"]+\.apk[^"]*)"', blk)
        if not apk:
            continue
        apk_url = htmlmod.unescape(apk.group(1))

        icon = re.search(r'<img[^>]+src="([^"]+)"', blk)
        icon_url = htmlmod.unescape(icon.group(1)) if icon else ""

        name = re.search(r'<p class="title">\s*<a[^>]*>(.*?)</a>', blk, re.S)
        desc = re.search(r'<p class="desc"[^>]*>(.*?)</p>', blk, re.S)
        size = re.search(r'大小：<font class="infoVal">([^<]*)</font>', blk)
        day = re.search(r'更新日期：<font class="infoVal">([^<]*)</font>', blk)
        star = re.search(r'class="star" number="(\d+)"', blk)

        nm = _clean(name.group(1)) if name else ""
        if not nm:
            nm = icon_url.rsplit("/", 1)[-1] or app_id

        out.append({
            "id": app_id,
            "name": nm,
            "desc": _clean(desc.group(1)) if desc else "",
            "size": parse_size(size.group(1) if size else ""),
            "size_str": (size.group(1).strip() if size else ""),
            "day": (day.group(1).strip() if day else ""),
            "ver": guess_ver(apk_url),
            "icon_url": icon_url,
            "apk_url": apk_url,
            "star": int(star.group(1)) if star else 0,
            "detail": "%s/app/tv/%s/%s/%s.html" % (BASE, m.group(1), m.group(2), app_id),
        })
    return out


_RE_PAGEINFO = re.compile(r'class="pageinfo">共\s*<strong>(\d+)</strong>页')


def fetch_cat(cat_id, group="", max_pages=12):
    """分类列表：第 1 页 list-<id>.html，第 n 页 list_<id>_<n>.html"""
    page = fetch("%s/app/tv/list-%s.html" % (BASE, cat_id))
    apps = parse_list(page)
    m = _RE_PAGEINFO.search(page)
    pages = min(int(m.group(1)), max_pages) if m else 1
    for n in range(2, pages + 1):
        try:
            apps.extend(parse_list(
                fetch("%s/app/tv/list_%s_%d.html" % (BASE, cat_id, n))))
        except Exception:
            break
        time.sleep(0.3)
    for a in apps:
        a["cat"] = group
        a["cat_id"] = cat_id
        a["src"] = "cat"
    return apps


def fetch_new():
    apps = parse_list(fetch(BASE + "/app/new/"))
    for a in apps:
        a.setdefault("cat", "")
        a["cat_id"] = "new"
        a["src"] = "new"
    return apps


def search(kw):
    q = urllib.parse.quote(kw)
    url = ("%s/app/plus/search.php?q=%s&kwtype=1&searchtype=titlekeyword" % (BASE, q))
    apps = parse_list(fetch(url))
    for a in apps:
        a["cat_id"] = "search"
        a["src"] = "search"
    return apps


if __name__ == "__main__":
    import json
    import sys
    what = sys.argv[1] if len(sys.argv) > 1 else "new"
    if what == "new":
        r = fetch_new()
    elif what == "search":
        r = search(sys.argv[2])
    else:
        r = fetch_cat(what, "影音")
    print("共 %d 条" % len(r))
    print(json.dumps(r[:3], ensure_ascii=False, indent=2))
