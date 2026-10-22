package com.weige.appstore;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;

public class PkgUtil {

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
}
