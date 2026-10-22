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
    public String iconUrl2 = "";  // 图标兜底：内置图标缺失时走 CDN（新收录的应用老客户端没有内置图）
    public String apkUrl = "";
    public String apkUrl2 = "";   // 备用下载源（自研应用走 CDN + 镜像两条）
    public boolean isSelf = false; // 自研应用，界面上单独标
    public String py = "";     // 拼音首字母，遥控器字母搜索用
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
        a.iconUrl2 = o.optString("icon2");
        a.apkUrl = o.optString("apk");
        a.apkUrl2 = o.optString("apk2");
        a.isSelf = o.optBoolean("self");
        a.py = o.optString("py");
        return a;
    }
}
