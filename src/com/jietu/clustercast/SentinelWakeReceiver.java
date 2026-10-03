package com.jietu.clustercast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.kooo.evcam.AppLog;

/**
 * 哨兵值守唤醒节拍：车机深度休眠（厂商整机休眠）会无视框架层 wakelock
 * 直接冻结 CPU，纯线程轮询收不到门信号（实车验证）。RTC 硬件闹钟是
 * 深休眠中仍可唤醒 CPU 的可靠通道——本 receiver 每 45 秒被
 * AlarmManager.setExactAndAllowWhileIdle 唤醒一次：
 * - 持 8 秒 PARTIAL_WAKE_LOCK 给常驻轮询线程一个检查窗口
 *   （哨兵轮询 150ms 一拍，几秒足够读完门信号；迎宾轮询线程同样受益）
 * - 链式排下一个闹钟（一次性的，必须续排）
 * 开门事件若发生在两次唤醒之间：门信号是电平不是边沿，唤醒后读到
 * "门开着"与上次基准比对仍能触发；开门后几秒内又关上且恰逢唤醒间隙
 * 的极端情况会漏检，45 秒间隔是功耗与漏检的平衡点。
 */
public class SentinelWakeReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jietu:sentinelTick");
            wl.setReferenceCounted(false);
            wl.acquire(8000);
            AppLog.d("SentinelController", "闹钟唤醒值守检查（持锁 8 秒）");
        } catch (Throwable t) {
            AppLog.w("SentinelController", "闹钟唤醒持锁失败: " + t);
        }
        SentinelController.scheduleNextWake(ctx);
    }
}
