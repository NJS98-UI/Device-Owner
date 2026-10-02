package com.ahui.vehprobe;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

import dalvik.system.DexClassLoader;

/**
 * Desay VDBus 的通用读写层（clustercast/Vd.java 的泛化版：任意 eventId + cmdId）。
 *
 * vdbus.jar 在 /system/framework 下但不在应用 classpath 上，所以运行时用 DexClassLoader 载入，全程反射。
 *
 * 关键约定：总线 set() 是 fire-and-forget，返回不代表生效。所以本类不提供"boolean 写成功"这种语义，
 * 只区分「已下发」和「回读到的值」；生效与否由调用方比对 get() 的结果判定。
 */
public class Bus {

    static final String TAG = "VehProbe";
    private static final String JAR = "/system/framework/vdbus.jar";

    static final int EV_CAR_SETTING = 327681;   // 0x50001 车身/座椅/后视镜/仪表
    static final int EV_VEHICLE_STATE = 327684; // 0x50004 档位等车辆状态
    static final int EV_HVAC = 327690;          // 0x5000A 空调
    static final int EV_TOP_WINDOW = 327696;    // 0x50010 天窗

    private static final String KEY_CMD = "CMD_ID";
    private static final String KEY_VALUE = "VALUE";

    private static volatile Bus sInst;

    public static synchronized Bus connect(Context c) {
        Bus v = sInst;
        if (v == null) { v = new Bus(); sInst = v; }
        if (v.bus == null) v.open(c.getApplicationContext());
        return v;
    }

    /** 只在真正调用失败时为 true；绑定成功不代表任何 cmdId 可读写。 */
    public boolean ok() { return bus != null; }
    public String lastError() { return err; }

    private ClassLoader cl;
    private Object bus;
    private Class<?> evCls;
    private Constructor<?> evCtor;
    private Method mGetOnce, mSet, mGetPayload;
    private String err;

    private void open(Context c) {
        err = null;
        try {
            cl = resolveLoader(c);
            Class<?> busCls = cl.loadClass("com.desaysv.ivi.vdb.client.VDBus");
            evCls = cl.loadClass("com.desaysv.ivi.vdb.event.VDEvent");
            Class<?> stCls = cl.loadClass("com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");

            Object carInfoType = enumByName(stCls, "CAR_INFO");
            if (carInfoType == null) { err = "no CAR_INFO enum"; return; }

            bus = busCls.getMethod("getDefault").invoke(null);
            busCls.getMethod("init", Context.class).invoke(bus, c);

            mGetOnce = busCls.getMethod("getOnce", evCls);
            mSet = busCls.getMethod("set", evCls);
            mGetPayload = evCls.getMethod("getPayload");
            evCtor = evCls.getConstructor(int.class, Bundle.class);

            Object bound = busCls.getMethod("bindService", stCls).invoke(bus, carInfoType);
            if (!Boolean.TRUE.equals(bound)) {
                err = "bindService(CAR_INFO)=false";
                bus = null;
            }
            Log.i(TAG, "connect bound=" + bound);
        } catch (Throwable t) {
            bus = null;
            err = String.valueOf(t);
            Log.w(TAG, "connect failed: " + t);
        }
    }

    private ClassLoader resolveLoader(Context c) throws ClassNotFoundException {
        try {
            Class.forName("com.desaysv.ivi.vdb.client.VDBus");
            return Bus.class.getClassLoader();
        } catch (ClassNotFoundException ignored) { }
        return new DexClassLoader(JAR, c.getCodeCacheDir().getAbsolutePath(), null, Bus.class.getClassLoader());
    }

    private static Object enumByName(Class<?> enumCls, String name) {
        for (Object o : enumCls.getEnumConstants()) {
            if (name.equals(((Enum<?>) o).name())) return o;
        }
        return null;
    }

    /** 一次读取的结果。value 为 null 表示没读到 int[]，此时看 raw。 */
    static final class R {
        final int[] value;
        final String raw;
        final String fail;

        R(int[] value, String raw, String fail) {
            this.value = value;
            this.raw = raw;
            this.fail = fail;
        }

        int first() { return (value == null || value.length == 0) ? Integer.MIN_VALUE : value[0]; }
        boolean good() { return value != null; }

        String text() {
            if (value == null) return fail != null ? "ERR:" + fail : "NO_VALUE";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length; i++) sb.append(i > 0 ? "," : "").append(value[i]);
            return sb.toString();
        }
    }

    /** 读一个 cmdId 的当前值。在子线程调用（getOnce 是同步 binder）。 */
    public R get(int eventId, int cmdId) {
        if (bus == null) return new R(null, null, "bus==null");
        try {
            Bundle b = new Bundle();
            b.putInt(KEY_CMD, cmdId);
            Object r = mGetOnce.invoke(bus, evCtor.newInstance(eventId, b));
            if (r == null) return new R(null, null, "getOnce=null");
            Bundle p = (Bundle) mGetPayload.invoke(r);
            if (p != null) p.setClassLoader(cl);
            int[] v = p == null ? null : p.getIntArray(KEY_VALUE);
            return new R(v, describe(p), v == null ? "no int[VALUE]" : null);
        } catch (Throwable t) {
            return new R(null, null, t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    /**
     * 下发一个值。**返回 true 只代表反射调用没抛异常，不代表 MCU 接受、更不代表执行成功**，
     * 判定必须走 get() 回读。
     */
    public boolean set(int eventId, int cmdId, int[] value) {
        if (bus == null) return false;
        try {
            Bundle b = new Bundle();
            b.putInt(KEY_CMD, cmdId);
            b.putIntArray(KEY_VALUE, value);
            mSet.invoke(bus, evCtor.newInstance(eventId, b));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "set ev=" + eventId + " cmd=" + cmdId + " failed: " + t);
            return false;
        }
    }

    /** 把 payload 里所有 key 打出来，用于核对协议表之外的返回结构。 */
    private static String describe(Bundle p) {
        if (p == null) return "payload=null";
        StringBuilder sb = new StringBuilder();
        Set<String> keys = p.keySet();
        for (String k : keys) sb.append(k).append('=').append(p.get(k)).append(' ');
        return sb.toString().trim();
    }
}
