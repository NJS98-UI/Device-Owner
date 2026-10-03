package com.jietu.clustercast;

import android.app.Dialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 激活/试用弹窗（遮罩式、不可关闭）：未激活与失效状态下挡住整个软件
 * （投屏/空调/车窗/记录仪全部功能，激活是整机激活）。
 * 布局照用户参考图：深蓝横向大卡片，顶行=金黄标题+白色激活码输入框+
 * 蓝色激活按钮+试用按钮；白色横线下左栏=状态提示+微信购买二维码，
 * 右栏=说明文字（机器码/免责声明/购买方式）。
 */
final class LicenseDialog {

    /** 用户指定免责声明原文。 */
    private static final String DISCLAIMER =
            "使用本软件请合法安全使用，激活和试用。造成任何后果，默认与作者无关。"
                    + "确保使用环境安全后操作，试用于激活后，默认是同意";

    interface Host {
        void onActivateClicked(String code);
        void onTrialClicked();
    }

    private static Dialog showing;
    private static TextView msgView;
    private static LinearLayout actionRow;

    private LicenseDialog() { }

    static boolean isShowing() { return showing != null && showing.isShowing(); }

    static void dismiss() {
        if (showing != null) {
            try { showing.dismiss(); } catch (Throwable ignore) { }
            showing = null;
            msgView = null;
            actionRow = null;
        }
    }

    /**
     * message：状态提示（检查中/未激活/已到期/断网等）。
     * checking：授权检查中（PENDING）——隐藏激活/试用按钮，避免误操作。
     * 已在显示时只更新提示文本，不重建（保住输入中的激活码）。
     */
    static void show(Context ctx, String message, boolean checking, Host host) {
        if (isShowing()) {
            if (msgView != null && message != null) msgView.setText(message);
            if (actionRow != null) actionRow.setVisibility(checking ? View.GONE : View.VISIBLE);
            return;
        }
        dismiss();
        final Dialog d = new Dialog(ctx);
        showing = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);

        // 全屏遮罩
        FrameLayout mask = new FrameLayout(ctx);
        mask.setBackgroundColor(0xDD0A1224);

        // 深蓝横向大卡片（超高可滚动）
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E3A6E);
        cardBg.setCornerRadius(Ui.dp(ctx, 16));
        card.setBackground(cardBg);
        int pad = Ui.dp(ctx, 20);
        card.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 880), ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.gravity = Gravity.CENTER;
        mask.addView(sv, svLp);
        FrameLayout.LayoutParams maskLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // ===== 顶行：标题 | 输入框 | 激活 | 试用 =====
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("捷途行车记录仪");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFC94D);
        top.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText codeBox = new EditText(ctx);
        codeBox.setHint("请输入激活码");
        codeBox.setTextColor(0xFF1B2A4A);
        codeBox.setHintTextColor(0xFF9AA6BF);
        codeBox.setTextSize(16);
        codeBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeBox.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeBox.setGravity(Gravity.CENTER);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xFFFFFFFF);
        boxBg.setCornerRadius(Ui.dp(ctx, 10));
        codeBox.setBackground(boxBg);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                0, Ui.dp(ctx, 46));
        boxLp.weight = 1f;
        boxLp.leftMargin = Ui.dp(ctx, 14);
        boxLp.rightMargin = Ui.dp(ctx, 10);
        top.addView(codeBox, boxLp);

        actionRow = top;

        TextView actBtn = pillButton(ctx, "激  活", 0xFF2F6FED);
        actBtn.setOnClickListener(v -> {
            String code = codeBox.getText().toString().trim();
            if (code.length() != 6) {
                Toast.makeText(ctx, "激活码为 6 位数字", Toast.LENGTH_SHORT).show();
                return;
            }
            host.onActivateClicked(code);
        });
        top.addView(actBtn, new LinearLayout.LayoutParams(
                Ui.dp(ctx, 110), Ui.dp(ctx, 46)));

        TextView trialBtn = pillButton(ctx, "试  用", 0xFF4A8CF0);
        trialBtn.setOnClickListener(v -> host.onTrialClicked());
        top.addView(trialBtn, new LinearLayout.LayoutParams(
                Ui.dp(ctx, 110), Ui.dp(ctx, 46)));
        ((LinearLayout.LayoutParams) trialBtn.getLayoutParams()).leftMargin = Ui.dp(ctx, 8);

        card.addView(top, Ui.lw());

        // 白色横线
        View line = new View(ctx);
        line.setBackgroundColor(0xFFE8EEFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 2));
        lineLp.topMargin = Ui.dp(ctx, 14);
        lineLp.bottomMargin = Ui.dp(ctx, 14);
        card.addView(line, lineLp);

        // ===== 下半：左栏（状态+二维码） | 右栏（说明） =====
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);

        // 左栏
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView msg = new TextView(ctx);
        msg.setText(message == null ? "" : message);
        msg.setTextSize(15);
        msg.setTextColor(0xFFE8EEFF);
        msg.setGravity(Gravity.CENTER);
        left.addView(msg, Ui.lw());
        msgView = msg;

        TextView buyTip = new TextView(ctx);
        buyTip.setText("请扫下方二维码购买");
        buyTip.setTextSize(14);
        buyTip.setTextColor(0xFFE8EEFF);
        buyTip.setGravity(Gravity.CENTER);
        buyTip.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        left.addView(buyTip, Ui.lw());

        ImageView qr = new ImageView(ctx);
        qr.setImageResource(R.drawable.qr_wechat);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int qs = Ui.dp(ctx, 170);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qs, qs);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        left.addView(qr, qrLp);
        bottom.addView(left, Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 白色竖线
        View vline = new View(ctx);
        vline.setBackgroundColor(0xFFE8EEFF);
        bottom.addView(vline, new LinearLayout.LayoutParams(Ui.dp(ctx, 2),
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 右栏：说明
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(Ui.dp(ctx, 16), 0, 0, 0);

        TextView brand = new TextView(ctx);
        brand.setText("【捷途行车记录仪】");
        brand.setTextSize(18);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(0xFFFFFFFF);
        brand.setGravity(Gravity.CENTER);
        right.addView(brand, Ui.lw());

        // 机器码（长按复制，报码购买用）
        final String mc = com.kooo.evcam.license.LicenseManager.machineCode(ctx);
        TextView mcView = new TextView(ctx);
        mcView.setText("机器码：" + mc);
        mcView.setTextSize(18);
        mcView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mcView.setTextColor(0xFFFFFFFF);
        mcView.setGravity(Gravity.CENTER);
        mcView.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10));
        mcView.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("machine_code", mc));
            Toast.makeText(ctx, "机器码已复制", Toast.LENGTH_SHORT).show();
            return true;
        });
        right.addView(mcView, Ui.lw());

        TextView dis = new TextView(ctx);
        dis.setText(DISCLAIMER);
        dis.setTextSize(13);
        dis.setLineSpacing(0, 1.25f);
        dis.setTextColor(0xFF4ADE80);
        dis.setGravity(Gravity.CENTER);
        right.addView(dis, Ui.lw());

        TextView price = new TextView(ctx);
        price.setText("激活码请联系客服微信购买，一台设备一个激活码，一次激活永久使用");
        price.setTextSize(13);
        price.setLineSpacing(0, 1.25f);
        price.setTextColor(0xFFFFD54A);
        price.setGravity(Gravity.CENTER);
        price.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        right.addView(price, Ui.lw());

        bottom.addView(right, Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 320)));

        d.setContentView(mask, maskLp);
        // Activity 重建/销毁边缘弹窗可能 BadTokenException——吞掉，避免把整个软件闪崩
        try { d.show(); } catch (Throwable ignore) { showing = null; return; }
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        // 检查中隐藏操作按钮
        if (checking && actionRow != null) {
            actBtn.setVisibility(View.GONE);
            trialBtn.setVisibility(View.GONE);
        }
    }

    private static TextView pillButton(Context ctx, String label, int color) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(Ui.dp(ctx, 10));
        b.setBackground(bg);
        return b;
    }

    private static View space(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(ctx, hDp)));
        return v;
    }
}
