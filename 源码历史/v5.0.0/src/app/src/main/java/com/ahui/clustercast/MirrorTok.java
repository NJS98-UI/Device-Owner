package com.ahui.clustercast;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

/**
 * 授权结果 + MediaProjection 的进程内保管处。
 * token（resultCode+data）只在本次进程里有效；projection 拿一次一直复用，
 * 退出镜像只收 VirtualDisplay 不停 projection（停了就得重新点授权）。
 * 服务销毁时 releaseAll() 把共享状态彻底摘干净，不留「后台还在截屏」。
 */
final class MirrorTok {
    private MirrorTok() { }

    private static volatile int rc = 0;
    private static volatile Intent data = null;
    static volatile MediaProjection projection = null;

    static boolean has() { return data != null; }

    static synchronized void set(int resultCode, Intent d) { rc = resultCode; data = d; }

    /** 用保管的授权换 MediaProjection；没授权或系统不给返回 null。 */
    static synchronized MediaProjection ensure(Context ctx) {
        if (projection != null) return projection;
        Intent d = data;
        if (d == null) return null;
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(rc, d);
            return projection;
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized void releaseAll() {
        try { if (projection != null) projection.stop(); } catch (Throwable t) { }
        projection = null;
        data = null;
        rc = 0;
    }
}

