package com.ahui.clustercast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 常驻前台服务：接收车机系统自己发出的三指手势广播。
 *
 * 该广播由 system_server 里的 CarSystemGesturesManager 用
 * sendBroadcastAsUser(intent, UserHandle.ALL) 发出，不带任何接收权限：
 *   action  = android.intent.action.car_system_gesture_mode
 *   extras  = gesture(int), display(int)
 *   gesture 编码 = 手指数*100 + 方向（0左 1右 2下 3上 4捏合）
 * 所以 300 = 三指左滑，301 = 三指右滑。普通应用注册个接收器就能收到，
 * 不需要 root、不需要 Shizuku、也不需要开无障碍。
 */
class CastService : Service() {

    internal lateinit var mHandler: Handler
    /** VDBus 的 bindService/getOnce 是同步 binder，绝不能在主线程跑。 */
    internal lateinit var mWork: Handler
    internal lateinit var mCfg: Cfg
    internal var mLastGestureAt = 0L
    private var mSink: LogSink? = null
    private val mLogBuf = StringBuilder()
    private val mTime = SimpleDateFormat("HH:mm:ss")

    interface LogSink { fun onLog(s: String) }

    /** 静态嵌套类风格的接收器：只持弱引用，服务没了就静默丢弃。 */
    class GestureRx(s: CastService) : BroadcastReceiver() {
        private val ref = WeakReference(s)
        override fun onReceive(context: Context, intent: Intent) {
            val s = ref.get() ?: return
            val g = intent.getIntExtra("gesture", -1)
            val disp = intent.getIntExtra("display", -1)
            if (g != G_3_LEFT && g != G_3_RIGHT) return
            s.log("收到三指手势 $g（来源屏 $disp）")
            val now = System.currentTimeMillis()
            synchronized(s) {
                if (now - s.mLastGestureAt < DEBOUNCE_MS) return
                s.mLastGestureAt = now
            }
            if (disp != 0 && disp != -1) return   // 只认主屏上的手势
            s.mWork.post(if (g == G_3_LEFT) s.mCast else s.mExit)
        }
    }

    private val mCast = Runnable { castFromGesture() }
    private val mExit = Runnable { exitFromGesture() }

    /** 给界面上的按钮用：动作一律丢到工作线程，总线调用不能卡主线程。 */
    fun castNow() { mWork.post(mCast) }
    fun exitNow() { mWork.post(mExit) }

    override fun onCreate() {
        super.onCreate()
        sInst = this
        mHandler = Handler(mainLooper)
        val ht = HandlerThread("cast-work")
        ht.start()
        mWork = Handler(ht.looper)
        mCfg = Cfg(this)
        startForegroundNotice()
        registerReceiver(GestureRx(this), IntentFilter(GESTURE_ACTION))
        val tick = MirrorTick(this)
        mWork.post(tick)
        mWork.post(GuardTick(this))
        mWork.post {
            val v = Vd.connect(this)
            if (!v.ok()) { log("车身总线连不上，投屏仍可用"); return@post }
            log("已连上车身总线")
            mWork.postDelayed(mThemeRetry, 1500)
        }
        log("服务已启动，正在监听三指手势广播")
        // 投屏槽位是内存态，进程重启就没了。这时若还留着「我们禁的高德」，
        // 就再没人去恢复它 —— 开机第一件事就是把它还原。
        if (mCfg.amapOffByUs()) {
            mWork.post { mAmapDisabledByUs = true; restoreAmap() }
        }
    }

    private var mThemeTries = 0
    private val mThemeRetry: Runnable = Runnable { themeRetry() }

    private fun themeRetry() {
        val v = Vd.inst() ?: return
        var t = v.getThemeViaProxy()
        if (t < 0) t = v.getTheme()
        if (t >= 0) { mThemeSeen = t; log("当前仪表模式：" + Vd.themeName(t)); return }
        if (++mThemeTries < 5) mWork.postDelayed(mThemeRetry, 1500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotice()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sInst = null
        super.onDestroy()
    }

    // ---------- 动作 ----------

    /** 进投屏前的仪表主题，退出时要还回去；-1 表示当前不在投屏中。 */
    internal var mThemeBefore = -1

    /** 仪表屏上唯一那个投屏槽位：当前投的是哪个包，null = 没投。 */
    internal var mCastPkg: String? = null

    /** 是我们自己禁的高德，退出时才允许恢复，别把用户手动禁的状态改回去。 */
    internal var mAmapDisabledByUs = false

    /**
     * 三指左滑：把「当前前台应用」投到仪表屏，仪表永远只有一个槽位。
     *   · 只有滑动才换，主屏点应用绝不自作主张投屏
     *   · 已投着 A 再投 B：A 退回主屏（不 finish、不杀进程，酷狗的歌不停），B 上仪表
     *   · 退到主屏的 A 之后照常能点
     *   · 右滑退出：投着的退回主屏，仪表还原原模式
     */
    internal fun castFromGesture() {
        val t = target()
        if (t != null && packageName == t[0]) { log("前台是我们自己的设置页，不投它"); return }
        if (t != null && t[0] == mCastPkg) { log("「" + label(t[0]!!) + "」已经在仪表上了"); return }
        switchThemeForCast()
        // 极简档仪表基本不画东西，我们的页面就是那张背景页；没这页仪表就是一片空。
        if (mCfg.castTheme() == Vd.THEME_SIMPLE) showClusterPage()
        if (t == null) {
            log("还没选投屏应用，只按档位把仪表画面准备好")
            return
        }
        applyAmapPolicy()
        retireCurrent()
        var err = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER)
        if (err != null) {
            // 第一发没成：不带 MULTIPLE_TASK 再试一次（把已有任务整搬仪表屏）
            err = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER, own = true)
            if (err == null) log("整任务搬到仪表（主屏那份会跟过去）")
        }
        if (err == null) {
            mCastPkg = t[0]
            log("已投屏 " + label(t[0]!!))
        } else {
            log("投屏失败 " + label(t[0]!!) + "：" + err)
            log("仪表屏状态：" + Caster.describeDisplays(this))
        }
    }

    /**
     * 把我们的仪表页铺到仪表屏（display 2）。
     * 必须 own=true：不能带 MULTIPLE_TASK，否则每次三指左滑都在仪表上叠一个新任务。
     */
    private fun showClusterPage() {
        val err = Caster.startOnDisplay(this, packageName,
                ClusterActivity::class.java.name, Caster.CLUSTER, own = true)
        if (err != null) log("仪表页铺不上去：" + err)
    }

    /** 把上一个投在仪表上的应用退回主屏，不杀进程。 */
    private fun retireCurrent() {
        val prev = mCastPkg
        mCastPkg = null
        if (prev == null) return
        if (packageName == prev) { ClusterActivity.closeAll(); return }
        if (Caster.moveToDisplay(this, prev, Caster.MAIN)) {
            log(label(prev) + " 已退回主屏（没杀进程）")
        } else {
            log(label(prev) + " 退回主屏失败，可能还留在仪表上")
        }
    }

    /**
     * 按界面上选的那一档切仪表模式，并回读校验。
     * 导航档 = 让原车导航画面占住仪表（再配禁用高德防它抢投屏）；
     * 极简档 = 仪表基本不画东西，我们的页面盖上去当背景。
     */
    private fun switchThemeForCast() {
        val want = mCfg.castTheme()
        val v = Vd.connect(this)
        if (!v.ok()) { log("总线没连上，仪表模式不变，直接把页面放上去"); return }
        if (mThemeBefore < 0) mThemeBefore = readTheme(v)
        v.lastPath = 0
        v.setTheme(want)
        val back = readBack(v)
        mThemeSeen = back
        val name = Vd.themeName(want)
        if (back == want) {
            log("仪表已切到$name（原=" + Vd.themeName(mThemeBefore) +
                    "，通道=" + pathName(v.lastPath) + "）")
        } else {
            log("切${name}没生效，仪表还停在" + Vd.themeName(back) +
                    "（通道=" + pathName(v.lastPath) + "）")
        }
    }

    /**
     * 高德策略：
     *   导航档 → 投屏期间禁用原车高德，防止 AmapAutoAdapter 抢仪表通道
     *   极简档 → 恢复高德，让原车高德自己那套照常能用
     * 禁用需要 signature 权限，普通应用大概率被拒，被拒就直说，不假装。
     */
    private fun applyAmapPolicy() {
        val wantDisabled = mCfg.castTheme() == Vd.THEME_NAVI
        if (!wantDisabled) { restoreAmap(); return }
        if (mAmapDisabledByUs || mCfg.amapOffByUs()) {
            mAmapDisabledByUs = true
            log("高德已处于我们禁用的状态，不重复操作"); return
        }
        if (!Caster.amapEnabled(this)) {
            log("原车高德本来就是禁用状态，不用动"); return
        }
        val err = Caster.setAmapEnabled(this, false)
        if (err == null) {
            mAmapDisabledByUs = true
            mCfg.setAmapOffByUs(true)
            log("已禁用原车高德，防止它抢仪表通道")
        } else {
            mAmapDisabledByUs = false
            log("禁用高德失败（普通应用没这个权限）：$err；改用看门狗，被抢就把页面顶回去")
        }
    }

    private fun restoreAmap() {
        if (!mAmapDisabledByUs && !mCfg.amapOffByUs()) return
        mAmapDisabledByUs = false
        mCfg.setAmapOffByUs(false)
        val err = Caster.setAmapEnabled(this, true)
        log(if (err == null) "已恢复原车高德" else "恢复高德失败：$err")
    }

    /** 三指右滑：投着的退回主屏，收掉我们的页面，仪表退回原显示模式。 */
    internal fun exitFromGesture() {
        retireCurrent()
        ClusterActivity.closeAll()
        restoreTheme()
        restoreAmap()
        log("已退出投屏，仪表退回原显示模式")
    }

    private fun restoreTheme() {
        val back = mThemeBefore
        mThemeBefore = -1
        if (back < 0) return
        val v = Vd.inst() ?: return
        v.setTheme(back)
        mThemeSeen = readBack(v)
    }

    private fun readTheme(v: Vd): Int {
        var t = v.getThemeViaProxy()
        if (t < 0) t = v.getTheme()
        mThemeSeen = t
        return t
    }

    /** 界面上显示的当前仪表主题，由工作线程刷新。 */
    @Volatile internal var mThemeSeen = -1

    // ---------- 原车桌面音乐卡片对接 ----------

    /** 原车音乐自己会发总线，别抢它的活。 */
    private val ORIG_MEDIA = "com.desaysv.mediacenter"
    private var mCardHinted = false
    private var mCardLogged = false

    /** 静态嵌套 + 弱引用：服务销毁后回环自动停。 */
    class MirrorTick internal constructor(s: CastService) : Runnable {
        private val ref = WeakReference(s)
        internal var last = ""
        internal var ticks = 0
        override fun run() {
            val s = ref.get() ?: return
            ticks++
            s.mirrorCard(this)
            s.mWork.postDelayed(this, 2000)
        }
    }

    internal fun mirrorCard(t: MirrorTick) {
        if (!mCfg.cardMirror()) return
        val l = MusicListener.inst()
        if (l == null) { cardHint(t, "还没给通知使用权，读不到播放信息"); return }
        val c = l.active() ?: run { cardHint(t, "没读到带歌名的播放会话"); return }
        val md = MusicListener.meta(c)
        if (md == null) { cardHint(t, "没读到带歌名的播放会话"); return }
        if (c.packageName == ORIG_MEDIA) { t.last = ""; return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title == null) { cardHint(t, "会话有元数据但没有歌名"); return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val key = c.packageName + "|" + title + "|" + artist
        if (key == t.last) return
        t.last = key
        val v = Vd.connect(this)
        if (!v.ok()) { cardHint(t, "车身总线连不上，推不上卡片（" + v.lastError + "）"); return }
        if (v.publishMedia(title, artist, album, null)) {
            if (!mCardLogged) {
                mCardLogged = true
                log("桌面音乐卡片已对接：$title - $artist")
            }
        } else {
            cardHint(t, "总线已连上但推送失败，看 logcat 的 ClusterCast.Vd")
        }
    }

    /** 对接没成功时只提示一次，别每 2 秒刷一条。 */
    private fun cardHint(t: MirrorTick, why: String) {
        if (t.ticks < 10 || mCardHinted) return
        mCardHinted = true
        log("桌面卡片未对接：$why")
    }

    // ---------- 守护回环：顶系统弹窗 + 同步沉浸列表 ----------

    /** 每 2 秒一拍：6 秒看一次弹窗，20 秒对一次沉浸名单，都是便宜的读操作。 */
    class GuardTick internal constructor(s: CastService) : Runnable {
        private val ref = WeakReference(s)
        internal var n = 0
        override fun run() {
            val s = ref.get() ?: return
            n++
            if (n % 3 == 0) s.guardOnce()
            if (n % 10 == 0) s.immersiveSync()
            s.mWork.postDelayed(this, 2000)
        }
    }

    internal fun guardOnce() {
        if (!mCfg.hideAdmin()) return
        AdminGuard.tick(this)?.let { log(it) }
    }

    internal fun immersiveSync() {
        if (!mCfg.immersive()) return
        Immersive.sync(this)?.let { log(it) }
    }

    /** 界面上按「dock 全屏沉浸」开关：结论一律回读后再写日志。 */
    fun applyImmersive() {
        mWork.post { log(if (mCfg.immersive()) Immersive.on(this) else Immersive.off(this)) }
    }

    /** 界面上按「屏蔽管理员弹窗」开关。 */
    fun applyHideAdmin() {
        mWork.post {
            log(if (mCfg.hideAdmin()) { AdminGuard.reset(); "开始盯系统弹窗，出现就顶掉" }
            else "已停止顶系统弹窗")
        }
    }

    /** 优先跟随前台应用（需使用情况访问权限），没授权就用上次选定的目标。 */
    internal fun target(): Array<String>? {
        if (mCfg.followTop()) {
            val pkg = TopApp.get(this)
            if (pkg != null && pkg != packageName) {
                val cls = Caster.launchable(this, pkg)
                if (cls != null) return arrayOf(pkg, cls)
                log("$pkg 没有可启动的界面，改用上次的目标")
            } else {
                log(if (TopApp.granted(this))
                    "没抓到前台应用（桌面或自身），改用上次的目标"
                else
                    "没给使用情况访问权限，取不到前台，改用上次的目标")
            }
        }
        val pkg = mCfg.pkg()
        val cls = mCfg.cls()
        if (pkg == null || cls == null) return null
        return arrayOf(pkg, cls)
    }

    internal fun label(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (t: Throwable) { pkg }

    // ---------- 日志 ----------

    fun setSink(s: LogSink?) {
        mSink = s
        s?.onLog(mLogBuf.toString())
    }

    fun log(s: String) {
        Log.i(TAG, s)
        val line = mTime.format(Date()) + "  " + s + "\n"
        synchronized(mLogBuf) {
            mLogBuf.append(line)
            if (mLogBuf.length > 8000) mLogBuf.delete(0, mLogBuf.length - 6000)
        }
        val sink = mSink ?: return
        mHandler.post { sink.onLog(mLogBuf.toString()) }
    }

    fun logText(): String = synchronized(mLogBuf) { mLogBuf.toString() }

    private fun startForegroundNotice() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = "cast"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    NotificationChannel(ch, "仪表投屏", NotificationManager.IMPORTANCE_MIN))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, ch) else Notification.Builder(this)
        b.setContentTitle("仪表投屏运行中")
                .setContentText("三指左滑投屏 · 三指右滑退出")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
        startForeground(42, b.build())
    }

    companion object {
        const val TAG = "ClusterCast"
        const val GESTURE_ACTION = "android.intent.action.car_system_gesture_mode"
        const val G_3_LEFT = 300
        const val G_3_RIGHT = 301
        private const val DEBOUNCE_MS = 800L

        /** 原车下发后也是延迟回读的，总线要一点时间才落到 MCU。 */
        internal fun readBack(v: Vd): Int {
            try { Thread.sleep(250) } catch (ignored: InterruptedException) { }
            var t = v.getThemeViaProxy()
            if (t >= 0) return t
            t = v.getTheme()
            if (t >= 0) return t
            try { Thread.sleep(400) } catch (ignored: InterruptedException) { }
            t = v.getThemeViaProxy()
            return if (t >= 0) t else v.getTheme()
        }

        internal fun pathName(p: Int): String = when (p) {
            3 -> "VDBus+Proxy"
            2 -> "Proxy"
            1 -> "VDBus"
            else -> "无"
        }

        @Volatile
        private var sInst: CastService? = null

        fun inst(): CastService? = sInst

        fun start(c: Context) {
            val i = Intent(c, CastService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
            else c.startService(i)
        }
    }
}
