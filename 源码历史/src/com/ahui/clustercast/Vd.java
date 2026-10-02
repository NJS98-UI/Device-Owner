package com.ahui.clustercast;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;

import dalvik.system.DexClassLoader;

/**
 * Desay 私有 VDBus 总线的反射封装。
 *
 * 车机上的两件事没有公开 API，只能走这条总线（协议是实机 dexdump 抓出来的）：
 *   1) 仪表主题：service=CAR_INFO, eventId=327681, Bundle{"CMD_ID":232, "VALUE":int[]}
 *      3=数字模式 4=极简模式。读写都可以，普通应用身份即可（服务端 service 是 exported 且无权限）。
 *   2) 原车桌面音乐卡片：service=MEDIA, eventId=393218, Bundle{"info":VDMediaInfo}
 *      把第三方音乐的标题/歌手/专辑塞进去，卡片就不再是「未知频道」。
 *
 * vdbus.jar 在 /system/framework 下但不在应用 classpath 上，所以运行时用 DexClassLoader 载入，
 * 全程反射。任何一步失败都只记日志、返回默认值，绝不让主流程崩。
 */
public class Vd {

    private static final String TAG = "ClusterCast.Vd";
    private static final String JAR = "/system/framework/vdbus.jar";

    public static final int EV_CAR_SETTING = 327681;
    public static final int CMD_CLUSTER_THEME = 232;
    /**
     * 仪表模式四档，取自原车 SystemUI「仪表模式」弹窗（数字/经典/导航/极简）。
     * 弹窗埋点 InstrumentMode 在写入 4 时报 3，即协议值 = 选项下标 + 1。
     */
    public static final int THEME_DIGITAL = 1;
    public static final int THEME_CLASSIC = 2;
    public static final int THEME_NAVI = 3;
    public static final int THEME_SIMPLE = 4;

    public static String themeName(int t) {
        switch (t) {
            case THEME_DIGITAL: return "数字模式";
            case THEME_CLASSIC: return "经典模式";
            case THEME_NAVI:    return "导航模式";
            case THEME_SIMPLE:  return "极简模式";
            default:            return t < 0 ? "未知" : "编号" + t;
        }
    }

    public static final int EV_MEDIA_SOURCE_INFO = 393218;
    /** MediaType 白名单里没有第三方音乐，用 ONLINE_MUSIC 顶上，卡片只按这个字段画来源图标。 */
    public static final int MEDIA_TYPE_ONLINE = 14;
    public static final int INFO_TYPE_ID3 = 1;

    private static final String KEY_CMD = "CMD_ID";
    private static final String KEY_VALUE = "VALUE";

    private static volatile Vd sInst;
    public static Vd inst() { return sInst; }

    private ClassLoader cl;
    private Object bus;
    private Class<?> evCls;
    private Class<?> infoCls;
    private Object carInfoType;
    private Object mediaServiceType;
    private Constructor<?> evCtor;
    private Method mGetOnce, mSet, mGetPayload, mCreateEvent;

    /** 幂等；在子线程调用（bindService 与 getOnce 都是同步 binder）。 */
    public static synchronized Vd connect(Context c) {
        Vd v = sInst;
        if (v == null) { v = new Vd(); sInst = v; }
        if (v.bus == null) v.open(c.getApplicationContext());
        return v;
    }

    public boolean ok() { return bus != null; }

    private void open(Context c) {
        try {
            cl = resolveLoader(c);
            Class<?> busCls = cl.loadClass("com.desaysv.ivi.vdb.client.VDBus");
            evCls = cl.loadClass("com.desaysv.ivi.vdb.event.VDEvent");
            infoCls = cl.loadClass("com.desaysv.ivi.vdb.event.id.media.bean.VDMediaInfo");
            Class<?> stCls = cl.loadClass("com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");

            carInfoType = enumByName(stCls, "CAR_INFO");
            mediaServiceType = enumByName(stCls, "MEDIA");

            bus = busCls.getMethod("getDefault").invoke(null);
            busCls.getMethod("init", Context.class).invoke(bus, c);

            mGetOnce = busCls.getMethod("getOnce", evCls);
            mSet = busCls.getMethod("set", evCls);
            mGetPayload = evCls.getMethod("getPayload");
            evCtor = evCls.getConstructor(int.class, Bundle.class);
            mCreateEvent = infoCls.getMethod("createEvent", int.class, infoCls);

            Method bind = busCls.getMethod("bindService", stCls);
            boolean a = Boolean.TRUE.equals(bind.invoke(bus, carInfoType));
            boolean b = Boolean.TRUE.equals(bind.invoke(bus, mediaServiceType));
            Log.i(TAG, "connect carInfo=" + a + " media=" + b);
        } catch (Throwable t) {
            bus = null;
            Log.w(TAG, "connect failed: " + t);
        }
    }

    private ClassLoader resolveLoader(Context c) throws ClassNotFoundException {
        try {
            Class.forName("com.desaysv.ivi.vdb.client.VDBus");
            return Vd.class.getClassLoader();
        } catch (ClassNotFoundException ignored) { }
        return new DexClassLoader(JAR, c.getCodeCacheDir().getAbsolutePath(), null, Vd.class.getClassLoader());
    }

    private static Object enumByName(Class<?> enumCls, String name) {
        for (Object o : enumCls.getEnumConstants()) {
            if (name.equals(((Enum<?>) o).name())) return o;
        }
        return null;
    }

    // ---------- 仪表主题 ----------

    /** 读当前仪表主题；拿不到返回 -1。 */
    public int getTheme() {
        if (bus == null) return -1;
        try {
            Bundle b = new Bundle();
            b.putInt(KEY_CMD, CMD_CLUSTER_THEME);
            Object r = mGetOnce.invoke(bus, evCtor.newInstance(EV_CAR_SETTING, b));
            if (r == null) return -1;
            return first(readPayload(r));
        } catch (Throwable t) {
            Log.w(TAG, "getTheme failed: " + t);
            return -1;
        }
    }

    /**
     * 写仪表模式（见 THEME_*）。总线这层是 fire-and-forget，返回不代表生效，
     * 是否真的切过去了只能再 getTheme 回读。
     */
    public void setTheme(int theme) {
        if (bus == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(KEY_CMD, CMD_CLUSTER_THEME);
            b.putIntArray(KEY_VALUE, new int[] { theme });
            mSet.invoke(bus, evCtor.newInstance(EV_CAR_SETTING, b));
        } catch (Throwable t) {
            Log.w(TAG, "setTheme failed: " + t);
        }
    }

    private Bundle readPayload(Object event) throws Exception {
        Bundle b = (Bundle) mGetPayload.invoke(event);
        if (b != null) b.setClassLoader(cl);
        return b;
    }

    private static int first(Bundle b) {
        if (b == null) return -1;
        int[] v = b.getIntArray(KEY_VALUE);
        return (v == null || v.length == 0) ? -1 : v[0];
    }

    // ---------- 原车桌面音乐卡片 ----------

    /**
     * 把第三方音乐的播放信息推给原车总线，桌面那张音乐卡片就会显示它。
     * 只走 MEDIA_SOURCE_INFO 一个事件，来源类型固定成在线音乐。
     */
    public boolean publishMedia(String title, String artist, String album, HashMap<String, String> extras) {
        if (bus == null) return false;
        try {
            Object info = infoCls.newInstance();
            set(infoCls, info, "title", title);
            set(infoCls, info, "artist", artist);
            set(infoCls, info, "album", album);
            set(infoCls, info, "mediaType", MEDIA_TYPE_ONLINE);
            set(infoCls, info, "infoType", INFO_TYPE_ID3);
            if (extras != null) infoCls.getField("extras").set(info, extras);
            Object ev = mCreateEvent.invoke(null, EV_MEDIA_SOURCE_INFO, info);
            mSet.invoke(bus, ev);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "publishMedia failed: " + t);
            return false;
        }
    }

    private static void set(Class<?> cls, Object target, String field, Object val) {
        if (val == null) return;
        try { cls.getField(field).set(target, val); } catch (Throwable ignored) { }
    }
}
