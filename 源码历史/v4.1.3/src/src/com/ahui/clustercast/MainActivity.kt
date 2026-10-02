package com.ahui.clustercast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date

/** 智能控制中心：投屏 / 空调 / 车窗尾门 / 倒车 / 记录仪 五个模块，底部页签切换。 */
class MainActivity : Activity(), CastService.LogSink {

    private val handler = Handler()
    private lateinit var cfg: Cfg
    private lateinit var log: TextView
    private val uiLog = ArrayList<String>()
    private lateinit var refreshLoop: Refresh

    private var content: FrameLayout? = null
    private val pages = arrayOfNulls<View>(5)
    private val tabs = ArrayList<TextView>()
    private var cur = 0
    private var tick = 0
    private var modChip: TextView? = null
    private lateinit var busChip: TextView
    private lateinit var carChip: TextView
    private lateinit var gearChip: TextView
    private var askedPerm = false
    private var askedCam = false

    // 投屏页开关
    private lateinit var btnNavi: TextView
    private lateinit var btnSimple: TextView
    private lateinit var btnFollow: TextView
    private lateinit var btnCard: TextView
    private lateinit var btnHideAdmin: TextView
    private lateinit var btnImmersive: TextView

    // 总线车控 / 摄像头
    private var car: CarCtl? = null
    private var cams: CamCtl? = null
    private var gear = 0                      // 327684/26 回读：1=P 2=R 3=N 4=D，0=没读到

    // 车窗/尾门
    private val winState = IntArray(4) { -1 }                         // 每扇窗回读到的枚举值
    private val winBtn = Array(4) { arrayOfNulls<TextView>(3) }       // [窗][透气/开/关]
    private val winTxt = ArrayList<TextView>()                        // 每扇窗后面的状态文字
    private lateinit var tailOpen: TextView
    private lateinit var tailClose: TextView
    private lateinit var tailState: TextView

    // 倒车
    private lateinit var revStatus: TextView
    private lateinit var revOn: TextView
    private lateinit var revOff: TextView
    private lateinit var gearText: TextView
    private lateinit var btnAutoR: TextView
    private var mirrorRaw = -1                // cmdId 51 的原始回读值

    // 记录仪
    private lateinit var recChip: TextView
    private lateinit var recPause: TextView
    private lateinit var dvrInfo: TextView
    private val loopBtns = ArrayList<TextView>()
    private var loopSel = 1                   // 1 / 3 / 5 分钟一段
    private var dvrSel = CamCtl.ID_BACK       // 当前录制那一路的 cameraId
    private val dvrBoxes = LinkedHashMap<String, CamView>()

    /** 弱引用回环：Activity 没了自动停。 */
    private class Refresh(a: MainActivity) : Runnable {
        private val ref = WeakReference(a)
        override fun run() {
            val a = ref.get() ?: return
            a.refresh()
            a.handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(b: Bundle?) {
        Ui.fit960(this)
        super.onCreate(b)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        cfg = Cfg(this)
        car = CarCtl(this, handler) { slog(it) }
        cams = CamCtl(this)
        setContentView(build())
        CastService.start(this)
        refreshLoop = Refresh(this)
        handler.postDelayed(refreshLoop, 300)
        slog("服务已启动，正在监听三指手势广播")
        handleQnx(getIntent())
    }

    override fun onNewIntent(it: Intent?) {
        super.onNewIntent(it)
        setIntent(it)
        handleQnx(it)
    }

    /**
     * adb 驱动仪表显示区试验：一条报文一次调用，配 adb 侧的屏摄就能逐档回读。
     *   am start -n com.ahui.clustercast/.MainActivity --es qnx area=2
     *   ... --es qnx load=1        加载态开/关
     *   ... --es qnx latch=1,1,0,0 占页+重闩（DisplayCluster,NaviFrontDesk,Perspective,RequestArea）
     *   ... --es qnx show=1,1,0,0  同上但不重闩
     */
    private fun handleQnx(it: Intent?) {
        val spec = it?.getStringExtra("qnx") ?: return
        val c = car ?: return
        val (head, arg) = if (spec.contains('='))
            spec.substringBefore('=') to spec.substringAfter('=') else spec to ""
        val n = arg.toIntOrNull()
        val q = arg.split(',').map { x -> x.trim().toIntOrNull() ?: 0 }
        fun quad(i: Int) = i != 0
        when (head) {
            "area" -> if (n != null) c.areaAction("adb area=$n") { it2 -> it2.areaRelatch(n) }
            "load" -> if (n != null) c.areaAction("adb load=$n") { it2 -> it2.loading(n != 0) }
            "latch" -> if (q.size == 4) c.areaAction("adb latch=$arg") { it2 ->
                it2.relatch(quad(q[0]), quad(q[1]), q[2], quad(q[3])) }
            "show" -> if (q.size == 4) c.areaAction("adb show=$arg") { it2 ->
                it2.clusterShow(quad(q[0]), quad(q[1]), q[2], quad(q[3])) }
            else -> slog("qnx 指令不认识：$spec")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(attach, 600)
        handler.postDelayed({ autoPermCheck() }, 1200)
        syncCams()
    }

    override fun onPause() {
        handler.removeCallbacks(attach)
        CastService.inst()?.setSink(null)
        cams?.stopAll()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshLoop)
        CastService.inst()?.setSink(null)
        cams?.destroy()
        cams = null
        car?.close()
        car = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        val g = grants.firstOrNull() ?: -1
        slog(if (g == 0) "CAMERA 已授权，开始取流" else "CAMERA 没给，环视画面用不了")
        if (g == 0) cams?.restart()
    }

    /** 摄像头只在用得到它的两页上跑，别的页面一律断流，不空转。 */
    private fun syncCams() {
        val c = cams ?: return
        if (cur == 3 || cur == 4) c.restart() else c.stopAll()
    }

    private val attach = Runnable {
        val svc = CastService.inst()
        if (svc == null) { CastService.start(this@MainActivity); return@Runnable }
        svc.setSink(this)
        refresh()
    }

    override fun onLog(s: String) { handler.post { renderLog() } }

    /** 界面操作也记进同一个日志，格式和投屏日志一致。 */
    private fun slog(s: String) {
        val t = SimpleDateFormat("HH:mm:ss").format(Date())
        uiLog.add(t + "  " + s)
        while (uiLog.size > 80) uiLog.removeAt(0)
        renderLog()
    }

    private fun renderLog() {
        if (!::log.isInitialized) return
        val sb = StringBuilder()
        CastService.inst()?.logText()?.let { sb.append(it).append('\n') }
        for (l in uiLog) sb.append(l).append('\n')
        log.text = sb
        (log.parent as? ScrollView)?.post {
            (log.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ---------- 整体骨架 ----------

    private fun build(): View {
        fun dp(v: Int) = Ui.dp(this, v)

        val shell = FrameLayout(this)
        shell.background = Ui.wallpaper(this)
        shell.setPadding(dp(12), dp(10), dp(12), dp(10))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        shell.addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        col.addView(header(), Ui.lw())

        content = FrameLayout(this)
        val clp = Ui.lw(); clp.weight = 1f; clp.topMargin = dp(10)
        col.addView(content, clp)
        pages[0] = pageCast()
        pages[1] = null                       // 空调走原车，不占页
        pages[2] = pageWindow()
        pages[3] = pageReverse()
        pages[4] = pageDvr()
        for (p in pages) if (p != null)
            content!!.addView(p, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val barWrap = Ui.lw()
        barWrap.topMargin = dp(10)
        barWrap.height = dp(52)
        col.addView(tabBar(), barWrap)
        showPage(0)
        return shell
    }

    private fun header(): View {
        val h = LinearLayout(this)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.background = Ui.paint(this, Ui.R_WHITE, 14)
        h.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9))
        val t = Ui.text(this, 19, Ui.INK, Typeface.BOLD, 1)
        t.text = "智能控制中心"
        h.addView(t, Ui.ww())
        val sp = LinearLayout.LayoutParams(0, 1, 1f)
        h.addView(View(this), sp)
        // 三个胶囊全部走真实检测：服务、总线、仪表屏。
        gearChip = chip(h, "挡位检测中")
        busChip = chip(h, "总线检测中")
        modChip = chip(h, "当前模块： 投屏")
        carChip = chip(h, "仪表屏检测中")
        return h
    }

    private fun chip(parent: LinearLayout, s: String): TextView {
        val c = Ui.text(this, 11, Ui.INK_SUB, Typeface.NORMAL, 1)
        c.text = s
        c.gravity = Gravity.CENTER
        c.background = Ui.paint(this, Ui.R_CHIP, 13)
        c.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5))
        val p = Ui.ww()
        p.marginEnd = Ui.dp(this, 8)
        parent.addView(c, p)
        return c
    }

    /** 底部页签条：五个等宽，整条铺满，当前页白底蓝字。 */
    private fun tabBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = Ui.paint(this, Ui.R_WHITE, 16)
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5))
        for (i in Presets.ITEMS.indices) {
            val b = Ui.text(this, 13, Ui.INK_SUB, Typeface.NORMAL, 1)
            b.gravity = Gravity.CENTER
            b.text = Presets.ITEMS[i].name
            b.isClickable = true
            Ui.click(b) { onTab(i) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            tabs.add(b)
            bar.addView(b, lp)
        }
        return bar
    }

    private fun onTab(i: Int) {
        if (i == 1) {   // 空调：以原车界面为准，直接拉起，不切页
            Presets.launch(this, Presets.ITEMS[1])?.let { toast(it) }
            slog("切换至 空调 页面")
            return
        }
        if (cur != i) slog("切换至 " + Presets.ITEMS[i].name + " 页面")
        showPage(i)
    }

    private fun showPage(i: Int) {
        cur = i
        for (k in pages.indices) pages[k]?.visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in tabs.indices) {
            val on = k == i
            tabs[k].setTextColor(if (on) Ui.ACCENT else Ui.INK_SUB)
            tabs[k].setTypeface(if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            tabs[k].background = if (on) Ui.paint(this, Ui.R_CARD, 12) else null
        }
        modChip?.text = "当前模块： " + Presets.ITEMS[i].name
        syncCams()
        if (i == 2) readVehicle()
        if (i == 3) readMirror()
    }

    // ---------- 投屏页（原有功能，一个不少） ----------

    private fun pageCast(): View {
        fun dp(v: Int) = Ui.dp(this, v)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // 左卡整体放进竖向滚动容器：小屏放不下时能滑，绝不裁掉按钮。
        val leftScroll = ScrollView(this)
        val slp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        row.addView(leftScroll, slp)
        val left = Ui.card(this, 16)
        leftScroll.addView(left, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        btnNavi = sBtn("导航模式") { pickTheme(Vd.THEME_NAVI) }
        btnSimple = sBtn("极简模式") { pickTheme(Vd.THEME_SIMPLE) }
        btnFollow = sBtn("跟随前台") {
            val on = !cfg.followTop()
            cfg.setFollowTop(on)
            if (on && !TopApp.granted(this)) toast(USAGE_ADB_HINT)
            syncToggles()
        }
        btnCard = sBtn("音乐卡片") {
            cfg.setCardMirror(!cfg.cardMirror())
            syncToggles()
        }
        btnHideAdmin = sBtn("屏蔽弹窗") {
            cfg.setHideAdmin(!cfg.hideAdmin())
            syncToggles()
            withSvc { it.applyHideAdmin() }
        }
        btnImmersive = sBtn("dock 沉浸") {
            cfg.setImmersive(!cfg.immersive())
            syncToggles()
            withSvc { it.applyImmersive() }
        }
        val restart = sBtnRestart()

        // 一排三个、靠左：第一排投屏动作，第二排仪表档位，第三排开关，第四排授权+重启。
        left.addView(btnRow(
                sBtn("选择投屏应用") { openPicker() },
                sBtn("立即投屏") { withSvc { it.castNow() } },
                sBtn("退出投屏") { withSvc { it.exitNow() } }), Ui.lw())
        val lab = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        lab.text = "投屏仪表"
        left.addView(lab, withMargin(Ui.lw(), dp(12)))
        left.addView(btnRow(btnNavi, btnSimple, btnFollow), withMargin(Ui.lw(), dp(6)))
        left.addView(btnRow(btnCard,
                sBtn("使用情况授权") { if (!TopApp.request(this)) toast(USAGE_ADB_HINT) },
                sBtn("通知使用权") { if (!openNotifSettings()) toast(NOTIF_ADB_HINT) }),
                withMargin(Ui.lw(), dp(6)))
        val lab2 = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        lab2.text = "辅助开关"
        left.addView(lab2, withMargin(Ui.lw(), dp(12)))
        left.addView(btnRow(btnHideAdmin, btnImmersive), withMargin(Ui.lw(), dp(6)))
        left.addView(btnRow(restart), withMargin(Ui.lw(), dp(6)))

        // 面板由 QNX 合成，它只把我们的整屏流塞进"导航显示区"那块矩形——边框就是矩形边界。
        // CAR_LAN 的 bean 事件在转发服务里没有对应 unpack（SVVDSCarLan 反查过），发出去静默丢，
        // 所以这里全部走 Vd 的裸隧道，并且每次改完都补一次"加载中1→内容→加载中0"重闩。
        val lab3 = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        lab3.text = "仪表铺满试验"
        left.addView(lab3, withMargin(Ui.lw(), dp(12)))
        left.addView(btnRow(
                areaBtn("档1·中间+重闩") { it.areaRelatch(1) },
                areaBtn("档2·左侧+重闩") { it.areaRelatch(2) },
                areaBtn("档3+重闩") { it.areaRelatch(3) }),
                withMargin(Ui.lw(), dp(6)))
        left.addView(btnRow(
                areaBtn("档4+重闩") { it.areaRelatch(4) },
                areaBtn("档0·关+重闩") { it.areaRelatch(0) },
                areaBtn("占页+申请区") { it.relatch(true, true, 0, true) }),
                withMargin(Ui.lw(), dp(6)))
        // Perspective 是这条报文里唯一的整数，QNX 回执还会把它原样吐回来，铺满最可能卡在它。
        left.addView(btnRow(
                areaBtn("视角0+重闩") { it.relatch(true, true, 0, false) },
                areaBtn("视角1+重闩") { it.relatch(true, true, 1, false) },
                areaBtn("视角2+重闩") { it.relatch(true, true, 2, false) }),
                withMargin(Ui.lw(), dp(6)))
        left.addView(btnRow(
                areaBtn("视角3+重闩") { it.relatch(true, true, 3, false) },
                areaBtn("视角4+重闩") { it.relatch(true, true, 4, false) },
                areaBtn("让位申请+重闩") { it.relatch(false, true, 0, true) }),
                withMargin(Ui.lw(), dp(6)))
        left.addView(btnRow(
                areaBtn("释放仪表") { it.relatch(false, false, 0, false) },
                areaBtn("只发占页不重闩") { it.clusterShow(true, true, 0, false) },
                areaBtn("探加载态") { it.loading(true) }), withMargin(Ui.lw(), dp(6)))

        val right = Ui.card(this, 16)
        val rp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f)
        rp.marginStart = dp(10)
        row.addView(right, rp)
        val lt = Ui.text(this, 13, Ui.INK_SUB, Typeface.NORMAL, 1)
        lt.text = "实时日志"
        right.addView(lt, Ui.lw())
        log = Ui.text(this, 11, Ui.INK_SUB, Typeface.NORMAL, 0)
        log.setPadding(dp(10), dp(8), dp(10), dp(8))
        val lsv = ScrollView(this)
        lsv.addView(log)
        val llp = Ui.lw()
        llp.topMargin = dp(8)
        llp.height = 0
        llp.weight = 1f
        right.addView(lsv, llp)
        return row
    }

    // ---------- 车窗/尾门页 ----------

    private fun pageWindow(): View {
        fun dp(v: Int) = Ui.dp(this, v)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // 左：四扇窗各一行。总线只有 关/开/透气 三个枚举值，没有百分比档，所以不做滑条。
        val lc = Ui.card(this, 16)
        row.addView(lc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        lc.addView(centerTitle("车窗控制"))
        lc.addView(CarTopView(this), withMargin(Ui.weighted(1f, 0), dp(6)))
        winTxt.clear()
        for (i in 0..3) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val nm = Ui.text(this, 12, Ui.INK, Typeface.BOLD, 1)
            nm.text = CarCtl.WIN_NAME[i]
            line.addView(nm, Ui.ww())
            val st = Ui.text(this, 11, Ui.INK_SUB, Typeface.NORMAL, 1)
            st.text = "状态读取中"
            winTxt.add(st)
            val sp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            sp.marginStart = dp(8)
            line.addView(st, sp)
            for (k in WIN_VALUES.indices) {
                val b = Ui.button(this, WIN_LABELS[k], 12, false, hPad = 10, vPad = 4,
                        kind = Ui.R_ONOFF)
                winBtn[i][k] = b
                val wi = i
                Ui.click(b) { setWin(wi, k) }
                val p = Ui.ww()
                p.marginStart = dp(6)
                line.addView(b, p)
            }
            lc.addView(line, withMargin(Ui.lw(), dp(5)))
        }
        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        for (k in WIN_VALUES.indices) {
            val b = Ui.button(this, "四窗" + WIN_LABELS[k], 12, false, hPad = 8, vPad = 5,
                    kind = if (WIN_VALUES[k] == CarCtl.WIN_CLOSE) Ui.R_DANGER else Ui.R_ONOFF)
            if (WIN_VALUES[k] == CarCtl.WIN_CLOSE) b.setTextColor(Ui.DANGER)
            val idx = k
            Ui.click(b) { setAllWindows(WIN_VALUES[idx]) }
            val p = LinearLayout.LayoutParams(0, dp(38), 1f)
            if (k > 0) p.marginStart = dp(6)
            quick.addView(b, p)
        }
        lc.addView(quick, withMargin(Ui.lw(), dp(8)))
        val note = Ui.text(this, 10, Ui.INK_FAINT, Typeface.NORMAL, 0)
        note.text = "总线只有关/开/透气三挡，没有百分比；非 P 挡时界面先拦开窗。" +
                "每次下发都会回读比对，日志里能看到是否真生效。"
        lc.addView(note, withMargin(Ui.lw(), dp(6)))

        // 右：尾门 cmdId 92，写 1=关 2=开，单条即等效原车长按。
        val rc = Ui.card(this, 16)
        val rlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        rlp.marginStart = dp(10)
        row.addView(rc, rlp)
        rc.addView(centerTitle("尾门控制"))
        rc.addView(TailgateView(this), withMargin(Ui.weighted(1f, 0), dp(8)))
        tailState = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        tailState.text = "尾门状态读取中"
        tailState.gravity = Gravity.CENTER
        rc.addView(tailState, Ui.lw())
        tailOpen = Ui.button(this, "尾门开", 13, true, hPad = 8, vPad = 0, kind = Ui.R_GREEN)
        Ui.click(tailOpen) { setTail(true) }
        val op = Ui.lw(); op.topMargin = dp(10); op.height = dp(44)
        rc.addView(tailOpen, op)
        tailClose = Ui.button(this, "尾门关", 13, false, hPad = 8, vPad = 0, kind = Ui.R_DANGER)
        tailClose.setTextColor(Ui.DANGER)
        Ui.click(tailClose) { setTail(false) }
        val tp = Ui.lw(); tp.topMargin = dp(8); tp.height = dp(44)
        rc.addView(tailClose, tp)
        return row
    }

    private fun centerTitle(s: String): View {
        val t = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1)
        t.text = s
        t.gravity = Gravity.CENTER
        return t
    }

    /** 一扇窗的动作：下发 + 回读校验，期间该行按钮置灰，防止连发把 MCU 打烦。 */
    private fun setWin(i: Int, k: Int) {
        val c = car ?: return toast("总线还没连上")
        val value = WIN_VALUES[k]
        if (value != CarCtl.WIN_CLOSE && CarCtl.blocksOpen(gear)) {
            slog("${CarCtl.WIN_NAME[i]}车窗没发：当前 ${CarCtl.gearName(gear)} 挡，" +
                    "开窗类先在界面拦一层")
            return toast("非 P 挡不发开窗指令（MCU 那边会不会拦还没实测）")
        }
        winRowEnabled(i, false)
        c.setWindow(i, value) { ok, m ->
            winRowEnabled(i, true)
            if (ok) { winState[i] = value; paintWin(i) }
            winTxt[i].text = if (ok) "当前 " + CarCtl.winName(value) else "未确认，看日志"
            toast(m)
        }
    }

    /** 四个窗一起动，逐条发 162~165（原车语音也是这么干的，没用未验证排布的 cmdId 175）。 */
    private fun setAllWindows(value: Int) {
        val c = car ?: return toast("总线还没连上")
        if (value != CarCtl.WIN_CLOSE && CarCtl.blocksOpen(gear)) {
            slog("四窗没发：当前 ${CarCtl.gearName(gear)} 挡")
            return toast("非 P 挡不发开窗指令")
        }
        for (i in 0..3) winRowEnabled(i, false)
        c.setAllWindows(value) { ok, m ->
            for (i in 0..3) {
                winRowEnabled(i, true)
                if (ok) { winState[i] = value; paintWin(i); winTxt[i].text = "当前 " + CarCtl.winName(value) }
                else winTxt[i].text = "未确认，看日志"
            }
            toast(m)
        }
    }

    private fun winRowEnabled(i: Int, on: Boolean) {
        for (b in winBtn[i]) b?.isEnabled = on
    }

    private fun paintWin(i: Int) {
        for (k in WIN_VALUES.indices) {
            val b = winBtn[i][k] ?: continue
            Ui.restyle(b, this, WIN_LABELS[k], winState[i] == WIN_VALUES[k], Ui.R_ONOFF)
        }
    }

    /** 尾门：开=2 关=1，回读 0/1 算关侧、≥2 算开侧。 */
    private fun setTail(open: Boolean) {
        val c = car ?: return toast("总线还没连上")
        if (open && CarCtl.blocksOpen(gear)) {
            slog("尾门没开：当前 ${CarCtl.gearName(gear)} 挡，界面先拦")
            return toast("非 P 挡不开尾门")
        }
        tailOpen.isEnabled = false
        tailClose.isEnabled = false
        c.setTailgate(open) { ok, m ->
            tailOpen.isEnabled = true
            tailClose.isEnabled = true
            tailState.text = if (ok) "尾门：" + (if (open) "开侧" else "关侧") + "（已回读）"
                else "尾门未确认，看日志"
            toast(m)
        }
    }

    /** 进页面/定时把车窗与尾门的真实状态读回来。 */
    private fun readVehicle() {
        val c = car ?: return
        c.readWindows { v ->
            for (i in 0..3) {
                winState[i] = v[i]
                paintWin(i)
                winTxt[i].text = "当前 " + CarCtl.winName(v[i])
            }
        }
        c.readTailgate { v ->
            tailState.text = if (v < 0) "尾门状态读不到（总线未连？）"
                else "尾门：" + (if (v >= 2) "开侧" else "关侧") + "（原始值 $v）"
        }
    }

    // ---------- 倒车页 ----------

    private fun pageReverse(): View {
        fun dp(v: Int) = Ui.dp(this, v)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // 三格真实取流。原车 360 是 Kanzi 直接出的硬件显示平面，Android 侧截不到帧，
        // 所以中间这格用我们自己的后视 id7，不冒充"360 拼接"。
        val cams = LinearLayout(this)
        cams.orientation = LinearLayout.HORIZONTAL
        row.addView(cams, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))
        revBox(cams, "左盲区", CamCtl.ID_LEFT, 1f, 0)
        revBox(cams, "后方", CamCtl.ID_BACK, 1.5f, dp(8))
        revBox(cams, "右盲区", CamCtl.ID_RIGHT, 1f, dp(8))

        val card = Ui.card(this, 16)
        val rp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        rp.marginStart = dp(10)
        row.addView(card, rp)
        val t = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1)
        t.text = "倒车后视镜自动下翻"
        card.addView(t, Ui.lw())

        val r0 = settingRow("当前挡位")
        gearText = Ui.text(this, 12, Ui.ACCENT, Typeface.BOLD, 1)
        gearText.text = "读取中"
        r0.addView(gearText, Ui.ww())

        val r1 = settingRow("下翻功能")
        revStatus = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        revStatus.text = "总线值读取中"
        revStatus.gravity = Gravity.CENTER
        r1.addView(revStatus, Ui.ww())

        val r2 = settingRow("保存当前位置")
        val saveBtn = Ui.button(this, "保存", 12, false, hPad = 16, vPad = 6, kind = Ui.R_WHITE)
        Ui.click(saveBtn) { saveMirror() }
        r2.addView(saveBtn, Ui.ww())

        val r3 = settingRow("自动下翻开关")
        val tg = LinearLayout(this)
        tg.orientation = LinearLayout.VERTICAL
        revOn = Ui.button(this, "开启", 12, false, hPad = 20, vPad = 5, kind = Ui.R_ONOFF)
        revOff = Ui.button(this, "关闭", 12, false, hPad = 20, vPad = 5, kind = Ui.R_ONOFF)
        Ui.click(revOn) { setReverse(true) }
        Ui.click(revOff) { setReverse(false) }
        tg.addView(revOn, Ui.lw())
        val op = Ui.lw(); op.topMargin = dp(5)
        tg.addView(revOff, op)
        r3.addView(tg, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val r4 = settingRow("挂 R 自动开画面")
        btnAutoR = Ui.button(this, "关", 12, false, hPad = 20, vPad = 6, kind = Ui.R_ONOFF)
        Ui.click(btnAutoR) {
            cfg.setAutoR(!cfg.autoR())
            syncReverse()
            slog(if (cfg.autoR()) "挂 R 自动开画面：已开" else "挂 R 自动开画面：已关")
        }
        r4.addView(btnAutoR, Ui.ww())

        card.addView(r0, withMargin(Ui.lw(), dp(10)))
        card.addView(Ui.divider(this), Ui.lw())
        card.addView(r1, Ui.lw())
        card.addView(Ui.divider(this), withMargin(Ui.lw(), dp(2)))
        card.addView(r3, Ui.lw())
        card.addView(Ui.divider(this), withMargin(Ui.lw(), dp(2)))
        card.addView(r2, Ui.lw())
        card.addView(Ui.divider(this), withMargin(Ui.lw(), dp(2)))
        card.addView(r4, Ui.lw())

        val reload = Ui.button(this, "重新读取状态", 13, false, hPad = 8, vPad = 0,
                kind = Ui.R_WHITE)
        Ui.click(reload) { readMirror(); readVehicle(); slog("重新读取后视镜/挡位状态") }
        val sap = Ui.lw()
        sap.topMargin = dp(12)
        sap.height = dp(42)
        card.addView(reload, sap)
        syncReverse()
        return row
    }

    private fun revBox(parent: LinearLayout, name: String, id: String, w: Float, ml: Int) {
        val v = CamView(this, name, true)
        cams?.attach(v, id)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
        lp.marginStart = ml
        parent.addView(v, lp)
    }

    /** 下翻开关（cmdId 51）：1=关 2=开，发完必须回读，回读不到就报未确认。 */
    private fun setReverse(on: Boolean) {
        val c = car ?: return toast("总线还没连上")
        revOn.isEnabled = false
        revOff.isEnabled = false
        c.setMirrorFlip(on) { ok, m ->
            revOn.isEnabled = true
            revOff.isEnabled = true
            if (ok) mirrorRaw = if (on) 2 else 1
            syncReverse()
            toast(m)
        }
    }

    /** 保存当前座椅+后视镜位置（cmdId 201，值=记忆位号）。 */
    private fun saveMirror() {
        val c = car ?: return toast("总线还没连上")
        c.saveMirrorPos(1) { ok, m -> toast(if (ok) m else "保存未确认：$m") }
    }

    private fun readMirror() {
        val c = car ?: return
        c.readMirrorFlip { v -> mirrorRaw = v; syncReverse() }
    }

    private fun syncReverse() {
        if (!::revStatus.isInitialized) return
        revStatus.text = when (mirrorRaw) {
            2 -> "已开启（总线值 2）"
            1 -> "已关闭（总线值 1）"
            -1 -> "读不到（总线未连？）"
            else -> "总线值 $mirrorRaw（本机约定 1=关 2=开）"
        }
        Ui.restyle(revOn, this, "开启", mirrorRaw == 2, Ui.R_ONOFF)
        Ui.restyle(revOff, this, "关闭", mirrorRaw == 1, Ui.R_ONOFF)
        Ui.restyle(btnAutoR, this, if (cfg.autoR()) "开" else "关", cfg.autoR(), Ui.R_ONOFF)
        gearText.text = if (gear == 0) "读不到" else CarCtl.gearName(gear) + " 挡"
    }

    /** 设置行：左标题 + 右侧留空由调用方塞控件。 */
    private fun settingRow(name: String): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = Ui.dp(this, 48)
        val t = Ui.text(this, 12, Ui.INK, Typeface.NORMAL, 1)
        t.text = name
        r.addView(t, Ui.ww())
        val sp = LinearLayout.LayoutParams(0, 1, 1f)
        r.addView(View(this), sp)
        return r
    }

    // ---------- 记录仪页 ----------

    private fun pageDvr(): View {
        fun dp(v: Int) = Ui.dp(this, v)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        // 2x2 四格真实取流；点哪一格，录像就切到哪一路。
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        row.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
        val ids = arrayOf(CamCtl.ID_FRONT, CamCtl.ID_BACK, CamCtl.ID_LEFT, CamCtl.ID_RIGHT)
        for (r in 0..1) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            dvrBox(line, dvrName(ids[r * 2]), ids[r * 2], 1f, 0)
            dvrBox(line, dvrName(ids[r * 2 + 1]), ids[r * 2 + 1], 1f, dp(8))
            val lp = Ui.lw()
            lp.height = 0; lp.weight = 1f
            if (r == 1) lp.topMargin = dp(8)
            grid.addView(line, lp)
        }

        val card = Ui.card(this, 16)
        val cp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        cp.marginStart = dp(10)
        row.addView(card, cp)

        val r1 = settingRow("录像状态")
        recChip = Ui.text(this, 12, Color.WHITE, Typeface.BOLD, 1)
        recChip.gravity = Gravity.CENTER
        recChip.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5))
        Ui.click(recChip) { toggleRec() }
        val rcp = Ui.ww()
        rcp.marginEnd = dp(6)
        r1.addView(recChip, rcp)
        recPause = Ui.button(this, "开始", 12, false, hPad = 14, vPad = 5, kind = Ui.R_WHITE)
        Ui.click(recPause) { toggleRec() }
        r1.addView(recPause, Ui.ww())

        val r2 = settingRow("当前这路")
        dvrInfo = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 1)
        dvrInfo.gravity = Gravity.END
        r2.addView(dvrInfo, Ui.ww())

        val r3 = settingRow("循环分段")
        loopBtns.clear()
        for ((i, n) in listOf("1分钟", "3分钟", "5分钟").withIndex()) {
            val b = Ui.button(this, n, 12, i == loopSel, hPad = 10, vPad = 5, kind = Ui.R_ONOFF)
            Ui.click(b) {
                loopSel = i
                cams?.setLoopMinutes(intArrayOf(1, 3, 5)[i])
                syncDvr()
                slog("循环分段 -> ${intArrayOf(1, 3, 5)[i]} 分钟一段")
            }
            loopBtns.add(b)
            val p = Ui.ww(); p.marginStart = dp(6)
            r3.addView(b, p)
        }
        cams?.setLoopMinutes(intArrayOf(1, 3, 5)[loopSel])

        card.addView(r1, withMargin(Ui.lw(), dp(4)))
        card.addView(Ui.divider(this), Ui.lw())
        card.addView(r2, Ui.lw())
        card.addView(Ui.divider(this), Ui.lw())
        card.addView(r3, Ui.lw())

        val fill = Ui.lw(); fill.height = 0; fill.weight = 1f
        card.addView(View(this), fill)

        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        val shot = Ui.button(this, "拍照", 13, false, hPad = 8, vPad = 0, kind = Ui.R_WHITE)
        Ui.click(shot) { snap() }
        val lock = Ui.button(this, "锁定最近一段", 13, false, hPad = 8, vPad = 0, kind = Ui.R_WHITE)
        Ui.click(lock) { lockLast() }
        br.addView(shot, LinearLayout.LayoutParams(0, dp(42), 1f))
        val lkp = LinearLayout.LayoutParams(0, dp(42), 1f)
        lkp.marginStart = dp(8)
        br.addView(lock, lkp)
        card.addView(br, Ui.lw())

        val where = Ui.button(this, "产物存在哪", 12, false, hPad = 8, vPad = 5, kind = Ui.R_WHITE)
        Ui.click(where) {
            val d = cams?.dir()?.absolutePath ?: "还没初始化"
            slog("环视产物目录：$d")
            toast(d)
        }
        val wp = Ui.lw(); wp.topMargin = dp(8); wp.height = dp(38)
        card.addView(where, wp)
        syncDvr()
        return row
    }

    private fun dvrName(id: String) = when (id) {
        CamCtl.ID_FRONT -> "前视 id4"
        CamCtl.ID_RIGHT -> "右视 id5"
        CamCtl.ID_LEFT -> "左视 id6"
        else -> "后视 id7"
    }

    private fun dvrBox(parent: LinearLayout, name: String, id: String, w: Float, ml: Int) {
        val v = CamView(this, name, id == dvrSel)
        dvrBoxes[id] = v
        cams?.attach(v, id)
        Ui.click(v) { selectDvr(id) }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
        lp.marginStart = ml
        parent.addView(v, lp)
    }

    private fun selectDvr(id: String) {
        if (cams?.recording() == true) {
            slog("录像中不换路：先按停止")
            return toast("先停止当前录像再换路")
        }
        dvrSel = id
        for ((k, b) in dvrBoxes) b.selected(k == id)
        syncDvr()
        slog("录像路数切到 " + dvrName(id))
    }

    private fun toggleRec() {
        val c = cams ?: return toast("摄像头还没就绪")
        c.record(if (c.recording()) null else dvrSel) { m ->
            slog(m)
            syncDvr()
            toast(m)
        }
    }

    private fun snap() {
        val c = cams ?: return toast("摄像头还没就绪")
        val box = dvrBoxes[dvrSel] ?: return
        c.snapshot(box) { p ->
            if (p == null) { slog("拍照失败：这一路还没出画"); toast("这路还没画面") }
            else { slog("拍照已存：$p"); toast("已存 " + File(p).name) }
        }
    }

    /** 锁定 = 改名加前缀 + 置只读，让我们自己的循环分段别把它清掉。 */
    private fun lockLast() {
        val c = cams ?: return toast("还没初始化")
        val f = c.dir().listFiles()
                ?.filter { it.isFile && it.name.startsWith("录像_") }
                ?.maxByOrNull { it.lastModified() }
        if (f == null) { slog("没有可锁定的录像文件"); return toast("还没有录像文件") }
        val to = File(f.parentFile, "锁定_" + f.name)
        if (!f.renameTo(to)) return toast("改名失败，文件可能被占用")
        try { to.setWritable(false) } catch (t: Throwable) { }
        slog("已锁定：" + to.name + "（" + to.length() / 1024 + "KB）")
        toast("已锁定 " + to.name)
    }

    private fun syncDvr() {
        if (!::recChip.isInitialized) return
        val c = cams
        val on = c?.recording() == true
        recChip.text = if (on) "● 录像中" else "● 未录像"
        recChip.background = Ui.paint(this, if (on) Ui.R_REC else Ui.R_WHITE, 12)
        recChip.setTextColor(if (on) Color.WHITE else Ui.INK_SUB)
        recPause.text = if (on) "停止" else "开始"
        dvrInfo.text = dvrName(dvrSel) + " · " + (c?.sizeOf(dvrSel) ?: "未取流")
        for ((i, b) in loopBtns.withIndex())
            Ui.restyle(b, this, b.text.toString(), i == loopSel, Ui.R_ONOFF)
    }

    // ---------- 公共小件 ----------

    private fun gap(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36))
        lp.marginEnd = Ui.dp(this, 8)
        return lp
    }

    private fun sBtn(name: String, body: () -> Unit): TextView {
        val b = Ui.button(this, name, 13, false)
        Ui.click(b) { body() }
        return b
    }

    /** 显示区试验按钮：总线是同步 binder，一律借用车控模块的 worker 线程下发。 */
    private fun areaBtn(name: String, body: (Vd) -> Boolean): TextView =
            sBtn(name) { car?.areaAction(name, body) ?: slog("车控模块还没起来") }

    private fun flowRow(vararg vs: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        for (v in vs) r.addView(v, gap())
        return r
    }

    /** 投屏页按钮排：固定一排三个等宽、靠左，不足三个补空位保证宽度一致。 */
    private fun btnRow(vararg vs: TextView): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val dp8 = Ui.dp(this, 8)
        for (i in 0 until 3) {
            val cell: View = if (i < vs.size) vs[i] else View(this)
            val p = LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1f)
            if (i > 0) p.marginStart = dp8
            r.addView(cell, p)
        }
        return r
    }

    private fun sBtnRestart(): TextView {
        val b = Ui.button(this, "重启服务", 13, false, hPad = 4, vPad = 7, kind = Ui.R_DANGER)
        b.setTextColor(Ui.DANGER)
        Ui.click(b) { restartService() }
        return b
    }

    private fun withMargin(lp: LinearLayout.LayoutParams, px: Int): LinearLayout.LayoutParams {
        lp.topMargin = px
        return lp
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPicker::class.java))
    }

    private fun pickTheme(t: Int) {
        cfg.setCastTheme(t)
        syncToggles()
    }

    private fun syncToggles() {
        Ui.restyle(btnNavi, this, "导航模式", cfg.castTheme() == Vd.THEME_NAVI, Ui.R_ONOFF)
        Ui.restyle(btnSimple, this, "极简模式", cfg.castTheme() == Vd.THEME_SIMPLE, Ui.R_ONOFF)
        Ui.restyle(btnFollow, this, "跟随前台", cfg.followTop(), Ui.R_ONOFF)
        Ui.restyle(btnCard, this, "音乐卡片", cfg.cardMirror(), Ui.R_ONOFF)
        Ui.restyle(btnHideAdmin, this, "屏蔽弹窗", cfg.hideAdmin(), Ui.R_ONOFF)
        Ui.restyle(btnImmersive, this, "dock 沉浸", cfg.immersive(), Ui.R_ONOFF)
    }

    // ---------- 状态刷新：全部真实检测，不写假状态 ----------

    private fun refresh() {
        val svc = CastService.inst()
        val vd = Vd.inst()
        busChip.text = if (svc == null) "服务未运行"
            else if (vd?.ok() == true) "总线已连 C" + (if (vd.lanOk) "√" else "×") +
                    " B" + (if (vd.cabinOk) "√" else "×")
            else "总线未连"
        busChip.setTextColor(if (svc != null && vd?.ok() == true)
            Ui.GREEN else if (svc == null) Ui.DANGER else Ui.INK_SUB)
        carChip.text = if (Caster.displayAlive(this, Caster.CLUSTER)) "仪表屏已亮" else "仪表屏未亮"
        modChip?.text = "当前模块： " + Presets.ITEMS[cur].name
        syncToggles()
        readGear()
        tick++
        if (cur == 2 && tick % 3 == 0) readVehicle()
        if (cur == 3 && tick % 4 == 0) readMirror()
        if (cur == 4) syncDvr()
    }

    /** 挡位是唯一的"行车中"依据（327684/26：1=P 2=R 3=N 4=D），只在变化的那一拍写日志。 */
    private fun readGear() {
        val c = car ?: return
        c.readGear { g ->
            if (g == gear) return@readGear
            val prev = gear
            gear = g
            gearChip.text = if (g == 0) "挡位读不到" else "挡位 " + CarCtl.gearName(g)
            gearChip.setTextColor(if (g == 0) Ui.DANGER else Ui.GREEN)
            if (cur == 3) syncReverse()
            slog("挡位 ${CarCtl.gearName(prev)} → ${CarCtl.gearName(g)}（总线回读）")
            if (g == 2 && prev != 2 && cfg.autoR() && cur != 3) {
                showPage(3)
                slog("挂 R：自动切到倒车画面")
            }
        }
    }

    /** 真重启：先停掉旧服务再拉起，日志里能看到重启痕迹。 */
    private fun restartService() {
        try { stopService(Intent(this, CastService::class.java)) } catch (t: Throwable) { }
        handler.postDelayed({
            CastService.start(this)
            handler.postDelayed(attach, 800)
        }, 400)
        slog("手动重启服务")
        toast("服务已重启")
    }

    /** 打开页面自动检查缺什么权限，缺哪个弹哪个，一次一个。 */
    private fun autoPermCheck() {
        if (askedPerm) return
        if (!TopApp.granted(this)) {
            askedPerm = true
            slog("检测到缺少使用情况权限，自动跳转授权")
            if (!TopApp.request(this)) toast(USAGE_ADB_HINT)
            return
        }
        if (!MusicListener.ready()) {
            askedPerm = true
            slog("检测到缺少通知使用权，自动跳转授权")
            if (!openNotifSettings()) toast(NOTIF_ADB_HINT)
            return
        }
        // 环视取流要 CAMERA。这台车弹窗授权就能给（camprobe 实测），不需要 adb。
        if (!askedCam && cams?.granted() == false) {
            askedCam = true
            slog("缺 CAMERA 权限，弹出授权窗")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    private fun openNotifSettings(): Boolean = try {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        true
    } catch (t: Throwable) { false }

    private fun withSvc(body: (CastService) -> Unit) {
        val svc = CastService.inst()
        if (svc == null) { toast("后台服务还在启动，稍等一下"); return }
        body(svc)
    }

    private fun toast(s: String) =
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        /** 车窗三挡：按钮顺序与总线枚举值一一对应（总线没有百分比档）。 */
        private val WIN_VALUES = intArrayOf(CarCtl.WIN_VENT, CarCtl.WIN_OPEN, CarCtl.WIN_CLOSE)
        private val WIN_LABELS = arrayOf("透气", "开", "关")

        private const val REQ_CAM = 41

        /** 这台车机没有使用情况授权页面，只能在电脑上用 adb 授权。 */
        const val USAGE_ADB_HINT =
                "这台车机没有使用情况授权页面。请在电脑上执行：" +
                        "adb shell appops set com.ahui.clustercast GET_USAGE_STATS allow"

        const val NOTIF_ADB_HINT =
                "这台车机没有通知使用权设置页。请在电脑上执行：" +
                        "adb shell cmd notification allow_listener com.ahui.clustercast/.MusicListener"
    }
}
