package com.ahui.clustercast;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;
/**
 * 一路摄像头的画面格：TextureView + 左上角真实状态条。
 * 状态只来自 Camera2 的真实回调，没出画就是黑底加一行字，绝不画假画面。
 */
public class CamView extends FrameLayout {

    public final String label;
    public final TextureView texture;
    private final TextView tag;

    public CamView(Context c, String label, boolean bordered) {
        super(c);
        this.label = label;
        texture = new TextureView(c);
        tag = Ui.text(c, 11, Color.WHITE, Typeface.NORMAL, 2);
        selected(bordered);
        addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tag.setBackgroundColor(0xB0000000);
        tag.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        addView(tag, lp);
        status("等待出画");
    }

    /**
     * 用户要求「除按钮文字外不要多余的字」：这一路正常出画后状态条自动隐藏，
     * 只有等授权 / 没出画 / 被系统收回这类真问题才把字留在格子上 —— 不藏故障，也不刷屏。
     */
    public void status(String s) {
        if (s.contains("帧=") || s.equals("录像中") || s.equals("录入四宫格")) {
            tag.setText("");
            tag.setVisibility(GONE);
            return;
        }
        tag.setVisibility(VISIBLE);
        tag.setText(label + " · " + s);
    }

    /** 蓝边=当前选中的那一路。 */
    public void selected(boolean on) {
        setBackground(Ui.paint(getContext(), on ? Ui.R_DARK_B : Ui.R_DARK, 10));
    }
}
