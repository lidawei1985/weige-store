package com.weige.appstore;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppAdapter extends BaseAdapter {
    private final Context ctx;
    private final LayoutInflater inf;
    private List<AppEntry> data = new ArrayList<AppEntry>();

    public AppAdapter(Context c) {
        ctx = c;
        inf = LayoutInflater.from(c);
    }

    public void setData(List<AppEntry> d) {
        data = (d == null) ? new ArrayList<AppEntry>() : d;
        notifyDataSetChanged();
    }

    public int getCount() {
        return data.size();
    }

    public AppEntry getItem(int i) {
        return data.get(i);
    }

    public long getItemId(int i) {
        return i;
    }

    public View getView(int pos, View v, ViewGroup parent) {
        if (v == null) v = inf.inflate(R.layout.item_app, parent, false);
        final AppEntry e = data.get(pos);

        TextView name = (TextView) v.findViewById(R.id.name);
        TextView meta = (TextView) v.findViewById(R.id.meta);
        ImageView icon = (ImageView) v.findViewById(R.id.icon);

        name.setText(e.name);

        String inst = PkgUtil.installedVerOf(ctx, e);
        if (inst != null) {
            if (e.ver.length() > 0 && !e.ver.equals(inst)) {
                meta.setText("可更新 " + inst + " → " + e.ver);
                meta.setTextColor(0xFFFFB020);
            } else {
                meta.setText("已安装 " + inst);
                meta.setTextColor(0xFF6FCF97);
            }
        } else if (e.ver.length() > 0) {
            meta.setText(e.ver + " · " + fmt(e.size));
            meta.setTextColor(0xFF8B97A8);
        } else {
            meta.setText(fmt(e.size));
            meta.setTextColor(0xFF8B97A8);
        }

        ImageLoader.load(e.iconUrl, icon);

        boolean focused = v.isFocused();
        v.setScaleX(focused ? 1.05f : 1f);
        v.setScaleY(focused ? 1.05f : 1f);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean has) {
                view.animate().cancel();
                view.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                        .setDuration(130).start();
            }
        });
        return v;
    }

    public static String fmt(long b) {
        if (b <= 0) return "";
        if (b >= 1073741824L) return String.format(Locale.US, "%.1fG", b / 1073741824.0);
        if (b >= 1048576L) return String.format(Locale.US, "%.1fM", b / 1048576.0);
        return String.format(Locale.US, "%.0fK", b / 1024.0);
    }
}
