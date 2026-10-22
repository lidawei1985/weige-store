# -*- coding: utf-8 -*-
"""
publish_to_store.py —— 本项目发布到「伟哥商店」的唯一入口
==========================================================
用法（在本工程根目录）:
    python publish_to_store.py              # 自动找本工程最新构建的 APK 上架
    python publish_to_store.py 某个.apk     # 指定 APK 上架

流程：读包名 -> 复制到商店仓库 self_apk/<包名>.apk -> 收录+重建索引 -> git 推送
     CI 几分钟内重建线上索引，电视端打开「伟哥商店」自己点「更新」。

规矩（用户拍板，必须遵守）:
  1. 本项目出新版，确定可用后立刻跑这个脚本，不要等、不要让别人代传
  2. versionCode 必须比上一版大（gradle 里已配自动递增就别改回去），
     否则商店判不出更新 / 或被别人当旧包拦掉
  3. 传完不要用 adb 手动装电视（除非用户明确发话），电视自己在商店里更
  4. push 失败多半是网络抖动，脚本会自动重试 6 轮；还失败就稍后再跑一次
"""
import os
import shutil
import subprocess
import sys
import time

STORE_DIR = r"F:\2026-10-22-14-49-53\cleanstore"
AAPT = r"C:\Users\sbqqq\Android\build-tools\36.0.0\aapt.exe"

# 自动找 APK 的候选目录（相对本脚本所在目录）
SEARCH_DIRS = [
    "app/build/outputs/apk/release",
    "app/build/outputs/apk/debug",
    "launcher/build/outputs/apk/release",
    "launcher/build/outputs/apk/debug",
    ".",
]


def read_pkg(apk_path):
    try:
        out = subprocess.check_output([AAPT, "dump", "badging", apk_path],
                                      stderr=subprocess.DEVNULL).decode("utf-8", "ignore")
        for line in out.splitlines():
            if line.startswith("package:"):
                return line.split("name='")[1].split("'")[0]
    except Exception:
        pass
    return None


def newest_apk():
    here = os.path.dirname(os.path.abspath(__file__))
    best = None
    for d in SEARCH_DIRS:
        full = os.path.join(here, d)
        if not os.path.isdir(full):
            continue
        for root, _, files in os.walk(full):
            for f in files:
                if f.lower().endswith(".apk") and not f.endswith(".idsig"):
                    p = os.path.join(root, f)
                    if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                        best = p
        break  # 命中第一个存在的候选目录就不再往下找
    return best


def git(args):
    # remote 已配 SSH over 443（github-store），HTTPS 直连 github.com 不稳
    return subprocess.call(["git"] + list(args), cwd=STORE_DIR)


def main():
    here = os.path.dirname(os.path.abspath(__file__))

    apk = sys.argv[1] if len(sys.argv) > 1 else newest_apk()
    if not apk or not os.path.isfile(apk):
        print("找不到 APK：先构建，或直接给文件名 python publish_to_store.py xxx.apk")
        return 1
    apk = os.path.abspath(apk)
    print("发布:", apk)

    pkg = read_pkg(apk)
    if not pkg:
        print("读不出包名，中止")
        return 1
    print("包名:", pkg)

    dst = os.path.join(STORE_DIR, "self_apk", pkg + ".apk")
    if os.path.exists(dst) and os.path.getsize(dst) == os.path.getsize(apk):
        print("商店里已是同字节版本，无需发布")
        return 0
    shutil.copyfile(apk, dst)
    print("已放进商店进货口 self_apk/%s.apk" % pkg)

    for cmd in ([sys.executable, "self_apps.py", "scan"],
                [sys.executable, "export.py"]):
        print("+", " ".join(cmd))
        if subprocess.call(cmd, cwd=STORE_DIR) != 0:
            print("!! 收录/索引失败")
            return 1

    subprocess.call(["git", "add", "-A"], cwd=STORE_DIR)
    if subprocess.call(["git", "diff", "--cached", "--quiet"], cwd=STORE_DIR) == 0:
        print("索引没有变化")
        return 0
    subprocess.call(["git", "commit", "-m", "auto: %s 上架 %s"
                     % (pkg, time.strftime("%Y-%m-%d %H:%M"))], cwd=STORE_DIR)

    for i in range(6):
        git(["pull", "--no-rebase", "-X", "theirs", "origin", "main"])
        if git(["push", "origin", "main"]) == 0:
            print("发布完成！CI 重建索引后，电视端打开「伟哥商店」即可看到更新")
            return 0
        print("push 失败（网络抖动），45 秒后重试…")
        time.sleep(45)
    print("!! push 一直失败：手动到 %s 里跑 git push origin main" % STORE_DIR)
    return 1


if __name__ == "__main__":
    sys.exit(main())
