package com.ahui.clustercast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 录制状态悬浮按钮（EVCam 悬浮窗快捷入口的自写版）：
 * 红=没在录，绿闪=录像中；单击打开主界面，按住拖动挪位置。
 * 挂载失败（没有悬浮窗权限）写日志自杀，绝不闪退。
 */
public class RecFloat extends Service {

    private WindowManager wm;
    private FrameLayout root = null;
    private View dot = null;
    private TextView label = null;
    private GradientDrawable paint = null;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Tick tick;
    private boolean blinkOn = true;

    private static class Tick implements Runnable {
        private final WeakReference<RecFloat> ref;
        Tick(RecFloat s) { ref = new WeakReference<>(s); }
        @Override public void run() {
            RecFloat s = ref.get();
            if (s == null) return;
            s.refresh();
            s.h.postDelayed(this, 800);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            build();
        } catch (Throwable t) {
            fail("挂载失败：" + t.getClass().getSimpleName());
            return;
        }
        tick = new Tick(this);
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                        new NotificationChannel("recfloat", "录制悬浮钮",
                                NotificationManager.IMPORTANCE_MIN));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "recfloat")
                    : new Notification.Builder(this);
            b.setContentTitle("录制悬浮钮运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_upload);
            startForeground(44, b.build());
        } catch (Throwable t) {
            CastService cs = CastService.inst();
            if (cs != null) cs.log("悬浮钮通知起不来：" + t.getClass().getSimpleName());
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        try { if (root != null) wm.removeView(root); } catch (Throwable ignored) { }
        root = null;
        if (sInst == this) sInst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void build() {
        final int size = dp(46);
        final FrameLayout fl = new FrameLayout(this);
        paint = new GradientDrawable();
        paint.setShape(GradientDrawable.OVAL);
        paint.setColor(0xCCB3261E);
        dot = new View(this);
        dot.setBackground(paint);
        fl.addView(dot, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        label = Ui.text(this, 11, Color.WHITE, Typeface.BOLD, 1);
        label.setGravity(Gravity.CENTER);
        fl.addView(label, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        fl.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean moved = false;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = pos.x; startY = pos.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        if (moved) {
                            pos.x = (int) (startX + dx);
                            pos.y = (int) (startY + dy);
                            try { wm.updateViewLayout(root, pos); } catch (Throwable t) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) openMain();
                        return true;
                    default:
                        return false;
                }
            }
        });

        final WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        int w = getResources().getDisplayMetrics().widthPixels;
        pos = p;
        pos.x = w - size - dp(16);
        pos.y = dp(160);
        wm.addView(fl, p);
        root = fl;
    }

    private WindowManager.LayoutParams pos = null;

    private void openMain() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            fail("打开主界面失败：" + t.getClass().getSimpleName());
        }
    }

    private void refresh() {
        CastService cs = CastService.inst();
        boolean rec = cs != null && cs.dvrRecording();
        label.setText(rec ? "REC" : "");
        if (rec) {
            blinkOn = !blinkOn;
            paint.setColor(blinkOn ? 0xFF1FA84F : 0xFF116633);
        } else {
            paint.setColor(0xCCB3261E);
        }
        try { dot.invalidate(); } catch (Throwable t) { }
    }

    private void fail(final String why) {
        CastService cs = CastService.inst();
        if (cs != null) cs.log("录制悬浮钮：" + why);
        stopSelf();
    }

    private static volatile RecFloat sInst = null;

    static boolean alive() { return sInst != null; }

    static boolean canDraw(Context c) {
        try { return android.provider.Settings.canDrawOverlays(c); }
        catch (Throwable t) { return false; }
    }

    static void show(Context c) {
        if (sInst != null) return;
        try { c.startService(new Intent(c, RecFloat.class)); } catch (Throwable t) { }
    }

    static void hide(Context c) {
        if (sInst == null) return;
        try { c.stopService(new Intent(c, RecFloat.class)); } catch (Throwable t) { }
    }
}
