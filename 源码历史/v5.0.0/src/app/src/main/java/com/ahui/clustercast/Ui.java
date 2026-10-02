package com.ahui.clustercast;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面统一视觉：浅色底 + 白色半透明「毛玻璃」卡片。
 * 不用 RenderEffect（那是 API 31，这台车是 Android 11），
 * 用低透明度白 + 细亮边 + 投影模拟玻璃质感，观感一致且不吃性能。
 *
 * 从 v4.7.5 的 Ui.kt 逐行等价翻译过来：颜色常量、款式编号、圆角、内边距全部照抄，
 * 一个数值都没改 —— 布局以我们的为准，所以这里必须一模一样。
 */
public final class Ui {

    private Ui() { }

    public static final int R_CARD = 1;        // 普通卡片玻璃
    public static final int R_BTN = 3;         // 按钮（未选中）
    public static final int R_BTN_ON = 4;      // 按钮（选中，蓝色高亮）
    public static final int R_FIELD = 6;       // 输入框/日志区：浅灰底，在白底上要看得清轮廓
    public static final int R_GREEN = 7;       // 绿色主操作按钮（尾门开/保存所有设置）
    public static final int R_DANGER = 8;      // 浅红底红字按钮（全关/尾门关/重启服务）
    public static final int R_REC = 15;        // 红色实心（录像中）
    public static final int R_CHIP = 16;       // 顶部白底描边小胶囊
    public static final int R_CHIP_BLUE = 17;  // 顶部淡蓝小胶囊
    public static final int R_SHELL = 18;      // 整页大底板
    public static final int R_WHITE = 19;      // 顶栏卡片 / 选中页签
    public static final int R_DARK = 20;       // 深色摄像头画面占位
    public static final int R_DARK_B = 21;     // 深色画面 + 蓝描边
    public static final int R_ONOFF = 22;      // 未选=白底，选中=蓝底白字

    public static final int INK = 0xFF1B2430;        // 主文字
    public static final int INK_SUB = 0xFF5A6A7C;    // 次级文字
    public static final int INK_FAINT = 0xFF8B99A8;  // 弱文字
    public static final int ACCENT = 0xFF1A6FF0;     // 蓝色主色
    public static final int DANGER = 0xFFD6455A;
    public static final int GREEN = 0xFF17B26A;      // 绿色主操作
    public static final int LINE = 0x2ED3DEE9;       // 卡片内分隔线

    public static final int BAR_H = 52;              // 底部导航栏高度（dp）

    /**
     * 设计宽度固定 1050dp（用户指定）：density = 屏宽像素/1050。
     * 只对横屏生效 —— 手机竖着拿时屏宽很小，硬按 1050 折算会把一切放大好几倍，
     * 按钮直接被挤出屏幕（实机反馈「有些按钮看不见」的根因之一）。
     */
    @SuppressWarnings("deprecation")
    public static void fit1050(Activity a) {
        if (a.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE) return;
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getMetrics(dm);
        if (dm.widthPixels <= 0) return;
        float density = dm.widthPixels / 1050f;
        Configuration cfg = new Configuration(a.getResources().getConfiguration());
        cfg.densityDpi = (int) (density * 160f);
        dm.density = density;
        dm.scaledDensity = density;
        a.getResources().updateConfiguration(cfg, dm);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int dp(Context c, int v) { return dp(c, (float) v); }

    public static Drawable paint(Context c, int kind, int radiusDp) {
        return paint(c, kind, (float) radiusDp);
    }

    public static Drawable paint(Context c, int kind, float radiusDp) {
        float r = (float) dp(c, radiusDp);
        GradientDrawable body = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, glassColors(kind));
        body.setCornerRadius(r);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(r);
        stroke.setColor(Color.TRANSPARENT);
        stroke.setStroke(dp(c, 1f), strokeColor(kind));
        return new LayerDrawable(new Drawable[]{stroke, body});
    }

    private static int[] glassColors(int kind) {
        switch (kind) {
            case R_CARD:   return new int[]{0xB3FFFFFF, 0x80FFFFFF};
            case R_FIELD:  return new int[]{0x80F0F5F9, 0x80F7FAFC};
            case R_BTN:    return new int[]{0x99FFFFFF, 0x66FFFFFF};
            case R_BTN_ON: return new int[]{0xE63D8BFF, 0xE61A6FF0};
            case R_GREEN:  return new int[]{0xFF1FC97E, 0xFF12A862};
            case R_DANGER: return new int[]{0xFFFDF0F1, 0xFFFBE7EA};
            case R_REC:    return new int[]{0xFFE5484D, 0xFFD6455A};
            case R_CHIP:   return new int[]{0xFFF9FBFE, 0xFFF4F8FC};
            case R_CHIP_BLUE: return new int[]{0xFFE4EEFD, 0xFFDBE8FC};
            case R_SHELL:  return new int[]{0xFFF6F8FB, 0xFFEDF1F7};
            case R_WHITE:  return new int[]{0xFFFFFFFF, 0xFFFAFCFE};
            case R_DARK:   return new int[]{0xFF1C2B41, 0xFF111C2E};
            case R_DARK_B: return new int[]{0xFF1E2F47, 0xFF121E31};
            default:       return new int[]{0x00FFFFFF, 0x00FFFFFF};
        }
    }

    private static int strokeColor(int kind) {
        switch (kind) {
            case R_BTN_ON: return 0x66FFFFFF;
            case R_FIELD:  return 0x33C3D0DC;
            case R_DANGER: return 0x66E0A5AE;
            case R_CHIP:   return 0x33C3D0DC;
            case R_SHELL:  return 0x40FFFFFF;
            case R_WHITE:  return 0x33D5E1EC;
            case R_DARK_B: return 0xCC4D8DFF;
            default:       return 0xE6FFFFFF;
        }
    }

    /** 页面底：白到浅灰蓝的渐变，加两团很淡的青/紫光斑。 */
    public static Drawable wallpaper(Context c) {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFFFFF, 0xFFF2F6FB, 0xFFF7FAFD});
        int rad = dp(c, 300f);
        return new LayerDrawable(new Drawable[]{
                base, radial(rad, 0x143D8BFF, 0.20f, 0.85f), radial(rad, 0x117A5CFF, 0.88f, 0.12f)});
    }

    /** fx/fy 是光斑中心在屏幕上的相对位置（0..1）。 */
    private static GradientDrawable radial(int radPx, int color, float fx, float fy) {
        GradientDrawable g = new GradientDrawable();
        g.setColors(new int[]{color, 0x00FFFFFF});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(fx - 0.5f, fy - 0.5f);
        g.setGradientRadius((float) radPx);
        return g;
    }

    /** 底部导航栏的整条玻璃底：通铺满宽，只有上面两个圆角和一条亮边。 */
    public static Drawable barGlass(Context c) {
        GradientDrawable body = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6FFFFFF, 0xCCF3F7FC});
        float r = (float) dp(c, 16f);
        body.setCornerRadius(r);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(r);
        stroke.setColor(Color.TRANSPARENT);
        stroke.setStroke(dp(c, 1f), 0xE6FFFFFF);
        return new LayerDrawable(new Drawable[]{stroke, body});
    }

    public static TextView text(Context c, int sizeDp, int color, int style, int maxLines) {
        return text(c, (float) sizeDp, color, style, maxLines);
    }

    public static TextView text(Context c, float sizeDp, int color, int style, int maxLines) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setTypeface(style == Typeface.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setMaxLines(maxLines > 0 ? maxLines : Integer.MAX_VALUE);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static LinearLayout.LayoutParams lw() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams ww() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weighted(float w, int hPx) {
        return new LinearLayout.LayoutParams(0, hPx, w);
    }

    public static void click(View v, final Runnable body) {
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { body.run(); }
        });
    }

    /** 款式落到具体配色：R_ONOFF 看 on 决定蓝底白字还是白底；kind<0 走通用按钮。 */
    private static int kindOf(int kind, boolean on) {
        if (kind == R_ONOFF) return on ? R_BTN_ON : R_WHITE;
        if (kind >= 0) return kind;
        return on ? R_BTN_ON : R_BTN;
    }

    private static boolean darkBg(int k) { return k == R_BTN_ON || k == R_GREEN || k == R_REC; }

    public static TextView button(Context c, String name, int sizeDp, boolean on) {
        return button(c, name, sizeDp, on, 14, 7, -1);
    }

    /** 紧凑玻璃按钮：宽度只包内容，不整条拉满。kind 决定配色款式。 */
    public static TextView button(Context c, String name, int sizeDp, boolean on,
                                  int hPad, int vPad, int kind) {
        int k = kindOf(kind, on);
        TextView b = text(c, sizeDp, darkBg(k) ? Color.WHITE : INK,
                (k == R_BTN_ON || k == R_GREEN) ? Typeface.BOLD : Typeface.NORMAL, 1);
        b.setGravity(Gravity.CENTER);
        b.setBackground(paint(c, k, 10));
        b.setPadding(dp(c, hPad), dp(c, vPad), dp(c, hPad), dp(c, vPad));
        b.setText(name);
        // R_REC 是实心红（录像中、尾门开启态）：这类底色必须配白字才看得清。
        b.setTextColor(darkBg(k) ? Color.WHITE : INK);
        b.setTypeface((k == R_BTN_ON || k == R_GREEN) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return b;
    }

    public static void restyle(TextView b, Context c, String name, boolean on) {
        restyle(b, c, name, on, -1);
    }

    public static void restyle(TextView b, Context c, String name, boolean on, int kind) {
        int k = kindOf(kind, on);
        b.setText(name);
        b.setBackground(paint(c, k, 10));
        b.setTextColor(darkBg(k) ? Color.WHITE : INK);
        b.setTypeface((k == R_BTN_ON || k == R_GREEN) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** 通用内容卡片：竖向、自带内边距。 */
    public static LinearLayout card(Context c, int radiusDp) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        v.setBackground(paint(c, R_CARD, radiusDp));
        return v;
    }

    /** 卡片内一条细分隔线。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f)));
        return v;
    }
}
