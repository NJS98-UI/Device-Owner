package com.ahui.clustercast

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面统一视觉：浅色底 + 白色半透明「毛玻璃」卡片。
 * 不用 RenderEffect（那是 API 31，这台车是 Android 11），
 * 用低透明度白 + 细亮边 + 投影模拟玻璃质感，观感一致且不吃性能。
 */
object Ui {

    const val R_CARD = 1    // 普通卡片玻璃
    const val R_BTN = 3     // 按钮（未选中）
    const val R_BTN_ON = 4  // 按钮（选中，蓝色高亮）
    const val R_FIELD = 6   // 输入框/日志区：浅灰底，在白底上要看得清轮廓
    const val R_GREEN = 7   // 绿色主操作按钮（尾门开/保存所有设置）
    const val R_DANGER = 8  // 浅红底红字按钮（全关/尾门关/重启服务）
    const val R_REC = 15    // 红色实心（录像中）
    const val R_CHIP = 16   // 顶部白底描边小胶囊
    const val R_CHIP_BLUE = 17 // 顶部淡蓝小胶囊
    const val R_SHELL = 18  // 整页大底板
    const val R_WHITE = 19  // 顶栏卡片 / 选中页签
    const val R_DARK = 20   // 深色摄像头画面占位
    const val R_DARK_B = 21 // 深色画面 + 蓝描边
    const val R_ONOFF = 22  // 未选=白底，选中=蓝底白字

    const val INK = 0xFF1B2430.toInt()        // 主文字
    const val INK_SUB = 0xFF5A6A7C.toInt()    // 次级文字
    const val INK_FAINT = 0xFF8B99A8.toInt()  // 弱文字
    const val ACCENT = 0xFF1A6FF0.toInt()     // 蓝色主色
    const val DANGER = 0xFFD6455A.toInt()
    const val GREEN = 0xFF17B26A.toInt()      // 绿色主操作
    const val LINE = 0x2ED3DEE9               // 卡片内分隔线

    const val BAR_H = 52                       // 底部导航栏高度（dp）

    /** 设计宽度 960dp：把 density 改写成 屏宽像素/960，大屏小屏一套 dp 走天下。 */
    @Suppress("DEPRECATION")
    fun fit960(a: Activity) {
        val dm = DisplayMetrics()
        a.windowManager.defaultDisplay.getMetrics(dm)
        if (dm.widthPixels <= 0) return
        val density = dm.widthPixels / 960f
        val cfg = Configuration(a.resources.configuration)
        cfg.densityDpi = (density * 160f).toInt()
        dm.density = density
        dm.scaledDensity = density
        a.resources.updateConfiguration(cfg, dm)
    }

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(c: Context, v: Int) = dp(c, v.toFloat())

    fun paint(c: Context, kind: Int, radiusDp: Int): Drawable =
            paint(c, kind, radiusDp.toFloat())

    fun paint(c: Context, kind: Int, radiusDp: Float): Drawable {
        val r = dp(c, radiusDp).toFloat()
        val body = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, glassColors(kind))
        body.cornerRadius = r
        val stroke = GradientDrawable()
        stroke.cornerRadius = r
        stroke.setColor(Color.TRANSPARENT)
        stroke.setStroke(dp(c, 1f), strokeColor(kind))
        return LayerDrawable(arrayOf(stroke, body))
    }

    private fun glassColors(kind: Int): IntArray = when (kind) {
        R_CARD -> intArrayOf(0xB3FFFFFF.toInt(), 0x80FFFFFF.toInt())
        R_FIELD -> intArrayOf(0x80F0F5F9.toInt(), 0x80F7FAFC.toInt())
        R_BTN -> intArrayOf(0x99FFFFFF.toInt(), 0x66FFFFFF)
        R_BTN_ON -> intArrayOf(0xE63D8BFF.toInt(), 0xE61A6FF0.toInt())
        R_GREEN -> intArrayOf(0xFF1FC97E.toInt(), 0xFF12A862.toInt())
        R_DANGER -> intArrayOf(0xFFFDF0F1.toInt(), 0xFFFBE7EA.toInt())
        R_REC -> intArrayOf(0xFFE5484D.toInt(), 0xFFD6455A.toInt())
        R_CHIP -> intArrayOf(0xFFF9FBFE.toInt(), 0xFFF4F8FC.toInt())
        R_CHIP_BLUE -> intArrayOf(0xFFE4EEFD.toInt(), 0xFFDBE8FC.toInt())
        R_SHELL -> intArrayOf(0xFFF6F8FB.toInt(), 0xFFEDF1F7.toInt())
        R_WHITE -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFFAFCFE.toInt())
        R_DARK -> intArrayOf(0xFF1C2B41.toInt(), 0xFF111C2E.toInt())
        R_DARK_B -> intArrayOf(0xFF1E2F47.toInt(), 0xFF121E31.toInt())
        else -> intArrayOf(0x00FFFFFF, 0x00FFFFFF)
    }

    private fun strokeColor(kind: Int): Int = when (kind) {
        R_BTN_ON -> 0x66FFFFFF
        R_FIELD -> 0x33C3D0DC
        R_DANGER -> 0x66E0A5AE
        R_CHIP -> 0x33C3D0DC
        R_SHELL -> 0x40FFFFFF
        R_WHITE -> 0x33D5E1EC
        R_DARK_B -> 0xCC4D8DFF.toInt()
        else -> 0xE6FFFFFF.toInt()
    }

    /** 页面底：白到浅灰蓝的渐变，加两团很淡的青/紫光斑。 */
    fun wallpaper(c: Context): Drawable {
        val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2F6FB.toInt(), 0xFFF7FAFD.toInt()))
        val rad = dp(c, 300f)
        val a = radial(rad, 0x143D8BFF, 0.20f, 0.85f)
        val b = radial(rad, 0x117A5CFF, 0.88f, 0.12f)
        return LayerDrawable(arrayOf(base, a, b))
    }

    /** 底部导航栏的整条玻璃底：通铺满宽，只有上面两个圆角和一条亮边。 */
    fun barGlass(c: Context): Drawable {
        val body = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6FFFFFF.toInt(), 0xCCF3F7FC.toInt()))
        val r = dp(c, 16f).toFloat()
        body.cornerRadius = r
        val stroke = GradientDrawable()
        stroke.cornerRadius = r
        stroke.setColor(Color.TRANSPARENT)
        stroke.setStroke(dp(c, 1f), 0xE6FFFFFF.toInt())
        return LayerDrawable(arrayOf(stroke, body))
    }

    /** fx/fy 是光斑中心在屏幕上的相对位置（0..1）。 */    private fun radial(radPx: Int, color: Int, fx: Float, fy: Float): GradientDrawable {
        val g = GradientDrawable()
        g.colors = intArrayOf(color, 0x00FFFFFF)
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.setGradientCenter(fx - 0.5f, fy - 0.5f)
        g.gradientRadius = radPx.toFloat()
        return g
    }

    fun text(c: Context, sizeDp: Int, color: Int, style: Int, maxLines: Int): TextView =
            text(c, sizeDp.toFloat(), color, style, maxLines)

    fun text(c: Context, sizeDp: Float, color: Int, style: Int, maxLines: Int): TextView {
        val t = TextView(c)
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        t.setTextColor(color)
        t.typeface = if (style == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        t.maxLines = if (maxLines > 0) maxLines else Int.MAX_VALUE
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        return t
    }

    fun lw(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun ww(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weighted(w: Float, hPx: Int): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, hPx, w)

    fun click(v: View, body: () -> Unit) {
        v.isClickable = true
        v.setOnClickListener { body() }
    }

    /** 紧凑玻璃按钮：宽度只包内容，不整条拉满。kind 决定配色款式。 */
    fun button(c: Context, name: String, sizeDp: Int, on: Boolean,
               hPad: Int = 14, vPad: Int = 7, kind: Int = -1): TextView {
        val k = when {
            kind == R_ONOFF -> if (on) R_BTN_ON else R_WHITE
            kind >= 0 -> kind
            else -> if (on) R_BTN_ON else R_BTN
        }
        val b = text(c, sizeDp, if (k == R_BTN_ON || k == R_GREEN || k == R_REC)
                Color.WHITE else INK,
                if (k == R_BTN_ON || k == R_GREEN) Typeface.BOLD else Typeface.NORMAL, 1)
        b.gravity = Gravity.CENTER
        b.background = paint(c, k, 10)
        b.setPadding(dp(c, hPad), dp(c, vPad), dp(c, hPad), dp(c, vPad))
        b.text = name
        return b
    }

    fun restyle(b: TextView, c: Context, name: String, on: Boolean, kind: Int = -1) {
        val k = when {
            kind == R_ONOFF -> if (on) R_BTN_ON else R_WHITE
            kind >= 0 -> kind
            else -> if (on) R_BTN_ON else R_BTN
        }
        b.text = name
        b.background = paint(c, k, 10)
        b.setTextColor(if (k == R_BTN_ON || k == R_GREEN) Color.WHITE else INK)
        b.setTypeface(if (k == R_BTN_ON || k == R_GREEN) Typeface.DEFAULT_BOLD
            else Typeface.DEFAULT)
    }

    /** 通用内容卡片：竖向、自带内边距。 */
    fun card(c: Context, radiusDp: Int): LinearLayout {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        v.background = paint(c, R_CARD, radiusDp)
        return v
    }

    /** 卡片内一条细分隔线。 */
    fun divider(c: Context): View {
        val v = View(c)
        v.setBackgroundColor(LINE)
        v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f))
        return v
    }
}
