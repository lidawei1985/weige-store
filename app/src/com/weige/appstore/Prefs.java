package com.weige.appstore;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "weige_store";

    /** 自定义索引地址（留空就用内置的） */
    public static String getIndex(Context c) {
        SharedPreferences sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getString("index", "");
    }

    public static void setIndex(Context c, String v) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString("index", v).apply();
    }
}
