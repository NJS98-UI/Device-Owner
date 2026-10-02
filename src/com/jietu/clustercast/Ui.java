package com.jietu.clustercast;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
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
 * 由 v5.1.0 的 Ui.java 裁剪移植；wallpaper() 改为优先解码 assets/wallpaper.png
 * （用户指定的 app 背景图），解码失败退回原渐变+光斑。
 */
public final class Ui {

    private Ui() { }

    public static final int R_CARD = 1;        // 普通卡片玻璃
    public static final int R_BTN = 3;         // 按钮（未选中）
    public static final int R_BTN_ON = 4;      // 按钮（选中，蓝色高亮）
    public static final int R_FIELD = 6;       // 输入框/日志区：浅灰底，在白底上要看得清轮廓
    public static final int R_GREEN = 7;       // 绿色主操作按钮
    public static final int R_DANGER = 8;      // 浅红底红字按钮（退出投屏）
    public static final int R_WHITE = 19;      // 白底卡片 / 未选中开关
    public static final int R_ONOFF = 22;      // 未选=白底，选中=蓝底白字

    public static final int INK = 0xFF1B2430;        // 主文字
    public static final int INK_SUB = 0xFF5A6A7C;    // 次级文字
    public static final int INK_FAINT = 0xFF8B99A8;  // 弱文字
    public static final int ACCENT = 0xFF1A6FF0;     // 蓝色主色
    public static final int DANGER = 0xFFD6455A;
    public static final int GREEN = 0xFF17B26A;      // 绿色主操作
    public static final int LINE = 0x2ED3DEE9;       // 卡片内分隔线

    // ===== 深色主题（冥城投屏助手主界面）=====
    // 半透明：底下垫 assets/wallpaper.jpg（用户指定的背景图），卡片要能透出背景
    // 全部 80% 透明（20% 不透明度）：页面底全透，卡片/按钮透出壁纸
    public static final int D_BG = 0x000A0E14;          // 页面底：全透明
    public static final int D_CARD = 0x33151A24;        // 深色卡片（80% 透明）
    public static final int D_BTN = 0x33222836;         // 深色按钮（80% 透明）
    public static final int D_BTN_ON = 0xE61A6FF0;      // 选中按钮（蓝，保辨识度）
    public static final int D_FIELD = 0x330D1118;       // 日志区深色底（80% 透明）
    public static final int D_GREEN = 0xFF17B26A;       // 绿色（开关等，不透明）
    public static final int D_GREEN_T = 0x3317B26A;     // 开始投屏：绿色但 80% 透明
    public static final int D_DANGER = 0xFF8B2030;      // 红色（文字/警示，不透明）
    public static final int D_DANGER_T = 0x338B2030;    // 结束投屏：红色但 80% 透明
    public static final int D_TEXT = 0xFFE8ECF1;        // 主文字
    public static final int D_TEXT_SUB = 0xFF8B99A8;    // 次级文字
    public static final int D_LINE = 0x22FFFFFF;        // 分隔线

    /**
     * 设计宽度固定 960dp（用户指定）：density = 屏宽像素/960。
     * 只对横屏生效 —— 手机竖着拿时屏宽很小，硬按 960 折算会把一切放大好几倍。
     */
    @SuppressWarnings("deprecation")
    public static void fit1050(Activity a) {
        if (a.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE) return;
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getMetrics(dm);
        if (dm.widthPixels <= 0) return;
        float density = dm.widthPixels / 960f;
        Configuration cfg = new Configuration(a.getResources().getConfiguration());
        cfg.densityDpi = (int) (density * 160f);
        dm.density = density;
        dm.scaledDensity = density;
        a.getResources().updateConfiguration(cfg, dm);
    }

    /** 界面整体缩放（顶栏「调整大小」键切换，Cfg 持久化；dp 和字号都乘它）。 */
    public static float uiScale = 1f;

    public static int dp(Context c, float v) {
        return (int) (v * uiScale * c.getResources().getDisplayMetrics().density + 0.5f);
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
            case R_WHITE:  return new int[]{0xFFFFFFFF, 0xFFFAFCFE};
            default:       return new int[]{0x00FFFFFF, 0x00FFFFFF};
        }
    }

    private static int strokeColor(int kind) {
        switch (kind) {
            case R_BTN_ON: return 0x66FFFFFF;
            case R_FIELD:  return 0x33C3D0DC;
            case R_DANGER: return 0x66E0A5AE;
            case R_WHITE:  return 0x33D5E1EC;
            default:       return 0xE6FFFFFF;
        }
    }

    /**
     * 页面底：优先用 assets/wallpaper.png（壁纸只是 app 自己界面的背景，
     * 不投到仪表），Gravity.FILL 铺满；解码失败退回白到浅灰蓝的渐变+光斑。
     * v13：失败原因写进 wallpaperDiag 给主屏显示，不再静默吞掉。
     */
    public static String wallpaperDiag = "未加载";

    public static Drawable wallpaper(Context c) {
        try {
            java.io.InputStream is = c.getAssets().open("wallpaper.png");
            Bitmap bm = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) { }
            if (bm != null) {
                BitmapDrawable bd = new BitmapDrawable(c.getResources(), bm);
                bd.setTargetDensity(c.getResources().getDisplayMetrics());
                bd.setGravity(Gravity.FILL);
                wallpaperDiag = "壁纸已加载 " + bm.getWidth() + "x" + bm.getHeight();
                return bd;
            }
            wallpaperDiag = "wallpaper.png 解码失败（decodeStream 返回 null），退回渐变底";
        } catch (Throwable t) {
            wallpaperDiag = "壁纸加载异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : "");
        }
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

    /** 深色壁纸：深蓝黑渐变 + 微光斑，用于主界面深色主题。 */
    public static Drawable darkWallpaper(Context c) {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0E14, 0xFF0F1420, 0xFF0A0E14});
        int rad = dp(c, 400f);
        return new LayerDrawable(new Drawable[]{
                base,
                radial(rad, 0x1A1A6FF0, 0.15f, 0.20f),
                radial(rad, 0x123D8BFF, 0.85f, 0.75f)});
    }

    /**
     * 主界面壁纸：assets/wallpaper.jpg（用户指定的背景图），等比放大到盖满再居中裁切
     * （图是 16:9，车机屏更宽，直接 FILL 会把车拉宽），上面压一层轻黑保文字对比度。
     * 解码失败退回 darkWallpaper 渐变，原因写 wallpaperDiag。
     */
    public static Drawable photoWallpaper(Context c) {
        try {
            java.io.InputStream is = c.getAssets().open("wallpaper.jpg");
            Bitmap bm = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) { }
            if (bm != null) {
                wallpaperDiag = "壁纸已加载 " + bm.getWidth() + "x" + bm.getHeight();
                return new CropWallpaper(bm);
            }
            wallpaperDiag = "wallpaper.jpg 解码失败（decodeStream 返回 null），退回渐变底";
        } catch (Throwable t) {
            wallpaperDiag = "壁纸加载异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : "");
        }
        return darkWallpaper(c);
    }

    /** 等比 cover + 居中裁切 + 顶部压暗，卡片半透明后文字仍可读。 */
    private static final class CropWallpaper extends Drawable {
        final Bitmap bm; final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        CropWallpaper(Bitmap b) { bm = b; }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty() || bm == null) return;
            float s = Math.max(b.width() / (float) bm.getWidth(),
                    b.height() / (float) bm.getHeight());
            float w = bm.getWidth() * s, h = bm.getHeight() * s;
            float dx = (b.width() - w) / 2f, dy = (b.height() - h) / 2f;
            canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(s, s);
            canvas.drawBitmap(bm, 0, 0, paint);
            canvas.restore();
            canvas.drawColor(0x2E000000);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    public static TextView text(Context c, int sizeDp, int color, int style, int maxLines) {
        return text(c, (float) sizeDp, color, style, maxLines);
    }

    public static TextView text(Context c, float sizeDp, int color, int style, int maxLines) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * uiScale);
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

    private static boolean darkBg(int k) { return k == R_BTN_ON || k == R_GREEN; }

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

    /** 深色圆角矩形背景（纯色 + 细边）。 */
    public static Drawable darkBg(Context c, int color, int radiusDp) {
        float r = (float) dp(c, radiusDp);
        GradientDrawable body = new GradientDrawable();
        body.setColor(color);
        body.setCornerRadius(r);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(r);
        stroke.setColor(Color.TRANSPARENT);
        stroke.setStroke(dp(c, 1f), D_LINE);
        return new LayerDrawable(new Drawable[]{stroke, body});
    }

    /** 深色卡片：竖向、自带内边距。 */
    public static LinearLayout darkCard(Context c, int radiusDp) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        v.setBackground(darkBg(c, D_CARD, radiusDp));
        return v;
    }

    /** 深色按钮文字。 */
    public static TextView darkButton(Context c, String name, int sizeDp, int bgColor, int textColor) {
        TextView b = text(c, sizeDp, textColor, Typeface.BOLD, 1);
        b.setGravity(Gravity.CENTER);
        b.setBackground(darkBg(c, bgColor, 10));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setText(name);
        return b;
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
