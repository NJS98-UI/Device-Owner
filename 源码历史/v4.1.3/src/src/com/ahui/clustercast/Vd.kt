package com.ahui.clustercast

import android.content.Context
import android.os.Bundle
import android.util.Log
import dalvik.system.DexClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.HashMap

/**
 * Desay 私有 VDBus 总线的反射封装（协议是实机 dexdump 抓出来的）。
 *   仪表主题：service=CAR_INFO, eventId=327681, Bundle{"CMD_ID":232,"VALUE":int[]}
 *   桌面音乐卡片：service=MEDIA, eventId=393218, Bundle{"info":VDMediaInfo}
 * vdbus.jar 不在应用 classpath 上，运行时用 DexClassLoader 载入，全程反射；
 * 任何一步失败只记日志，绝不让主流程崩。
 */
class Vd private constructor() {

    private var cl: ClassLoader? = null
    private var bus: Any? = null
    private var evCls: Class<*>? = null
    private var infoCls: Class<*>? = null
    private var evCtor: Constructor<*>? = null
    private var mGetOnce: Method? = null
    private var mSet: Method? = null
    private var mGetPayload: Method? = null
    private var mCreateEvent: Method? = null
    private var carInfoType: Any? = null
    private var mediaServiceType: Any? = null
    private var carLanType: Any? = null
    private var cabinLanType: Any? = null

    /** CAR_LAN 有没有绑上：投屏铺满整屏的那条通道全靠它。 */
    var lanOk = false
        private set

    /** CABIN_LAN 是 system service（ServiceManager 里的 CabinLanService），裸 JSON 隧道走它。 */
    var cabinOk = false
        private set

    /** 第二条写入路径：SystemUI 用的 CarInfoProxy。 */
    private var proxy: Any? = null
    private var mProxySend: Method? = null
    private var mProxyGet: Method? = null

    /** 上一次 setTheme 实际走了哪条路：1=VDBus 2=Proxy，用于日志定位。 */
    var lastPath = 0

    /** 上一次连接/推送失败的真实原因，界面日志要用，绝不吞异常。 */
    @Volatile var lastError: String? = null

    fun ok(): Boolean = bus != null

    private fun open(c: Context) {
        try {
            val loader = resolveLoader(c)
            cl = loader
            val busCls = loader.loadClass("com.desaysv.ivi.vdb.client.VDBus")
            evCls = loader.loadClass("com.desaysv.ivi.vdb.event.VDEvent")
            infoCls = loader.loadClass("com.desaysv.ivi.vdb.event.id.media.bean.VDMediaInfo")
            val stCls = loader.loadClass("com.desaysv.ivi.vdb.client.bind.VDServiceDef\$ServiceType")

            carInfoType = enumByName(stCls, "CAR_INFO")
            mediaServiceType = enumByName(stCls, "MEDIA")
            carLanType = enumByName(stCls, "CAR_LAN")
            cabinLanType = enumByName(stCls, "CABIN_LAN")

            val b = busCls.getMethod("getDefault").invoke(null)
            busCls.getMethod("init", Context::class.java).invoke(b, c)
            bus = b

            mGetOnce = busCls.getMethod("getOnce", evCls)
            mSet = busCls.getMethod("set", evCls)
            mGetPayload = evCls!!.getMethod("getPayload")
            evCtor = evCls!!.getConstructor(Int::class.javaPrimitiveType, Bundle::class.java)
            mCreateEvent = infoCls!!.getMethod("createEvent",
                    Int::class.javaPrimitiveType, infoCls)

            val bind = busCls.getMethod("bindService", stCls)
            val a = bind.invoke(b, carInfoType) as? Boolean ?: false
            val m = bind.invoke(b, mediaServiceType) as? Boolean ?: false
            lanOk = try {
                bind.invoke(b, carLanType) as? Boolean ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "bind CAR_LAN failed: $t")
                false
            }
            cabinOk = try {
                bind.invoke(b, cabinLanType) as? Boolean ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "bind CABIN_LAN failed: $t")
                false
            }
            Log.i(TAG, "connect carInfo=$a media=$m carLan=$lanOk cabinLan=$cabinOk")
            openProxy(c)
        } catch (t: Throwable) {
            bus = null
            lastError = t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, "connect failed: $t")
        }
    }

    /**
     * 载入 SystemUI 那条路径上的 CarInfoProxy。它和 VDBus 必须在同一个 ClassLoader 里
     * （proxy 内部是静态 VDBus.getDefault().set()），所以两个 jar 一起丢给一个 DexClassLoader。
     * 失败不影响主流程，只是少一条写入通道。
     */
    private fun openProxy(c: Context) {
        try {
            val pCls = cl!!.loadClass(PROXY_CLS)
            val p = pCls.getMethod("getInstance").invoke(null)
            pCls.getMethod("init", Context::class.java).invoke(p, c)
            mProxySend = pCls.getMethod("sendItemValues",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java)
            mProxyGet = pCls.getMethod("getItemValues",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            proxy = p
            Log.i(TAG, "proxy ready connected=" +
                    pCls.getMethod("isServiceConnnected").invoke(p))
        } catch (t: Throwable) {
            proxy = null
            mProxySend = null
            mProxyGet = null
            Log.w(TAG, "proxy unavailable: $t")
        }
    }

    private fun resolveLoader(c: Context): ClassLoader {
        try {
            Class.forName("com.desaysv.ivi.vdb.client.VDBus")
            Class.forName(PROXY_CLS)
            return Vd::class.java.classLoader!!
        } catch (ignored: ClassNotFoundException) { }
        return DexClassLoader(JAR + ":" + EXTRA_JAR,
                c.codeCacheDir.absolutePath, null, Vd::class.java.classLoader)
    }

    // ---------- 通用读写（车窗 / 尾门 / 后视镜 / 档位都走这条） ----------

    /**
     * 读一个 cmdId 的当前值。优先走 CarInfoProxy（和原车应用同一条连接），退回裸总线。
     * 返回 null 表示没读到（没绑上、payload 里没有 int[VALUE]、或反射异常）。必须在子线程调用。
     */
    fun get(eventId: Int, cmdId: Int): IntArray? {
        val p = proxy
        if (p != null) {
            val v = try {
                @Suppress("UNCHECKED_CAST")
                (mProxyGet!!.invoke(p, eventId, cmdId) as? IntArray) ?: IntArray(0)
            } catch (t: Throwable) {
                Log.w(TAG, "get $eventId/$cmdId via proxy failed: $t")
                IntArray(0)
            }
            if (v.isNotEmpty()) return v
        }
        val b = bus ?: return null
        return try {
            val bundle = Bundle()
            bundle.putInt(KEY_CMD, cmdId)
            val r = mGetOnce!!.invoke(b, evCtor!!.newInstance(eventId, bundle)) ?: return null
            readPayload(r)?.getIntArray(KEY_VALUE)
        } catch (t: Throwable) {
            Log.w(TAG, "get $eventId/$cmdId failed: $t")
            null
        }
    }

    /**
     * 下发一个值。总线这层是 fire-and-forget，**返回的位掩码只代表哪条反射通道没抛异常**
     * （1=裸 VDBus，2=CarInfoProxy，0=两条都失败），MCU 是否真执行只能靠 get() 回读判定。
     */
    fun send(eventId: Int, cmdId: Int, value: IntArray): Int {
        var paths = 0
        bus?.let { b ->
            try {
                val bundle = Bundle()
                bundle.putInt(KEY_CMD, cmdId)
                bundle.putIntArray(KEY_VALUE, value)
                mSet!!.invoke(b, evCtor!!.newInstance(eventId, bundle))
                paths = paths or 1
            } catch (t: Throwable) {
                Log.w(TAG, "send $eventId/$cmdId via VDBus failed: $t")
            }
        }
        proxy?.let { p ->
            try {
                mProxySend!!.invoke(p, eventId, cmdId, value)
                paths = paths or 2
            } catch (t: Throwable) {
                Log.w(TAG, "send $eventId/$cmdId via CarInfoProxy failed: $t")
            }
        }
        return paths
    }

    // ---------- 仪表显示区域（CabinLAN 裸隧道，投屏铺满整屏就靠这条） ----------

    /**
     * 申请/释放原车留给"导航画面"的那块仪表区域。原车高德开机首启发的是
     * DisplayCluster=false + RequestDisplayNaviArea=true（= 别占仪表页，把显示区让给我）。
     * 面板上那圈黑边框就是这块区域的边界：QNX 只把我们的整屏流缩进这个矩形里。
     *
     * 必须走 CabinLAN 裸隧道。实机抓过：同一条内容用 CAR_LAN 事件 721699 发出去，
     * vdev.service.cabinlan 那边一条 0x1004/0x2 都没有，等于没发。
     * JSON 的键序和字面量跟原车 AmapAutoConstants 逐字一致，多一个空格都可能被拒。
     */
    fun clusterShow(displayCluster: Boolean, frontDesk: Boolean, perspective: Int,
                    requestNaviArea: Boolean): Boolean =
            qnxRaw(QNX_MSG_CLUSTER, QNX_SUB_CLUSTER_SHOW,
                    "{\"DisplayCluster\":\"" + yn(displayCluster) +
                            "\",\"NaviFrontDeskStatus\":\"" + yn(frontDesk) +
                            "\",\"Perspective\":" + perspective +
                            ",\"PerspectiveResult\":\"false\"" +
                            ",\"RequestDisplayNaviArea\":\"" + yn(requestNaviArea) + "\"}")

    /** 仪表导航布局档：0=关 1=中间优先 2=左侧优先 3=第二主题 4=第三主题，区域大小跟着变。 */
    fun setNaviDisplayArea(area: Int): Boolean =
            qnxRaw(QNX_MSG_CLUSTER, QNX_SUB_CLUSTER_AREA,
                    "{\"NaviDisplayArea\":" + area + ",\"NaviDisplayAreaResult\":\"true\"}")

    /** 改布局档并立刻重闩：QNX 只在加载那一串里重新取一次区域。 */
    fun areaRelatch(area: Int): Boolean {
        loading(true)
        val ok = setNaviDisplayArea(area)
        loading(false)
        return ok
    }

    /**
     * QNX 侧这块区域是"闩锁"的：原车高德只在开机那一次发 加载中1 → 内容 → 加载中0，
     * 之后 psmap 死了 QNX 也一直把 display 2 透出来，从不重读。
     * 所以改过布局/申请过区域之后，必须再走一遍这个三连 QNX 才会重新取一次画面。
     */
    fun relatch(displayCluster: Boolean, frontDesk: Boolean, perspective: Int,
                requestNaviArea: Boolean): Boolean {
        loading(true)
        val ok = clusterShow(displayCluster, frontDesk, perspective, requestNaviArea)
        loading(false)
        return ok
    }

    /** 仪表"地图信息准备中"那个占位页的开/关。 */
    fun loading(on: Boolean): Boolean =
            qnxRaw(QNX_MSG_CLUSTER, QNX_SUB_CLUSTER_LOADING,
                    "{\"NaviDisplayLoading\":" + (if (on) 1 else 0) + "}")

    /**
     * CabinLAN 裸 JSON 隧道：msgType/subtype 原样透传给 QNX。
     * 实机验证过 display 2 能透出的只有这一条通道，全项目都走它。
     */
    fun qnxRaw(msgType: Int, subtype: Int, json: String): Boolean {
        if (!cabinOk) {
            lastError = "CABIN_LAN 未绑定"
            return false
        }
        val ok = sendBean(CLS_CL_COMMON, EV_CABIN_COMMON) { cls, bean ->
            setInt(cls, bean, "setMsgType", msgType)
            setInt(cls, bean, "setSubtype", subtype)
            setStr(cls, bean, "setMessage", json)
        }
        if (ok) {
            lastQnx = "0x" + Integer.toHexString(msgType) + "/" + subtype + " " + json
            qnxTrace.append(lastQnx).append("\n")
        }
        return ok
    }

    /** 界面日志要用：报出去的那一条原文，好跟 vdev.service.cabinlan 的实机日志逐字对。 */
    @Volatile var lastQnx: String? = null

    /** 一次动作可能连发好几条（重闩就是三条），按顺序攒着给界面打印。调用方自己清空。 */
    val qnxTrace = StringBuilder()

    private fun yn(b: Boolean) = if (b) "true" else "false"

    /** bean 类只存在于运行时 jar 里，全程反射；返回 true 只代表发出去了，QNX 不回话。 */
    private fun sendBean(beanCls: String, eventId: Int, fill: (Class<*>, Any) -> Unit): Boolean {
        val b = bus ?: return false
        return try {
            val cls = cl!!.loadClass(beanCls)
            val bean = cls.newInstance()
            fill(cls, bean)
            val ev = cls.getMethod("createEvent",
                    Int::class.javaPrimitiveType, cls).invoke(null, eventId, bean)
            mSet!!.invoke(b, ev)
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, "send $beanCls($eventId) failed: $t")
            false
        }
    }

    private fun setStr(cls: Class<*>, bean: Any, m: String, v: String) {
        cls.getMethod(m, String::class.java).invoke(bean, v)
    }

    private fun setInt(cls: Class<*>, bean: Any, m: String, v: Int) {
        cls.getMethod(m, Int::class.javaPrimitiveType).invoke(bean, v)
    }

    // ---------- 仪表主题 ----------

    /** 读当前仪表主题；拿不到返回 -1。 */
    fun getTheme(): Int = get(EV_CAR_SETTING, CMD_CLUSTER_THEME)?.firstOrNull() ?: -1

    /**
     * 写仪表模式（见 THEME_*）。两条通道都发一遍，生效与否由调用方回读判定。
     */
    fun setTheme(theme: Int) {
        lastPath = send(EV_CAR_SETTING, CMD_CLUSTER_THEME, intArrayOf(theme))
    }

    /** 读仪表模式，优先走 proxy（和 SystemUI 一致），退回 VDBus 由调用方做。 */
    fun getThemeViaProxy(): Int {
        val p = proxy ?: return -1
        return try {
            @Suppress("UNCHECKED_CAST")
            val v = mProxyGet!!.invoke(p, EV_CAR_SETTING, CMD_CLUSTER_THEME) as? IntArray
            if (v == null || v.isEmpty()) -1 else v[0]
        } catch (t: Throwable) {
            Log.w(TAG, "getThemeViaProxy failed: $t")
            -1
        }
    }

    private fun readPayload(event: Any): Bundle? {
        val b = mGetPayload!!.invoke(event) as? Bundle
        if (b != null) b.classLoader = cl
        return b
    }

    // ---------- 原车桌面音乐卡片 ----------

    /** 把第三方音乐的播放信息推给原车总线，桌面那张音乐卡片就会显示它。 */
    fun publishMedia(title: String?, artist: String?, album: String?,
                     extras: HashMap<String, String>?): Boolean {
        val b = bus ?: return false
        return try {
            val info = infoCls!!.newInstance()
            setField(infoCls!!, info, "title", title)
            setField(infoCls!!, info, "artist", artist)
            setField(infoCls!!, info, "album", album)
            setField(infoCls!!, info, "mediaType", MEDIA_TYPE_ONLINE)
            setField(infoCls!!, info, "infoType", INFO_TYPE_ID3)
            if (extras != null) infoCls!!.getField("extras").set(info, extras)
            val ev = mCreateEvent!!.invoke(null, EV_MEDIA_SOURCE_INFO, info)
            mSet!!.invoke(b, ev)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "publishMedia failed: $t")
            false
        }
    }

    companion object {
        private const val TAG = "ClusterCast.Vd"
        private const val JAR = "/system/framework/vdbus.jar"
        private const val EXTRA_JAR = "/system/framework/vdbus_extra.jar"
        private const val PROXY_CLS =
                "com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy"

        const val EV_CAR_SETTING = 327681
        const val CMD_CLUSTER_THEME = 232

        /**
         * CabinLAN 裸隧道。实机抓自原车高德启动那 4 秒，也是目前唯一验证过能把
         * display 2 透到仪表面板上的通道；CAR_LAN 那三条 bean 发出去 QNX 根本不收。
         */
        const val EV_CABIN_COMMON = 1114113
        const val QNX_MSG_CLUSTER = 0x1004
        const val QNX_SUB_CLUSTER_SHOW = 0x2       // 占/让仪表页
        const val QNX_SUB_CLUSTER_AREA = 0x5       // 导航布局档 0~4
        const val QNX_SUB_CLUSTER_LOADING = 0x9    // "地图信息准备中"占位页开关
        private const val CLS_CL_COMMON =
                "com.desaysv.ivi.vdb.event.id.cabin.bean.VDCLCommonMessage"

        /** 仪表模式四档，取自原车 SystemUI「仪表模式」弹窗，协议值 = 选项下标 + 1。 */
        const val THEME_DIGITAL = 1
        const val THEME_CLASSIC = 2
        const val THEME_NAVI = 3
        const val THEME_SIMPLE = 4

        const val EV_MEDIA_SOURCE_INFO = 393218
        private const val MEDIA_TYPE_ONLINE = 14
        private const val INFO_TYPE_ID3 = 1

        private const val KEY_CMD = "CMD_ID"
        private const val KEY_VALUE = "VALUE"

        @Volatile
        private var sInst: Vd? = null

        fun inst(): Vd? = sInst

        fun themeName(t: Int): String = when (t) {
            THEME_DIGITAL -> "数字模式"
            THEME_CLASSIC -> "经典模式"
            THEME_NAVI -> "导航模式"
            THEME_SIMPLE -> "极简模式"
            else -> if (t < 0) "未知" else "编号$t"
        }

        /** 幂等；在子线程调用（bindService 与 getOnce 都是同步 binder）。 */
        @Synchronized
        fun connect(c: Context): Vd {
            var v = sInst
            if (v == null) { v = Vd(); sInst = v }
            if (v.bus == null) v.open(c.applicationContext)
            return v
        }

        private fun enumByName(enumCls: Class<*>, name: String): Any? =
                (enumCls.enumConstants ?: emptyArray()).firstOrNull {
                    (it as Enum<*>).name == name
                }

        private fun setField(cls: Class<*>, target: Any, field: String, value: Any?) {
            if (value == null) return
            try { cls.getField(field).set(target, value) } catch (ignored: Throwable) { }
        }
    }
}
