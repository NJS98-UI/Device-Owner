package com.jietu.tools;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import dalvik.system.DexClassLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * shell 侧 VDBus 直连抓包工具（adb shell CLASSPATH=bus.jar app_process / com.jietu.tools.BusDump）。
 * 不走 Context.bindService（shell 进程没有 IApplicationThread，AMS 会拒），
 * 直接 ServiceManager 拿平台 IVDBus 裸 binder 调 get。
 * 用法：BusDump <eventId> [秒数]   默认 327681，600 秒。
 * 先 SNAP 全量 cmdId 1..128，再 300ms 轮询打印 CHG（值变化）。stdout 直出，不吃 logcat。
 */
public class BusDump {

    static Object ivdbus;
    static Method mGet;
    static Constructor<?> evCtor;
    static ClassLoader vcl;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        int eventId = args.length > 0 ? Integer.parseInt(args[0]) : 327681;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 600;

        vcl = new DexClassLoader(
                "/system/framework/vdbus.jar:/system/framework/vdbus_extra.jar",
                "/data/local/tmp/dexcache", null, BusDump.class.getClassLoader());

        Class<?> evCls = vcl.loadClass("com.desaysv.ivi.vdb.event.VDEvent");
        evCtor = evCls.getConstructor(int.class, Bundle.class);

        IBinder raw = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "com.desaysv.ivi.vds.vdev.service.VehicleDevice");
        out("RAW binder " + (raw == null ? "NULL" : raw.toString()));
        if (raw == null) { out("DONE"); return; }
        ivdbus = vcl.loadClass("com.desaysv.ivi.vdb.IVDBus$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, raw);
        mGet = ivdbus.getClass().getMethod("get",
                vcl.loadClass("com.desaysv.ivi.vdb.event.VDEvent"));

        int[] prev = new int[129];
        Arrays.fill(prev, -1);
        StringBuilder snap = new StringBuilder("SNAP");
        for (int id = 1; id <= 128; id++) {
            prev[id] = getItem(eventId, id);
            if (prev[id] >= 0) snap.append(' ').append(id).append('=').append(prev[id]);
        }
        out(snap.toString());

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            for (int id = 1; id <= 128; id++) {
                int now = getItem(eventId, id);
                if (now < 0) { continue; }
                if (prev[id] >= 0 && prev[id] != now) {
                    out("CHG id=" + id + " " + prev[id] + "->" + now);
                }
                prev[id] = now;
            }
        }
        out("DONE");
        System.exit(0);
    }

    static int getItem(int eventId, int cmdId) {
        try {
            Bundle bundle = new Bundle();
            bundle.putInt("CMD_ID", cmdId);
            Object r = mGet.invoke(ivdbus, evCtor.newInstance(eventId, bundle));
            if (r == null) return -1;
            java.lang.reflect.Method gp = r.getClass().getMethod("getPayload");
            Bundle p = (Bundle) gp.invoke(r);
            if (p == null) return -1;
            p.setClassLoader(vcl);
            int[] arr = p.getIntArray("VALUE");
            if (arr == null || arr.length == 0) return -1;
            return arr[0];
        } catch (Throwable t) {
            if (errShown < 5) { errShown++; out("ERR " + cmdId + " " + t); }
            return -1;
        }
    }

    static int errShown;

    static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
