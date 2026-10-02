package com.ahui.clustercast;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 原厂屏-屏投屏通道（从系统包 /system/framework/desay.projection.jar 里逐字节复原的签名）：
 *   descriptor  com.desaysv.ivi.projection.IProject
 *   servicename desaysv_project_service
 *   事务码     1 startProjectToDisplay(II)
 *              2 startProjectToDisplayWithSurface(Surface,I,I)
 *              3 startProjectToDisplays(I,[I)
 *              4 stopProjectForDisplay(I)
 *              5 stopProjectForDisplays([I)
 *              6 setAntiControllEnabled(I,Z)
 * 我们用裸 Binder 调它：不 import 它的任何类，所以拿不到这个服务时也只是返回
 * 真实异常写日志，绝不谎报成功。能否被第三方应用取得取决于 SELinux
 * （desaysv_project_service 不在 AOSP plat_service_contexts 里，厂商域标签待 product
 * 分区解完确认）——所以这条通道只做「探测/对照」，默认不动现有投屏行为。
 */
public final class ProjCtl {

    private ProjCtl() { }

    public static final String DESC = "com.desaysv.ivi.projection.IProject";
    public static final String SVC = "desaysv_project_service";

    /**
     * 官方客户端 jar（/system/.../proj.jar，我们逐字节 dump 过）里第二条拿服务的路：
     * 不查 ServiceManager，而是拿这个 action 去 bindService，bind 不上就先
     * startForegroundService 把服务拉起来再 bind。这条路走的是普通 Intent 解析，
     * 只要那个 service exported 且没挂签名级权限，第三方应用也能用。
     */
    public static final String ACTION = "action.desaysv.ivi.vds.projection.IProject";

    /** 服务是否可见。null = 拿不到（没这个服务，或非系统应用被 SELinux 拦）。 */
    public static IBinder getService() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, SVC);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 探测/取用：先按老办法查 ServiceManager（第三方基本会被 SELinux 拦），
     * 查不到就用官方 jar 里那条 bindService 的路子。两条都没成，binder() 返回 null，
     * 上层照实写日志。
     */
    private static volatile IBinder mBound = null;

    public static IBinder binder() {
        IBinder s = getService();
        return s != null ? s : mBound;
    }

    /**
     * 按原厂客户端的顺序试 bind 通道：bindService(action) → 被拒就
     * startForegroundService(action) 把服务拉起来 → 再 bind 一次。
     * 必须在**后台线程**调用：里面有等待，而 onServiceConnected 是回主线程的，
     * 主线程调自己会白等 3 秒。全程只等有限时间，不静默重试。
     * @return null = 成功拿到 binder；否则是给用户看的实话。
     */
    public static synchronized String bind(Context ctx) {
        if (mBound != null) return null;
        final Intent it = new Intent(ACTION);
        final CountDownLatch got = new CountDownLatch(1);
        final ArrayList<String> why = new ArrayList<>();
        final ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder b) {
                mBound = b;
                got.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                mBound = null;
            }
        };
        boolean ok;
        try {
            ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            why.add("bindService 抛 " + t.getClass().getSimpleName() + "：" + t.getMessage());
            ok = false;
        }
        if (!ok) {
            // bind 被拒：先按官方 SDK 的做法把服务拉起来，再试一次
            boolean started;
            try {
                ctx.startForegroundService(new Intent(ACTION));
                started = true;
            } catch (Throwable t) {
                why.add("拉起服务也失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
                started = false;
            }
            if (started) {
                try {
                    ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    why.add("二次 bind 抛 " + t.getClass().getSimpleName());
                    ok = false;
                }
                if (!ok) why.add("二次 bind 仍被系统拒");
            }
        }
        if (!ok) {
            if (why.isEmpty())
                return "bind 被拒（这个 action 在本机没有可绑的 exported 服务）";
            return String.join("；", why);
        }
        // 只等 3 秒：连不上就照实说，不做无限等待
        try {
            if (!got.await(3, TimeUnit.SECONDS)) {
                try { ctx.unbindService(conn); } catch (Throwable ignored) { }
                return "bind 已接受但 3 秒内没连上（对方没回调 onServiceConnected）";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "等待被中断";
        }
        return mBound == null ? "bind 回来了但 binder 是空的" : null;
    }

    /**
     * 一次裸事务：返回 null=调用成功（okToken 通过），否则是真实异常描述。
     * 只对「查询/开关」类调用安全；真正改屏幕前必须先把 displayId 在实车上对清楚。
     */
    public static String transact(int code, int[] ints) { return transact(code, ints, null); }

    public static String transact(int code, int[] ints, Boolean bool) {
        IBinder b = binder();
        if (b == null) return "拿不到投屏服务（ServiceManager 被拒、bind 通道也没连上）";
        Parcel data = null, reply = null;
        try {
            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken(DESC);
            for (int v : ints) data.writeInt(v);
            if (bool != null) data.writeInt(bool ? 1 : 0);
            if (!b.transact(code, data, reply, 0)) return "投屏服务没响应（事务被拒）";
            reply.readException();
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            try { if (data != null) data.recycle(); } catch (Exception e) { }
            try { if (reply != null) reply.recycle(); } catch (Exception e) { }
        }
    }

    public static String startProject(int from, int to) {
        return transact(1, new int[]{from, to});
    }

    public static String stopProject(int to) {
        return transact(4, new int[]{to});
    }
}
