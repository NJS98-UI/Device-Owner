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
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.RecordCallback;
import com.kooo.evcam.camera.VideoRecorder;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 行车记录仪：DVR 摄像头（id 2）预览 + EVCam 录制引擎分段录像。
 * 录制引擎（VideoRecorder 分段/watchdog/损坏文件清理）来自 EVCam 原样移植；
 * 本类只做取流适配：预览 Surface + 录制 Surface 同会话，分段切换时按
 * EVCam MultiCameraManager 的时序重建会话（onSegmentSwitch → 重建 →
 * onConfigured 里 startRecording 拉起下一段）。
 */
public final class DvrManager {

    public static final String TAG = "ClusterCast.Dvr";

    public interface Report { void onStatus(String s); }

    private final Context ctx;
    private final Report report;

    private CameraDevice cam;
    private CameraCaptureSession sess;
    private HandlerThread ht;
    private Handler bg;
    private SurfaceTexture previewTex;
    private Size previewSize;
    private boolean stopped = true;

    private VideoRecorder recorder;
    private Surface recordSurface;
    private boolean recording = false;
    private boolean pendingStart = false;
    private long segmentMs = 60000;

    /** 原车会在一定车速回收相机，这里不断重连，不跟随原车的关闭策略。 */
    private static final long RECONNECT_DELAY_MS = 2000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean reconnectPending = false;

    public DvrManager(Context c, Report r) {
        ctx = c;
        report = r;
    }

    private void say(String s) {
        Log.i(TAG, s);
        report.onStatus(s);
    }

    /** TextureView surface 就绪后调。 */
    public void attach(SurfaceTexture st) {
        previewTex = st;
        if (stopped) return;
        openCamera();
    }

    /** 页面可见时调（幂等）。 */
    public void resume() {
        if (!stopped) return;
        stopped = false;
        if (previewTex != null) openCamera();
    }

    /** 页面离开时调：停录制、关相机。 */
    public void pause() {
        stopped = true;
        main.removeCallbacks(reconnectRun);
        reconnectPending = false;
        stopRecordingInternal(true);
        releaseCam();
    }

    public void setSegmentMs(long ms) {
        segmentMs = ms;
        if (recorder != null && recording) recorder.setSegmentDuration(ms);
    }

    public boolean isRecording() { return recording; }

    // ---------- 相机 ----------

    private void openCamera() {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("没有 CAMERA 权限");
            return;
        }
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics ch = cm.getCameraCharacteristics(DvrIds.ID);
            StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = pick(map.getOutputSizes(SurfaceTexture.class), 1280, 800);
            previewTex.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (ht == null) {
                ht = new HandlerThread("dvr");
                ht.start();
                bg = new Handler(ht.getLooper());
            }
            cm.openCamera(DvrIds.ID, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (stopped) { c.close(); return; }
                    cam = c;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice c) {
                    say("相机被系统断开，2 秒后重连");
                    releaseCam();
                    scheduleReconnect();
                }
                @Override public void onError(CameraDevice c, int error) {
                    say("相机打开失败 error=" + error + "，2 秒后重试");
                    releaseCam();
                    scheduleReconnect();
                }
            }, bg);
        } catch (Throwable t) {
            say("相机打开失败：" + t);
            releaseCam();
            scheduleReconnect();
        }
    }

    private final Runnable reconnectRun = new Runnable() {
        @Override public void run() {
            reconnectPending = false;
            if (stopped) return;
            // 录像中掉线：重新挂上编码器 Surface 恢复录像
            if (recorder != null && recording && recordSurface == null) {
                recordSurface = recorder.getSurface();
            }
            openCamera();
        }
    };

    private void scheduleReconnect() {
        if (stopped || reconnectPending) return;
        reconnectPending = true;
        main.postDelayed(reconnectRun, RECONNECT_DELAY_MS);
    }

    private void createSession() {
        closeSession();
        try {
            List<Surface> outs = new ArrayList<>();
            outs.add(new Surface(previewTex));
            if (recordSurface != null && recordSurface.isValid()) outs.add(recordSurface);
            cam.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (stopped) { try { s.close(); } catch (Throwable ignored) { } return; }
                    sess = s;
                    try {
                        CaptureRequest.Builder rb = cam.createCaptureRequest(
                                recordSurface != null
                                        ? CameraDevice.TEMPLATE_RECORD
                                        : CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(new Surface(previewTex));
                        if (recordSurface != null) rb.addTarget(recordSurface);
                        rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        sess.setRepeatingRequest(rb.build(), null, bg);
                        // EVCam 时序：会话就绪后先拉起等待中的下一段，再拉起首次录制
                        if (recorder != null && recorder.isWaitingForSessionReconfiguration()) {
                            recorder.clearWaitingForSessionReconfiguration();
                            recorder.startRecording();
                        } else if (pendingStart) {
                            pendingStart = false;
                            if (!recorder.startRecording()) {
                                say("录像启动失败");
                                recorder = null;
                                recordSurface = null;
                            }
                        }
                        say(recordSurface != null ? "录制中" : "预览中（" + previewSize.getWidth()
                                + "x" + previewSize.getHeight() + "）");
                    } catch (Throwable t) {
                        say("启动预览失败：" + t);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    say("会话配置失败");
                }
            }, bg);
        } catch (Throwable t) {
            say("建会话失败：" + t);
        }
    }

    private void closeSession() {
        try { if (sess != null) sess.close(); } catch (Throwable ignored) { }
        sess = null;
    }

    private void releaseCam() {
        closeSession();
        try { if (cam != null) cam.close(); } catch (Throwable ignored) { }
        cam = null;
        if (ht != null) {
            ht.quitSafely();
            ht = null;
            bg = null;
        }
    }

    // ---------- 录制（EVCam VideoRecorder） ----------

    /** 开始录像：预览没起来就先只起预览，下个周期用户再点。 */
    public void startRecording() {
        if (recording) { say("已经在录像了"); return; }
        if (cam == null || sess == null) {
            say("相机还没就绪，稍后再试");
            return;
        }
        File dir = StorageHelper.getVideoDir(ctx, false);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String path = new File(dir, ts + "_dvr.mp4").getAbsolutePath();
        recorder = new VideoRecorder(DvrIds.ID);
        recorder.setSegmentDuration(segmentMs);
        recorder.setCallback(new RecordCallback() {
            @Override public void onRecordStart(String cameraId) {
                recording = true;
                say("录像开始（分段 " + (segmentMs / 1000) + " 秒）");
            }
            @Override public void onRecordStop(String cameraId) {
                recording = false;
                say("录像已停止");
            }
            @Override public void onRecordError(String cameraId, String error) {
                recording = false;
                recordSurface = null;
                if (!stopped) createSession();
                say("录像出错：" + error);
            }
            @Override public void onPrepareSegmentSwitch(String cameraId, int idx) {
                // EVCam 约定：先停掉向旧 Surface 送帧，VideoRecorder 等 50ms 再 stop
                try { if (sess != null) sess.stopRepeating(); } catch (Throwable ignored) { }
            }
            @Override public void onSegmentSwitch(String cameraId, int newIdx, String donePath) {
                recordSurface = recorder.getSurface();
                if (!stopped) createSession(); // onConfigured 里 startRecording 拉起下一段
            }
            @Override public void onCorruptedFilesDeleted(String cameraId, List<String> files) {
                say("已删除 " + files.size() + " 个损坏分段");
            }
            @Override public void onRecordingRebuildRequested(String cameraId, String reason) {
                say("录制异常（" + reason + "），停止录制");
                recording = false;
                recordSurface = null;
                if (!stopped) createSession();
            }
            @Override public void onFirstDataWritten(String cameraId) { }
        });
        if (!recorder.prepareRecording(path, previewSize.getWidth(), previewSize.getHeight())) {
            say("录制器准备失败（编码器不可用？）");
            recorder = null;
            return;
        }
        recordSurface = recorder.getSurface();
        pendingStart = true;
        createSession(); // onConfigured（会话就绪）后统一在这里 startRecording，与 冥城记录仪 一致
    }

    public void stopRecording() {
        stopRecordingInternal(false);
    }

    private void stopRecordingInternal(boolean leaving) {
        VideoRecorder r = recorder;
        recorder = null;
        recording = false;
        pendingStart = false;
        recordSurface = null;
        if (r != null) {
            String lastPath = r.getCurrentFilePath();
            r.stopRecording();
            r.release();
            say("录像已停止" + (lastPath != null ? "，最后分段：" + lastPath : ""));
        }
        if (!leaving && !stopped && cam != null) createSession(); // 回到纯预览
    }

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

    /** DVR 摄像头 id（工程模式实测：0=DMS 1=OMS 2=DVR 4~7=环视）。 */
    private static final class DvrIds {
        static final String ID = "2";
    }
}
