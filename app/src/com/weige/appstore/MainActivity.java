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
import android.widget.FrameLayout;
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
    /** 顶部标签；「搜索」不是分类，点了直接弹搜索框 */
    private static final String[] CATS = {"精选", "我的", "影音", "儿童", "工具", "生活", "搜索"};
    private static final String[] HOT = {"电视直播", "影视", "投屏", "电视家", "K歌",
            "浏览器", "音乐", "网盘", "儿童", "教育"};

    private GridView grid;
    private TextView empty, status, countTv, dlText, dlPct;
    private LinearLayout tabs, dlBox;
    private ProgressBar dlBar;
    private AppAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<AppEntry> all = new ArrayList<AppEntry>();
    private String cat = "精选";
    private String mode = "home";     // home / mine / search
    private String kw = "";
    private boolean loadedOnce = false;
    private boolean sortByName = false;

    // 批量更新队列
    private final List<AppEntry> queue = new ArrayList<AppEntry>();
    private boolean waitingInstall = false;
    private int updDone = 0, updTotal = 0;

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

        // 已安装清单扫一遍（1~2 秒），用于识别「已安装 / 可更新」
        new Thread(new Runnable() {
            public void run() {
                PkgUtil.scanInstalled(MainActivity.this);
                ui.post(new Runnable() {
                    public void run() {
                        buildTabs();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();

        buildTabs();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PkgUtil.invalidate();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (waitingInstall) {
            waitingInstall = false;
            ui.postDelayed(new Runnable() {
                public void run() {
                    nextInQueue();
                }
            }, 1200);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            showSearch();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && searchPanel != null
                && searchPanel.getVisibility() == View.VISIBLE) {
            closeSearch(false);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && !"home".equals(mode)) {
            mode = "home";
            kw = "";
            cat = "精选";
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
                    status.setText(buildStatus());
                }
                buildTabs();
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

    private String buildStatus() {
        String up = IndexApi.updatedText.length() > 0
                ? " · 更新于 " + IndexApi.updatedText : "";
        int upd = countUpdatable();
        String s = "已通过安全筛选 · " + all.size() + " 个正规应用" + up;
        if (upd > 0) s += " · " + upd + " 个可更新";
        return s;
    }

    /** 索引里「已安装且有新版本」的数量 */
    private int countUpdatable() {
        int n = 0;
        for (AppEntry a : all) {
            String inst = PkgUtil.installedVerOf(this, a);
            if (inst != null && a.ver.length() > 0 && !a.ver.equals(inst)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 筛选
    private void applyFilter() {
        List<AppEntry> out = new ArrayList<AppEntry>();
        if ("search".equals(mode)) {
            if (kw.length() == 0) {
                out.addAll(all);
            } else {
                String k = kw.toLowerCase();
                List<AppEntry> top = new ArrayList<AppEntry>();
                List<AppEntry> mid = new ArrayList<AppEntry>();
                List<AppEntry> low = new ArrayList<AppEntry>();
                for (AppEntry a : all) {
                    if (!matchSearch(a, k)) continue;
                    String n = a.name.toLowerCase();
                    if (n.contains(k)) top.add(a);
                    else if (a.py.toLowerCase().startsWith(k)) mid.add(a);
                    else low.add(a);
                }
                top.addAll(mid);
                top.addAll(low);
                out = top;
            }
        } else if ("mine".equals(mode)) {
            // 我的：只列已安装的，可更新的排最前
            List<AppEntry> upd = new ArrayList<AppEntry>();
            for (AppEntry a : all) {
                String inst = PkgUtil.installedVerOf(this, a);
                if (inst == null) continue;
                if (a.ver.length() > 0 && !a.ver.equals(inst)) upd.add(a);
                else out.add(a);
            }
            upd.addAll(out);
            out = upd;
        } else if ("精选".equals(cat)) {
            out.addAll(all);
        } else {
            for (AppEntry a : all) {
                if (cat.equals(a.cat)) out.add(a);
            }
        }

        if (sortByName) {
            java.util.Collections.sort(out, new java.util.Comparator<AppEntry>() {
                public int compare(AppEntry x, AppEntry y) {
                    return x.name.compareToIgnoreCase(y.name);
                }
            });
        }

        adapter.setData(out);
        countTv.setText(out.isEmpty() ? "" : (out.size() + " 个应用"));

        if (out.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            if (all.isEmpty()) {
                empty.setText(R.string.empty);
            } else if ("search".equals(mode)) {
                empty.setText("没搜到「" + kw + "」\n按 MENU 换个词，或按 BACK 返回");
            } else if ("mine".equals(mode)) {
                empty.setText("还没装过这里收录的应用\n装完会出现在这里，有新版也能一键更新");
            } else {
                empty.setText("这个分类下暂时没有应用\n按 BACK 或点「精选」");
            }
        } else {
            empty.setVisibility(View.GONE);
        }
        grid.requestFocus();
    }

    // ------------------------------------------------------------ 顶部标签
    private void buildTabs() {
        tabs.removeAllViews();
        int upd = ("mine".equals(mode) || all.size() > 0) ? countUpdatable() : 0;
        for (final String c : CATS) {
            TextView t = new TextView(this);
            String label = c;
            if ("我的".equals(c) && upd > 0) label = "我的 " + upd;
            t.setText(label);
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
            boolean on = c.equals(cat) && !"search".equals(mode);
            if ("搜索".equals(c)) on = "search".equals(mode);
            t.setSelected(on);
            t.setNextFocusDownId(R.id.grid);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if ("搜索".equals(c)) {
                        showSearch();
                        return;
                    }
                    kw = "";
                    cat = c;
                    mode = "我的".equals(c) ? "mine" : "home";
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
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(440),
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

        int upd = countUpdatable();
        View r2 = row(upd > 0 ? ("更新全部（" + upd + " 个）") : "检查更新");
        r2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                startUpdateAll();
            }
        });
        box.addView(r2);

        View r3 = row(sortByName ? "排序：改回最近更新优先" : "排序：改成名称 A→Z");
        r3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                sortByName = !sortByName;
                applyFilter();
                Toast.makeText(MainActivity.this,
                        sortByName ? "已按名称排序" : "已按更新时间排序",
                        Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(r3);

        View r4 = row("刷新索引");
        r4.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                PkgUtil.invalidate();
                refresh();
                Toast.makeText(MainActivity.this, "正在刷新索引", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(r4);

        View r5 = row("自定义索引地址");
        r5.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                showIndexDialog();
            }
        });
        box.addView(r5);

        View r6 = row("关于 / 安装权限");
        r6.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                showAbout();
            }
        });
        box.addView(r6);

        ui.postDelayed(new Runnable() {
            public void run() {
                r1.requestFocus();
            }
        }, 300);
    }

    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.dialog_bg);
        box.setPadding(dp(26), dp(24), dp(26), dp(24));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(520),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final CardDialog dlg = new CardDialog(this, box);
        dlg.show();

        box.addView(title("伟哥应用商店"));
        TextView tv = new TextView(this);
        tv.setTextColor(getResources().getColor(R.color.tx2));
        tv.setTextSize(13);
        tv.setLineSpacing(dp(6), 1f);
        tv.setText("自己家用的电视应用商店，没有广告和推荐位。\n\n"
                + "收录 " + all.size() + " 个应用，全部过大厂正版白名单："
                + "只放行牌照方和大厂应用，棋牌、破解、加速器、清理大师一律不收。\n\n"
                + "索引每 6 小时自动更新一次，不需要开着电脑。\n\n"
                + "装应用时如果提示「禁止安装」，请在系统设置里允许本应用安装未知应用。");
        box.addView(tv);

        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(13);
        ok.setTextColor(Color.WHITE);
        ok.setBackgroundResource(R.drawable.btn_primary);
        ok.setPadding(dp(30), dp(10), dp(30), dp(10));
        ok.setFocusable(true);
        ok.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.gravity = Gravity.RIGHT;
        ok.setLayoutParams(lp);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        box.addView(ok);
        ui.postDelayed(new Runnable() {
            public void run() {
                ok.requestFocus();
            }
        }, 300);
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
    // 电视上没有实体键盘，系统输入法一弹就遮掉半个屏幕，所以自己做字母键盘。
    // 索引里带了拼音首字母（py），输入 aqy 就能搜到爱奇艺。
    private LinearLayout searchPanel;
    private TextView sqText, sqCount;
    private TextView firstKey;

    private void showSearch() {
        if (searchPanel == null) buildSearchPanel();
        mode = "search";
        kw = "";
        refreshSearchText();
        searchPanel.setVisibility(View.VISIBLE);
        // 面板刚显示时子 View 还没布局完，直接 requestFocus 会失败，
        // 结果就是"按遥控器没反应"，必须等一帧之后
        searchPanel.post(new Runnable() {
            public void run() {
                if (firstKey != null) firstKey.requestFocus();
            }
        });
    }

    /** apply=true 关掉面板看结果；false 直接退回精选 */
    private void closeSearch(boolean apply) {
        if (searchPanel != null) searchPanel.setVisibility(View.GONE);
        if (!apply) {
            mode = "home";
            kw = "";
            cat = "精选";
        }
        buildTabs();
        applyFilter();
    }

    private void buildSearchPanel() {
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);
        searchPanel.setBackgroundColor(0xFF0A0C10);
        searchPanel.setPadding(dp(48), dp(30), dp(48), dp(22));
        root.addView(searchPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = new TextView(this);
        t.setText("搜索");
        t.setTextColor(getResources().getColor(R.color.tx));
        t.setTextSize(19);
        searchPanel.addView(t);

        sqText = new TextView(this);
        sqText.setTextSize(25);
        sqText.setTextColor(getResources().getColor(R.color.tx));
        sqText.setPadding(0, dp(10), 0, 0);
        searchPanel.addView(sqText);

        sqCount = new TextView(this);
        sqCount.setTextSize(13);
        sqCount.setTextColor(getResources().getColor(R.color.tx2));
        sqCount.setPadding(0, dp(4), 0, dp(10));
        searchPanel.addView(sqCount);

        // 热门词
        LinearLayout hotRow = new LinearLayout(this);
        hotRow.setOrientation(LinearLayout.HORIZONTAL);
        hotRow.setPadding(0, 0, 0, dp(8));
        for (final String k : HOT) {
            TextView hv = key(k, 0, 36);
            hv.setTextSize(12);
            hv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    kw = k;
                    refreshSearchText();
                    closeSearch(true);
                }
            });
            hotRow.addView(hv);
        }
        searchPanel.addView(hotRow);

        // 字母键盘：3 行 9/9/8
        final java.util.List<TextView> lastRow = new ArrayList<TextView>();
        String[] rows = {"ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < rows[r].length(); i++) {
                final String ch = String.valueOf(rows[r].charAt(i));
                TextView kb = key(ch, 1, 46);
                kb.setId(View.generateViewId());
                kb.setTextSize(17);
                kb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        kw = kw + ch.toLowerCase();
                        refreshSearchText();
                    }
                });
                line.addView(kb);
                if (firstKey == null) firstKey = kb;
                if (r == rows.length - 1) lastRow.add(kb);
            }
            searchPanel.addView(line);
        }

        // 操作行
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, dp(8), 0, 0);
        TextView del = key("删除", 1, 44);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (kw.length() > 0) {
                    kw = kw.substring(0, kw.length() - 1);
                    refreshSearchText();
                }
            }
        });
        del.setId(View.generateViewId());
        ops.addView(del);
        TextView clr = key("清空", 1, 44);
        clr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                kw = "";
                refreshSearchText();
            }
        });
        clr.setId(View.generateViewId());
        ops.addView(clr);
        TextView go = key("查看结果", 2, 44);
        go.setTextColor(Color.WHITE);
        go.setBackgroundResource(R.drawable.btn_primary);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeSearch(true);
            }
        });
        go.setId(View.generateViewId());
        ops.addView(go);
        searchPanel.addView(ops);

        // 遥控器从字母行按「下」要能落到按钮上
        for (int i = 0; i < lastRow.size(); i++) {
            lastRow.get(i).setNextFocusDownId(i < 3 ? del.getId() : go.getId());
        }
        del.setNextFocusUpId(lastRow.get(0).getId());
        clr.setNextFocusUpId(lastRow.get(3).getId());
        go.setNextFocusUpId(lastRow.get(lastRow.size() - 1).getId());
    }

    /** 造一个可聚焦的键盘按钮；weight=0 表示按内容宽度 */
    private TextView key(String label, int weight, int height) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(14);
        v.setTextColor(getResources().getColorStateList(R.drawable.tab_text));
        v.setBackgroundResource(R.drawable.tab_bg);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        LinearLayout.LayoutParams lp;
        if (weight > 0) {
            lp = new LinearLayout.LayoutParams(0, dp(height), weight);
            lp.rightMargin = dp(5);
        } else {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(height));
            lp.rightMargin = dp(6);
            v.setPadding(dp(16), 0, dp(16), 0);
        }
        v.setLayoutParams(lp);
        return v;
    }

    private void refreshSearchText() {
        String k = kw.toLowerCase();
        if (k.length() == 0) {
            sqText.setText("输入拼音首字母，如 aqy = 爱奇艺");
            sqText.setTextColor(getResources().getColor(R.color.tx3));
            sqCount.setText("共 " + all.size() + " 个应用");
            return;
        }
        sqText.setText(kw.toUpperCase());
        sqText.setTextColor(getResources().getColor(R.color.tx));
        int n = 0;
        for (AppEntry a : all) {
            if (matchSearch(a, k)) n++;
        }
        sqCount.setText(n == 0 ? "没找到，换个词试试" : ("匹配 " + n + " 个应用，点「查看结果」"));
    }

    /** 名称含关键词 / 拼音首字母含关键词 / 简介含中文关键词 */
    private boolean matchSearch(AppEntry a, String k) {
        if (a.name.toLowerCase().contains(k)) return true;
        if (a.py.length() > 0 && a.py.toLowerCase().contains(k)) return true;
        return k.length() > 0 && !k.matches("[a-z0-9]+") && a.desc.contains(k);
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
        if (e.ver.length() > 0) sb.append("版本 ").append(e.ver).append("   ");
        sb.append(AppAdapter.fmt(e.size));
        if (e.cat.length() > 0) sb.append("   ").append(e.cat);
        if (e.day.length() > 0) sb.append("   ").append(e.day).append(" 更新");
        meta.setText(sb.toString());
        desc.setText(e.desc.length() > 0 ? e.desc : "（这个应用没有简介）");
        ImageLoader.load(e.iconUrl, ic);

        final String inst = PkgUtil.installedVerOf(this, e);
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
                    PkgUtil.openApp(MainActivity.this, PkgUtil.resolvePkg(MainActivity.this, e));
                } else {
                    queue.clear();
                    startDownload(e);
                }
            }
        });
        dlg.show();
        // 延迟聚焦：否则遥控器 OK 的 keyup 会落进弹窗按钮，造成「一按直接安装/打开」
        ui.postDelayed(new Runnable() {
            public void run() {
                action.requestFocus();
            }
        }, 300);
    }

    // ------------------------------------------------------------ 下载安装
    private void startDownload(final AppEntry e) {
        if (e.apkUrl.length() == 0) {
            Toast.makeText(this, "这个应用没有下载地址", Toast.LENGTH_SHORT).show();
            return;
        }
        dlBox.setVisibility(View.VISIBLE);
        dlText.setText("正在下载 " + e.name
                + (updTotal > 0 ? "（" + (updDone + 1) + "/" + updTotal + "）" : ""));
        dlPct.setText("0%");
        dlBar.setProgress(0);
        Downloader.download(this, e.apkUrl, e.id + ".apk", new Downloader.Cb() {
            public void progress(int pct, long got, long total) {
                dlBar.setProgress(pct);
                dlPct.setText(pct + "%  " + AppAdapter.fmt(got)
                        + (total > 0 ? " / " + AppAdapter.fmt(total) : ""));
            }

            public void done(File f) {
                // 顺便把真实包名记下来，下次判断「已安装」就是准的
                String[] info = PkgUtil.apkInfo(MainActivity.this, f.getAbsolutePath());
                if (info != null) PkgUtil.learn(MainActivity.this, e.id, info[0]);
                dlText.setText("下载完成，正在调起安装：" + e.name);
                dlPct.setText("100%");
                install(f, e);
                if (updTotal == 0) {
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            dlBox.setVisibility(View.GONE);
                        }
                    }, 3000);
                }
            }

            public void fail(String err) {
                dlText.setText("下载失败：" + err);
                dlPct.setText("");
                updTotal = 0;
                updDone = 0;
                queue.clear();
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
            if (updTotal > 0) waitingInstall = true;   // 装完回来自动下一个
        } catch (Exception ex) {
            status.setText("调起安装器失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 批量更新
    private void startUpdateAll() {
        queue.clear();
        for (AppEntry a : all) {
            String inst = PkgUtil.installedVerOf(this, a);
            if (inst != null && a.ver.length() > 0 && !a.ver.equals(inst)) queue.add(a);
        }
        if (queue.isEmpty()) {
            Toast.makeText(this, "所有应用都是最新版", Toast.LENGTH_SHORT).show();
            return;
        }
        updTotal = queue.size();
        updDone = 0;
        Toast.makeText(this, "开始更新 " + updTotal + " 个应用，每个装完会自动下一个",
                Toast.LENGTH_LONG).show();
        nextInQueue();
    }

    private void nextInQueue() {
        if (queue.isEmpty()) {
            updTotal = 0;
            updDone = 0;
            dlBox.setVisibility(View.GONE);
            Toast.makeText(this, "更新完成", Toast.LENGTH_SHORT).show();
            PkgUtil.invalidate();
            applyFilter();
            return;
        }
        AppEntry e = queue.remove(0);
        startDownload(e);
        updDone++;
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
