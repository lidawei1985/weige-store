package com.weige.appstore;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String[] CATS = {"全部", "影音", "工具", "生活", "游戏", "新上架"};
    private static final String[] HOT = {"电视直播", "影视", "投屏", "电视家", "K歌",
            "浏览器", "音乐", "游戏", "网盘", "儿童"};

    private GridView grid;
    private TextView empty, status, countTv, dlText, dlPct;
    private LinearLayout tabs, dlBox;
    private ProgressBar dlBar;
    private AppAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<AppEntry> all = new ArrayList<AppEntry>();
    private String cat = "全部";
    private String mode = "home";
    private String kw = "";
    private boolean loadedOnce = false;

    // ------------------------------------------------------------ 生命周期
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        grid = (GridView) findViewById(R.id.grid);
        empty = (TextView) findViewById(R.id.empty);
        status = (TextView) findViewById(R.id.status);
        countTv = (TextView) findViewById(R.id.count);
        tabs = (LinearLayout) findViewById(R.id.tabs);
        dlBox = (LinearLayout) findViewById(R.id.dlbox);
        dlText = (TextView) findViewById(R.id.dltext);
        dlPct = (TextView) findViewById(R.id.dlpct);
        dlBar = (ProgressBar) findViewById(R.id.dlbar);

        ImageLoader.init(this);
        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                showDetail(adapter.getItem(pos));
            }
        });

        buildTabs();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && "search".equals(mode)) {
            mode = "home";
            kw = "";
            cat = "全部";
            buildTabs();
            applyFilter();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------ 索引
    private void refresh() {
        empty.setVisibility(View.VISIBLE);
        empty.setText(R.string.loading);
        status.setText("正在读取索引…");
        IndexApi.load(this, new IndexApi.Cb() {
            public void ok(List<AppEntry> apps, boolean fromCache) {
                all.clear();
                all.addAll(apps);
                loadedOnce = true;
                if (fromCache) {
                    status.setText("本地缓存 · " + all.size() + " 个应用，正在刷新…");
                } else {
                    String up = IndexApi.updatedText.length() > 0
                            ? "更新于 " + IndexApi.updatedText : "已就绪";
                    status.setText(up + " · 共 " + all.size() + " 个应用");
                }
                applyFilter();
            }

            public void fail(String err) {
                if (loadedOnce) return;
                empty.setVisibility(View.VISIBLE);
                empty.setText("索引读取失败\n\n" + err
                        + "\n\n按 MENU 键可换索引地址，或检查网络");
                status.setText("离线");
            }
        });
    }

    // ------------------------------------------------------------ 筛选
    private void applyFilter() {
        List<AppEntry> out = new ArrayList<AppEntry>();
        if ("search".equals(mode)) {
            if (kw.length() == 0) {
                out.addAll(all);
            } else {
                for (AppEntry a : all) {
                    if (a.name.contains(kw) || a.desc.contains(kw)
                            || a.name.toLowerCase().contains(kw.toLowerCase())) {
                        out.add(a);
                    }
                }
            }
        } else if ("新上架".equals(cat)) {
            for (int i = 0; i < all.size() && i < 80; i++) out.add(all.get(i));
        } else if ("全部".equals(cat)) {
            out.addAll(all);
        } else {
            for (AppEntry a : all) {
                if (cat.equals(a.cat)) out.add(a);
            }
        }

        adapter.setData(out);
        countTv.setText(out.isEmpty() ? "" : (out.size() + " 个应用"));

        if (out.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            if (all.isEmpty()) {
                empty.setText(R.string.empty);
            } else if ("search".equals(mode)) {
                empty.setText("没搜到「" + kw + "」\n按 MENU 换个词，或按 BACK 返回");
            } else {
                empty.setText("这个分类下暂时没有应用\n按 BACK 或点「全部」");
            }
        } else {
            empty.setVisibility(View.GONE);
        }
        grid.requestFocus();
    }

    // ------------------------------------------------------------ 顶部标签
    private void buildTabs() {
        tabs.removeAllViews();
        for (final String c : CATS) {
            TextView t = new TextView(this);
            t.setText(c);
            t.setTextSize(14);
            t.setTextColor(getResources().getColorStateList(R.drawable.tab_text));
            t.setBackgroundResource(R.drawable.tab_bg);
            t.setPadding(dp(22), dp(7), dp(22), dp(7));
            t.setFocusable(true);
            t.setClickable(true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            t.setLayoutParams(lp);
            t.setSelected(c.equals(cat) && "home".equals(mode));
            t.setNextFocusDownId(R.id.grid);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mode = "home";
                    kw = "";
                    cat = c;
                    buildTabs();
                    applyFilter();
                }
            });
            tabs.addView(t);
        }
    }

    // ------------------------------------------------------------ 菜单
    private void showMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_bg);
        box.setPadding(dp(26), dp(24), dp(26), dp(24));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(420),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final CardDialog dlg = new CardDialog(this, box);
        dlg.show();

        box.addView(title("菜单"));

        View r1 = row("搜索应用");
        r1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                showSearch();
            }
        });
        box.addView(r1);

        View r2 = row("刷新索引");
        r2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                refresh();
                Toast.makeText(MainActivity.this, "正在刷新索引", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(r2);

        View r3 = row("返回到「全部」");
        r3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                mode = "home";
                kw = "";
                cat = "全部";
                buildTabs();
                applyFilter();
            }
        });
        box.addView(r3);

        View r4 = row("自定义索引地址");
        r4.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                showIndexDialog();
            }
        });
        box.addView(r4);

        r1.requestFocus();
    }

    private void showIndexDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_bg);
        box.setPadding(dp(26), dp(24), dp(26), dp(24));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(560),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final CardDialog dlg = new CardDialog(this, box);
        dlg.show();

        box.addView(title("自定义索引地址"));

        TextView hint = new TextView(this);
        hint.setText("填 index.json 的完整地址。留空则用内置的 GitHub / jsDelivr 源。");
        hint.setTextColor(getResources().getColor(R.color.tx2));
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, dp(10));
        box.addView(hint);

        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        in.setTextColor(getResources().getColor(R.color.tx));
        in.setTextSize(14);
        in.setBackgroundResource(R.drawable.input_bg);
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        in.setText(Prefs.getIndex(this));
        box.addView(in);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.RIGHT);
        btns.setPadding(0, dp(16), 0, 0);

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextSize(13);
        ok.setTextColor(Color.WHITE);
        ok.setBackgroundResource(R.drawable.btn_primary);
        ok.setPadding(dp(30), dp(10), dp(30), dp(10));
        ok.setFocusable(true);
        ok.setClickable(true);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = in.getText().toString().trim();
                Prefs.setIndex(MainActivity.this, s);
                dlg.dismiss();
                refresh();
            }
        });
        btns.addView(ok);
        box.addView(btns);
        in.requestFocus();
    }

    // ------------------------------------------------------------ 搜索
    private void showSearch() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_search, null);
        final EditText input = (EditText) v.findViewById(R.id.sinput);
        LinearLayout hot = (LinearLayout) v.findViewById(R.id.hotwrap);
        final CardDialog dlg = new CardDialog(this, v);
        dlg.show();

        LinearLayout rowLine = null;
        for (int i = 0; i < HOT.length; i++) {
            if (i % 4 == 0) {
                rowLine = new LinearLayout(this);
                rowLine.setOrientation(LinearLayout.HORIZONTAL);
                rowLine.setPadding(0, dp(6), 0, 0);
                hot.addView(rowLine);
            }
            final String k = HOT[i];
            TextView t = new TextView(this);
            t.setText(k);
            t.setTextSize(12);
            t.setTextColor(getResources().getColorStateList(R.drawable.tab_text));
            t.setBackgroundResource(R.drawable.tab_bg);
            t.setPadding(dp(16), dp(7), dp(16), dp(7));
            t.setFocusable(true);
            t.setClickable(true);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    dlg.dismiss();
                    doSearch(k);
                }
            });
            if (rowLine != null) rowLine.addView(t);
        }

        v.findViewById(R.id.s_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                dlg.dismiss();
            }
        });
        v.findViewById(R.id.s_go).setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                String q = input.getText().toString().trim();
                dlg.dismiss();
                if (q.length() > 0) doSearch(q);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView tv, int action, KeyEvent e) {
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                    String q = input.getText().toString().trim();
                    dlg.dismiss();
                    if (q.length() > 0) doSearch(q);
                    return true;
                }
                return false;
            }
        });
        input.requestFocus();
    }

    private void doSearch(String q) {
        mode = "search";
        kw = q;
        buildTabs();
        applyFilter();
    }

    // ------------------------------------------------------------ 详情
    private void showDetail(final AppEntry e) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_detail, null);
        ImageView ic = (ImageView) v.findViewById(R.id.dicon);
        TextView name = (TextView) v.findViewById(R.id.dname);
        TextView meta = (TextView) v.findViewById(R.id.dmeta);
        TextView desc = (TextView) v.findViewById(R.id.ddesc);
        TextView state = (TextView) v.findViewById(R.id.dstate);
        TextView action = (TextView) v.findViewById(R.id.btn_action);
        TextView cancel = (TextView) v.findViewById(R.id.btn_cancel);

        name.setText(e.name);
        StringBuilder sb = new StringBuilder();
        if (e.ver.length() > 0) sb.append(e.ver).append(" · ");
        sb.append(AppAdapter.fmt(e.size));
        if (e.cat.length() > 0) sb.append(" · ").append(e.cat);
        if (e.day.length() > 0) sb.append(" · ").append(e.day);
        meta.setText(sb.toString());
        desc.setText(e.desc.length() > 0 ? e.desc : "（这个应用没有简介）");
        ImageLoader.load(e.iconUrl, ic);

        final String inst = PkgUtil.installedVer(this, e.pkg);
        boolean canOpen = false;
        String stateTxt = "";
        String actionTxt = getString(R.string.install);

        if (inst != null) {
            if (e.ver.length() > 0 && !e.ver.equals(inst)) {
                stateTxt = "已安装 " + inst + "，可更新到 " + e.ver;
                actionTxt = getString(R.string.update);
            } else {
                stateTxt = "已安装 " + inst;
                actionTxt = getString(R.string.open);
                canOpen = true;
            }
        }
        state.setText(stateTxt);
        action.setText(actionTxt);

        final CardDialog dlg = new CardDialog(this, v);
        final boolean openMode = canOpen;
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                dlg.dismiss();
            }
        });
        action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                dlg.dismiss();
                if (openMode) {
                    PkgUtil.openApp(MainActivity.this, e.pkg);
                } else {
                    startDownload(e);
                }
            }
        });
        dlg.show();
        action.requestFocus();
    }

    // ------------------------------------------------------------ 下载安装
    private void startDownload(final AppEntry e) {
        if (e.apkUrl.length() == 0) {
            Toast.makeText(this, "这个应用没有下载地址", Toast.LENGTH_SHORT).show();
            return;
        }
        dlBox.setVisibility(View.VISIBLE);
        dlText.setText("正在下载 " + e.name);
        dlPct.setText("0%");
        dlBar.setProgress(0);
        Downloader.download(this, e.apkUrl, e.id + ".apk", new Downloader.Cb() {
            public void progress(int pct, long got, long total) {
                dlBar.setProgress(pct);
                dlPct.setText(pct + "%  " + AppAdapter.fmt(got)
                        + (total > 0 ? " / " + AppAdapter.fmt(total) : ""));
            }

            public void done(File f) {
                dlText.setText("下载完成，正在调起安装：" + e.name);
                dlPct.setText("100%");
                install(f, e);
                ui.postDelayed(new Runnable() {
                    public void run() {
                        dlBox.setVisibility(View.GONE);
                    }
                }, 3000);
            }

            public void fail(String err) {
                dlText.setText("下载失败：" + err);
                dlPct.setText("");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        dlBox.setVisibility(View.GONE);
                    }
                }, 5000);
            }
        });
    }

    private void install(File f, AppEntry e) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许「伟哥应用商店」安装应用，再点一次安装",
                    Toast.LENGTH_LONG).show();
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(s);
            } catch (Exception ignore) {
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.uriFor(f), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ex) {
            status.setText("调起安装器失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 小工具
    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getResources().getColor(R.color.tx));
        t.setTextSize(17);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private TextView row(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(getResources().getColor(R.color.tx));
        t.setBackgroundResource(R.drawable.btn_ghost);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
