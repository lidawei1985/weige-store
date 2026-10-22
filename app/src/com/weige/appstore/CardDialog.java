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
        root.addView(content, lp);
        setContentView(root);
        setCanceledOnTouchOutside(true);
    }
}
