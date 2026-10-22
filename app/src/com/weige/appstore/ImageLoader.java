package com.weige.appstore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    private static LruCache<String, Bitmap> mem;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static File dir;
    private static Context app;

    public static void init(Context c) {
        app = c.getApplicationContext();
        dir = new File(c.getCacheDir(), "icons");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = c.getCacheDir();
        }
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mem = new LruCache<String, Bitmap>(maxKb) {
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
    }

    public static void load(final String url, final ImageView iv) {
        load(url, null, iv);
    }

    public static void load(final String url, final String fallbackUrl, final ImageView iv) {
        if (url == null || url.length() == 0) {
            iv.setImageResource(R.drawable.ic_placeholder);
            return;
        }
        // 自研应用的图标直接打包在 APK 里，不走网络，永远有图
        if (url.startsWith("asset:")) {
            loadAsset(url.substring(6), iv);
            return;
        }
        final String key = md5(url);
        iv.setTag(key);
        Bitmap b = mem.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageResource(R.drawable.ic_placeholder);
        final File f = new File(dir, key + ".img");
        if (f.exists()) {
            Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (d != null) {
                mem.put(key, d);
                iv.setImageBitmap(d);
                return;
            }
        }
        POOL.execute(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "WeigeStore/1.0");
                    InputStream in = c.getInputStream();
                    File tmp = new File(dir, key + ".tmp");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close();
                    in.close();
                    final Bitmap bm = BitmapFactory.decodeFile(tmp.getAbsolutePath());
                    if (bm != null) {
                        if (!tmp.renameTo(f)) {
                            // 重命名失败就直接用临时文件
                        }
                        mem.put(key, bm);
                        UI.post(new Runnable() {
                            public void run() {
                                if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
                            }
                        });
                    } else {
                        tmp.delete();
                    }
                } catch (Exception e) {
                    // 网络失败：先试 APK 内置的同名图标，再保持占位
                    final String name = url.substring(url.lastIndexOf('/') + 1);
                    if (!loadAssetQuiet("self_icon/" + name, iv, key)
                            && fallbackUrl != null && fallbackUrl.length() > 0
                            && !fallbackUrl.equals(url)) {
                        load(fallbackUrl, iv);
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    /** 从 APK 的 assets 里读图标 */
    private static boolean loadAssetQuiet(String path, ImageView iv, String key) {
        try {
            InputStream in = app.getAssets().open(path);
            final Bitmap bm = BitmapFactory.decodeStream(in);
            in.close();
            if (bm == null) return false;
            mem.put(key, bm);
            UI.post(new Runnable() {
                public void run() {
                    if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void loadAsset(final String path, ImageView iv) {
        final String key = "asset:" + path;
        Bitmap b = mem.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageResource(R.drawable.ic_placeholder);
        loadAssetQuiet(path, iv, key);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
