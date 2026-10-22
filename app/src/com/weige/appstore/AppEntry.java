package com.weige.appstore;

import org.json.JSONObject;

public class AppEntry {
    public String id = "";
    public String name = "";
    public String pkg = "";
    public String ver = "";
    public String cat = "";
    public String desc = "";
    public String day = "";
    public String iconUrl = "";
    public String apkUrl = "";
    public long size = 0;

    public static AppEntry from(JSONObject o) {
        AppEntry a = new AppEntry();
        a.id = o.optString("id");
        a.name = o.optString("name");
        a.pkg = o.optString("pkg");
        a.ver = o.optString("ver");
        a.cat = o.optString("cat");
        a.desc = o.optString("desc");
        a.day = o.optString("day");
        a.size = o.optLong("size");
        a.iconUrl = o.optString("icon");
        a.apkUrl = o.optString("apk");
        return a;
    }
}
