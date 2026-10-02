package com.kooo.evcam.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 四合一合成录制器：4 路 OES SurfaceTexture → 2×2 视口合成一帧 →
 * MediaCodec 硬编 → MediaMuxer 输出 mp4。
 * 支持分段（setSegmentDurationMs）：到时在 handler 线程上收掉旧段
 * （EOS→drain→muxer.stop），换新 codec+封装器继续录，OES 纹理/SurfaceTexture/
 * GL 程序都留在同一个 EGL context 里复用，相机输入面全程不断流。
 * 分段文件命名：quad_2026-1001-0805.mp4（每段用自己开始那分钟命名），
 * 同一分钟已有同名文件时补秒（quad_2026-1001-0805-42）避免覆盖。
 */
public class QuadComposer {
    private static final String TAG = "QuadComposer";
    private static final String MIME = "video/avc";

    /** 取一个新段名（不含 quad_ 前缀和扩展名）：分钟级时间戳。 */
    public static String newSegmentName(File dir) {
        java.util.Date now = new java.util.Date();
        String base = new java.text.SimpleDateFormat(
                "yyyy-MMdd-HHmm", Locale.US).format(now);
        if (!new File(dir, "quad_" + base + ".mp4").exists()) return base;
        return base + "-" + new java.text.SimpleDateFormat(
                "ss", Locale.US).format(now);
    }

    public interface Callback {
        void onStarted(String firstFilePath);
        void onStopped(String lastFilePath);
        void onError(String error);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // 全屏四边形：aPosition(x,y) + aTextureCoord(u,v)，交错
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
    };

    private final Object lock = new Object();

    private HandlerThread thread;
    private volatile Handler handler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private final int[] oesTex = new int[4];
    private final SurfaceTexture[] stex = new SurfaceTexture[4];
    private final Surface[] inputs = new Surface[4];
    private final boolean[] pending = new boolean[4];
    private final float[][] lastMatrix = new float[4][16];
    private final boolean[] hasImage = new boolean[4];
    private final float[] texMatrix = new float[16];
    private FloatBuffer quadBuf;

    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uTexMatrix;
    private int uSampler;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxerStarted;
    private MediaFormat savedFormat;
    private boolean stopping;

    private volatile boolean running;
    private String currentPath;
    private Callback callback;

    public void setCallback(Callback cb) {
        callback = cb;
    }

    private long segmentMs;
    private int pendingBitrate;
    private int pendingFps;
    private long segmentStartNs;

    /** 分段时长毫秒；0=不分段。start() 之前调用。 */
    public void setSegmentDurationMs(long ms) {
        segmentMs = ms;
    }

    public boolean isRunning() {
        return running;
    }

    public String getCurrentPath() {
        synchronized (lock) {
            return currentPath;
        }
    }

    /** 取某路的输入面（pos: 0=前 1=后 2=左 3=右），start() 成功后调用。 */
    public Surface getInputSurface(int pos) {
        return inputs[pos];
    }

    /** 取某路的合成输入纹理：相机侧（Surround）用 setRecordTexture 把帧喂进来。 */
    public SurfaceTexture getInputTexture(int pos) {
        return stex[pos];
    }

    public void start(Context ctx, String dir, String name,
                      int width, int height, int bitrate, int fps) throws IOException {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("QuadComposer already running");
            }
        }
        final File outFile = new File(dir, name + ".mp4");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        thread = new HandlerThread(TAG);
        thread.start();
        Handler h = new Handler(thread.getLooper());
        handler = h;
        final Throwable[] err = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final int w = width, ht = height, br = bitrate, fr = fps;
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    setupEgl(w, ht, outFile);
                    setupCodec(w, ht, br, fr, outFile);
                    synchronized (lock) {
                        currentPath = outFile.getAbsolutePath();
                        running = true;
                    }
                    if (callback != null) {
                        callback.onStarted(outFile.getAbsolutePath());
                    }
                } catch (Throwable t) {
                    err[0] = t;
                    releaseEglLocked();
                    HandlerThread dead = thread;
                    if (dead != null) {
                        dead.quitSafely();
                        thread = null;
                        handler = null;
                    }
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("QuadComposer start timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("QuadComposer start interrupted", e);
        }
        if (err[0] != null) {
            throw err[0] instanceof IOException
                    ? (IOException) err[0] : new IOException(err[0]);
        }
    }

    public void stop() {
        Handler h = handler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override public void run() {
                shutdownLocked();
            }
        });
    }

    public void release() {
        stop();
        HandlerThread t = thread;
        if (t != null) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    // ---------- 渲染线程内部 ----------

    private void setupEgl(int width, int height, File outFile) throws IOException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IOException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IOException("eglInitialize failed");
        }
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, num, 0)
                || num[0] < 1) {
            throw new IOException("no suitable EGLConfig");
        }
        eglConfigs[0] = configs[0];
        int[] ctxAttrib = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IOException("eglCreateContext failed");
        }
        // codecInputSurface 由 setupCodec 创建后再建 EGL 窗口面
        int[] surfAttrib = {EGL14.EGL_NONE};
        eglSurface = EGL14.EGL_NO_SURFACE;
        pendingOutFile = outFile;
        pendingWidth = width;
        pendingHeight = height;
    }

    private File pendingOutFile;
    private int pendingWidth;
    private int pendingHeight;

    private void setupGl() {
        program = buildProgram();
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uSampler = GLES20.glGetUniformLocation(program, "sTexture");
        quadBuf = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuf.put(QUAD).position(0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void setupCodec(int width, int height, int bitrate, int fps,
                            File outFile) throws IOException {
        pendingBitrate = bitrate;
        pendingFps = fps;
        segmentStartNs = System.nanoTime();
        openSegment(outFile);
        setupGl();
        createInputs(width, height);
        handler.post(renderLoop);
    }

    /** 起一段：新 codec + 输入面 + EGL 窗口面 + 新封装器。要求 EGL context 已就绪。 */
    private void openSegment(File outFile) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, pendingWidth, pendingHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, pendingBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, pendingFps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface input = codec.createInputSurface();
        codec.start();

        int[] surfAttrib = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfigs[0],
                input, surfAttrib, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IOException("eglCreateWindowSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("eglMakeCurrent failed");
        }
        muxer = new MediaMuxer(outFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        synchronized (lock) {
            currentPath = outFile.getAbsolutePath();
        }
        segmentStartNs = System.nanoTime();
    }

    private EGLConfig[] eglConfigs = new EGLConfig[1];

    private void createInputs(int width, int height) {
        for (int i = 0; i < 4; i++) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            oesTex[i] = tex[0];
            SurfaceTexture st = new SurfaceTexture(tex[0]);
            st.setDefaultBufferSize(width / 2, height / 2);
            st.setOnFrameAvailableListener(new FrameCb(i), handler);
            stex[i] = st;
            inputs[i] = new Surface(st);
        }
    }

    private class FrameCb implements SurfaceTexture.OnFrameAvailableListener {
        final int pos;
        FrameCb(int p) { pos = p; }
        @Override public void onFrameAvailable(SurfaceTexture t) {
            synchronized (lock) {
                pending[pos] = true;
            }
        }
    }

    private final Runnable renderLoop = new Runnable() {
        @Override public void run() {
            if (!running || stopping) {
                return;
            }
            // 新到的相机帧只做 updateTexImage（取最新变换矩阵），画面本身
            // 每拍都全量重画四象限 —— 编码器拿到的是恒定 30fps 的时间戳，
            // 回放才不会跳（按事件渲染会让 mp4 帧间距忽长忽短）
            synchronized (lock) {
                for (int i = 0; i < 4; i++) {
                    if (!pending[i]) {
                        continue;
                    }
                    pending[i] = false;
                    try {
                        stex[i].updateTexImage();
                        stex[i].getTransformMatrix(lastMatrix[i]);
                        hasImage[i] = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "updateTexImage cam" + i + " failed", t);
                    }
                }
            }
            drawFrame();
            drainCodec(false);
            // 到分段时长就切：收尾旧段、开新段（相机不断流）。
            // 不能加"本段有相机画面"的前置条件——某路相机断流/平台无
            // cam4-7 时画面永远不来，分段会被卡死成单文件无限录。
            // 编码帧本身由 renderLoop 每拍重画产生，与相机断流无关。
            if (segmentMs > 0
                    && System.nanoTime() - segmentStartNs >= segmentMs * 1000000L) {
                rotateSegment();
                if (!running || stopping) {
                    return;
                }
            }
            handler.postDelayed(this, 33);
        }
    };

    /** 分段切换：旧段 EOS→drain→muxer.stop 落盘，再起新 codec+封装器。 */
    private void rotateSegment() {
        try {
            try {
                if (codec != null) codec.signalEndOfInputStream();
                drainCodec(true);
            } catch (Throwable t) {
                Log.e(TAG, "rotate: EOS/drain failed", t);
            }
            synchronized (lock) {
                try {
                    if (muxer != null && muxerStarted) {
                        muxer.stop();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "rotate: muxer.stop failed", t);
                }
                try {
                    if (muxer != null) muxer.release();
                } catch (Throwable ignored) { }
                muxer = null;
                muxerStarted = false;
                track = -1;
            }
            try {
                if (codec != null) codec.stop();
            } catch (Throwable ignored) { }
            try {
                if (codec != null) codec.release();
            } catch (Throwable ignored) { }
            codec = null;
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            // 新段用自己开录的时刻命名（quad_2026-1001-0805），每分钟
            // 一个新文件，回放列表各自一条记录
            File dir = pendingOutFile.getParentFile();
            File next = new File(dir, "quad_" + newSegmentName(dir) + ".mp4");
            openSegment(next);
            Log.i(TAG, "segment rotated -> " + next.getName());
            if (callback != null) {
                callback.onStarted(next.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.e(TAG, "rotateSegment failed", t);
            running = false;
            if (callback != null) {
                callback.onError("分段切换失败: " + t);
            }
            shutdownLocked();
        }
    }

    private void drawFrame() {
        GLES20.glClearColor(0.05f, 0.05f, 0.07f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int vw = pendingWidth / 2;
        int vh = pendingHeight / 2;
        for (int i = 0; i < 4; i++) {
            if (!hasImage[i]) {
                continue;
            }
            // 视口：0=左上 1=右上 2=左下 3=右下
            int vx = (i % 2) * vw;
            int vy = (1 - (i / 2)) * vh;
            GLES20.glViewport(vx, vy, vw, vh);
            currentTex = oesTex[i];
            drawQuad(lastMatrix[i]);
        }
        GLES20.glViewport(0, 0, pendingWidth, pendingHeight);
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            // 编码面异常，交由 drain/stop 收尾
        }
    }

    private void drawQuad(float[] tm) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, currentTex);
        GLES20.glUniform1i(uSampler, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, tm, 0);
        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    private int currentTex;

    private void drawQuadAt(int pos, float[] tm) {
        currentTex = oesTex[pos];
        drawQuad(tm);
    }

    private int buildProgram() {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(p);
            throw new RuntimeException("program link failed: " + log);
        }
        return p;
    }

    private static int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] status = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return s;
    }

    private void drainCodec(boolean eos) {
        if (codec == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // eos 模式必须限时：部分车机编码器 surface 输入不给 EOS 输出，
        // 死等会把渲染线程卡死 → 分段永远不会切换（实车踩到）
        long deadline = eos ? System.nanoTime() + 500_000_000L : 0;
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, eos ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) {
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    Log.w(TAG, "EOS drain timeout, finalize segment anyway");
                    break;
                }
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) {
                    savedFormat = codec.getOutputFormat();
                    if (muxer != null && !muxerStarted) {
                        track = muxer.addTrack(savedFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                }
                continue;
            } else if (idx < 0) {
                continue;
            }
            ByteBuffer out = codec.getOutputBuffer(idx);
            if (out != null && info.size > 0) {
                synchronized (lock) {
                    if (muxer != null && muxerStarted) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(track, out, info);
                    }
                }
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }

    private void shutdownLocked() {
        if (stopping) return;   // stop 可能被连调多次（界面+服务侧），只收一次尾
        stopping = true;
        running = false;
        try {
            if (codec != null) {
                codec.signalEndOfInputStream();
                drainCodec(true);
                android.util.Log.i(TAG, "EOS signaled and drained");
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "EOS/drain failed", t);
        }
        String last = null;
        synchronized (lock) {
            last = currentPath;
            try {
                if (muxer != null && muxerStarted) {
                    muxer.stop();
                    android.util.Log.i(TAG, "muxer.stop ok, moov written");
                } else {
                    android.util.Log.w(TAG, "muxer.stop skipped: muxer=" + (muxer != null)
                            + " started=" + muxerStarted);
                }
            } catch (Throwable t) {
                android.util.Log.e(TAG, "muxer.stop failed", t);
            }
            releaseLocked();
        }
        releaseEglLocked();
        if (callback != null) {
            callback.onStopped(last);
        }
        HandlerThread t = thread;
        if (t != null) {
            t.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private void releaseLocked() {
        try {
            if (codec != null) {
                codec.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (codec != null) {
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        codec = null;
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Throwable ignored) {
        }
        muxer = null;
        muxerStarted = false;
        track = -1;
        for (int i = 0; i < 4; i++) {
            try {
                if (inputs[i] != null) {
                    inputs[i].release();
                }
            } catch (Throwable ignored) {
            }
            inputs[i] = null;
            try {
                if (stex[i] != null) {
                    stex[i].release();
                }
            } catch (Throwable ignored) {
            }
            stex[i] = null;
        }
    }

    private void releaseEglLocked() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignored) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }
}
