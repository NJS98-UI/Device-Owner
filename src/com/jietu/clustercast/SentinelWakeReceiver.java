package com.jietu.clustercast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.kooo.evcam.AppLog;

/**
 * 哨兵值守唤醒节拍：车机深度休眠（厂商整机休眠）会无视框架层 wakelock
 * 直接冻结 CPU，纯线程轮询收不到门信号（实车验证）。本 receiver 每 5 秒被
 * AlarmManager.setAlarmClock（闹钟语义最高优先级，无限频）唤醒一次：
 * - 持 6.5 秒 PARTIAL_WAKE_LOCK（> 5 秒间隔，锁链无缝重叠）：浅睡级休眠
 *   被完全挡住，CPU 实时活着，150ms 轮询即秒级检测开门；迎宾轮询线程同样受益
 * - 厂商休眠若连闹钟唤醒后的锁都强制无视（整机冻结），仍由 5 秒闹钟节拍
 *   兜底——唤醒间隙读电平基准比对，检测延迟上限 5 秒
/**
 * 哨兵值守唤醒节拍：车机深度休眠（厂商整机休眠）会无视框架层 wakelock
 * 直接冻结 CPU，纯线程轮询收不到门信号（实车验证）。本 receiver 每 5 秒被
 * AlarmManager.setAlarmClock（闹钟语义最高优先级，无限频）唤醒一次：
 * - 持 6.5 秒 PARTIAL_WAKE_LOCK（> 5 秒间隔，锁链无缝重叠）：浅睡级休眠
 *   被完全挡住，CPU 实时活着，150ms 轮询即秒级检测开门；迎宾轮询线程同样受益
 * - 每拍开一个限时检测流窗口（4.5 秒后自动关流）：跨休眠持有 Camera2
 *   会话会把 HAL/ISP 卡死、唤醒后相机全黑（2026-10-03 实车实锤），
 *   所以检测流绝不能跨休眠——窗口在闹钟间隔内开合，休眠瞬间必然是关流态
 * - 厂商休眠若连闹钟唤醒后的锁都强制无视（整机冻结），仍由 5 秒闹钟节拍
 *   兜底——唤醒间隙读电平基准比对，检测延迟上限 5 秒
 * - 链式排下一个闹钟（一次性的，必须续排）
 */
public class SentinelWakeReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jietu:sentinelTick");
            wl.setReferenceCounted(false);
            // 持锁时长 > 5 秒闹钟间隔：锁链无缝重叠，浅睡级休眠被完全挡住，
            // CPU 实时活着轮询即秒级检测；厂商强制冻结时由闹钟节拍兜底
            wl.acquire(SentinelController.WAKE_HOLD_MS);
            AppLog.d("SentinelController", "闹钟唤醒值守检查（持锁 " + SentinelController.WAKE_HOLD_MS + " 毫秒）");
        } catch (Throwable t) {
            AppLog.w("SentinelController", "闹钟唤醒持锁失败: " + t);
        }
        SentinelController.scheduleNextWake(ctx);
        // 新节拍 = 新检测流窗口（先排闹钟，窗口超时关流在 MOTION_WINDOW_MS 后）
        SentinelController.startMotionWindow();
    }
}
