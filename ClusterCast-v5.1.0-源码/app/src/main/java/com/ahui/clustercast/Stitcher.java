package com.ahui.clustercast;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 四宫格拼接层：四路摄像头 → 一个 1280x800 画面 → 一个录像文件。
 *
 * 这是纯 GLES20 + EGL14 的正常应用做法，不需要 root、不需要任何系统权限：
 * 每一路摄像头挂一个 ExternalTexture（作为该路 Camera2 会话的第二输出），
 * 谁来了新帧就在 GL 线程重画一次 2x2，把结果 swap 到 QuadRec 的编码器输入面上 ——
 * 编码器看到的就是一张拼好的整画。
 *
 * 四格的摆法跟界面一致：左上=前、右上=后、左下=左、右下=右。
 * 哪一路没出画，它的格子就停在最后一帧（或黑底），不画假画面。
 *
 * 任一环节抛异常（这台 8155 的驱动不给 EGL 窗口面、OES 扩展缺失之类）
 * 都会通过 fail 原样报出来，CamCtl 收到后停录并在日志里写清原因 ——
 * 没有「退回四路各录一个」这回事了（用户指定只留四宫格），绝不假装拼上。
 * v4.7.0 起循环换段默认走 QuadRec 的无缝换段（编码器和这层都不动），
 * 无缝失败才整层重建（stopComposite→startComposite），所以仍然不需要 retarget()。
 */
public class Stitcher {

    /**
     * 拼接层初始化完成回调。
     */
    public interface DoneCb {
        void accept(boolean ok);
    }

    /**
     * 录像水印数据源（盯盯车的 "nativeConfigureVehicleOverlay" 同款能力，我们纯 GLES 实现）：
     * 每秒取一次要画的行（时间/车速/档位——全部真实来源），null = 不开水印。
     */
    public interface OsdProvider {
        List<String> lines();
    }

    /**
     * 画面调节（盯盯车 saturation/对比度/亮度那套的自写版）：4 个系数
     * [加亮, 对比度, 饱和度, gamma]，全部作用在拼接着色器里 ——
     * 也就是说调的是录进文件的成片画面，实时生效、可回退（改回标准 = 恒等变换）。
     * 只作用于摄像头四格，水印文字走另一个 program 不受影响。null = 不调。
     */
    public volatile float[] colorAdj;

    /**
     * 录像水印提供者。null = 不开水印。赋值后会触发 GL 线程刷新。
     */
    public volatile OsdProvider osdProvider;

    private final int w;
    private final int h;
    private final CarCtl.LogCallback fail;

    private HandlerThread thread;
    private Handler gl;

    private volatile boolean ready = false;
    private boolean released = false;

    // ---------- 水印（编码进录像，不是屏幕贴纸） ----------
    private int osdProg = 0;
    private int osdTid = 0;
    private Bitmap osdBmp = null;
    private boolean osdTexOk = false;
    private final Runnable osdTick = new Runnable() {
        @Override
        public void run() {
            Handler g = gl;
            if (g == null) return;
            OsdProvider p = osdProvider;
            if (p != null && ready && !released) {
                try {
                    rebuildOsd(p.lines());
                    // 水印每秒变一次，得真出一帧才看得见；顺带保证镜头全停时
                    // 编码器仍有节拍输入，不至于被看门狗误判成卡死。
                    scheduleTick();
                } catch (Throwable t) {
                    fail.log("水印画不出来：" + t.getClass().getSimpleName());
                    osdProvider = null;
                    return;
                }
                g.postDelayed(this, 1000);
            }
        }
    };

    public Stitcher(int w, int h, CarCtl.LogCallback fail) {
        this.w = w;
        this.h = h;
        this.fail = fail;
    }

    /** 文字条 → Bitmap → 纹理。字号按成片宽等比走，1280 宽时约 26px。 */
    private void rebuildOsd(List<String> lines) {
        if (lines.isEmpty()) { osdTexOk = false; return; }
        int fs = Math.max((int) (w * 0.022f), 14);
        int pad = (int) (fs * 0.5f);
        Paint pw = new Paint();
        pw.setAntiAlias(true);
        pw.setTextSize((float) fs);
        int tw = 0;
        for (String s : lines) tw = Math.max(tw, (int) pw.measureText(s));
        int bh = lines.size() * (fs + pad / 2) + pad;
        int bw = tw + pad * 2;
        Bitmap bmp = (osdBmp != null && osdBmp.getWidth() == bw && osdBmp.getHeight() == bh && !osdBmp.isRecycled())
                ? osdBmp
                : Bitmap.createBitmap(Math.max(bw, 1), Math.max(bh, 1), Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0);
        Canvas cv = new Canvas(bmp);
        Paint sh = new Paint();
        sh.setAntiAlias(true);
        sh.setTextSize((float) fs);
        sh.setColor(0x99000000);
        Paint pd = new Paint();
        pd.setAntiAlias(true);
        pd.setTextSize((float) fs);
        pd.setColor(0xFFEEEDEF);       // 近白 (Kotlin: -0x111112 = 0xFFEEEEEE)
        int y = pad + fs;
        for (String s : lines) {
            cv.drawText(s, (float) pad + 1f, (float) (y + 1), sh);
            cv.drawText(s, (float) pad, (float) y, pd);
            y += fs + pad / 2;
        }
        osdBmp = bmp;
        if (osdTid == 0) {
            int[] g = new int[1];
            GLES20.glGenTextures(1, g, 0);
            osdTid = g[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTid);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        osdTexOk = true;
    }

    private void drawOsd() {
        if (!osdTexOk || osdBmp == null || osdProg == 0) return;
        Bitmap b = osdBmp;
        // 全屏 quad 复用平铺纹理的画法：uv 直接用 bitmap 的比例区域。
        float[] data = new float[]{
                -1f, 1f, 0f, 0f,
                -1f, 1f - 2f * b.getHeight() / h, 0f, 1f,
                -1f + 2f * b.getWidth() / w, 1f, 1f, 0f,
                -1f + 2f * b.getWidth() / w, 1f - 2f * b.getHeight() / h, 1f, 1f};
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data);
        fb.position(0);
        GLES20.glUseProgram(osdProg);
        int a = GLES20.glGetAttribLocation(osdProg, "aPos");
        int au = GLES20.glGetAttribLocation(osdProg, "aUv");
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 16, fb);
        GLES20.glEnableVertexAttribArray(a);
        fb.position(2);
        GLES20.glVertexAttribPointer(au, 2, GLES20.GL_FLOAT, false, 16, fb);
        GLES20.glEnableVertexAttribArray(au);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTid);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(osdProg, "uTex"), 1);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private final int[] texIds = new int[4];
    private final SurfaceTexture[] stex = new SurfaceTexture[4];
    private final Surface[] ssurf = new Surface[4];
    private final float[][] mats = new float[4][16];

    private EGLDisplay dpy = null;
    private EGLContext eglCtx = null;
    private EGLSurface win = null;
    private int prog = 0;
    private int aPos = 0;
    private int aUv = 0;
    private int uTex = 0;
    private int uMtx = 0;
    private int uOff = 0;
    private int uScale = 0;
    private int uAdj = 0;
    private FloatBuffer quad = null;

    // 每格的偏移/缩放（GL 坐标，y 向上）。i 的次序=TILES（前0 后1 左2 右3）：
    // 前=左上、后=右上、左=左下、右=右下，和界面四格一模一样。
    private final float[][] offs = new float[][]{
            {-0.5f, 0.5f}, {0.5f, 0.5f},
            {-0.5f, -0.5f}, {0.5f, -0.5f}};

    /** 第 i 格的输入面（start 成功后才有）；i 的次序就是 TILES 的前/后/左/右。 */
    public Surface surfaceOf(int i) {
        return ready ? ssurf[i] : null;
    }

    /** 起 GL 线程并把输出面接到编码器输入面。done 在 GL 线程回调。 */
    public void start(final Surface input, final DoneCb done) {
        HandlerThread t = new HandlerThread("stitch");
        t.start();
        thread = t;
        Handler g = new Handler(t.getLooper());
        gl = g;
        g.post(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    initEgl(input);
                    initGl();
                    initTiles();
                    osdProg = build(OV, OF);
                    ready = true;
                    drawNow();                       // 先交一张底，编码器立刻有第一帧
                    if (osdProvider != null) g.postDelayed(osdTick, 1000);
                } catch (Throwable e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                if (err != null) {
                    ready = false;
                    fail.log(err);
                    releaseNow();
                    done.accept(false);
                } else {
                    done.accept(true);
                }
            }
        });
    }

    public void release() {
        Handler g = gl;
        if (g == null) return;
        ready = false;
        g.post(new Runnable() {
            @Override
            public void run() {
                releaseNow();
            }
        });
    }

    // ---------- GL 线程内部 ----------

    private void initEgl(Surface input) {
        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(d, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        int[] cfgAttribs = new int[]{
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(d, cfgAttribs, 0, cfgs, 0, 1, n, 0) || n[0] == 0)
            throw new IllegalStateException("eglChooseConfig");
        EGLConfig c = cfgs[0];
        EGLContext ctx = EGL14.eglCreateContext(d, c, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surf = EGL14.eglCreateWindowSurface(d, c, input,
                new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE}, 0);
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("无窗口面");
        if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) throw new IllegalStateException("makeCurrent");
        dpy = d;
        eglCtx = ctx;
        win = surf;
    }

    private void initGl() {
        prog = build(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aUv = GLES20.glGetAttribLocation(prog, "aUv");
        uTex = GLES20.glGetUniformLocation(prog, "uTex");
        uMtx = GLES20.glGetUniformLocation(prog, "uMtx");
        uOff = GLES20.glGetUniformLocation(prog, "uOff");
        uScale = GLES20.glGetUniformLocation(prog, "uScale");
        uAdj = GLES20.glGetUniformLocation(prog, "uAdj");
        // TRIANGLE_STRIP：左下、右下、左上、右上；uv 与位置同向，翻转交给变换矩阵。
        float[] data = new float[]{
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f};
        ByteBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        for (float f : data) b.putFloat(f);
        b.position(0);
        quad = b.asFloatBuffer();
    }

    private void initTiles() {
        final Handler g = gl;
        for (int i = 0; i < 4; i++) {
            GLES20.glGenTextures(1, texIds, i);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            SurfaceTexture st = new SurfaceTexture(texIds[i]);
            // 必须是摄像头支持的尺寸（1280x800 已实测支持），否则会话直接配不上；
            // 缩到四分之一格由着色器做，不吃额外内存。
            st.setDefaultBufferSize(w, h);
            final int me = i;
            // 来帧只记脏位，重绘交给 25 帧/秒的节拍合流。以前是每一路来一帧就
            // 全屏重绘 + eglSwapBuffers 一次：四路各 25 帧 = 每秒 100 多次换帧，
            // 编码器输入队列被塞满后回头堵死摄像头 —— 这就是录像会断的根因。
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    dirty[me] = true;
                    scheduleTick();
                }
            }, g);
            stex[i] = st;
            ssurf[i] = new Surface(st);
        }
    }

    // ---------- 出帧节拍（钉死成片帧率，见 initTiles 的注释） ----------
    private final long frameMs = 40L;                 // 25fps，和 QuadRec 的 KEY_FRAME_RATE 一致
    private final boolean[] dirty = new boolean[4];
    private long nextTickAt = 0L;
    private boolean tickScheduled = false;

    private void scheduleTick() {
        Handler g = gl;
        if (g == null) return;
        if (released) return;
        // 注意口径：postAtTime 收的是 SystemClock.uptimeMillis 时间轴，
        // 用 currentTimeMillis 会把排期算到几十年后，一帧都不出。
        long now = SystemClock.uptimeMillis();
        if (nextTickAt < now) nextTickAt = now;
        if (tickScheduled) return;
        tickScheduled = true;
        g.postAtTime(frameTick, nextTickAt);
        nextTickAt += frameMs;
    }

    private final Runnable frameTick = new Runnable() {
        @Override
        public void run() {
            tickScheduled = false;
            if (!ready || released) return;
            boolean any = false;
            for (int i = 0; i < 4; i++) if (dirty[i]) { dirty[i] = false; any = true; }
            if (!any) return;
            drawNow();
            // 画的过程中又攒了新帧就排下一拍，不busy-spin。
            for (int i = 0; i < 4; i++) if (dirty[i]) { scheduleTick(); break; }
        }
    };

    private void drawNow() {
        if (!ready || released) return;
        EGLDisplay d = dpy;
        if (d == null) return;
        EGLSurface s = win;
        if (s == null) return;
        EGLContext c = eglCtx;
        if (c == null) return;
        EGL14.eglMakeCurrent(d, s, s, c);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        FloatBuffer b = quad;
        if (b == null) return;
        b.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, b);
        GLES20.glEnableVertexAttribArray(aPos);
        b.position(2);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, b);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glUniform2f(uScale, 0.5f, 0.5f);
        float[] a = colorAdj;
        if (uAdj >= 0) {
            if (a != null && a.length >= 4) GLES20.glUniform4f(uAdj, a[0], a[1], a[2], a[3]);
            else GLES20.glUniform4f(uAdj, 0f, 1f, 1f, 1f);   // 恒等：不改变原画面
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(uTex, 0);
        for (int i = 0; i < 4; i++) {
            SurfaceTexture st = stex[i];
            if (st == null) continue;
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[i]);
            try {
                st.updateTexImage();
                st.getTransformMatrix(mats[i]);
                GLES20.glUniformMatrix4fv(uMtx, 1, false, mats[i], 0);
            } catch (Throwable t) {
                // 这路还没来帧：格子留黑，别的格照常画。
                GLES20.glUniformMatrix4fv(uMtx, 1, false, IDENTITY, 0);
            }
            GLES20.glUniform2f(uOff, offs[i][0], offs[i][1]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        drawOsd();
        EGL14.eglSwapBuffers(d, s);
    }

    private void releaseNow() {
        if (released) return;
        released = true;
        Handler g = gl;
        if (g != null) g.removeCallbacksAndMessages(null);
        try { if (osdTid != 0) GLES20.glDeleteTextures(1, new int[]{osdTid}, 0); }
        catch (Throwable t) { }
        osdTid = 0;
        try { if (osdBmp != null) osdBmp.recycle(); } catch (Throwable t) { }
        osdBmp = null;
        for (int i = 0; i < 4; i++) {
            try { if (ssurf[i] != null) ssurf[i].release(); } catch (Throwable t) { }
            try { if (stex[i] != null) stex[i].release(); } catch (Throwable t) { }
            ssurf[i] = null;
            stex[i] = null;
        }
        try {
            EGLDisplay d = dpy;
            if (d != null && win != null) {
                EGL14.eglDestroySurface(d, win);
                EGL14.eglDestroyContext(d, eglCtx);
                EGL14.eglTerminate(d);
            }
        } catch (Throwable t) { }
        win = null;
        eglCtx = null;
        dpy = null;
        if (g != null) g.removeCallbacksAndMessages(null);
        try { if (thread != null) thread.quitSafely(); } catch (Throwable t) { }
        thread = null;
        gl = null;
    }

    // ---------- 着色器 ----------

    private int build(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] st = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0);
        if (st[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] st = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0);
        if (st[0] == 0) throw new IllegalStateException("compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    // ---------- 常量 ----------

    private static final float[] IDENTITY = new float[]{
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};

    private static final String VS =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "uniform vec2 uOff;\n" +
            "uniform vec2 uScale;\n" +
            "uniform mat4 uMtx;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos.xy * uScale + uOff, 0.0, 1.0);\n" +
            "  vUv = (uMtx * vec4(aUv, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTex;\n" +
            // 画面调节四参数：加亮、对比度、饱和度、gamma（档位表在 CamCtl，
            // 恒等 = (0.0, 1.0, 1.0, 1.0)）。调的是录进文件的成片像素。
            "uniform vec4 uAdj;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTex, vUv).rgb;\n" +
            "  c = pow(max(c, vec3(0.0)), vec3(uAdj.w));\n" +
            "  c = (c - 0.5) * uAdj.y + 0.5;\n" +
            "  c += uAdj.x;\n" +
            "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n" +
            "  c = mix(vec3(l), c, uAdj.z);\n" +
            "  gl_FragColor = vec4(c, 1.0);\n" +
            "}\n";

    // 水印用的普通 RGBA 纹理着色器（和上面只差 sampler 类型）
    private static final String OV =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "void main() { gl_Position = aPos; vUv = aUv; }\n";

    private static final String OF =
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform sampler2D uTex;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUv); }\n";
}
