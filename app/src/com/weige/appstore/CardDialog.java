package com.weige.appstore;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class CardDialog extends Dialog {
    public CardDialog(Activity a, View content) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xCC05070A);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        // 布局文件里已经写死了宽度（比如 560dp）就沿用，
        // 否则 WRAP_CONTENT 会被长简介撑到铺满整个屏幕
        ViewGroup.LayoutParams src = content.getLayoutParams();
        if (src != null) {
            if (src.width > 0) lp.width = src.width;
            if (src.height > 0) lp.height = src.height;
        }
        content.setLayoutParams(lp);
        root.addView(content);
        setContentView(root);
        setCanceledOnTouchOutside(true);
    }
}
