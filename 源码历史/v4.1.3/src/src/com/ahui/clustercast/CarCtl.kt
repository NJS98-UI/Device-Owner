package com.ahui.clustercast

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 车身控制：车窗 / 尾门 / 后视镜 / 档位。
 * 协议来自实机抓取（见 协议说明-车窗尾门后视镜.md），取值是枚举不是百分比。
 *
 * 总线 set 是 fire-and-forget，返回不代表 MCU 接受，所以每个动作都下发后回读比对；
 * 回读不到期望值就报"未确认"，绝不把"反射没抛异常"当成成功。
 * 总线调用是同步 binder，全部丢到 worker 线程，回调统一回主线程。
 */
class CarCtl(c: Context, private val ui: Handler, private val log: (String) -> Unit) {

    private val ctx = c.applicationContext
    private val bg = HandlerThread("carctl").apply { start() }
    private val worker = Handler(bg.looper)

    fun close() {
        try { bg.quitSafely() } catch (t: Throwable) { }
    }

    // ---------- 读取 ----------

    /** 档位：1=P 2=R 3=N 4=D，读不到 0。 */
    fun readGear(cb: (Int) -> Unit) = readOne(EV_STATE, CMD_GEAR) { cb(it ?: 0) }

    /** 尾门状态原始值（0/1=关闭侧，≥2=开启侧），读不到 -1。 */
    fun readTailgate(cb: (Int) -> Unit) = readOne(EV_BODY, CMD_TAILGATE) { cb(it ?: -1) }

    /** 倒车后视镜自动下翻的当前配置值，读不到 -1。 */
    fun readMirrorFlip(cb: (Int) -> Unit) = readOne(EV_BODY, CMD_MIRROR_FLIP) { cb(it ?: -1) }

    private fun readOne(ev: Int, cmd: Int, cb: (Int?) -> Unit) {
        worker.post {
            val vd = Vd.connect(ctx)
            val v = if (vd.ok()) vd.get(ev, cmd)?.firstOrNull() else null
            ui.post { cb(v) }
        }
    }

    /** 四扇窗当前状态，元素顺序同 [WIN_CMD]，读不到的位置为 -1。 */
    fun readWindows(cb: (IntArray) -> Unit) {
        worker.post {
            val vd = Vd.connect(ctx)
            val out = IntArray(WIN_CMD.size) { -1 }
            if (vd.ok()) for (i in WIN_CMD.indices)
                out[i] = vd.get(EV_BODY, WIN_CMD[i])?.firstOrNull() ?: -1
            ui.post { cb(out) }
        }
    }

    // ---------- 动作 ----------

    /**
     * 仪表显示区一类的 CAR_LAN 动作：总线不回了，所以只报"已发出"，绝不报"已生效"。
     * 没绑上 CAR_LAN 时事件根本不会路由过去，这点必须写在日志里。
     */
    fun areaAction(label: String, body: (Vd) -> Boolean) {
        worker.post {
            val vd = Vd.connect(ctx)
            if (!vd.ok()) {
                ui.post { log("$label 失败：总线没连上（${vd.lastError ?: "未知原因"}）") }
                return@post
            }
            vd.qnxTrace.setLength(0)
            val ok = try { body(vd) } catch (t: Throwable) { false }
            ui.post {
                val ch = "CAR_LAN " + (if (vd.lanOk) "已绑" else "未绑") +
                        " / CABIN_LAN " + (if (vd.cabinOk) "已绑" else "未绑")
                if (ok) log("$label 已发出（$ch）\n${vd.qnxTrace}")
                else log("$label 失败：${vd.lastError ?: "反射没通过"}（$ch）")
            }
        }
    }

    /** 单个车窗：i 对应 [WIN_CMD]（162主驾 163副驾 164左后 165右后），value 见 WIN_*。 */
    fun setWindow(i: Int, value: Int, cb: (Boolean, String) -> Unit) =
            act(WIN_NAME[i] + "车窗→" + winName(value), EV_BODY, WIN_CMD[i], intArrayOf(value),
                    WIN_TIMEOUT, WIN_INTERIM, { r -> winVerdict(r, value) }, cb)

    /**
     * 四扇窗一起动。原车语音走的就是"四条各发一次 162~165"（已实测），这里照做；
     * 不用 cmdId 175 —— 它 int[4] 的各位排布没验证过。
     */
    fun setAllWindows(value: Int, cb: (Boolean, String) -> Unit) {
        var pending = WIN_CMD.size
        var failed = 0
        val why = StringBuilder()
        for (i in WIN_CMD.indices) setWindow(i, value) { ok, m ->
            if (!ok) { failed++; why.append(m).append('；') }
            if (--pending == 0) {
                val name = "四窗→" + winName(value)
                if (failed == 0) cb(true, "$name 已生效") else cb(false, "$name：$failed 扇未确认")
            }
        }
    }

    /** 尾门：open=true 发 2、false 发 1（单条即等效原车长按）。 */
    fun setTailgate(open: Boolean, cb: (Boolean, String) -> Unit) =
            act("尾门→" + (if (open) "开" else "关"), EV_BODY, CMD_TAILGATE,
                    intArrayOf(if (open) 2 else 1), TAIL_TIMEOUT, "尾门动作中", { r ->
                val v = r?.firstOrNull() ?: return@act "没有回读"
                if ((v >= 2) == open) null else "回读=$v"
            }, cb)

    /** 倒车后视镜自动下翻开关。1=关 2=开 是本车总线开关类的通用约定，最终以回读为准。 */
    fun setMirrorFlip(on: Boolean, cb: (Boolean, String) -> Unit) =
            act("倒车自动下翻→" + (if (on) "开" else "关"), EV_BODY, CMD_MIRROR_FLIP,
                    intArrayOf(if (on) 2 else 1), SETTING_TIMEOUT, null, { r ->
                val v = r?.firstOrNull() ?: return@act "没有回读"
                if (v == (if (on) 2 else 1)) null else "回读=$v"
            }, cb)

    /** 保存当前座椅+后视镜位置到记忆位（cmdId 201，值=档位号）。 */
    fun saveMirrorPos(pos: Int, cb: (Boolean, String) -> Unit) =
            act("保存座椅/后视镜位置→记忆位$pos", EV_BODY, CMD_SAVE_POSI,
                    intArrayOf(pos), SETTING_TIMEOUT, null, { r ->
                val v = r?.firstOrNull() ?: return@act "没有回读"
                if (v == pos) null else "回读=$v"
            }, cb)

    // ---------- 下发 + 回读 ----------

    /**
     * verdict 返回 null 表示回读已符合期望，返回文字表示还差在哪。
     * 超时前一直停在 interim 文案（电机还在动）时按"已下发"处理，不报失败。
     */
    private fun act(label: String, ev: Int, cmd: Int, v: IntArray, timeoutMs: Long,
                    interim: String?, verdict: (IntArray?) -> String?,
                    cb: (Boolean, String) -> Unit) {
        worker.post {
            val vd = Vd.connect(ctx)
            if (!vd.ok()) {
                finish(label + " 失败：总线未连（${vd.lastError ?: "未知原因"}）", false, cb)
                return@post
            }
            if (vd.send(ev, cmd, v) == 0) {
                finish("$label 失败：两条通道下发都没成功", false, cb)
                return@post
            }
            val end = SystemClock.elapsedRealtime() + timeoutMs
            var last = "没有回读"
            while (SystemClock.elapsedRealtime() < end) {
                SystemClock.sleep(400)
                val r = vd.get(ev, cmd)
                val s = verdict(r)
                if (s == null) {
                    finish("$label 已生效（回读=${fmt(r)}）", true, cb)
                    return@post
                }
                last = s
            }
            val moving = interim != null && last == interim
            finish(if (moving) "$label 已下发，$interim" else "$label 未确认（$last）", moving, cb)
        }
    }

    /** 日志和回调都回主线程：界面只认这一条路。 */
    private fun finish(m: String, ok: Boolean, cb: (Boolean, String) -> Unit) {
        ui.post { log(m); cb(ok, m) }
    }

    private fun winVerdict(r: IntArray?, want: Int): String? {
        val v = r?.firstOrNull() ?: return "没有回读"
        if (v == want) return null
        // 0 空闲 / 4 运行中：到位前的瞬时态，不是失败。
        if (v == 0 || v == 4) return WIN_INTERIM
        return "回读=$v"
    }

    private fun fmt(v: IntArray?): String = v?.joinToString() ?: "null"

    companion object {
        const val EV_BODY = 327681      // 0x50001 车身 / 座椅 / 后视镜 / 仪表
        const val EV_STATE = 327684     // 0x50004 档位等车辆状态（只读）
        const val CMD_GEAR = 26         // 1=P 2=R 3=N 4=D（177 与它逐挡同步）

        /** 下标顺序即界面四扇窗的顺序。 */
        val WIN_CMD = intArrayOf(162, 163, 164, 165)
        val WIN_NAME = arrayOf("主驾", "副驾", "左后", "右后")

        const val WIN_CLOSE = 1
        const val WIN_OPEN = 2
        const val WIN_VENT = 3

        const val CMD_TAILGATE = 92
        const val CMD_MIRROR_FLIP = 51
        const val CMD_SAVE_POSI = 201

        private const val WIN_TIMEOUT = 6000L
        private const val TAIL_TIMEOUT = 12000L
        private const val SETTING_TIMEOUT = 2500L
        private const val WIN_INTERIM = "车窗运行中"

        fun winName(v: Int): String = when (v) {
            WIN_CLOSE -> "关"; WIN_OPEN -> "开"; WIN_VENT -> "透气"
            0 -> "空闲"; 4 -> "运行中"; else -> if (v < 0) "未知" else "值$v"
        }

        fun gearName(g: Int): String = when (g) {
            1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; else -> "?"
        }

        /** 开窗/开尾门的行车拦截：原车应用层没这段，是否在 MCU 未实测，先在 UI 侧拦一层。 */
        fun blocksOpen(gear: Int) = gear != 1
    }
}
