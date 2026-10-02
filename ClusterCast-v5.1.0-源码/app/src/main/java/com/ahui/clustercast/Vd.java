package com.ahui.clustercast;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.function.Consumer;

import dalvik.system.DexClassLoader;

/**
 * Desay 私有 VDBus 总线的反射封装（协议是实机 dexdump 抓出来的）。
 *   仪表主题：service=CAR_INFO, eventId=327681, Bundle{"CMD_ID":232,"VALUE":int[]}
 *   桌面音乐卡片：service=MEDIA, eventId=393218, Bundle{"info":VDMediaInfo}
 *   仪表导航投屏：service=CAR_LAN, eventId=721699, Bundle{"data":VDNaviDisplayCluster}
 *   仪表导航区域：service=CAR_LAN, eventId=721702, Bundle{"data":VDNaviDisplayArea}
 * vdbus.jar 不在应用 classpath 上，运行时用 DexClassLoader 载入，全程反射；
 * 任何一步失败都返回真实异常描述写进界面日志，绝不假装成功。
 */
public class Vd {

    private ClassLoader cl = null;
    private Object bus = null;
    private Class<?> evCls = null;
    private Class<?> infoCls = null;
    private Class<?> playCls = null;
    private Class<?> clusterCls = null;
    private Class<?> areaCls = null;
    private Class<?> roadCls = null;
    private Class<?> digitalCls = null;
    private Constructor<?> evCtor = null;
    private Constructor<?> evCtor1 = null;
    private Method mGetOnce = null;
    private Method mSet = null;
    private Method mGetPayload = null;
    private Method mCreateEvent = null;
    private Method mCreateEventArea = null;
    private Method mCreateEventCluster = null;
    private Method mCreateEventRoad = null;
    private Method mCreateEventDigital = null;
    private Method mCreateEventPlay = null;
    private Object carInfoType = null;
    private Object mediaServiceType = null;
    private Object carLanType = null;
    private Object naviServiceType = null;
    private Class<?> notifyCls = null;

    /** 第二条写入路径：SystemUI 用的 CarInfoProxy。 */
    private Object proxy = null;
    private Method mProxySend = null;
    private Method mProxyGet = null;

    /** 上一次 setTheme 实际走了哪条路：1=VDBus 2=Proxy，用于日志定位。 */
    public int lastPath = 0;

    /** 上一次连接/推送失败的真实原因，界面日志要用，绝不吞异常。 */
    public volatile String lastError = null;

    /** 四个服务各自绑定结果，界面日志原样打印，不美化。 */
    public volatile String bindReport = "未连接";

    public boolean ok() { return bus != null; }

    // ---------- 常量（原 companion object） ----------

    public static final String TAG = "ClusterCast.Vd";
    public static final String JAR = "/system/framework/vdbus.jar";
    public static final String EXTRA_JAR = "/system/framework/vdbus_extra.jar";
    public static final String PROXY_CLS =
            "com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy";

    public static final int EV_CAR_SETTING = 327681;
    public static final int CMD_CLUSTER_THEME = 232;

    // VDEventCarInfo：原车 360 环视 / 记录仪各自的模块事件号（实机 dexdump 逐个核）
    public static final int EV_MODULE_AVM = 327688;        // 0x50008 MODULE_AVM —— 360 环视本体
    public static final int EV_MODULE_DVR = 327700;        // 0x50014 MODULE_DVR —— 记录仪
    public static final int EV_MODULE_RVC = 327691;        // 0x5000b MODULE_RVC —— 倒车影像

    /**
     * AvmID（vdbus_extra.jar 里 com.desaysv.ivi.extra.project.carinfo.AvmID）：
     * 这是原车自己的 360 控制号，和 QueryID.ID_AVM=2 配套。
     * 记录仪 app（svdvrfilemanger）是选装的，很多车没有；
     * 360 环视是本来就有的，所以一律走这条，不去拉那个 app。
     */
    public static final int AVM_TOUCH_EVT = 6;
    public static final int AVM_DISPLAY = 8;               // 显示/唤起 360
    public static final int AVM_RLCR_WARNING = 9;
    public static final int AVM_DVR = 100;                 // AVM 通道的录像
    public static final int AVM_OFF = 31;                  // 关掉 360

    /** CarInfoConstant：AVM 环视的系统/录制状态，和主题 232 同一个类。 */
    public static final int CMD_AVM_DVR_SYSTEM_STS = 13;
    public static final int CMD_AVM_DVR_RECORD_STS = 14;

    /** 仪表模式四档，取自原车 SystemUI「仪表模式」弹窗，协议值 = 选项下标 + 1。 */
    public static final int THEME_DIGITAL = 1;
    public static final int THEME_CLASSIC = 2;
    public static final int THEME_NAVI = 3;
    public static final int THEME_SIMPLE = 4;

    public static final int EV_MEDIA_SOURCE_INFO = 393218;
    public static final int EV_MEDIA_PLAY_STATUS = 393220;
    /** VDEventMedia.MIN（0x60000）/ CURRENT_SOURCE_TYPE（0x60001）：卡片先认源。 */
    public static final int EV_MEDIA_MIN = 393216;
    public static final int EV_MEDIA_CURRENT_SOURCE = 393217;
    /** 第三方自装音乐在总线上按「在线音乐」这一档报，原车卡片才认。 */
    public static final int MEDIA_TYPE_ONLINE = 14;
    private static final int INFO_TYPE_ID3 = 1;
    private static final int PLAY_STATUS_PLAYING = 1;
    private static final int PLAY_STATUS_PAUSED = 2;

    // VDEventCarLan（实机 dexdump 里逐个常量核对过）
    public static final int EV_NAVI_DISPLAY_TO_CLUSTER = 721699;
    public static final int EV_NAVI_ROAD_INFO = 721698;
    public static final int EV_NAVI_DIGITAL_INFO = 721701;
    public static final int EV_NAVI_DISPLAY_AREA = 721702;

    // VDValueCarLan$NaviDisplaySwitch / $NaviDisplayArea
    public static final int NAVI_SWITCH_CLOSE = 1;
    public static final int NAVI_SWITCH_OPEN = 2;
    public static final int NAVI_AREA_CLOSE = 0;
    public static final int NAVI_AREA_SECOND = 3;

    // VDEventVehicleHal（VDEventVehicleHal.java 里的常量，实机抓包最高频的就是 519）
    public static final int EV_PERF_VEHICLE_SPEED = 519;
    public static final int EV_GEAR_SELECTION = 1024;

    /**
     * 抓包订阅的事件：音乐那一串 + 导航投屏那一串 + 仪表模式本身。
     * 都是原车真在发的那几个，从实机日志和 dexdump 常量对出来的。
     */
    public static final int[] SNIFF_IDS = new int[]{
            393217, 393218, 393219, 393220, 393221, 393222, 393223, 393224, 393236, 393237,
            721697, 721698, 721699, 721700, 721701, 721702, 721703, 721704, 721705,
            589825, 589828, 589830, 589834, 589835, 589842, 589847,
            EV_CAR_SETTING, CarCtl.EV_STATE, EV_MODULE_AVM, EV_MODULE_DVR, EV_MODULE_RVC,
            722397};

    /** VDThreadType.CHILD_THREAD=1：回调走子线程，绝不占住主线程。 */
    private static final int THREAD_CHILD = 1;

    private static final String KEY_CMD = "CMD_ID";
    private static final String KEY_VALUE = "VALUE";

    private static volatile Vd sInst = null;

    public static Vd inst() { return sInst; }

    public static String themeName(int t) {
        switch (t) {
            case THEME_DIGITAL: return "数字模式";
            case THEME_CLASSIC: return "经典模式";
            case THEME_NAVI:    return "导航模式";
            case THEME_SIMPLE:  return "极简模式";
            default: return t < 0 ? "未知" : "编号" + t;
        }
    }

    /** 幂等；在子线程调用（bindService 与 getOnce 都是同步 binder）。 */
    public static synchronized Vd connect(Context c) {
        Vd v = sInst;
        if (v == null) { v = new Vd(); sInst = v; }
        if (v.bus == null) v.open(c.getApplicationContext());
        return v;
    }

    private static Object enumByName(Class<?> enumCls, String name) {
        Object[] constants = enumCls.getEnumConstants();
        if (constants == null) return null;
        for (Object o : constants) {
            if (((Enum<?>) o).name().equals(name)) return o;
        }
        return null;
    }

    private static int first(Bundle b) {
        if (b == null) return -1;
        int[] arr = b.getIntArray(KEY_VALUE);
        if (arr == null || arr.length == 0) return -1;
        return arr[0];
    }

    private static boolean asBool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    /** 只走 public setter：字段全是 private，getField 会静默失败。 */
    private static void call(Class<?> cls, Object target, String name,
                             Class<?> pt, Object arg) {
        if (arg == null) return;
        try {
            cls.getMethod(name, pt).invoke(target, arg);
        } catch (Throwable t) {
            Log.w(TAG, name + " rejected: " + t);
        }
    }

    // ---------- 构造 / 连接 ----------

    private Vd() {}

    private void open(Context c) {
        try {
            ClassLoader loader = resolveLoader(c);
            cl = loader;
            Class<?> busCls = loader.loadClass("com.desaysv.ivi.vdb.client.VDBus");
            evCls = loader.loadClass("com.desaysv.ivi.vdb.event.VDEvent");
            infoCls = loader.loadClass("com.desaysv.ivi.vdb.event.id.media.bean.VDMediaInfo");
            playCls = loader.loadClass("com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlay");
            clusterCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDisplayCluster");
            areaCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDisplayArea");
            roadCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviRoadInfo");
            digitalCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDigitalInfo");
            notifyCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.client.listener.VDNotifyListener");
            Class<?> stCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");

            carInfoType = enumByName(stCls, "CAR_INFO");
            mediaServiceType = enumByName(stCls, "MEDIA");
            carLanType = enumByName(stCls, "CAR_LAN");
            naviServiceType = enumByName(stCls, "NAVI");

            Object b = busCls.getMethod("getDefault").invoke(null);
            busCls.getMethod("init", Context.class).invoke(b, c);
            bus = b;

            mGetOnce = busCls.getMethod("getOnce", evCls);
            mSet = busCls.getMethod("set", evCls);
            mGetPayload = evCls.getMethod("getPayload");
            evCtor = evCls.getConstructor(int.class, Bundle.class);
            evCtor1 = evCls.getConstructor(int.class);
            mCreateEvent = infoCls.getMethod("createEvent", int.class, infoCls);
            mCreateEventPlay = playCls.getMethod("createEvent", int.class, playCls);
            mCreateEventCluster = clusterCls.getMethod("createEvent", int.class, clusterCls);
            mCreateEventArea = areaCls.getMethod("createEvent", int.class, areaCls);
            mCreateEventRoad = roadCls.getMethod("createEvent", int.class, roadCls);
            mCreateEventDigital = digitalCls.getMethod("createEvent", int.class, digitalCls);

            Method bind = busCls.getMethod("bindService", stCls);
            boolean a = asBool(bind.invoke(b, carInfoType));
            boolean m = asBool(bind.invoke(b, mediaServiceType));
            boolean l = asBool(bind.invoke(b, carLanType));
            boolean n = asBool(bind.invoke(b, naviServiceType));
            bindReport = "CAR_INFO=" + a + " MEDIA=" + m + " CAR_LAN=" + l + " NAVI=" + n;
            Log.i(TAG, "connect " + bindReport);
            openProxy(c);
        } catch (Throwable t) {
            bus = null;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            bindReport = "失败：" + lastError;
            Log.w(TAG, "connect failed: " + t);
        }
    }

    /**
     * 载入 SystemUI 那条路径上的 CarInfoProxy。它和 VDBus 必须在同一个 ClassLoader 里
     * （proxy 内部是静态 VDBus.getDefault().set()），所以两个 jar 一起丢给一个 DexClassLoader。
     * 失败不影响主流程，只是少一条写入通道。
     */
    private void openProxy(Context c) {
        try {
            Class<?> pCls = cl.loadClass(PROXY_CLS);
            Object p = pCls.getMethod("getInstance").invoke(null);
            pCls.getMethod("init", Context.class).invoke(p, c);
            mProxySend = pCls.getMethod("sendItemValues",
                    int.class, int.class, int[].class);
            mProxyGet = pCls.getMethod("getItemValues",
                    int.class, int.class);
            proxy = p;
            Log.i(TAG, "proxy ready connected=" +
                    pCls.getMethod("isServiceConnnected").invoke(p));
        } catch (Throwable t) {
            proxy = null;
            mProxySend = null;
            mProxyGet = null;
            Log.w(TAG, "proxy unavailable: " + t);
        }
    }

    private ClassLoader resolveLoader(Context c) {
        try {
            Class.forName("com.desaysv.ivi.vdb.client.VDBus");
            Class.forName(PROXY_CLS);
            return Vd.class.getClassLoader();
        } catch (ClassNotFoundException ignored) { }
        return new DexClassLoader(JAR + ":" + EXTRA_JAR,
                c.getCodeCacheDir().getAbsolutePath(), null, Vd.class.getClassLoader());
    }

    // ---------- 仪表主题 ----------

    /** 读当前仪表主题；拿不到返回 -1。 */
    public int getTheme() {
        Object b = bus;
        if (b == null) return -1;
        try {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_CMD, CMD_CLUSTER_THEME);
            Object r = mGetOnce.invoke(b, evCtor.newInstance(EV_CAR_SETTING, bundle));
            if (r == null) return -1;
            return first(readPayload(r));
        } catch (Throwable t) {
            Log.w(TAG, "getTheme failed: " + t);
            return -1;
        }
    }

    /**
     * 读一条 CAR_INFO 命令的当前值（和主题 232 同一个 getOnce 通道）。
     * 拿不到返回 -1，绝不猜个 0 上去——0 往往就是「关/停止」，编错等于骗人。
     */
    private int cmdRead(int eventId, int cmd) {
        Object b = bus;
        if (b == null) return -1;
        try {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_CMD, cmd);
            Object r = mGetOnce.invoke(b, evCtor.newInstance(eventId, bundle));
            if (r == null) return -1;
            return first(readPayload(r));
        } catch (Throwable t) {
            Log.w(TAG, "cmdRead(ev=" + eventId + ",cmd=" + cmd + ") failed: " + t);
            return -1;
        }
    }

    /**
     * 往 CAR_INFO 总线写一条命令，和 setTheme 完全同样的两条路都发一遍：
     * A 自己拼 VDEvent，B 走原车 CarInfoProxy（SystemUI 用的就是它）。
     * 总线是 fire-and-forget，成没成只能靠回读，所以这里只报「发出去没有」。
     */
    private int cmdWrite(int eventId, int cmd, int value) {
        int path = 0;
        int[] v = new int[]{value};
        Object b = bus;
        if (b != null) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(KEY_CMD, cmd);
                bundle.putIntArray(KEY_VALUE, v);
                mSet.invoke(b, evCtor.newInstance(eventId, bundle));
                path = path | 1;
            } catch (Throwable t) { Log.w(TAG, "cmdWrite via VDBus failed: " + t); }
        }
        Object p = proxy;
        if (p != null) {
            try {
                mProxySend.invoke(p, eventId, cmd, v);
                path = path | 2;
            } catch (Throwable t) { Log.w(TAG, "cmdWrite via proxy failed: " + t); }
        }
        return path;
    }

    /**
     * 通用读：任意 eventId + CMD_ID，返回原值数组；失败 null。
     * CarCtl / 档位 / 车窗回读全走这里，和主题 232 同一通道。
     */
    public int[] get(int eventId, int cmd) {
        Object b = bus;
        if (b == null) return null;
        try {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_CMD, cmd);
            Object r = mGetOnce.invoke(b, evCtor.newInstance(eventId, bundle));
            if (r == null) return null;
            Bundle payload = readPayload(r);
            if (payload == null) return null;
            return payload.getIntArray(KEY_VALUE);
        } catch (Throwable t) {
            Log.w(TAG, "get(ev=" + eventId + ",cmd=" + cmd + ") failed: " + t);
            return null;
        }
    }

    /** 通用写（多值）：返回通道位掩码，0=两条通道都没发出去。 */
    public int sendArr(int eventId, int cmd, int[] v) {
        int path = 0;
        Object b = bus;
        if (b != null) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(KEY_CMD, cmd);
                bundle.putIntArray(KEY_VALUE, v);
                mSet.invoke(b, evCtor.newInstance(eventId, bundle));
                path = path | 1;
            } catch (Throwable t) { Log.w(TAG, "sendArr via VDBus failed: " + t); }
        }
        Object p = proxy;
        if (p != null) {
            try {
                mProxySend.invoke(p, eventId, cmd, v);
                path = path | 2;
            } catch (Throwable t) { Log.w(TAG, "sendArr via proxy failed: " + t); }
        }
        return path;
    }

    /**
     * 唤起 / 关闭原车 360 环视（AvmID.ID_AVM_DISPLAY=8 / ID_AVM_OFF=31）。
     * 注意：实机取证（live_1920 全量日志）显示这台车 DVR 子系统不存在
     * （isDVRExist==0、moduleId 327700 一次都没出现），360 的渲染在 Kanzi/QNX 侧。
     * 所以这里只是「请原车把 360 弹出来」的单方面尝试：
     * 回执了算赚到，没回执日志里明说，记录仪画面由我们自己的四路取流负责。
     */
    public String openAvm(boolean open) {
        if (bus == null && proxy == null) return "总线没连上";
        int cmd = open ? AVM_DISPLAY : AVM_OFF;
        int path = 0;
        int[] evs = {EV_MODULE_AVM, EV_MODULE_RVC};
        for (int ev : evs) path = path | sendArr(ev, cmd, new int[]{1});
        if (path == 0) return "指令被拒（VDBus 和 CarInfoProxy 两条通道都失败）";
        Log.i(TAG, "openAvm(" + open + ") cmd=" + cmd + " path=" + path);
        return null;
    }

    /** 写仪表模式（见 THEME_*）。总线这层是 fire-and-forget，是否真生效只能回读。
     * 两条路径都发一遍：A 自己拼 VDEvent，B 走原车注册好的 CarInfoProxy 连接。 */
    public void setTheme(int theme) {
        int[] v = new int[]{theme};
        Object b = bus;
        if (b != null) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(KEY_CMD, CMD_CLUSTER_THEME);
                bundle.putIntArray(KEY_VALUE, v);
                mSet.invoke(b, evCtor.newInstance(EV_CAR_SETTING, bundle));
                lastPath = lastPath | 1;
            } catch (Throwable t) {
                Log.w(TAG, "setTheme via VDBus failed: " + t);
            }
        }
        Object p = proxy;
        if (p != null) {
            try {
                mProxySend.invoke(p, EV_CAR_SETTING, CMD_CLUSTER_THEME, v);
                lastPath = lastPath | 2;
            } catch (Throwable t) {
                Log.w(TAG, "setTheme via CarInfoProxy failed: " + t);
            }
        }
    }

    /** 读仪表模式，优先走 proxy（和 SystemUI 一致），退回 VDBus 由调用方做。 */
    public int getThemeViaProxy() {
        Object p = proxy;
        if (p == null) return -1;
        try {
            int[] v = (int[]) mProxyGet.invoke(p, EV_CAR_SETTING, CMD_CLUSTER_THEME);
            if (v == null || v.length == 0) return -1;
            return v[0];
        } catch (Throwable t) {
            Log.w(TAG, "getThemeViaProxy failed: " + t);
            return -1;
        }
    }

    private Bundle readPayload(Object event) {
        Bundle b;
        try {
            b = (Bundle) mGetPayload.invoke(event);
        } catch (Throwable t) {
            return null;
        }
        if (b != null) b.setClassLoader(cl);
        return b;
    }

    // ---------- 原车桌面音乐卡片 ----------

    /**
     * 把第三方音乐的播放信息推给原车总线，桌面那张音乐卡片就会显示它。
     * VDMediaInfo 的字段全是 private，只能走它自己的 putXxx()，
     * 之前用 getField() 直接塞是静默失败的（等于推了个空对象），这条已修正。
     */
    public boolean publishMedia(String title, String artist, String album,
                                byte[] cover) {
        Object b = bus;
        if (b == null) return false;
        try {
            Object info = infoCls.newInstance();
            call(infoCls, info, "putTitle", String.class, title);
            call(infoCls, info, "putArtist", String.class, artist);
            call(infoCls, info, "putAlbum", String.class, album);
            call(infoCls, info, "putMediaType", int.class, MEDIA_TYPE_ONLINE);
            call(infoCls, info, "putInfoType", int.class, INFO_TYPE_ID3);
            if (cover != null && cover.length > 0) {
                try {
                    infoCls.getMethod("setAlbumBuffer", byte[].class).invoke(info, cover);
                } catch (Throwable t) {
                    Log.w(TAG, "album cover rejected: " + t);
                }
            }
            mSet.invoke(b, mCreateEvent.invoke(null, EV_MEDIA_SOURCE_INFO, info));
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishMedia failed: " + t);
            return false;
        }
    }

    /**
     * 桌面卡片那层桥（launcher 的 AudioSourceModel）先认「当前音源」再收歌：
     * 反编译看到卡片走 AudioSourceModel 转发，且按包名白名单过滤（只认
     * com.desaysv.mediacenter），393217 = VDEventMedia.CURRENT_SOURCE_TYPE 就是那个「当前源」。
     * 之前只推 393218/393220 从不声明源，所以卡片一直不理 —— 这条就是补上那一步。
     * payload 沿用 VDMediaInfo（393217 就在同一段里，字段格式一样），
     * 能读到原车当前那份就照抄它的写法，读不到再新建。
     * @return null=已发出（是否真被卡片接受还得实机看），否则是真实异常描述。
     */
    public String setMediaSource(int sourceType) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Class<?> cls = infoCls;
            if (cls == null) return "VDMediaInfo 载入失败";
            Object cur = readBean(EV_MEDIA_CURRENT_SOURCE, cls);
            Object info = cur != null ? cur : cls.newInstance();
            call(cls, info, "putMediaType", int.class, sourceType);
            mSet.invoke(b, mCreateEvent.invoke(null, EV_MEDIA_CURRENT_SOURCE, info));
            Log.i(TAG, "setMediaSource(" + sourceType + ") sent copy=" + (cur != null));
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "setMediaSource failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 回读 393217 里的 mediaType，看原车把什么值当"在线音乐"。读不到 -1。 */
    public int readMediaSource() {
        Class<?> cls = infoCls;
        if (cls == null) return -1;
        Object bean = readBean(EV_MEDIA_CURRENT_SOURCE, cls);
        if (bean == null) return -1;
        try {
            Object r = cls.getMethod("getMediaType").invoke(bean);
            if (r instanceof Integer) return (Integer) r;
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "readMediaSource failed: " + t);
            return -1;
        }
    }

    /** 播放/暂停状态：卡片要的是 393220，光有歌名不够。 */
    public boolean publishPlayStatus(boolean playing) {
        Object b = bus;
        if (b == null) return false;
        try {
            Object st = playCls.newInstance();
            call(playCls, st, "putMediaType", int.class, MEDIA_TYPE_ONLINE);
            call(playCls, st, "putPlayStatus", int.class,
                    playing ? PLAY_STATUS_PLAYING : PLAY_STATUS_PAUSED);
            mSet.invoke(b, mCreateEventPlay.invoke(null, EV_MEDIA_PLAY_STATUS, st));
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishPlayStatus failed: " + t);
            return false;
        }
    }

    // ---------- 仪表导航投屏（原车通道，和原车一样不搬窗口） ----------

    /**
     * 通知原车仪表渲染器：导航要不要显示在仪表上。
     * enable=true 时请求「第二主题」整屏区域（原车极简档下导航就占不住仪表），
     * false 时关通道，仪表回到原显示模式。
     * 返回 null=总线已发出（是否真落到仪表还得看实机），否则是真实异常描述。
     */
    public String publishNaviCluster(boolean enable) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = clusterCls.newInstance();
            // 用原车自己那份字符串格式，能读到就照抄，读不到才用数字串兜底。
            Object cur = readBean(EV_NAVI_DISPLAY_TO_CLUSTER, clusterCls);
            String[] fmt = stringShape(cur);
            setStr(clusterCls, bean, "DisplayCluster",
                    enable ? NAVI_SWITCH_OPEN : NAVI_SWITCH_CLOSE, fmt);
            setStr(clusterCls, bean, "NaviFrontDeskStatus",
                    enable ? NAVI_SWITCH_OPEN : NAVI_SWITCH_CLOSE, fmt);
            setStr(clusterCls, bean, "RequestDisplayNaviArea",
                    enable ? NAVI_AREA_SECOND : NAVI_AREA_CLOSE, fmt);
            if (cur != null) copyUnset(bean, cur, "Perspective", "PerspectiveResult");
            mSet.invoke(b, mCreateEventCluster.invoke(null, EV_NAVI_DISPLAY_TO_CLUSTER, bean));
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "publishNaviCluster failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 仪表导航显示区域（721702）：原车用这个决定导航画在仪表的哪块、占多宽。 */
    public String publishNaviArea(boolean toCluster) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = areaCls.newInstance();
            areaCls.getMethod("setNaviDisplayArea", int.class)
                    .invoke(bean, toCluster ? NAVI_AREA_SECOND : NAVI_AREA_CLOSE);
            mSet.invoke(b, mCreateEventArea.invoke(null, EV_NAVI_DISPLAY_AREA, bean));
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "publishNaviArea failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 使用系统 VDS Adapter 的字符串协议打开/关闭仪表导航视图。
     * QNX 实际读取的是 {@code true/false}，不是旧代码里的数字 1/2。
     */
    public String publishNaviDisplayJson(boolean show) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = clusterCls.newInstance();
            call(clusterCls, bean, "setDisplayCluster", String.class, show ? "true" : "false");
            call(clusterCls, bean, "setNaviFrontDeskStatus", String.class, show ? "true" : "false");
            call(clusterCls, bean, "setPerspective", int.class, 0);
            call(clusterCls, bean, "setPerspectiveResult", String.class, "false");
            call(clusterCls, bean, "setRequestDisplayNaviArea", String.class, "false");
            mSet.invoke(b, mCreateEventCluster.invoke(null, EV_NAVI_DISPLAY_TO_CLUSTER, bean));
            Log.i(TAG, "navi display json show=" + show);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviDisplayJson failed: " + t);
            return lastError;
        }
    }

    /** 发送普通导航显示区域，对应 {@code {"NaviDisplayArea":1,...}}。 */
    public String publishNaviAreaNormal() {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = areaCls.newInstance();
            call(areaCls, bean, "setNaviDisplayArea", int.class, 1);
            call(areaCls, bean, "setNaviDisplayAreaResult", String.class, "true");
            mSet.invoke(b, mCreateEventArea.invoke(null, EV_NAVI_DISPLAY_AREA, bean));
            Log.i(TAG, "navi area normal sent");
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviAreaNormal failed: " + t);
            return lastError;
        }
    }

    /** 发送 721698 VDNaviRoadInfo；QNX 靠它绘制路线区域，而不是只认开关。 */
    public String publishNaviRoad(int segRemainDis, int roadType, int roadIcon,
                                  String roadName, String nextRoadName,
                                  int progressPercent, int intersectionZoom) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = roadCls.newInstance();
            call(roadCls, bean, "setSegRemainDis", int.class, segRemainDis);
            call(roadCls, bean, "setRoadType", int.class, roadType);
            call(roadCls, bean, "setRoadIcon", int.class, roadIcon);
            call(roadCls, bean, "setRoadName", String.class, roadName);
            call(roadCls, bean, "setNextRoadName", String.class, nextRoadName);
            call(roadCls, bean, "setNextNaviActionProgbar", int.class, progressPercent);
            call(roadCls, bean, "setIntersectionZoomStatus", int.class, intersectionZoom);
            mSet.invoke(b, mCreateEventRoad.invoke(null, EV_NAVI_ROAD_INFO, bean));
            Log.i(TAG, "navi road 721698 sent name=" + roadName + " dis=" + segRemainDis);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviRoad failed: " + t);
            return lastError;
        }
    }

    /** 发送 721701 TBT/电子眼状态。 */
    public String publishNaviDigital(int cameraType, int naviStatus,
                                     int speedingInfo, int warningMessage) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = digitalCls.newInstance();
            call(digitalCls, bean, "setCameraType", int.class, cameraType);
            call(digitalCls, bean, "setNaviStatus", int.class, naviStatus);
            call(digitalCls, bean, "setSpeedingInfo", int.class, speedingInfo);
            call(digitalCls, bean, "setWarningMessage", int.class, warningMessage);
            mSet.invoke(b, mCreateEventDigital.invoke(null, EV_NAVI_DIGITAL_INFO, bean));
            Log.i(TAG, "navi digital 721701 sent status=" + naviStatus);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviDigital failed: " + t);
            return lastError;
        }
    }

    /** 回读总线上当前那个 bean，用来照抄原车的字段格式，读不到返回 null。 */
    private Object readBean(int eventId, Class<?> cls) {
        Object b = bus;
        if (b == null) return null;
        try {
            Object ev = mGetOnce.invoke(b, evCtor1.newInstance(eventId));
            if (ev == null) return null;
            Bundle p = readPayload(ev);
            if (p == null) return null;
            Object hit = null;
            for (String k : p.keySet()) {
                Object v = p.get(k);
                if (cls.isInstance(v)) { hit = v; break; }
            }
            return hit;
        } catch (Throwable t) {
            Log.w(TAG, "readBean " + eventId + " failed: " + t);
            return null;
        }
    }

    /** 从原车 bean 里摸出字符串字段的写法（是 "2" 还是 "OPEN" 还是别的）。 */
    private String[] stringShape(Object bean) {
        ArrayList<String> out = new ArrayList<>();
        if (bean == null) return new String[0];
        try {
            for (java.lang.reflect.Field f : bean.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object val = f.get(bean);
                if (val instanceof String) {
                    String s = (String) val;
                    if (!out.contains(s)) out.add(s);
                }
            }
        } catch (Throwable ignored) { }
        return out.toArray(new String[0]);
    }

    /** 把没打算改的字段从原车那份抄过来，别把人家维护的状态写没。 */
    private void copyUnset(Object target, Object src, String... names) {
        if (src == null) return;
        for (String n : names) {
            try {
                java.lang.reflect.Field rf = src.getClass().getDeclaredField(n);
                rf.setAccessible(true);
                java.lang.reflect.Field wf = target.getClass().getDeclaredField(n);
                wf.setAccessible(true);
                wf.set(target, rf.get(src));
            } catch (Throwable ignored) { }
        }
    }

    /** 原车用什么写法就用什么写法：能匹配到已有格式（按数字/关键字）就照抄。 */
    private void setStr(Class<?> cls, Object bean, String field, int want, String[] fmt) {
        String v = matchFormat(want, fmt);
        if (v == null) v = String.valueOf(want);
        try {
            cls.getMethod("set" + field, String.class).invoke(bean, v);
        } catch (Throwable t) {
            Log.w(TAG, "set " + field + " rejected: " + t);
        }
    }

    private String matchFormat(int want, String[] fmt) {
        if (fmt.length == 0) return null;
        String ws = String.valueOf(want);
        for (String s : fmt) {
            if (s.equals(ws)) return s;
        }
        return null;
    }

    // ---------- 总线抓包：真实协议不再靠猜 ----------

    private volatile Object listener = null;

    /**
     * 订阅一批 eventId，把车机自己发的原始内容回调出来。
     * SDK 里 addSubscribe 只是本地缓冲，必须 subscribeCommit 才推给服务端，
     * 两个都不能漏（实机 dexdump 里 VDConnector.addSubscribe / subscribeCommit 看到的）。
     * 返回 null=已挂上，否则是真实异常描述。
     */
    public String sniff(int[] ids, final Consumer<String> dump) {
        final Object b = bus;
        if (b == null) return "总线没连上";
        try {
            final Class<?> busCls = b.getClass();
            if (listener == null) {
                InvocationHandler handler = new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("onVDNotify".equals(m.getName()) && args != null && args.length > 0) {
                            try { dump.accept(describe(args[0])); } catch (Throwable t) {
                                Log.w(TAG, "sniff dump failed: " + t);
                            }
                        }
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        return null;
                    }
                };
                listener = Proxy.newProxyInstance(cl, new Class<?>[]{notifyCls}, handler);
                // 用新版按服务注册：不带 ServiceType 的那个老方法内部会打
                // "Please use new registerVD* methods" 的告警（实机 dexdump 里看到的）。
                Class<?> stCls = cl.loadClass(
                        "com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");
                Method reg = busCls.getMethod("registerVDNotifyListener", stCls, notifyCls);
                int bound = 0;
                for (Object t : services()) {
                    try { reg.invoke(b, t, listener); bound++; } catch (Throwable e) {
                        Log.w(TAG, "register listener on " + t + " rejected: " + e);
                    }
                }
                if (bound == 0) return "四个服务的监听器全被拒，抓不了包";
            }
            Method addI = busCls.getMethod("addSubscribe", int.class, int.class);
            for (int i : ids) {
                try { addI.invoke(b, i, THREAD_CHILD); } catch (Throwable t) {
                    Log.w(TAG, "addSubscribe " + i + " rejected: " + t);
                }
            }
            busCls.getMethod("subscribeCommit").invoke(b);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "sniff failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private ArrayList<Object> services() {
        ArrayList<Object> out = new ArrayList<>();
        Object[] ts = {carInfoType, mediaServiceType, carLanType, naviServiceType};
        for (Object t : ts) {
            if (t != null) out.add(t);
        }
        return out;
    }

    public String stopSniff() {
        Object b = bus;
        if (b == null) return null;
        final Object l = listener;
        if (l == null) return null;
        try {
            Class<?> stCls = cl.loadClass(
                    "com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");
            Method un = b.getClass().getMethod("unregisterVDNotifyListener", stCls, notifyCls);
            for (Object t : services()) {
                try { un.invoke(b, t, l); } catch (Throwable e) {
                    Log.w(TAG, "unregister " + t + ": " + e);
                }
            }
            listener = null;
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "stopSniff failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    // ---------- 仪表实时数据（我们自己画在投屏之上用） ----------

    /** VEHICLE_HAL 的车速/档位，实机抓包里 519 是最高频事件，就是它。 */
    public volatile float hudSpeed = -1f;
    public volatile int hudGear = -1;
    public volatile String hudSeen = "";

    private Object hudListener = null;

    /**
     * 挂一个常驻监听，专门把车速和档位读进 hudSpeed/hudGear。
     * 载荷里的键名没有文档，所以做法是：把载荷里所有数值都倒出来，
     * 第一次看到的原样写进 hudSeen 供日志核对，同时取第一个浮点当车速。
     */
    public String watchHud() {
        final Object b = bus;
        if (b == null) return "总线没连上";
        if (hudListener != null) return null;
        try {
            final Class<?> busCls = b.getClass();
            Class<?> stCls = cl.loadClass(
                    "com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");
            Object veh = enumByName(stCls, "VEHICLE_HAL");
            if (veh == null) return "没有 VEHICLE_HAL 服务";
            busCls.getMethod("bindService", stCls).invoke(b, veh);
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method m, Object[] args) {
                    if ("onVDNotify".equals(m.getName()) && args != null && args.length > 0) {
                        try { absorbHud(args[0]); } catch (Throwable t) {
                            Log.w(TAG, "hud parse failed: " + t);
                        }
                    }
                    if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                    return null;
                }
            };
            hudListener = Proxy.newProxyInstance(cl, new Class<?>[]{notifyCls}, handler);
            busCls.getMethod("registerVDNotifyListener", stCls, notifyCls)
                    .invoke(b, veh, hudListener);
            Method addI = busCls.getMethod("addSubscribe", int.class, int.class);
            addI.invoke(b, EV_PERF_VEHICLE_SPEED, THREAD_CHILD);
            addI.invoke(b, EV_GEAR_SELECTION, THREAD_CHILD);
            busCls.getMethod("subscribeCommit").invoke(b);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "watchHud failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private void absorbHud(Object event) {
        if (event == null) return;
        int id;
        try {
            id = (Integer) evCls.getMethod("getId").invoke(event);
        } catch (Throwable t) { return; }
        Bundle p = readPayload(event);
        if (p == null) return;
        ArrayList<String> nums = new ArrayList<>();
        boolean seenFloat = false;
        float firstFloat = 0f;
        boolean seenInt = false;
        int firstInt = 0;
        for (String k : p.keySet()) {
            Object v = p.get(k);
            if (v instanceof Float) {
                nums.add(k + "=" + v);
                if (!seenFloat) { firstFloat = (Float) v; seenFloat = true; }
            } else if (v instanceof Double) {
                nums.add(k + "=" + v);
                if (!seenFloat) { firstFloat = ((Double) v).floatValue(); seenFloat = true; }
            } else if (v instanceof Integer) {
                nums.add(k + "=" + v);
                if (!seenInt) { firstInt = (Integer) v; seenInt = true; }
            } else if (v instanceof float[]) {
                float[] fa = (float[]) v;
                if (fa.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < fa.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(fa[i]);
                    }
                    nums.add(k + "=" + sb.toString());
                    if (!seenFloat) { firstFloat = fa[0]; seenFloat = true; }
                }
            } else if (v instanceof int[]) {
                int[] ia = (int[]) v;
                if (ia.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ia.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(ia[i]);
                    }
                    nums.add(k + "=" + sb.toString());
                    if (!seenInt) { firstInt = ia[0]; seenInt = true; }
                }
            }
        }
        if (id == EV_PERF_VEHICLE_SPEED) {
            if (seenFloat) hudSpeed = firstFloat;
            else if (seenInt) hudSpeed = (float) firstInt;
            if (hudSeen.isEmpty()) hudSeen = "519 载荷：" + nums;
        } else if (id == EV_GEAR_SELECTION) {
            if (seenInt) hudGear = firstInt;
            else if (seenFloat) hudGear = (int) firstFloat;
            if (!hudSeen.contains("1024")) hudSeen += " | 1024 载荷：" + nums;
        }
    }

    /** 把 VDEvent 拆成「id + payload 每个键 + Parcelable 每个字段」，全字段倒出来看。 */
    private String describe(Object event) {
        if (event == null) return "空事件";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("event=").append(evCls.getMethod("getId").invoke(event));
            Bundle p = readPayload(event);
            if (p == null) { sb.append(" 无载荷"); return sb.toString(); }
            for (String k : p.keySet()) {
                sb.append(" [").append(k).append("]=");
                appendVal(sb, p.get(k), 0);
            }
        } catch (Throwable t) {
            sb.append(" 解析失败 ").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private void appendVal(StringBuilder sb, Object v, int depth) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof byte[]) { sb.append("<字节 ").append(((byte[]) v).length).append('>'); return; }
        if (v instanceof int[]) {
            int[] ia = (int[]) v;
            for (int i = 0; i < ia.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(ia[i]);
            }
            return;
        }
        if (depth > 1 || v instanceof String || v instanceof Number || v instanceof Boolean) {
            sb.append(v); return;
        }
        Class<?> c = v.getClass();
        if (c.getName().startsWith("android.os.") || c.getName().startsWith("java.util.") ||
                c.getName().startsWith("java.lang.")) { sb.append(v); return; }
        sb.append(c.getSimpleName()).append('{');
        try {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                sb.append(f.getName()).append('=');
                appendVal(sb, f.get(v), depth + 1);
                sb.append(',');
            }
        } catch (Throwable t) { sb.append("?"); }
        sb.append('}');
    }
}
