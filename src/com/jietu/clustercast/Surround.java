package com.jietu.clustercast;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

/**
 * 环视单路取流（Camera2 标准设备）。这台车四路环视 id：4=前 5=右 6=左 7=后，
 * 1280x800；四路并发已实测与原车 360 互不影响（交付包 协议说明 §9.2）。
 * 权限没给、open 失败、收不到帧都如实上报，由调用方显示黑框，不卡页面。
 */
public final class Surround {

    public static final String TAG = "ClusterCast.Surround";

    /** 状态回报（权限缺失 / open 失败 / 流错误），调用方写进页面状态行。 */
    public interface Report { void onStatus(String s); }

    /** 运动检测回调（Smart 哨兵值守用）：帧差超阈值且连续两拍确认后触发。 */
    public interface MotionListener { void onMotion(); }

    private final Context ctx;
    private final int camId;
    private Report report;

    private CameraDevice cam;
    private CameraCaptureSession sess;
    private HandlerThread ht;
    private SurfaceTexture tex;
    /** 第二输出（录像合成纹理）：非空时 session 同时喂预览面和它。 */
    private volatile SurfaceTexture recTex;
    private boolean stopped = true;

    /** 原车会在一定车速回收环视相机，这里不断重连，保证画面常开。 */
    private static final long RECONNECT_DELAY_MS = 2000;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private boolean reconnectPending = false;

    /** 帧看门狗：静默断流（HAL 不回调任何错误）时只有靠"多久没出帧"才能发现。 */
    private static final long STALL_MS = 8000;
    private volatile long lastFrameAt = 0;
    private volatile long sessionStartedAt = 0;
    private boolean watchdogRunning = false;

    private final CameraCaptureSession.CaptureCallback frameCb =
            new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s,
                        CaptureRequest r, android.hardware.camera2.TotalCaptureResult res) {
                    lastFrameAt = System.currentTimeMillis();
                }
            };

    private final Runnable watchdogRun = new Runnable() {
        @Override public void run() {
            watchdogRunning = false;
            if (stopped || cam == null || sess == null) return;
            long now = System.currentTimeMillis();
            long base = lastFrameAt > 0 ? lastFrameAt : sessionStartedAt;
            if (now - base > STALL_MS) {
                say("超过 " + (STALL_MS / 1000) + " 秒无帧，重开相机");
                release();
                open();
                return;
            }
            scheduleWatchdog();
        }
    };

    private void scheduleWatchdog() {
        if (stopped || watchdogRunning) return;
        watchdogRunning = true;
        main.postDelayed(watchdogRun, 4000);
    }

    // ---------- 休眠闸门：跨休眠持有 Camera2 会话会把 HAL/ISP 卡死 ----------
    // （2026-10-02 实车：唤醒后我们四路黑屏，连原车倒车影像都黑，只能重启。）
    // 熄屏时全部释放、唤醒重开。只登记"正在出流"的实例，已 stop 的不归闸门管。

    private static final java.util.ArrayList<Surround> sAll =
            new java.util.ArrayList<Surround>();
    private static boolean sGateClosed = false;
    private boolean gateHeld = false;
    /** 闸门豁免（Smart 哨兵检测流）：熄屏照常出流、不登记闸门，
     *  生命周期完全由 SentinelController 管理（持锁值守系统不 suspend）。 */
    private volatile boolean gateExempt = false;

    public void setGateExempt(boolean exempt) { gateExempt = exempt; }

    private static void register(Surround s) {
        synchronized (sAll) { if (!sAll.contains(s)) sAll.add(s); }
    }

    private static void unregister(Surround s) {
        synchronized (sAll) { sAll.remove(s); }
    }

    /** 熄屏：停掉全部在出流的相机（记住谁在出流，唤醒自动重开）。 */
    public static void suspendAll() {
        Surround[] snap;
        synchronized (sAll) { sGateClosed = true; snap = sAll.toArray(new Surround[sAll.size()]); }
        for (Surround s : snap) s.suspendForGate();
        Log.i(TAG, "休眠闸门：已释放全部环视相机");
    }

    /** 唤醒：休眠前在出流的自动重开。 */
    public static void resumeAll() {
        Surround[] snap;
        synchronized (sAll) { sGateClosed = false; snap = sAll.toArray(new Surround[sAll.size()]); }
        for (Surround s : snap) s.resumeFromGate();
    }

    private void suspendForGate() {
        if (gateExempt) return;   // 哨兵检测流：熄屏照常出流
        if (stopped || gateHeld) return;
        gateHeld = true;
        stopped = true;
        main.removeCallbacks(reconnectRun);
        reconnectPending = false;
        release();
    }

    private void resumeFromGate() {
        if (!gateHeld) return;
        gateHeld = false;
        stopped = false;
        open();
    }

    public Surround(Context c, int id) {
        ctx = c;
        camId = id;
    }

    public void setReport(Report r) { report = r; }

    public int camId() { return camId; }

    private void say(String s) {
        Log.i(TAG, "cam" + camId + ": " + s);
        Report r = report;
        if (r != null) r.onStatus("摄像头 " + camId + "：" + s);
    }

    /** TextureView 的 surface 就绪后调；重复调安全。已在开着时换预览面 → 只重建会话。 */
    public void start(SurfaceTexture st) {
        if (!gateExempt) register(this);
        boolean changed = st != tex;
        tex = st;
        if (!stopped) {
            if (cam != null && changed) rebuildSession();
            return;
        }
        if (sGateClosed && !gateExempt) { gateHeld = true; return; }   // 熄屏期不碰相机，唤醒后 resumeAll 开
        stopped = false;
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("没有 CAMERA 权限（去设置页看授权方法）");
            return;
        }
        open();
    }

    /**
     * 挂/摘第二输出（录像合成纹理）。挂着时相机每帧同时进预览面和录像合成器；
     * 摘掉（null）恢复纯预览。已开着就重建会话，摄像头不重开。
     */
    public void setRecordTexture(SurfaceTexture st) {
        recTex = st;
        if (!stopped && cam != null) rebuildSession();
    }

    /** 只停预览、留着录像输出（退后台/切页签但录像继续时用）。 */
    public void keepRecordingOnly() {
        tex = null;
        if (!stopped && cam != null) rebuildSession();
    }

    // ---------- 运动检测输出（Smart 哨兵值守）----------
    // session 额外挂一个低分辨率 YUV ImageReader，帧差超阈值且连续两拍确认
    // 才回调 onMotion。检测流单独跑（tex/recTex 都为 null 时 session 只出
    // YUV，ISP 开销远低于全尺寸预览/录像）。

    private static final int MOTION_W = 320;
    private static final int MOTION_H = 240;
    /** 采样网格 64x48（步长 5），像素差阈值，变化比例阈值。 */
    private static final int SAMPLE_COLS = 64, SAMPLE_ROWS = 48, SAMPLE_STEP = 5;
    private static final int LUMA_DELTA = 24;
    private static final double MOTION_RATIO = 0.06;
    /** 两拍间隔节流与触发冷却。 */
    private static final long CHECK_INTERVAL_MS = 300;
    private static final long MOTION_COOLDOWN_MS = 30_000;

    private android.media.ImageReader motionReader;
    private MotionListener motionListener;
    private byte[] prevLuma = new byte[SAMPLE_COLS * SAMPLE_ROWS];
    private long lastCheckAt;
    private long lastMotionAt;
    private int motionStreak;

    /** 挂运动检测回调（在 start 前调；已开流时重建会话补挂 YUV target）。 */
    public void setMotionListener(MotionListener l) {
        motionListener = l;
        if (l != null && motionReader == null) {
            try {
                motionReader = android.media.ImageReader.newInstance(
                        MOTION_W, MOTION_H, android.graphics.ImageFormat.YUV_420_888, 2);
                motionReader.setOnImageAvailableListener(r -> {
                    android.media.Image img = null;
                    try { img = r.acquireLatestImage(); } catch (Throwable ignored) { }
                    if (img == null) return;
                    long now = System.currentTimeMillis();
                    boolean motion = now - lastCheckAt >= CHECK_INTERVAL_MS
                            && checkMotion(img);
                    lastCheckAt = now;
                    img.close();
                    if (motion && now - lastMotionAt >= MOTION_COOLDOWN_MS) {
                        lastMotionAt = now;
                        say("运动检测触发");
                        MotionListener ml = motionListener;
                        if (ml != null) ml.onMotion();
                    }
                }, null);
            } catch (Throwable t) {
                say("运动检测初始化失败：" + t);
                motionReader = null;
                motionListener = null;
            }
        }
        if (!stopped && cam != null) rebuildSession();
    }

    public void clearMotionListener() {
        MotionListener ml = motionListener;
        motionListener = null;
        if (motionReader != null) {
            try { motionReader.close(); } catch (Throwable ignored) { }
            motionReader = null;
            if (ml != null) say("运动检测流已摘除");
            if (!stopped && cam != null) rebuildSession();
        }
        motionStreak = 0;
    }

    /** Y 平面采样帧差。连续两拍超阈值才返回 true（防单帧噪声）。 */
    private boolean checkMotion(android.media.Image img) {
        try {
            android.media.Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer buf = p.getBuffer();
            int rowStride = p.getRowStride();
            int changed = 0;
            for (int j = 0; j < SAMPLE_ROWS; j++) {
                int rowOff = j * SAMPLE_STEP * rowStride;
                for (int i = 0; i < SAMPLE_COLS; i++) {
                    int off = rowOff + i * SAMPLE_STEP;
                    if (off >= buf.capacity()) continue;
                    byte cur = buf.get(off);
                    byte prev = prevLuma[j * SAMPLE_COLS + i];
                    prevLuma[j * SAMPLE_COLS + i] = cur;
                    if (Math.abs(cur - prev) > LUMA_DELTA) changed++;
                }
            }
            if (changed > (SAMPLE_COLS * SAMPLE_ROWS * MOTION_RATIO)) {
                motionStreak++;
                if (motionStreak >= 2) { motionStreak = 0; return true; }
            } else {
                motionStreak = 0;
            }
        } catch (Throwable t) {
            // 读帧失败不影响检测流本身
        }
        return false;
    }

    /** 真正开流；被系统断开/出错后由重连循环反复调，不跟随原车的速度回收。 */
    private void open() {
        lastFrameAt = 0;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics ch = cm.getCameraCharacteristics(String.valueOf(camId));
            StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            // 纯检测流（gateExempt，无预览纹理）没有 tex，跳过预览尺寸设置
            if (tex != null) {
                Size best = pick(map.getOutputSizes(SurfaceTexture.class), 1280, 800);
                tex.setDefaultBufferSize(best.getWidth(), best.getHeight());
            }
            ht = new HandlerThread("surround-" + camId);
            ht.start();
            Handler h = new Handler(ht.getLooper());
            cm.openCamera(String.valueOf(camId), new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (stopped) { c.close(); return; }
                    cam = c;
                    runSession();
                }
                @Override public void onDisconnected(CameraDevice c) {
                    say("连接被系统断开，2 秒后重连");
                    release();
                    scheduleReconnect();
                }
                @Override public void onError(CameraDevice c, int error) {
                    say("打开失败 error=" + error + "，2 秒后重试");
                    release();
                    scheduleReconnect();
                }
            }, h);
        } catch (SecurityException se) {
            say("权限被拒：" + se.getMessage());
            release();
        } catch (Throwable t) {
            say("打开失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            release();
            scheduleReconnect();
        }
    }

    private final Runnable reconnectRun = new Runnable() {
        @Override public void run() {
            reconnectPending = false;
            if (!stopped) open();
        }
    };

    private void scheduleReconnect() {
        if (stopped || reconnectPending) return;
        reconnectPending = true;
        main.postDelayed(reconnectRun, RECONNECT_DELAY_MS);
    }

    /** 关掉旧会话（不动 CameraDevice）再按当前 tex/recTex 重建。 */
    private void rebuildSession() {
        try { if (sess != null) sess.close(); } catch (Throwable ignored) { }
        sess = null;
        runSession();
    }

    private void runSession() {
        try {
            if (cam == null || (tex == null && recTex == null && motionReader == null)) return;
            sessionStartedAt = System.currentTimeMillis();
            java.util.List<Surface> targets = new java.util.ArrayList<>();
            if (tex != null) targets.add(new Surface(tex));
            // 录像面和预览面是同一个纹理时只挂一次（后台直接开录的场景）
            if (recTex != null && recTex != tex) targets.add(new Surface(recTex));
            if (motionReader != null) targets.add(motionReader.getSurface());
            cam.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (stopped) { s.close(); return; }
                            sess = s;
                            try {
                                CaptureRequest.Builder rb = cam.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                for (Surface sf : targets) rb.addTarget(sf);
                                rb.set(CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO);
                                sess.setRepeatingRequest(rb.build(), frameCb,
                                        new Handler(ht.getLooper()));
                                scheduleWatchdog();
                            } catch (Throwable t) {
                                say("下发预览失败：" + t);
                                release();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            say("会话配置失败");
                            release();
                        }
                    }, new Handler(ht.getLooper()));
        } catch (Throwable t) {
            say("建会话失败：" + t);
            release();
        }
    }

    /** TextureView 销毁 / 页面离开时调，幂等。_owner 主动 stop 会取消闸门登记
     *  （唤醒不再重开它）；闸门自己停的走 suspendForGate，不走这里。 */
    public void stop() {
        stopped = true;
        gateHeld = false;
        main.removeCallbacks(reconnectRun);
        reconnectPending = false;
        release();
        unregister(this);
    }

    private synchronized void release() {
        main.removeCallbacks(watchdogRun);
        watchdogRunning = false;
        try { if (sess != null) sess.close(); } catch (Throwable ignored) { }
        sess = null;
        try { if (cam != null) cam.close(); } catch (Throwable ignored) { }
        cam = null;
        if (motionReader != null) {
            try { motionReader.close(); } catch (Throwable ignored) { }
            motionReader = null;
        }
        if (ht != null) {
            ht.quitSafely();
            ht = null;
        }
    }

    /** 挑最接近目标尺寸的输出档，拿不到就取第一个。 */
    private static Size pick(Size[] sizes, int tw, int th) {
        if (sizes == null || sizes.length == 0) return new Size(tw, th);
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long diff = Math.abs((long) s.getWidth() * th - (long) tw * s.getHeight())
                    + Math.abs(s.getWidth() - tw);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best;
    }
}
