package com.weige.appstore;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 索引读取器。
 * 数据是一份静态 index.json（由 GitHub Actions 每 6 小时自动生成），
 * 客户端拉下来缓存到本地，之后分类、搜索全在本地内存里做，秒开。
 *
 * 换仓库只改 SOURCES。
 */
public class IndexApi {
    // ↓↓↓ 换成你自己的 GitHub 用户名/仓库名 ↓↓↓
    private static final String USER = "USER";
    private static final String REPO = "REPO";

    /** 多源容错，按顺序试，谁先通用谁 */
    private static final String[] DEFAULT_SOURCES = {
            "https://cdn.jsdelivr.net/gh/" + USER + "/" + REPO + "@main/site/index.json",
            "https://raw.githubusercontent.com/" + USER + "/" + REPO + "/main/site/index.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/" + USER + "/" + REPO
                    + "/main/site/index.json",
            "https://gitee.com/" + USER + "/" + REPO + "/raw/main/site/index.json",
    };

    public interface Cb {
        void ok(List<AppEntry> apps, boolean fromCache);

        void fail(String err);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** 索引的更新时间，界面上显示 */
    public static String updatedText = "";
    public static String lastError = "";

    public static File cacheFile(Context c) {
        return new File(c.getCacheDir(), "index.json");
    }

    private static List<String> sources(Context c) {
        List<String> out = new ArrayList<String>();
        String custom = Prefs.getIndex(c);
        if (custom != null && custom.length() > 5) out.add(custom);
        for (String s : DEFAULT_SOURCES) {
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    public static void load(final Context ctx, final Cb cb) {
        final List<AppEntry> cached = readCache(ctx);
        if (cached != null && !cached.isEmpty()) {
            UI.post(new Runnable() {
                public void run() {
                    cb.ok(cached, true);
                }
            });
        }
        new Thread(new Runnable() {
            public void run() {
                String err = "没有可用的索引地址";
                for (String url : sources(ctx)) {
                    try {
                        String body = fetch(url);
                        final List<AppEntry> list = parse(body);
                        if (list.size() < 20) {
                            throw new Exception("条数太少(" + list.size() + ")");
                        }
                        writeCache(ctx, body);
                        lastError = "";
                        UI.post(new Runnable() {
                            public void run() {
                                cb.ok(list, false);
                            }
                        });
                        return;
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                lastError = err;
                if (cached == null || cached.isEmpty()) {
                    final String e = err;
                    UI.post(new Runnable() {
                        public void run() {
                            cb.fail(e);
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------------------------------------------------- 解析 / 缓存
    private static List<AppEntry> parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        updatedText = o.optString("updated_text", "");
        JSONArray arr = o.optJSONArray("apps");
        List<AppEntry> list = new ArrayList<AppEntry>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x != null) list.add(AppEntry.from(x));
            }
        }
        return list;
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", "WeigeStore/1.0");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<AppEntry> readCache(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return parse(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context c, String body) {
        try {
            File tmp = new File(c.getCacheDir(), "index.json.tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(body.getBytes("UTF-8"));
            fo.close();
            File f = cacheFile(c);
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception ignore) {
        }
    }
}
