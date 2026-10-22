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
 *
 * 数据是一份静态 index.json（GitHub Actions 每 6 小时自动生成，已过安全白名单）。
 * 三层容错，从近到远：
 *   1. 本地缓存（秒开）
 *   2. 公网源（ghproxy → jsDelivr → raw），并且拒绝「比本地更旧」的数据，
 *      防止 CDN 缓存回退把老索引（可能没过滤）盖回来
 *   3. APK 内置的出厂索引（assets/index.json），永远有一份干净数据兜底
 */
public class IndexApi {
    private static final String USER = "lidawei1985";
    private static final String REPO = "weige-store";
    private static final String RAW = "https://raw.githubusercontent.com/" + USER + "/" + REPO
            + "/main/site/index.json";

    /** 实时源优先；jsDelivr 有 12 小时缓存，放后面当备份 */
    private static final String[] DEFAULT_SOURCES = {
            "https://ghproxy.net/" + RAW,
            "https://cdn.jsdelivr.net/gh/" + USER + "/" + REPO + "@main/site/index.json",
            RAW,
    };

    public interface Cb {
        void ok(List<AppEntry> apps, boolean fromCache);

        void fail(String err);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    public static String updatedText = "";
    public static long updatedTs = 0;
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
        // 出厂索引既做兜底，也做"新鲜度基线"：比它旧的公网数据一律不收
        final List<AppEntry> builtin = readAssets(ctx);
        final long builtinTs = updatedTs;
        final List<AppEntry> cached = readCache(ctx);
        long cacheTs = updatedTs;
        if (builtinTs > cacheTs) cacheTs = builtinTs;   // 以两者较新的为基线
        final long baseTs = cacheTs;

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
                        final List<AppEntry> list = parseStrict(body, baseTs);
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

                // 网络全挂：用 APK 里自带的出厂索引
                if (builtin != null && !builtin.isEmpty()) {
                    lastError = err;
                    UI.post(new Runnable() {
                        public void run() {
                            cb.ok(builtin, true);
                        }
                    });
                    return;
                }
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
    /**
     * 严格解析：三道校验，任何一条不过就丢弃换源。
     * 1. 必须是过了安全白名单的索引（safe_only=true）——老版本索引没这个标记，直接拒
     * 2. 不能比本地基线旧（防 CDN 缓存回退）
     * 3. count 和实际条数要对得上（防半截文件）
     */
    private static List<AppEntry> parseStrict(String body, long baseTs) throws Exception {
        JSONObject o = new JSONObject(body);
        if (!o.optBoolean("safe_only", false)) {
            throw new Exception("索引未经过安全筛选，已拒绝");
        }
        long ts = o.optLong("updated", 0);
        if (baseTs > 0 && ts > 0 && ts < baseTs) {
            throw new Exception("索引比本地旧，已跳过");
        }
        int cnt = o.optInt("count", 0);
        JSONArray arr = o.optJSONArray("apps");
        List<AppEntry> list = new ArrayList<AppEntry>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x != null) list.add(AppEntry.from(x));
            }
        }
        if (list.size() < 20) throw new Exception("条数太少(" + list.size() + ")");
        if (cnt > 0 && Math.abs(cnt - list.size()) > 2) {
            throw new Exception("条数校验失败(" + list.size() + "/" + cnt + ")");
        }
        updatedText = o.optString("updated_text", "");
        updatedTs = ts;
        return list;
    }

    private static List<AppEntry> parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        updatedText = o.optString("updated_text", "");
        updatedTs = o.optLong("updated", 0);
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

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
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
            return readAll(c.getInputStream());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<AppEntry> readCache(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        try {
            String body = readAll(new java.io.FileInputStream(f));
            // 老版本留下的、没过白名单的缓存直接删掉，绝不能让它显出来
            if (!new JSONObject(body).optBoolean("safe_only", false)) {
                f.delete();
                return null;
            }
            return parse(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<AppEntry> readAssets(Context c) {
        try {
            return parse(readAll(c.getAssets().open("index.json")));
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
