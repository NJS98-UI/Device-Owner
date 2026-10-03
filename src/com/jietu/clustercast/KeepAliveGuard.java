package com.jietu.clustercast;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.kooo.evcam.AppLog;

import java.util.Collections;

/**
 * Device Owner 防杀加固：把"被系统/用户杀后台"的每一条路都封上。
 *
 * 杀后台的形态与对策：
 * 1. 强行停止（用户误点/厂商省电静默 force-stop）→ setUserControlDisabledPackages：
 *    系统设置里"强行停止"按钮灰掉。force-stop 是最彻底的杀——sticky 重启失效、
 *    Alarm 清空、WorkManager 停摆，其余一切保活手段都会被它废掉，必须优先封死。
 * 2. App Standby / Doze / 省电模式 → 电池优化白名单（ensureDozeWhitelist）
 *    + targetSdk 28 的宽松待机策略
 * 3. 低内存杀进程 → START_STICKY 自动重启（CameraForegroundService/CastService
 *    均已配置）+ KeepAliveManager 15 分钟 WorkManager tick 兜底
 * 4. 卸载 / 权限被收回 → setPermissionGrantState 静默
 *    永久授权（CAMERA/录音/存储），防运行时权限被"仅本次"或撤销。
 *    注意：故意不设 setUninstallBlocked —— 本机 ROM 拒绝覆盖升级，
 *    自更新/换装全靠"卸载→全新安装"，防卸载会把自己堵死（表现为
 *    dpm set-device-owner 失败：旧 owner 还占着位）。
 *
 * 全部幂等，每次启动都应用一遍；每项独立 try/catch——车机系统版本不一
 * （minSdk 27），API 30/33 的方法在低版本系统会 NoSuchMethodError，
 * 单项失败不影响其余加固。
 */
public final class KeepAliveGuard {
    private static final String TAG = "KeepAliveGuard";

    private KeepAliveGuard() { }

    /** 幂等应用全部防杀策略（CastService/MainActivity 启动时各调一次）。 */
    public static void apply(Context ctx) {
        String pkg = ctx.getPackageName();
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        if (dpm == null || !dpm.isDeviceOwnerApp(pkg)) {
            AppLog.w(TAG, "非 Device Owner，防杀加固不可用（需 adb shell dpm set-device-owner "
                    + pkg + "/.CastAdminReceiver）");
            return;
        }
        ComponentName admin = new ComponentName(ctx, CastAdminReceiver.class);

        // 1. 封死"强行停止"（API 30+）：设置页按钮灰掉，厂商省电也无法 force-stop
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                dpm.setUserControlDisabledPackages(admin, Collections.singletonList(pkg));
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "防强行停止设置失败: " + t);
        }

        // 2.（防卸载已移除——ROM 拒覆盖升级，卸载重装是唯一升级通道）

        // 3. 省电豁免：setApplicationExemptions 是 API 33 的受限 API（公开 SDK 无符号，
        //    且车机系统普遍低于 13），App Standby/Doze 豁免由电池优化白名单
        //    （ensureDozeWhitelist）+ targetSdk 28 的宽松策略覆盖

        // 4. 运行时权限静默永久授权（权限须已在 manifest 声明）
        grant(dpm, admin, pkg, android.Manifest.permission.CAMERA);
        grant(dpm, admin, pkg, android.Manifest.permission.RECORD_AUDIO);
        grant(dpm, admin, pkg, android.Manifest.permission.READ_EXTERNAL_STORAGE);
        grant(dpm, admin, pkg, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);

        AppLog.d(TAG, "防杀加固已应用（" + status(ctx) + "）");
    }

    private static void grant(DevicePolicyManager dpm, ComponentName admin, String pkg, String perm) {
        try {
            dpm.setPermissionGrantState(admin, pkg, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        } catch (Throwable t) {
            AppLog.w(TAG, "静默授权失败 " + perm + ": " + t);
        }
    }

    /** 防杀加固状态摘要（主界面诊断用）。 */
    public static String status(Context ctx) {
        String pkg = ctx.getPackageName();
        try {
            DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(pkg)) return "非DeviceOwner";
            StringBuilder sb = new StringBuilder();
            sb.append("Owner");
            // setUserControlDisabledPackages 无 getter，防强停状态无法查询，应用成功即视为生效
            if (dpm.isUninstallBlocked(new ComponentName(ctx, CastAdminReceiver.class), pkg)) {
                sb.append("/防卸载");
            }
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
                sb.append("/电池白名单");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Owner(状态未知)";
        }
    }
}
