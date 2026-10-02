package com.ahui.clustercast

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 仪表屏（display 2）专用极简页：只显示"正在放什么歌"，不搬任何第三方 App 的界面。
 * 音乐 App 那份实例留在主屏，所以点它照常打开、投屏互不影响。
 */
class ClusterActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tick: Tick

    private lateinit var root: FrameLayout
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var line: TextView
    private lateinit var fill: View
    private lateinit var rest: View
    private var lastKey = ""

    /** 静态嵌套 + 弱引用：服务销毁后回环自动停。 */
    private class Tick(a: ClusterActivity) : Runnable {
        private val ref = WeakReference(a)
        override fun run() {
            val a = ref.get() ?: return
            a.refresh()
            a.h.postDelayed(this, 400)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(0x00000080 /* FLAG_KEEP_SCREEN_ON */)
        setContentView(build())
        goFullscreen()
        synchronized(ALIVE) { ALIVE.add(this) }
        tick = Tick(this)
        h.post(tick)
    }

    /** 强制全屏：状态栏、导航栏、刘海全部让路，仪表屏 1920x720 一点不浪费。 */
    private fun goFullscreen() {
        window.addFlags(0x00000400 /* FLAG_FULLSCREEN */ or 0x00002000 /* FLAG_LAYOUT_NO_LIMITS */)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /** 沉浸标志偶尔会被系统改回去（切屏、焦点变化），拿到焦点再压一次。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        synchronized(ALIVE) { ALIVE.remove(this) }
        super.onDestroy()
    }

    private fun build(): View {
        root = FrameLayout(this)
        // 仪表页保持纯黑底：极简模式下这页就是背景，不能透出别的颜色
        root.setBackgroundColor(Color.BLACK)

        cover = ImageView(this)
        cover.scaleType = ImageView.ScaleType.FIT_CENTER
        add(root, cover, dp(200), dp(200), Gravity.START or Gravity.CENTER_VERTICAL,
                dp(56), 0, 0, 0)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        val lp = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        lp.marginStart = dp(200) + dp(84)
        lp.marginEnd = dp(56)
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(col, lp)

        title = Ui.text(this, 44, Color.WHITE, Typeface.BOLD, 2)
        col.addView(title, Ui.lw())

        artist = Ui.text(this, 26, 0xFFB9C2CC.toInt(), Typeface.NORMAL, 1)
        val alp = Ui.lw(); alp.topMargin = dp(10)
        col.addView(artist, alp)

        // 车机字体没有 █░ 这类方块字形，进度条只能用真 View 画
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        tvPos = Ui.text(this, 20, GREEN, Typeface.NORMAL, 1)
        row.addView(tvPos, Ui.ww())
        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        track.setBackgroundColor(0xFF2A2A2A.toInt())  // 仪表页始终是黑底，用深色槽
        fill = View(this)
        fill.setBackgroundColor(GREEN)
        rest = View(this)
        track.addView(fill, Ui.weighted(1f, dp(8)))
        track.addView(rest, Ui.weighted(0f, dp(8)))
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(16); tlp.rightMargin = dp(16)
        row.addView(track, tlp)
        tvDur = Ui.text(this, 20, GREEN, Typeface.NORMAL, 1)
        row.addView(tvDur, Ui.ww())
        val rlp = Ui.lw(); rlp.topMargin = dp(26)
        col.addView(row, rlp)

        line = Ui.text(this, 18, 0xFF7A838C.toInt(), Typeface.NORMAL, 1)
        val llp = Ui.lw(); llp.topMargin = dp(10)
        col.addView(line, llp)
        return root
    }

    private fun add(parent: FrameLayout, v: View, w: Int, hgt: Int, gravity: Int,
                    l: Int, t: Int, r: Int, b: Int) {
        val lp = FrameLayout.LayoutParams(w, hgt)
        lp.gravity = gravity
        lp.setMargins(l, t, r, b)
        parent.addView(v, lp)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun refresh() {
        val c: MediaController? = MusicListener.inst()?.active()
        val md = MusicListener.meta(c)

        if (c == null || md == null) {
            val tip = if (MusicListener.ready()) "没有正在播放的音乐"
            else "未获得通知使用权，读不到播放信息"
            show(tip, "", 0, 0, null, null)
            return
        }

        val name = md.getString(MediaMetadata.METADATA_KEY_TITLE)
        var who = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (who == null) who = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val src = label(c.packageName)
        var art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val pos = MusicListener.position(c)
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val key = src + "|" + name + "|" + who + "|" + (pos / 1000)
        if (key == lastKey) return
        lastKey = key
        show(if (name == null) "未知歌曲" else name, who ?: "", pos, dur, src, art)
    }

    private fun show(t: String, a: String, pos: Long, dur: Long, src: String?, art: Bitmap?) {
        title.text = t
        artist.text = a
        val hasBar = dur > 0
        tvPos.visibility = if (hasBar) View.VISIBLE else View.GONE
        tvDur.visibility = if (hasBar) View.VISIBLE else View.GONE
        (fill.layoutParams as LinearLayout.LayoutParams).weight =
                if (hasBar) pos.toFloat() else 0f
        (rest.layoutParams as LinearLayout.LayoutParams).weight =
                if (hasBar) Math.max(0f, (dur - pos).toFloat()) else 1f
        fill.requestLayout()
        tvPos.text = if (hasBar) fmt(pos) else ""
        tvDur.text = if (hasBar) fmt(dur) else ""
        line.text = if (src == null) "" else "来源：$src"
        if (art != null) cover.setImageBitmap(art) else cover.setImageDrawable(null)
    }

    /** 包名换成应用名，仪表屏上不用露 com.xxx。 */
    private fun label(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) { pkg }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return (s / 60).toString() + ":" + (if (s % 60 < 10) "0" else "") + (s % 60)
    }

    companion object {
        private const val GREEN = 0xFF61C96BL.toInt()
        private val ALIVE = ArrayList<ClusterActivity>()

        /** 三指右滑退出时由服务调用：把这些实例收掉，仪表屏还给原车画面。 */
        fun closeAll() {
            val copy: List<ClusterActivity>
            synchronized(ALIVE) { copy = ArrayList(ALIVE); ALIVE.clear() }
            for (a in copy) try { a.finish() } catch (ignored: Throwable) { }
        }
    }
}
