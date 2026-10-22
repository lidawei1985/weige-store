# -*- coding: utf-8 -*-
"""
自研应用接入
============
把伟哥投屏 / 伟哥直播 / 星幕 / 心屋这些自己做的 APP 也挂进商店，
跟当贝那批应用一样能搜、能装、能更新。

目录结构（都跟着仓库走，公网直取）：
    self/apk/<slug>.apk      安装包
    self/icon/<slug>.png     图标（从 APK 里抽出来的）
    self/apps.json           清单

用法：
    python self_apps.py scan                 # 扫 self_apk/ 里所有 apk，有新版就收录
    python self_apps.py add a.apk b.apk      # 收录指定 apk
    python self_apps.py list                 # 看看现在收了哪些

收录后会自动：解析包名版本 → 复制成 self/apk/<slug>.apk → 抽图标 → 更新 apps.json。
再跑 export.py 或等 Actions，电视上就能看到新版本。
"""
import json
import os
import shutil
import sys
import time

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

SELF_DIR = os.path.join(BASE_DIR, "self")
APK_DIR = os.path.join(SELF_DIR, "apk")
ICON_DIR = os.path.join(SELF_DIR, "icon")
MANIFEST = os.path.join(SELF_DIR, "apps.json")

# 公网直链：jsDelivr 为主，ghproxy 兜底（客户端下载失败会自动换）
CDN = "https://cdn.jsdelivr.net/gh/lidawei1985/weige-store@main/self/"
MIRROR = "https://ghproxy.net/https://raw.githubusercontent.com/lidawei1985/weige-store/main/self/"

# 包名 -> (文件名, 分类, 简介)。没收录的包名会自动用包名当文件名。
KNOWN = {
    "io.github.jqssun.airplay": ("weige-screen", "工具",
                                 "自己家的投屏接收端，手机一点就推到电视上，不用登录不用投币"),
    "com.weige.live": ("weige-live", "影音",
                       "自己家的电视直播，频道自己维护，没有广告和购物台"),
    "com.yinghuo.tv": ("xingmu", "影音",
                       "星幕影视，自己家的片源聚合，无广告无弹窗，遥控器直接操作"),
    "com.zhuiyi.tv": ("xinwu", "影音",
                      "心屋影院，自己家的影视库，海报墙 + 收藏 + 续播"),
    "com.weige.fresh": ("weige-fresh", "工具",
                        "伟哥保鲜，占位小工具"),
    "com.weige.appstore": ("weige-store", "工具",
                           "伟哥应用商店自己，换电视时用它把其它应用装回来"),
}


def _mkdirs():
    for d in (SELF_DIR, APK_DIR, ICON_DIR):
        os.makedirs(d, exist_ok=True)


def load():
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"apps": []}


def save(data):
    _mkdirs()
    data["apps"].sort(key=lambda a: a.get("order", 99))
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)


def _slug(pkg):
    if pkg in KNOWN:
        return KNOWN[pkg][0]
    return pkg.replace(".", "_")


def _fallback_icon(apk_path, out_png, name):
    """apkmeta 合不出图标时兜底：优先找 apk 里最大的 launcher png，再不行画一张文字图"""
    import zipfile
    best = None
    try:
        with zipfile.ZipFile(apk_path) as z:
            cands = []
            for n in z.namelist():
                ln = n.lower()
                if not ln.endswith(".png"):
                    continue
                if ("mipmap" in ln or "launcher" in ln or "ic_icon" in ln):
                    cands.append((z.getinfo(n).file_size, n))
            cands.sort(reverse=True)
            for _sz, n in cands[:6]:
                blob = z.read(n)
                if blob[:8] == b"\x89PNG\r\n\x1a\n":
                    best = blob
                    break
        if best:
            with open(out_png, "wb") as f:
                f.write(best)
            return True
    except Exception:
        pass

    try:
        from PIL import Image, ImageDraw, ImageFont
        S = 432
        im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.23),
                            fill=(59, 130, 246, 255))
        ch = (name or "伟")[0]
        f = ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc", int(S * 0.5))
        bb = d.textbbox((0, 0), ch, font=f)
        d.text(((S - (bb[2] - bb[0])) / 2 - bb[0], (S - (bb[3] - bb[1])) / 2 - bb[1]),
               ch, font=f, fill=(255, 255, 255, 255))
        im.resize((256, 256), Image.LANCZOS).save(out_png)
        return True
    except Exception:
        return False


def add(apk_path, verbose=True):
    """收录一个 APK；同包名且版本没变时跳过，返回 'added' / 'updated' / 'skip'。"""
    import apkmeta

    apk_path = os.path.abspath(apk_path)
    if not os.path.exists(apk_path):
        if verbose:
            print("  找不到文件:", apk_path)
        return "skip"

    icon_tmp = os.path.join(ICON_DIR, "_tmp.png")
    _mkdirs()
    try:
        info = apkmeta.parse_apk(apk_path, icon_tmp)
    except Exception as e:
        if verbose:
            print("  解析失败 %s: %s" % (os.path.basename(apk_path), e))
        return "skip"

    pkg = info.get("pkg") or ""
    if not pkg:
        if verbose:
            print("  读不出包名，跳过:", os.path.basename(apk_path))
        return "skip"

    slug, cat, desc = KNOWN.get(pkg, (_slug(pkg), "工具", ""))
    data = load()
    apps = data.get("apps", [])
    cur = next((a for a in apps if a.get("pkg") == pkg), None)
    ver = info.get("versionName") or ""
    code = int(info.get("versionCode") or 0)

    if cur is not None:
        old_code = int(cur.get("code") or 0)
        same_ver = (cur.get("ver") == ver)
        if old_code > 0 and code > 0:
            if code < old_code:
                if verbose:
                    print("  %-12s 已有更新版本 %s，本次 %s 更旧，跳过"
                          % (info.get("name", pkg), cur.get("ver"), ver))
                return "skip"
            if code == old_code and same_ver:
                if verbose:
                    print("  %-12s %s 已是最新，跳过" % (info.get("name", pkg), ver))
                return "skip"
        elif same_ver:
            if verbose:
                print("  %-12s %s 已是最新，跳过" % (info.get("name", pkg), ver))
            return "skip"

    # 落盘
    dst_apk = os.path.join(APK_DIR, slug + ".apk")
    shutil.copyfile(apk_path, dst_apk)
    dst_icon = os.path.join(ICON_DIR, slug + ".png")
    has_icon = os.path.exists(icon_tmp)
    if has_icon:
        shutil.move(icon_tmp, dst_icon)
    else:
        # apkmeta 合不出（adaptive icon 被混淆过），自己再找一遍
        has_icon = (os.path.exists(dst_icon)
                    or _fallback_icon(apk_path, dst_icon, info.get("name") or slug))

    entry = {
        "pkg": pkg,
        "slug": slug,
        "name": info.get("name") or slug,
        "ver": ver,
        "code": code,
        "size": os.path.getsize(dst_apk),
        "cat": cat,
        "desc": desc,
        "day": time.strftime("%Y-%m-%d"),
        "order": len(apps),
        "icon": bool(has_icon),
    }
    if cur is not None:
        apps.remove(cur)
        entry["order"] = cur.get("order", len(apps))
    apps.append(entry)
    data["apps"] = apps
    save(data)

    if verbose:
        print("  %s %-12s %-10s %s"
              % ("更新" if cur else "新增", entry["name"], entry["ver"],
                 "%.1fMB" % (entry["size"] / 1048576.0)))
    return "updated" if cur else "added"


def collect():
    """给 export.py 用：返回索引条目列表"""
    import pinyin

    data = load()
    out = []
    for a in data.get("apps", []):
        slug = a["slug"]
        rel_apk = "apk/%s.apk" % slug
        if not os.path.exists(os.path.join(SELF_DIR, rel_apk)):
            continue
        icon = ""
        if a.get("icon") and os.path.exists(os.path.join(ICON_DIR, slug + ".png")):
            icon = CDN + "icon/%s.png" % slug
        out.append({
            "id": "self:" + slug,
            "name": a.get("name", ""),
            "ver": a.get("ver", ""),
            "size": a.get("size", 0),
            "cat": a.get("cat", "工具"),
            "icon": icon,
            "apk": CDN + rel_apk,
            "apk2": MIRROR + rel_apk,
            "pkg": a.get("pkg", ""),
            "day": a.get("day", ""),
            "desc": a.get("desc", ""),
            "py": pinyin.initials(a.get("name", "")),
            "self": True,
        })
    return out


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "scan"
    if cmd == "scan":
        src_dir = os.path.join(BASE_DIR, "self_apk")
        if not os.path.isdir(src_dir):
            print("没有 self_apk 目录，把要收录的 apk 丢进去再跑")
            return
        files = [os.path.join(src_dir, f) for f in sorted(os.listdir(src_dir))
                 if f.lower().endswith(".apk")]
        print("扫描 %d 个安装包…" % len(files))
        n = 0
        for f in files:
            if add(f) in ("added", "updated"):
                n += 1
        print("完成：%d 个有变化" % n)
    elif cmd == "add":
        for f in sys.argv[2:]:
            add(f)
    elif cmd == "list":
        for a in load().get("apps", []):
            print("  %-14s %-22s %-10s %5.1fMB  %s"
                  % (a.get("name"), a.get("pkg"), a.get("ver"),
                     a.get("size", 0) / 1048576.0, a.get("day")))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
