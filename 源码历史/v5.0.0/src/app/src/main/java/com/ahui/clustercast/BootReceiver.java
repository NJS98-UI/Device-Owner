package com.ahui.clustercast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启：让三指手势随时可用，不需要每次手动打开应用。 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Log.i("ClusterCast", "boot -> start CastService");
        CastService.start(c);
    }
}
