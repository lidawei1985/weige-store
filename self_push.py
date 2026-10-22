# -*- coding: utf-8 -*-
"""
自研 APP 一键上架（发布流程的唯一入口）
========================================
用法（在 cleanstore 目录下）:
    python self_push.py                 # 收录 self_apk/ 里已有的新包 + 重建索引 + 推送
    python self_push.py 某个.apk ...    # 先把指定 APK 按包名放进 self_apk/ 再走上面流程

规矩（用户拍板）:
  自研所有 APP 改完、新版本确定可用后，直接跑这个脚本上架商店，
  电视端打开商店自己点「更新」。不需要再通知谁去安装最新版，
  也绝不用 adb 手动逐台装（除非用户明确发话）。
"""
import json
import os
import shutil
import subprocess
import sys

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SELF_APK = os.path.join(BASE_DIR, "self_apk")

# 已知自研 APP 的构建产物目录（按包名 -> 候选目录列表，取里面最新的 apk）
# 新建了自研 APP 工程就到这里加一行
KNOWN_DIRS = {
    "com.weige.live": [
        r"F:\2026-10-22-14-36-54",
        r"F:\2026-10-22-14-36-54\weige-live\app\build\outputs\apk\release",
    ],
    "com.weige.appstore": [
        os.path.join(BASE_DIR, "app"),
    ],
}


def pkg_of(apk_path):
    """用 aapt 读包名，失败返回 None"""
    aapt = r"C:\Users\sbqqq\Android\build-tools\36.0.0\aapt.exe"
    try:
        out = subprocess.check_output([aapt, "dump", "badging", apk_path],
                                      stderr=subprocess.DEVNULL).decode("utf-8", "ignore")
        for line in out.splitlines():
            if line.startswith("package:"):
                return line.split("name='")[1].split("'")[0]
    except Exception:
        pass
    return None


def newest_apk_in(dirs):
    """目录列表里找 mtime 最新的 apk"""
    best = None
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if f.lower().endswith(".apk") and not f.endswith(".idsig"):
                p = os.path.join(d, f)
                if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                    best = p
    return best


def run(cmd):
    print("+", " ".join(cmd))
    r = subprocess.call(cmd, cwd=BASE_DIR)
    if r != 0:
        print("!! 命令失败 rc=%s: %s" % (r, " ".join(cmd)))
    return r


def main():
    args = sys.argv[1:]

    # 1) 显式给 APK 的，按包名归位到 self_apk/<pkg>.apk
    for a in args:
        if not os.path.isfile(a):
            print("找不到文件:", a)
            continue
        pkg = pkg_of(a)
        if not pkg:
            print("读不出包名，跳过:", a)
            continue
        dst = os.path.join(SELF_APK, pkg + ".apk")
        if os.path.abspath(a) != os.path.abspath(dst):
            shutil.copyfile(a, dst)
            print("归位 %s -> self_apk/%s.apk" % (os.path.basename(a), pkg))

    # 2) 没给参数时，从已知工程目录抓每个包的最新构建
    if not args:
        os.makedirs(SELF_APK, exist_ok=True)
        for pkg, dirs in KNOWN_DIRS.items():
            src = newest_apk_in(dirs)
            if not src:
                continue
            real_pkg = pkg_of(src)
            if real_pkg != pkg:
                print("目录里最新包 %s 是 %s（要的是 %s），跳过" % (src, real_pkg, pkg))
                continue
            dst = os.path.join(SELF_APK, pkg + ".apk")
            if os.path.exists(dst) and os.path.getsize(src) == os.path.getsize(dst):
                continue  # 没变化
            shutil.copyfile(src, dst)
            print("抓取 %s -> self_apk/%s.apk" % (os.path.basename(src), pkg))

    # 3) 收录 + 重建索引 + 提交推送（push 带重试，直连抖动时多试几轮）
    if run([sys.executable, "self_apps.py", "scan"]) != 0:
        return 1
    if run([sys.executable, "export.py"]) != 0:
        return 1

    subprocess.call(["git", "add", "-A"], cwd=BASE_DIR)
    diff = subprocess.call(["git", "diff", "--cached", "--quiet"], cwd=BASE_DIR)
    if diff == 0:
        print("没有变化，不用发布")
        return 0
    subprocess.call(["git", "commit", "-m", "auto: 自研应用上架 " +
                     __import__("time").strftime("%Y-%m-%d %H:%M")], cwd=BASE_DIR)

    # remote 已配 SSH over 443（github-store），无需代理参数
    for i in range(6):
        subprocess.call(["git", "pull", "--no-rebase", "-X", "theirs",
                         "origin", "main"], cwd=BASE_DIR)
        r = subprocess.call(["git", "push", "origin", "main"], cwd=BASE_DIR)
        if r == 0:
            print("已推送到 GitHub，CI 会在几分钟内重建线上索引，电视端打开商店即可看到更新")
            return 0
        print("push 失败（网络抖动），45 秒后重试…")
        import time
        time.sleep(45)
    print("!! push 一直失败，稍后手动跑: git push origin main")
    return 1


if __name__ == "__main__":
    sys.exit(main())
