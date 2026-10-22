package com.weige.appstore;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 包名识别
 * --------
 * 索引里没有包名（当贝网页拿不到），所以「已安装 / 可更新」靠两条路：
 *   1. 名称比对：把已安装应用的 label 归一化（去掉 TV/HD 后缀、空格、大小写），
 *      和索引里的应用名比，命中就认成同一个应用。覆盖大部分情况。
 *   2. 学习记忆：本 App 下载并安装过的包，把 索引id → 真实包名 记到本地，
 *      以后优先用这条，绝对准确。
 */
public class PkgUtil {
    private static final String SP = "pkgmap";

    private static Map<String, String> installedByName;   // 归一化名 -> 包名
    private static Map<String, String> installedPkgLabel; // 包名 -> 原始 label

    /** 已装返回版本名，未装返回 null */
    public static String installedVer(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    public static void openApp(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }

    // ------------------------------------------------------------ 名称归一化
    static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.US).trim();
        // 去掉常见后缀和修饰词，让「奇异果TV」和「奇异果」能对上
        String[] junk = {"极速版", "电视版", "tv版", "hd版", "手机版", "官方版",
                "免费版", "正式版", "最新版", "(tv)", "（tv）", "for tv"};
        for (String j : junk) s = s.replace(j, "");
        // 只留字母数字和汉字
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    /** 扫一遍已安装应用，建名称索引（1~2 秒，只在启动时做一次） */
    public static synchronized void scanInstalled(Context c) {
        if (installedByName != null) return;
        installedByName = new HashMap<String, String>();
        installedPkgLabel = new HashMap<String, String>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : list) {
                try {
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    String n = norm(label);
                    if (n.length() >= 2) installedByName.put(n, ai.packageName);
                    installedPkgLabel.put(ai.packageName, label);
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }
    }

    /** 本地学习到的映射：索引 id -> 包名 */
    private static String learned(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        String p = sp.getString(id, null);
        return (p == null || p.length() == 0) ? null : p;
    }

    /**
     * 推断索引里这个应用对应哪个包名（可能返回 null = 没装或认不出来）
     */
    public static String resolvePkg(Context c, AppEntry e) {
        // 索引里带了包名最省事
        if (e.pkg != null && e.pkg.length() > 0) return e.pkg;

        String learnedPkg = learned(c, e.id);
        if (learnedPkg != null) return learnedPkg;

        scanInstalled(c);
        String n = norm(e.name);
        String hit = installedByName.get(n);
        if (hit != null) return hit;
        // 再试一次：索引名包含已装 label，或已装 label 包含索引名（如「云视听极光」vs「腾讯视频」不行，长度要对得上）
        for (Map.Entry<String, String> en : installedByName.entrySet()) {
            String k = en.getKey();
            if (k.length() >= 4 && (n.contains(k) || k.contains(n))) return en.getValue();
        }
        return null;
    }

    /** 已安装版本号（认不出来返回 null） */
    public static String installedVerOf(Context c, AppEntry e) {
        return installedVer(c, resolvePkg(c, e));
    }

    /** 下载装完后记下真实包名，以后免推断 */
    public static void learn(Context c, String id, String pkg) {
        if (id == null || pkg == null || pkg.length() == 0) return;
        c.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putString(id, pkg).apply();
    }

    /** 从 APK 文件解析包名和三方版本号，解析失败返回 null */
    public static String[] apkInfo(Context c, String path) {
        try {
            PackageInfo pi = c.getPackageManager()
                    .getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES);
            if (pi == null) return null;
            String ver = pi.versionName == null ? "" : pi.versionName;
            return new String[]{pi.packageName, ver};
        } catch (Exception e) {
            return null;
        }
    }

    /** 名称索引刷新（安装/卸载后调用） */
    public static synchronized void invalidate() {
        installedByName = null;
        installedPkgLabel = null;
    }

    /** 已安装应用总数（用于「我的」页标题） */
    public static int installedCount(Context c) {
        scanInstalled(c);
        return installedPkgLabel == null ? 0 : installedPkgLabel.size();
    }

    /** 已安装包名集合 */
    public static Set<String> installedPackages(Context c) {
        scanInstalled(c);
        return installedPkgLabel == null ? new HashSet<String>() : installedPkgLabel.keySet();
    }
}
