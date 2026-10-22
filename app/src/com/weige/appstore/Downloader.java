package com.weige.appstore;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Downloader {
    public interface Cb {
        void progress(int pct, long got, long total);

        void done(File f);

        void fail(String err);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean cancelled = false;

    public static void cancel() {
        cancelled = true;
    }

    public static void download(final android.content.Context ctx, final String url,
                               final String filename, final Cb cb) {
        cancelled = false;
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    File dir = new File(ctx.getCacheDir(), "apk");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new Exception("无法创建下载目录");
                    }
                    final File out = new File(dir, filename);
                    if (out.exists() && out.length() > 0) {
                        UI.post(new Runnable() {
                            public void run() {
                                cb.progress(100, out.length(), out.length());
                                cb.done(out);
                            }
                        });
                        return;
                    }
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(40000);
                    c.setRequestProperty("User-Agent", "WeigeStore/1.0");
                    c.connect();
                    long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    File tmp = new File(dir, filename + ".part");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    long got = 0;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) {
                            fo.close();
                            tmp.delete();
                            return;
                        }
                        fo.write(buf, 0, n);
                        got += n;
                        final int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != last) {
                            last = pct;
                            final long g = got, t = total;
                            UI.post(new Runnable() {
                                public void run() {
                                    cb.progress(pct, g, t);
                                }
                            });
                        }
                    }
                    fo.close();
                    in.close();
                    c.disconnect();
                    if (total > 0 && got != total) {
                        tmp.delete();
                        throw new Exception("下载不完整 " + got + "/" + total);
                    }
                    if (out.exists()) out.delete();
                    if (!tmp.renameTo(out)) {
                        throw new Exception("写入失败");
                    }
                    UI.post(new Runnable() {
                        public void run() {
                            cb.done(out);
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("WeigeStore", "download fail: " + url + " => " + e);
                    UI.post(new Runnable() {
                        public void run() {
                            cb.fail(String.valueOf(e.getMessage()));
                        }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }
}
