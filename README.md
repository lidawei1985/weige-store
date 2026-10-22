# 伟哥应用商店

自己家用的电视应用商店。无广告、无推荐位、**只放正规应用**，遥控器点一下就装。
没有任何服务器要维护 —— 索引在 GitHub Actions 上每 6 小时自动更新一次。

```
GitHub Actions (每 6h)                TV 客户端
  export.py                           启动 → 读本地缓存（秒开）
   ├ 抓当贝市场网页版                    └→ 后台拉新索引（3 个源容错）
   ├ curation.py 安全白名单                    ├ ghproxy（实时）
   └ 生成 site/index.json                     ├ jsDelivr（备份）
        ↓                                     └ raw.githubusercontent
   jsDelivr / ghproxy  CDN  ──────────→  图标 / APK 直连当贝 CDN
```

## 安全：这是最重要的一条

宁可少，不能脏。三道闸，全在 `curation.py`：

1. **黑名单关键词**（一票否决）：棋牌、麻将、斗地主、赌博、破解、内购、成人、加速器、清理大师、万能钥匙、赚钱返利、私服页游、山寨影视聚合…… 一共 100+ 个词
2. **白名单品牌**：只认大厂正版和持牌 OTT —— 爱奇艺/奇异果、腾讯视频、优酷/CIBN酷喵、芒果TV、哔哩哔哩、央视频、咪咕、华数、BesTV、CIBN、NewTV、云视听、宝宝巴士、洪恩、斑马、QQ音乐、网易云、酷狗、百度网盘、阿里云盘、WPS、当贝投屏、乐播投屏、Kodi、VLC……
3. **精确名单**：少数名字不带品牌词但确认干净的（ES文件浏览器、电视猫、悟空识字、万年历……）

当前结果：**放行 79 个，拦掉 545 个**。游戏分类整体不收（TV 游戏几乎全是灰产山寨）。

想加/删应用：改 `curation.py` 里的 `SAFE_BRANDS` 或 `SAFE_EXACT`，push 上去，下一次 Actions 自动生效，客户端最长 6 小时拿到。

额外两道保护：

- **拒绝更旧数据** —— 索引里带 `updated` 时间戳，客户端拿到比本地还旧的直接丢弃（防 CDN 缓存回退把没过滤的老数据盖回来）
- **出厂索引兜底** —— 白名单版索引直接打进 APK 的 `assets/`，全网都连不上时也有干净内容

## 装到电视

```bash
adb connect 192.168.8.159:5555
adb install -r 伟哥应用商店-v1.0.apk
```

首次安装会要求允许「安装未知应用」，允许一次即可。

## 遥控器操作

| 键 | 作用 |
|---|---|
| 方向键 | 移动焦点 |
| OK | 打开详情 / 安装 |
| MENU | 菜单：搜索、刷新索引、自定义索引地址 |
| BACK | 搜索页返回分类 |

分类：精选 / 影音 / 儿童 / 工具 / 生活。搜索是本地内存过滤，几百个应用秒出结果。

## 目录

```
cleanstore/
├── export.py                索引生成（Actions 每次跑这个）
├── curation.py              安全白名单 / 黑名单 / 儿童分类规则
├── source_dangbei.py        当贝市场网页版抓取
├── site/index.json          生成的索引（客户端读它）
├── .github/workflows/       每 6 小时自动更新
├── app/                     TV 客户端源码
│   ├── build.sh             aapt2 + javac + d8 打包（不用 gradle）
│   ├── assets/index.json    出厂索引
│   └── 伟哥应用商店-v1.0.apk
├── server.py                可选：想自己在局域网跑就启动它
├── web/index.html           可选：手机/电脑网页版商店
└── data/                    可选自建时的本地缓存
```

## 改客户端

```bash
cd app && bash build.sh
```

依赖：JDK 17+、Android SDK（build-tools 36 的 d8 + platforms/android-34）。
包名 `com.weige.appstore`，minSdk 21 / targetSdk 28，Android 5.0 以上的盒子都行。

想换索引仓库地址：改 `IndexApi.java` 里的 `USER` / `REPO`。

## 可选的局域网自建

不联网或想完全离线时，`python server.py` 起一个本地服务，网页版在 `http://本机IP:8018/`。
客户端菜单里可以把索引地址指过去。但正常用不需要它。

## 已知情况

- 当贝网页版每个分类最多 5 页 50 条，全站去重后约 400 条；白名单筛完剩 79 条 —— 家庭常用的大厂应用都在里面
- 上游改版会导致抓取失败：Actions 会保留旧索引不提交，客户端不受影响；改 `source_dangbei.py` 即可修复
- 「是否已安装」靠包名判断，包名要 APK 下载后由 `apkmeta.py` 解析补库，所以全新应用第一次只显示「安装」
