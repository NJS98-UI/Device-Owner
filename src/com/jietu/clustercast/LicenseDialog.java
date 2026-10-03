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
import android.widget.TextView;
import android.widget.Toast;

/**
 * 激活/试用弹窗（遮罩式、不可关闭）：未激活与失效状态下挡住主界面。
 * 样式照用户参考图：深蓝底圆角 + 金黄标题 + 白色输入框 + 蓝色按钮 +
 * 黄色免责声明 + 机器码展示 + 微信联系二维码（扫码购买激活码）。
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

    private LicenseDialog() { }

    static boolean isShowing() { return showing != null && showing.isShowing(); }

    static void dismiss() {
        if (showing != null) {
            try { showing.dismiss(); } catch (Throwable ignore) { }
            showing = null;
            msgView = null;
        }
    }

    /** message：状态提示（未激活/已到期/断网等）。已在显示时只更新提示，不重建（保住输入中的激活码）。 */
    static void show(Context ctx, String message, Host host) {
        if (isShowing()) {
            if (msgView != null && message != null) msgView.setText(message);
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

        // 深蓝圆角主卡片
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1B2A4A);
        cardBg.setCornerRadius(Ui.dp(ctx, 18));
        card.setBackground(cardBg);
        int pad = Ui.dp(ctx, 24);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 420), ViewGroup.LayoutParams.WRAP_CONTENT);
        FrameLayout.LayoutParams cardFlp = new FrameLayout.LayoutParams(cardLp);
        cardFlp.gravity = Gravity.CENTER;
        mask.addView(card, cardFlp);
        FrameLayout.LayoutParams maskLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // 标题（金黄）
        TextView title = new TextView(ctx);
        title.setText("捷途行车记录仪");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFC94D);
        title.setGravity(Gravity.CENTER);
        card.addView(title, Ui.lw());

        card.addView(space(ctx, 10));

        // 状态提示（白）
        TextView msg = new TextView(ctx);
        msg.setText(message == null ? "" : message);
        msg.setTextSize(14);
        msg.setTextColor(0xFFE8EEFF);
        msg.setGravity(Gravity.CENTER);
        card.addView(msg, Ui.lw());
        msgView = msg;

        card.addView(space(ctx, 12));

        // 机器码（长按复制）
        final String mc = com.kooo.evcam.license.LicenseManager.machineCode(ctx);
        TextView mcView = new TextView(ctx);
        mcView.setText("机器码：" + mc);
        mcView.setTextSize(15);
        mcView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mcView.setTextColor(0xFFFFFFFF);
        mcView.setGravity(Gravity.CENTER);
        mcView.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("machine_code", mc));
            Toast.makeText(ctx, "机器码已复制", Toast.LENGTH_SHORT).show();
            return true;
        });
        card.addView(mcView, Ui.lw());

        card.addView(space(ctx, 12));

        // 激活码输入框（白底圆角）
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
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 48));
        boxLp.leftMargin = Ui.dp(ctx, 6);
        boxLp.rightMargin = Ui.dp(ctx, 6);
        card.addView(codeBox, boxLp);

        card.addView(space(ctx, 14));

        // 激活按钮（蓝）
        TextView actBtn = button(ctx, "激  活", 0xFF2F6FED);
        actBtn.setOnClickListener(v -> {
            String code = codeBox.getText().toString().trim();
            if (code.length() != 6) {
                Toast.makeText(ctx, "激活码为 6 位数字", Toast.LENGTH_SHORT).show();
                return;
            }
            host.onActivateClicked(code);
        });
        card.addView(actBtn, Ui.lw());

        card.addView(space(ctx, 10));

        // 试用按钮（蓝，浅一点区分）
        TextView trialBtn = button(ctx, "试  用", 0xFF4A8CF0);
        trialBtn.setOnClickListener(v -> host.onTrialClicked());
        card.addView(trialBtn, Ui.lw());

        card.addView(space(ctx, 14));

        // 免责声明（黄）
        TextView dis = new TextView(ctx);
        dis.setText(DISCLAIMER);
        dis.setTextSize(12);
        dis.setLineSpacing(0, 1.2f);
        dis.setTextColor(0xFFFFD54A);
        dis.setGravity(Gravity.CENTER);
        card.addView(dis, Ui.lw());

        card.addView(space(ctx, 14));

        // 微信联系二维码
        ImageView qr = new ImageView(ctx);
        qr.setImageResource(R.drawable.qr_wechat);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int qs = Ui.dp(ctx, 120);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qs, qs);
        qrLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qr, qrLp);

        card.addView(space(ctx, 6));

        TextView qrTip = new TextView(ctx);
        qrTip.setText("微信扫码联系购买激活码");
        qrTip.setTextSize(12);
        qrTip.setTextColor(0xFFE8EEFF);
        qrTip.setGravity(Gravity.CENTER);
        card.addView(qrTip, Ui.lw());

        d.setContentView(mask, maskLp);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
    }

    private static TextView button(Context ctx, String label, int color) {
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 46));
        lp.leftMargin = Ui.dp(ctx, 6);
        lp.rightMargin = Ui.dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private static View space(Context ctx, int hDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(ctx, hDp)));
        return v;
    }
}
