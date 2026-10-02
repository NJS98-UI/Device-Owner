package com.ahui.camprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 环视取流探测：只回答"能不能 open、帧到不到、挂 R 时会不会被抢"。 */
public class MainActivity extends Activity {

    static final String TAG = "CamProbe";
    static final String[] IDS = { "4", "5", "6", "7" };
    static final String[] NAMES = { "前 id4", "右 id5", "左 id6", "后 id7" };

    HandlerThread thread;
    Handler bg;
    CameraManager mgr;
    final List<Cell> cells = new ArrayList<Cell>();
    TextView header;

    static class Cell {
        String id;
        String name;
        TextureView view;
        TextView label;
        CameraDevice device;
        CameraCaptureSession session;
        String state = "INIT";
        long frames;
        long lastTick;
    }

    static class CamCb extends CameraDevice.StateCallback {
        final MainActivity a;
        CamCb(MainActivity a) { this.a = a; }
        @Override public void onOpened(CameraDevice d) { a.onOpened(d); }
        @Override public void onDisconnected(CameraDevice d) { a.onGone(d, "DISCONNECTED"); }
        @Override public void onError(CameraDevice d, int e) { a.onErr(d, e); }
    }

    static class SessCb extends CameraCaptureSession.StateCallback {
        final MainActivity a;
        final Cell c;
        SessCb(MainActivity a, Cell c) { this.a = a; this.c = c; }
        @Override public void onConfigured(CameraCaptureSession s) { a.onConfigured(c, s); }
        @Override public void onConfigureFailed(CameraCaptureSession s) { a.set(c, "SESSION_FAILED"); }
    }

    static class FrameCb extends CameraCaptureSession.CaptureCallback {
        final MainActivity a;
        final Cell c;
        FrameCb(MainActivity a, Cell c) { this.a = a; this.c = c; }
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                                 TotalCaptureResult res) {
            c.frames++;
            long now = System.currentTimeMillis();
            if (now - c.lastTick >= 1000) {
                c.lastTick = now;
                a.set(c, c.state);
            }
        }
    }

    static class SurfCb implements TextureView.SurfaceTextureListener {
        final MainActivity a;
        final Cell c;
        SurfCb(MainActivity a, Cell c) { this.a = a; this.c = c; }
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { a.open(c); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        thread = new HandlerThread("cam");
        thread.start();
        bg = new Handler(thread.getLooper());
        mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        header = new TextView(this);
        header.setTextColor(Color.YELLOW);
        header.setTextSize(15f);
        header.setPadding(16, 6, 16, 6);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row1 = row(root);
        LinearLayout row2 = row(root);
        for (int i = 0; i < 4; i++) {
            Cell c = new Cell();
            c.id = IDS[i];
            c.name = NAMES[i];
            FrameLayout box = new FrameLayout(this);
            c.view = new TextureView(this);
            box.addView(c.view, new FrameLayout.LayoutParams(-1, -1));
            c.label = new TextView(this);
            c.label.setTextColor(Color.WHITE);
            c.label.setBackgroundColor(0xB0000000);
            c.label.setTextSize(15f);
            c.label.setPadding(10, 4, 10, 4);
            box.addView(c.label, new FrameLayout.LayoutParams(-2, -2));
            (i < 2 ? row1 : row2).addView(box, new LinearLayout.LayoutParams(0, -1, 1f));
            c.view.setSurfaceTextureListener(new SurfCb(this, c));
            cells.add(c);
        }
        setContentView(root);

        dumpIds();
        int p = checkSelfPermission("android.permission.CAMERA");
        setHeader("CAMERA grant=" + (p == 0 ? "GRANTED" : ("DENIED(" + p + ")"))
                + "\nP档：四格都要 STREAMING 且 frames 上涨  →  挂R保持30s：盯原车360有没有黑/花  →  退R：frames 要能恢复");
        if (p != 0) requestPermissions(new String[] { "android.permission.CAMERA" }, 1);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        return r;
    }

    private void dumpIds() {
        try {
            StringBuilder sb = new StringBuilder("cameraIds:");
            for (String id : mgr.getCameraIdList()) {
                Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                sb.append(" ").append(id).append("(facing=").append(f).append(")");
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "dumpIds", t);
        }
    }

    void open(Cell c) {
        if (c.device != null) return;
        if (checkSelfPermission("android.permission.CAMERA") != 0) {
            set(c, "NO_PERM");
            return;
        }
        try {
            Size s = pickSize(c.id);
            c.view.getSurfaceTexture().setDefaultBufferSize(s.getWidth(), s.getHeight());
            set(c, "OPENING " + s.getWidth() + "x" + s.getHeight());
            mgr.openCamera(c.id, new CamCb(this), bg);
        } catch (Throwable t) {
            set(c, "OPEN_THROW " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.e(TAG, "open " + c.id, t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] grants) {
        int g = (grants != null && grants.length > 0) ? grants[0] : -1;
        Log.i(TAG, "permResult rc=" + rc + " grant=" + g);
        setHeader("授权返回 grant=" + g + (g == 0 ? " (GRANTED)，重新 open" : " (还是没给)"));
        if (g == 0) bg.post(() -> {
            for (Cell c : cells) if (c.device == null && c.view.isAvailable()) open(c);
        });
    }

    private Size pickSize(String id) throws Exception {
        StreamConfigurationMap m = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (m == null) return new Size(1280, 800);
        Size[] ss = m.getOutputSizes(SurfaceTexture.class);
        if (ss == null || ss.length == 0) return new Size(1280, 800);
        for (Size s : ss) if (s.getWidth() == 1280 && s.getHeight() == 800) return s;
        Size best = null;
        for (Size s : ss) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) for (Size s : ss) if (best == null || area(s) > area(best)) best = s;
        return best != null ? best : new Size(1280, 800);
    }

    private static long area(Size s) { return (long) s.getWidth() * s.getHeight(); }

    Cell byId(String id) {
        for (Cell c : cells) if (c.id.equals(id)) return c;
        return null;
    }

    void onOpened(CameraDevice d) {
        Cell c = byId(d.getId());
        if (c == null) { d.close(); return; }
        c.device = d;
        set(c, "OPEN_OK");
        try {
            List<Surface> outs = new ArrayList<Surface>();
            outs.add(new Surface(c.view.getSurfaceTexture()));
            d.createCaptureSession(outs, new SessCb(this, c), bg);
        } catch (Throwable t) {
            set(c, "SESS_THROW " + t.getMessage());
            Log.e(TAG, "session " + c.id, t);
        }
    }

    void onConfigured(Cell c, CameraCaptureSession s) {
        c.session = s;
        try {
            CaptureRequest.Builder b = c.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(new Surface(c.view.getSurfaceTexture()));
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            s.setRepeatingRequest(b.build(), new FrameCb(this, c), bg);
            c.lastTick = System.currentTimeMillis();
            set(c, "STREAMING");
        } catch (Throwable t) {
            set(c, "REPEAT_THROW " + t.getMessage());
            Log.e(TAG, "repeat " + c.id, t);
        }
    }

    void onGone(CameraDevice d, String what) {
        Cell c = byId(d.getId());
        d.close();
        if (c != null) {
            c.device = null;
            c.session = null;
            set(c, what);
        }
    }

    void onErr(CameraDevice d, int e) {
        Cell c = d == null ? null : byId(d.getId());
        String msg = "ERR=" + e + "(" + errName(e) + ")";
        Log.e(TAG, (c == null ? "no-cell" : c.name) + " " + msg);
        if (c != null) {
            c.device = null;
            c.session = null;
            set(c, msg);
        }
    }

    static String errName(int e) {
        if (e == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) return "IN_USE";
        if (e == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) return "MAX_IN_USE";
        if (e == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) return "DISABLED";
        if (e == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE) return "DEVICE";
        if (e == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE) return "SERVICE";
        return "UNKNOWN";
    }

    void set(final Cell c, String state) {
        c.state = state;
        final String txt = c.name + "  " + state + "  frames=" + c.frames;
        Log.i(TAG, txt);
        runOnUiThread(() -> { if (c.label != null) c.label.setText(txt); });
    }

    void setHeader(final String s) {
        Log.i(TAG, "HEADER " + s.replace('\n', ' '));
        runOnUiThread(() -> { if (header != null) header.setText(s); });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Cell c : cells) {
            try { if (c.session != null) c.session.close(); } catch (Throwable t) { }
            try { if (c.device != null) c.device.close(); } catch (Throwable t) { }
        }
        if (thread != null) thread.quitSafely();
    }
}
