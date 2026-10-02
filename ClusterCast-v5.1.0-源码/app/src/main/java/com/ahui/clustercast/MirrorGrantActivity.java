package com.ahui.clustercast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像授权一次性收集页（参考开源 ScreenMirro/启源A06 的做法，全公开 API 无 root）：
 * 弹系统「立即开始」录屏授权，同意后把 result 存进 MirrorTok，立刻改走投屏动作。
 * 我们这里点授权 = 只为「镜像投屏」服务；授权被拒/没有弹窗都照实写日志。
 */
public class MirrorGrantActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final CastService svc = CastService.inst();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), 1);
        } catch (Throwable t) {
            if (svc != null)
                svc.log("弹不出系统录屏授权框：" + t.getClass().getSimpleName() + " " + t.getMessage());
            finish();
        }
    }

    @Deprecated // targetSdk 28，onActivityResult 就是这套 ROM 的正常路径
    @Override protected void onActivityResult(int rc, int code, Intent data) {
        super.onActivityResult(rc, code, data);
        CastService svc = CastService.inst();
        if (code == RESULT_OK && data != null) {
            MirrorTok.set(rc, data);
            if (svc != null) {
                svc.log("镜像授权已拿到，正在上仪表屏");
                svc.mirrorNow();
            }
        } else {
            if (svc != null) svc.log("镜像授权被取消，没动仪表屏");
        }
        finish();
    }
}

