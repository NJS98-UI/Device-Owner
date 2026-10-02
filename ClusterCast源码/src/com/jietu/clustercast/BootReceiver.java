package com.jietu.clustercast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：拉起常驻前台服务，监听三指手势广播。
 * 装上即用，无需任何手动步骤。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        context.startForegroundService(new Intent(context, CastService.class));
    }
}
