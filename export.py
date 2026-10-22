# -*- coding: utf-8 -*-
"""
索引生成器 —— 无人值守用
=========================
抓当贝市场全部分类 + 新秀榜，合成一份静态 index.json。
GitHub Actions 定时跑它，客户端只读这份 JSON，图标和 APK 都直连上游 CDN。

设计要点（都是为了「一劳永逸」）：
  · 增量合并 —— 这次没抓到的应用保留上次的记录，网络抖动不会让应用凭空消失
  · 单类失败不影响整体 —— 每个分类独立 try，失败记进 errors 字段
  · 体积可控 —— desc 截断，字段精简，800 个应用约 250KB
  · 幂等 —— 同一个输入产出同一份文件，方便 git 判断有没有变化

    python export.py                   # 输出 site/index.json
    python export.py --out x.json
"""
import argparse
import json
import os
import sys
import time

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)
import source_dangbei as src  # noqa: E402
import curation  # noqa: E402

DESC_MAX = 110
BLOCKED = [0]          # 被白名单拦掉的数量（用于日志）


def slim(a, cat=""):
    d = a.get("desc", "") or ""
    if len(d) > DESC_MAX:
        d = d[:DESC_MAX] + "…"
    icon = a.get("icon_url", "") or ""
    if icon.startswith("//"):
        icon = "https:" + icon
    return {
        "id": a["id"],
        "name": a.get("name", ""),
        "ver": a.get("ver", ""),
        "size": a.get("size", 0),
        "cat": curation.refine_cat(a.get("name", ""), cat),
        "icon": icon,
        "apk": a.get("apk_url", ""),
        "day": a.get("day", ""),
        "desc": d,
    }


def merge(bag, a, cat=""):
    """只放行白名单应用；同一个 id 保留信息更全的那份。"""
    name = a.get("name", "")
    eff_cat = cat or bag.get(a["id"], {}).get("cat", "") or a.get("cat", "")

    good, _why = curation.is_safe(name, eff_cat if eff_cat in curation.ALLOW_GROUPS else "")
    if not good:
        BLOCKED[0] += 1
        return

    cur = bag.get(a["id"])
    new = slim(a, eff_cat)
    if cur is None:
        bag[a["id"]] = new
        return
    for k, v in new.items():
        if v not in ("", 0, None):
            cur[k] = v
        elif not cur.get(k):
            cur[k] = v


def load_old(path):
    try:
        with open(path, encoding="utf-8") as f:
            return {a["id"]: a for a in json.load(f).get("apps", [])}
    except Exception:
        return {}


def build(out_path):
    t0 = time.time()
    old = load_old(out_path)
    bag = {}
    errors = []

    for cid, name, group in src.CATS:
        try:
            apps = src.fetch_cat(cid, group)
            for a in apps:
                merge(bag, a, group)
            print("  %-8s %3d 条" % (name, len(apps)))
        except Exception as e:
            errors.append("%s: %s" % (name, e))
            print("  %-8s 失败: %s" % (name, e))
        time.sleep(0.4)

    try:
        news = src.fetch_new()
        for a in news:
            merge(bag, a, a.get("cat", ""))
        print("  %-8s %3d 条" % ("新秀榜", len(news)))
    except Exception as e:
        errors.append("新秀榜: %s" % e)
        print("  新秀榜 失败: %s" % e)

    fresh = len(bag)
    for k, v in old.items():
        if k not in bag:
            # 老数据也要过一遍白名单，避免以前混进来的垃圾一直留着
            good, _ = curation.is_safe(v.get("name", ""), v.get("cat", ""))
            if good:
                bag[k] = v

    apps = list(bag.values())
    # 有更新日期的排前面，其次按名称
    apps.sort(key=lambda x: (x.get("day") or "", x.get("name") or ""), reverse=True)

    data = {
        "v": 1,
        "name": "伟哥应用商店",
        "updated": int(time.time()),
        "updated_text": time.strftime("%Y-%m-%d %H:%M"),
        "count": len(apps),
        "fresh": fresh,
        "safe_only": True,
        "blocked": BLOCKED[0],
        "errors": errors,
        "apps": apps,
    }

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, out_path)

    kb = os.path.getsize(out_path) / 1024.0
    print("  完成：白名单放行 %d 个，拦掉 %d 个不安全/不相关，%.0fKB，用时 %.1fs"
          % (len(apps), BLOCKED[0], kb, time.time() - t0))
    if errors:
        print("  有 %d 个分类失败（已用上次数据补齐）" % len(errors))
    return data


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(BASE_DIR, "site", "index.json"))
    args = ap.parse_args()
    print("开始抓取…")
    build(args.out)
