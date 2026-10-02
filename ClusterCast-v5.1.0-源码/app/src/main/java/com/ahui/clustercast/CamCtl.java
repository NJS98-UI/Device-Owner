package com.ahui.clustercast;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 四路环视摄像头的预览 / 录像 / 拍照。方位实测钉死（协议说明 §9）：
 * id4=前、id5=右、id6=左、id7=后；1280x800 YUV_420_888，运行时弹窗授权 CAMERA 就够，
 * 不需要 root，四路可以同时开（工程模式 camprobe 实测同开过 device 4/5/6/7）。
 *
 * 这版解决用户实机反馈的三件事：
 *   1) 「摄像头老是提前被系统关掉 / 经常打不开」→ onDisconnected / 取流中止 /
 *      会话配置失败一律走 releaseAndRetry 自动重开；这四路由原车 RVC 服务管着，
 *      倒车 360 活动窗口里必然 IN_USE，所以 IN_USE 走 3 秒慢速重试（取证见摄像头报告）。
 *   2) 「四宫格录像没成功，还是各一个」→ 四路各一个文件的形态整个删掉，
 *      只录 Stitcher（GLES20 拼接 2x2）+ QuadRec（MediaCodec.createInputSurface）
 *      的一条路，输出就一个 mp4 文件；起不来就在日志写真实原因并且明确「这次没开录」，
 *      绝不假成功。之前失败出在 MediaRecorder 输入面绑定上，取证确认编码器/GL 健康。
 *   3) 「存 U 盘、够长能选、录满自动循环」→ 每段开始前先向 Storage 要空间，
 *      满了删我们自己的最旧一段；时长可选 3/5/10 分钟；落点由 Storage 现查。
 *
 * 静态成像的「一张图四宫格」拍照（snapshotQuad）照旧可用。
 */
public class CamCtl {

    /** 抓帧回调：String=文件路径，null=没出画（不造假图）。 */
    public interface StrCb { void accept(String s); }

    /** pickSize 的取流表选择器（Kotlin 的高阶参数落地成接口）。 */
    private interface SizeMap { Size[] get(StreamConfigurationMap m); }

    private final Context ctx;
    private final CarCtl.LogCallback log;
    private final CameraManager mgr;
    private final HandlerThread thread;
    final Handler bg;
    final Handler main;
    private final ArrayList<Cell> cells = new ArrayList<>();

    /** 分段时长，用户可选 1/3/5/10 分钟（1 分钟档是 EVCam 分段录制带来的）。 */
    public volatile int loopMinutes = 5;
    private volatile boolean recording = false;
    private long startedAt = 0L;

    /**
     * 参与录制的摄像头掩码（EVCam「录制摄像头选择」）：bit0=前 bit1=后 bit2=左 bit3=右，
     * 位序 = TILES。没选中的路不开镜头、四宫格里对应格黑；预览页想看随时能再开。
     */
    public volatile int recordMask = 0xF;

    private static boolean maskBit(int tileIdx, int mask) {
        return tileIdx >= 0 && tileIdx < 4 && (mask & (1 << tileIdx)) != 0;
    }

    /** 这一格按当前掩码是否参录（界面和取流共用）。 */
    boolean maskOn(String id) { return maskBit(tileIndexOf(id), recordMask); }

    /** 画质档换算出的码率（低/标准/高 = 4/8/12 Mbps），标准 8M。 */
    private volatile int bitrate = 8_000_000;
    private void applyQuality(int q) {
        switch (q) {
            case 0: bitrate = 4_000_000; break;
            case 2: bitrate = 12_000_000; break;
            default: bitrate = 8_000_000;
        }
    }

    public CamCtl(Context c, CarCtl.LogCallback log) {
        this.ctx = c.getApplicationContext();
        this.log = log;
        this.mgr = (CameraManager) this.ctx.getSystemService(Context.CAMERA_SERVICE);
        this.thread = new HandlerThread("cam");
        this.thread.start();
        this.bg = new Handler(this.thread.getLooper());
        this.main = new Handler(this.ctx.getMainLooper());
    }

    /**
     * 画面调节档（盯盯车 saturation/对比度/亮度的自写等价）：
     * 每档 4 个 GL 系数 [加亮, 对比度, 饱和度, gamma]，作用在拼接的 shader 上，
     * 调的就是录进文件的成片像素；标准档是恒等变换，一帧都不改。
     * 明亮/柔和两档同时尝试推 Camera2 曝光补偿（设备支持才推，参数越界绝不写）。
     */
    float[] pictureAdj(int p) {
        switch (p) {
            case 1: return new float[]{0.06f, 0.95f, 1.05f, 0.90f};   // 明亮：抬亮、压一点对比
            case 2: return new float[]{0.00f, 1.12f, 1.35f, 1.00f};   // 鲜艳：加对比加饱和
            case 3: return new float[]{0.00f, 0.90f, 0.90f, 1.15f};   // 柔和：gamma 提暗部、降对比
            default: return new float[]{0.0f, 1.0f, 1.0f, 1.0f};      // 标准：恒等
        }
    }
    /** 各档要的曝光补偿（EV 步数符号；实际步数按设备 AE 补偿范围夹住）。 */
    int pictureEvSteps(int p) {
        switch (p) { case 1: return 1; case 3: return -1; default: return 0; }
    }

    /**
     * 这一路实际能推的曝光补偿步数：先问 CameraCharacteristics 的 AE 补偿范围，
     * 不支持（range 下限 0）或者要的步数越界就返回 0 —— 一个数都不写。
     */
    private int evFor(String id) {
        int want = pictureEvSteps(new Cfg(ctx).dvrPicture());
        if (want == 0) return 0;
        android.util.Range<Integer> r;
        try {
            r = mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        } catch (Throwable t) {
            r = null;
        }
        if (r == null) return 0;
        int lo = r.getLower(), hi = r.getUpper();
        if (want < lo) return lo;
        if (want > hi) return hi;
        return want;
    }

    /**
     * 开录/停录音效：ToneGenerator 走通知音路，不占麦克风、不抢媒体焦点。
     * 注意 ToneGenerator 一 release 声音就断，所以只能等音效自己放完再回收，
     * 起不来就在日志写真实原因（不装作响了）。
     */
    private ToneGenerator tone = null;
    private void beep(final boolean start) {
        if (!new Cfg(ctx).dvrTone()) return;
        try {
            if (tone == null)
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65);
            tone.startTone(start ? ToneGenerator.TONE_PROP_BEEP
                            : ToneGenerator.TONE_PROP_BEEP2, 250);
            bg.postDelayed(releaseTone, 400);
        } catch (Throwable t) {
            final String why = t.getClass().getSimpleName();
            main.post(new Runnable() {
                @Override public void run() {
                    log.log("提示音没响：" + (why == null ? "null" : why.getClass().getSimpleName()));
                }
            });
        }
    }
    private final Runnable releaseTone = new Runnable() {
        @Override public void run() {
            try { if (tone != null) tone.release(); } catch (Throwable t) { }
            tone = null;
        }
    };

    /** 存储上限清理：根目录我们的 .mp4 总量超过 capMB 就从最旧删，锁定目录不碰。 */
    private void capSweep() {
        sweep(".mp4", new Cfg(ctx).dvrCapMB(), "段（锁定不动）");
    }

    /** 照片存储上限（EVCam photo max storage）：.png 超了从最旧删。 */
    private void photoSweep() {
        sweep(".png", new Cfg(ctx).dvrPhotoCapMB(), "张");
    }

    /** 按上限从最旧删指定后缀的文件（视频/照片共用），删完日志说人话。 */
    private void sweep(String suffix, int cap, final String unit) {
        if (cap <= 0) return;
        File dir = Storage.dir(ctx);
        File[] all = dir.listFiles();
        if (all == null) return;
        ArrayList<File> vids = new ArrayList<>();
        for (File f : all)
            if (f.isFile() && f.getName().endsWith(suffix)) vids.add(f);
        java.util.Collections.sort(vids, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long total = 0;
        for (File f : vids) total += f.length();
        long lim = (long) cap * 1024 * 1024;
        int gone = 0;
        for (File f : vids) {
            if (total <= lim) break;
            long n = f.length();
            if (f.delete()) { total -= n; gone++; }
        }
        if (gone > 0) {
            final int g = gone;
            main.post(new Runnable() {
                @Override public void run() { log.log("到达存储上限，已删最旧 " + g + " " + unit); }
            });
        }
    }

    /** 四宫格单文件录像：拼接层 + MediaCodec 编码器（createInputSurface 路径）+ 当前文件。 */
    private Stitcher stitch = null;
    private QuadRec quad = null;
    private File compFile = null;

    static class Cell {
        final String id;
        final String name;
        CamView box;
        CameraDevice device = null;
        CameraCaptureSession session = null;
        long frames = 0L;
        String state = "等待出画";
        String size = "";
        int retries = 0;
        /** 格子还在界面上。录像中切走页面 = false：预览面下线，镜头不撒手，后台继续录。 */
        boolean uiAlive = true;

        Cell(String id, String name, CamView box) {
            this.id = id; this.name = name; this.box = box;
        }
    }

    // ---------- 挂载与生命周期 ----------

    /** 把一个摄像头格接到指定 cameraId（界面 inflate 时调用）。 */
    public void attach(final CamView box, final String id, final String name) {
        bg.post(new Runnable() {
            @Override public void run() { attachNow(box, id, name); }
        });
    }

    private void attachNow(CamView box, String id, String name) {
        // 录像中切回这一页：镜头不能重开，格子对象也不能换（换格子会把在跑的
        // MediaRecorder 变成孤儿，文件永远差一次 stop）。只把新视图换到原格子上补预览。
        Cell live = cellOf(id);
        if (live != null && recording) {
            live.box.texture.setSurfaceTextureListener(null);
            live.box = box;
            live.uiAlive = true;
            live.frames = 0;
            box.texture.setSurfaceTextureListener(new Surf(live));
            // 镜头还开着：会话里没有预览面，必须重建一次把新视图的面挂回去。
            if (live.device != null) configure(live);
            else if (box.texture.isAvailable()) openNow(live);
            return;
        }
        ArrayList<Cell> olds = new ArrayList<>();
        for (Cell c : cells) if (c.id.equals(id)) olds.add(c);
        for (Cell old : olds) {
            old.box.texture.setSurfaceTextureListener(null);
            release(old);
        }
        for (Cell old : olds) cells.remove(old);
        Cell cell = new Cell(id, name, box);
        cells.add(cell);
        box.texture.setSurfaceTextureListener(new Surf(cell));
        if (box.texture.isAvailable()) openNow(cell);
    }

    /** 这一页不用摄像头了（切走/退出）：摘掉指定几路并断流，把镜头让给别的页。 */
    public void detach(String[] ids) {
        HashSet<String> s = new HashSet<>();
        for (String id : ids) s.add(id);
        detachInner(s);
    }

    /** 摘掉除 keep 以外的全部（倒车页只留三路时用）。 */
    public void detachAllExcept(String[] keep) {
        HashSet<String> set = new HashSet<>();
        for (String k : keep) set.add(k);
        ArrayList<String> drop = new ArrayList<>();
        for (Cell cell : new ArrayList<>(cells))
            if (!set.contains(cell.id)) drop.add(cell.id);
        HashSet<String> d = new HashSet<>(drop);
        detachInner(d);
    }

    /**
     * 后台录像的关键一条：在录的那几路「摘格」不等于「停录」。
     * 切走页面只把预览视图下线（uiAlive=false，会话里去掉预览面），
     * 镜头和编码器继续跑 —— 这样离开记录仪页、回桌面、甚至退出应用都还在录。
     * 只有界面上按「停止」或服务销毁才真正收尾文件。
     */
    private void detachInner(final HashSet<String> ids) {
        bg.post(new Runnable() {
            @Override public void run() {
                for (final Cell cell : new ArrayList<>(cells)) {
                    if (!ids.contains(cell.id)) continue;
                    if (recording && stitch != null) {
                        cell.box.texture.setSurfaceTextureListener(null);
                        cell.uiAlive = false;
                        cell.frames = 0;
                        release(cell);                  // 只断会话和设备，格子留在表里
                        bg.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!cell.uiAlive && recording) openNow(cell);
                            }
                        }, 400);
                        continue;
                    }
                    cell.box.texture.setSurfaceTextureListener(null);
                    release(cell);
                    cells.remove(cell);
                }
                // 四宫格在录时被摘线：只要还有格子活着就继续录，全没了才收尾。
                boolean noneAlive = true;
                for (Cell c : cells) if (c.uiAlive) noneAlive = false;
                if (stitch != null && !ids.isEmpty() && headlessCells().isEmpty() && noneAlive) {
                    if (recording) { recording = false; wakeHold(false); bg.removeCallbacks(spin); }
                    final int n = stopComposite();
                    if (n > 0) main.post(new Runnable() {
                        @Override public void run() {
                            log.log("摘线打断四宫格录像，已收尾 " + n + " 个文件");
                        }
                    });
                }
            }
        });
    }

    /** 界面已下线、但镜头还为我们录着的那几路。 */
    private ArrayList<Cell> headlessCells() {
        ArrayList<Cell> r = new ArrayList<>();
        for (Cell c : cells) if (!c.uiAlive) r.add(c);
        return r;
    }

    /** 回前台 / 刚拿到授权后调：把已就绪但没连上的格子补开。 */
    public void restart() {
        bg.post(new Runnable() {
            @Override public void run() {
                for (Cell cell : cells)
                    if (cell.device == null &&
                            (!cell.uiAlive || cell.box.texture.isAvailable())) openNow(cell);
            }
        });
    }

    /** 离开页面：收尾录像 + 断流，别让摄像头空转占住 HAL。 */
    public void stopAll() {
        bg.post(new Runnable() {
            @Override public void run() {
                if (recording) { recording = false; bg.removeCallbacks(spin); }
                stopComposite();
                for (Cell cell : cells) release(cell);
            }
        });
    }

    public void destroy() {
        stopAll();
        bg.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    // ---------- 取流 ----------

    void open(final Cell cell) {
        bg.post(new Runnable() {
            @Override public void run() { openNow(cell); }
        });
    }

    private void openNow(final Cell cell) {
        if (cell.device != null) return;
        if (!granted()) { show(cell, "等 CAMERA 授权"); return; }
        // EVCam「录制摄像头选择」：没勾参录的格子不开镜头，预览和成片都停在这格。
        if (!maskOn(cell.id)) { show(cell, "参录已停用"); return; }
        try {
            Size s = pickSize(cell.id, new SizeMap() {
                @Override public Size[] get(StreamConfigurationMap m) {
                    return m.getOutputSizes(SurfaceTexture.class);
                }
            });
            cell.size = s.getWidth() + "x" + s.getHeight();
            SurfaceTexture st = cell.box.texture.getSurfaceTexture();
            if (st != null) st.setDefaultBufferSize(s.getWidth(), s.getHeight());
            show(cell, "打开中 " + s.getWidth() + "x" + s.getHeight());
            mgr.openCamera(cell.id, new CamCb(cell), bg);
        } catch (Throwable t) {
            show(cell, "open 失败 " + t.getClass().getSimpleName());
        }
    }

    private void release(Cell cell) {
        try { if (cell.session != null) cell.session.close(); } catch (Throwable t) { }
        try { if (cell.device != null) cell.device.close(); } catch (Throwable t) { }
        cell.session = null;
        cell.device = null;
    }

    /**
     * 重建会话：预览面常驻；在录时再挂拼接层的输入面（每路只有两个输出，最稳）。
     * 用户定死只录四宫格一个文件，所以没有「每路各挂一个录像面」那条分支了。
     */
    void configure(final Cell cell) {
        final CameraDevice d = cell.device;
        if (d == null) return;
        ArrayList<Surface> outs = new ArrayList<>();
        // 界面下线的格子只挂录像侧的输出面：预览面已经随视图销毁，再挂必崩会话。
        final SurfaceTexture st = cell.uiAlive ? cell.box.texture.getSurfaceTexture() : null;
        if (st != null) outs.add(new Surface(st));
        Stitcher s = stitch;
        if (s != null) {
            int i = tileIndexOf(cell.id);
            if (i >= 0) {
                Surface su = s.surfaceOf(i);
                if (su != null) outs.add(su);
            }
        }
        if (outs.isEmpty()) { show(cell, "画面还没准备好"); return; }
        try {
            d.createCaptureSession(outs, new SessCb(cell), bg);
        } catch (Throwable t) {
            show(cell, "会话失败 " + t.getClass().getSimpleName());
            // 后台录像的格子没有预览面，失败也必须重开，不能停在 return 上。
            if (st == null && cell.uiAlive) return;
            else releaseAndRetry(cell, 800);
        }
    }

    void configured(Cell cell, CameraCaptureSession s) {
        cell.session = s;
        cell.retries = 0;
        CameraDevice d = cell.device;
        if (d == null) return;
        SurfaceTexture st = cell.uiAlive ? cell.box.texture.getSurfaceTexture() : null;
        try {
            CaptureRequest.Builder b =
                    d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (st != null) b.addTarget(new Surface(st));
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            // 画面调节档带的曝光补偿：设备不支持或范围不含这步数就不写（盯盯车
            // 那句「当前设备不支持亮度/降噪调节」的诚实版 —— 我们直接不推）。
            int ev = evFor(cell.id);
            if (ev != 0) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
            Stitcher sc = stitch;
            if (sc != null) {
                int i = tileIndexOf(cell.id);
                if (i >= 0) {
                    Surface su = sc.surfaceOf(i);
                    if (su != null) b.addTarget(su);
                }
            }
            s.setRepeatingRequest(b.build(), new FrameCb(cell), bg);
        } catch (Throwable t) {
            show(cell, "出图失败 " + t.getClass().getSimpleName());
        }
    }

    /**
     * 状态字只能回主线程写：tick/会话回调都跑在 cam 后台线程上，
     * 直接 setText 就是 CalledFromWrongThreadException —— 记录仪页「一出画就闪退」的根因。
     * 格子视图已随页面销毁时（后台录像那几路）直接跳过，不去碰死视图。
     */
    void show(final Cell cell, final String s) {
        cell.state = s;
        main.post(new Runnable() {
            @Override public void run() {
                CamView box = cell.box;
                try {
                    if (box.isAttachedToWindow())
                        box.status(cell.frames > 0 ? s + " 帧=" + cell.frames : s);
                } catch (Throwable t) { }
            }
        });
    }

    void tick(Cell cell) {
        cell.frames++;
        if (cell.frames % 60L == 0L) show(cell, cell.state);
    }

    /**
     * 被系统收回 / 硬件掉线 / 根本打不开：一律自动重开，这是「经常打不开摄像头」的正解。
     *
     * 取证补充（摄像头报告 A + Q8）：这四路由原车 RVC 服务（com.desaysv.ivi.vds.rvc）
     * 管理，倒车 360 活动窗口里我们 open 必然 IN_USE，800ms 快重试只会连环撞墙。
     * 所以：IN_USE 走 3 秒慢速重试；普通失败 800ms；重试次数不设死上限但间隔封顶
     * 6 秒 —— 原车一撒手我们就能进，不需要人管，也不把画面停在黑帧上装作还在录。
     */
    void releaseAndRetry(final Cell cell) { releaseAndRetry(cell, 800); }

    void releaseAndRetry(final Cell cell, long delayMs) {
        release(cell);
        cell.retries++;
        long d = delayMs > 6000 ? 6000 : delayMs;
        show(cell, "被系统收回，重开中（第" + cell.retries + "次）");
        if (cell.retries == 3)
            log.log(cell.name + "这一路被反复收回，已重试 3 次，仍在重开");
        bg.postDelayed(new Runnable() {
            @Override public void run() { openNow(cell); }
        }, d);
    }

    Cell cellOf(String id) {
        for (Cell c : cells) if (c.id.equals(id)) return c;
        return null;
    }

    ArrayList<Cell> liveCells() { return new ArrayList<>(cells); }

    // ---------- 四路同时录像 ----------

    /** 当前是否在录（四路一起开、一起停，只出一个四宫格文件）。 */
    public boolean isRecording() { return recording; }

    /**
     * 四宫格单文件（用户定死只要这一种）：拼接层 + MediaCodec.createInputSurface()。
     * 取证（摄像头报告 B）：这台 8155 的编码器和 GLES 都正常，起不来出在
     * MediaRecorder 输入面绑定上 —— 所以这里换标准路径，失败就把真实原因写日志，
     * 不再退回「四路各录一个文件」。
     * 只在 bg 线程调用，拼接层初始化用闩等一下 GL 线程，等不到按失败处理。
     */
    private boolean startComposite() {
        Cfg cfg = new Cfg(ctx);
        applyQuality(cfg.dvrQuality());
        File dir = Storage.dir(ctx);
        capSweep();   // 开录前先按存储上限清一次（上限没设就直接 return）
        // 码率按画质档走，一段按分钟算，留 1.3 倍余量。
        long need = bitrate / 8L * loopMinutes * 60L * 13 / 10;
        final int gone = Storage.makeRoom(dir, need);
        if (gone > 0) main.post(new Runnable() {
            @Override public void run() { log.log("存储不够，已自动删掉最旧 " + gone + " 段"); }
        });
        final File f = new File(dir, "四宫格_" + stamp() + ".mp4");
        QuadRec q = new QuadRec(f, CW, CH, bitrate, 25);
        // 声音是尽力而为：不给/起不来只在日志写原因，画面照录（盯盯车也是这个次序）。
        boolean withAudio = cfg.dvrSound() &&
                ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
        String err = q.start(withAudio);
        if (err != null) {
            final String e2 = err;
            main.post(new Runnable() {
                @Override public void run() { log.log("四宫格编码器起不来：" + e2); }
            });
            return false;
        }
        if (q.getAudioNote() != null) {
            final String note = q.getAudioNote();
            main.post(new Runnable() {
                @Override public void run() { log.log("音轨没录上：" + note + "（画面照常录）"); }
            });
        }
        Stitcher st = new Stitcher(CW, CH, new CarCtl.LogCallback() {
            @Override public void log(String e) { CamCtl.this.log.log("拼接层故障：" + e); }
        });
        if (cfg.dvrWatermark()) st.osdProvider = new Stitcher.OsdProvider() {
            @Override public java.util.List<String> lines() { return osdLines(); }
        };
        st.colorAdj = pictureAdj(cfg.dvrPicture());
        final boolean[] ok = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        final Stitcher stf = st;
        stf.start(q.getInputSurface(), new Stitcher.DoneCb() {
            @Override public void accept(boolean o) { ok[0] = o; latch.countDown(); }
        });
        boolean waited;
        try { waited = latch.await(6, TimeUnit.SECONDS) && ok[0]; }
        catch (InterruptedException t) { waited = false; }
        if (!waited) {
            stf.release();
            q.stop();
            main.post(new Runnable() {
                @Override public void run() {
                    log.log("这台机器的 GL 拼接层起不来（详见上一条故障原因）");
                }
            });
            return false;
        }
        stitch = stf;
        quad = q;
        compFile = f;
        for (Cell cell : cells) if (cell.device != null) { configure(cell); show(cell, "录入四宫格"); }
        return true;
    }

    /**
     * 水印三行：时间、车速、档位。全部真实来源 —— 车速/档位走 Vd 的 VEHICLE_HAL
     * 常驻订阅（和仪表悬浮层同一份数据），拿不到就写 "--"，绝不编个数字上去。
     */
    private ArrayList<String> osdLines() {
        ArrayList<String> l = new ArrayList<>(3);
        l.add(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Vd v = Vd.inst();
        if (v == null) { l.add("车速 --km/h"); l.add("挡位 --"); } else {
            float s = v.hudSpeed;
            l.add(s < 0f ? "车速 --km/h"
                    : "车速 " + String.format(Locale.US, "%.0f", s > 999f ? 999f : s) + "km/h");
            l.add("挡位 " + (CarCtl.gearLetter(v.hudGear) != null
                    ? CarCtl.gearLetter(v.hudGear) : "--"));
        }
        return l;
    }

    /** 收尾拼接层+编码器（bg 线程）。@return 完成的文件数（0/1）。 */
    private int stopComposite() {
        int n = 0;
        QuadRec q = quad;
        quad = null;
        File f = compFile;
        compFile = null;
        Stitcher st = stitch;
        stitch = null;
        if (st != null) {
            // 先停编码器吐 EOS 再拆 GL：顺序反了最后几百毫秒的帧会丢。
            String err = q != null ? q.stop() : null;
            if (err == null) n = 1;
            else {
                final String e2 = err;
                main.post(new Runnable() {
                    @Override public void run() {
                        log.log("四宫格这一段收尾失败（文件可能不完整）：" + e2);
                    }
                });
            }
            st.release();
        } else if (q != null) q.stop();
        if (f != null && f.exists() && f.length() == 0L) f.delete();
        return n;
    }

    private int tileIndexOf(String id) {
        for (int i = 0; i < TILES.length; i++) if (TILES[i][0].equals(id)) return i;
        return -1;
    }

    /** 界面上那行小字：第几段、录了多久。没录就直说。 */
    public String segmentInfo() {
        if (!recording) return "没在录像";
        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        String hm = (sec / 60) + "分" + String.format(Locale.US, "%02d", sec % 60) + "秒";
        String seg = compFile != null
                ? compFile.getName().substring(compFile.getName().lastIndexOf('_') + 1) : "?";
        return "第 " + seg + " 段 · " + hm + " · 每 " + loopMinutes + "分钟切一段";
    }

    public void recordAll() {
        bg.post(new Runnable() {
            @Override public void run() {
                if (recording) {
                    main.post(new Runnable() {
                        @Override public void run() { log.log("已经在录了，先停再开"); }
                    });
                    return;
                }
                ArrayList<Cell> ready = new ArrayList<>();
                for (Cell c : cells) if (c.device != null) ready.add(c);
                if (ready.isEmpty()) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("录不了：四路都还没出画（先等画面出来，或被原车 360 占着）");
                        }
                    });
                    return;
                }
                if (!Storage.dir(ctx).canWrite()) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("录不了：存放位置不可写（" + Storage.describe(ctx) + "）");
                        }
                    });
                    return;
                }
                recording = true;
                startedAt = System.currentTimeMillis();
                if (startComposite()) {
                    wakeHold(true);
                    beep(true);
                    bg.postDelayed(spin, loopMinutes * 60_000L);
                    bg.postDelayed(heal, 5000);        // 盯盯车式健康巡检：5 秒一探
                    emgRegister(true);                 // 紧急锁定：只在录的时候监听加速度计
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("开始四宫格录像（四路拼成 2x2，一个文件），落在 "
                                    + Storage.dir(ctx).getAbsolutePath());
                        }
                    });
                } else {
                    recording = false;
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("四宫格没起来，这次没开录（起不来的原因就在上面两行）");
                        }
                    });
                }
            }
        });
    }

    /**
     * 开机/服务启动自动录像（盯盯车 auto_start_recording）：
     * 记录仪页没打开过时界面上没有格子 —— 这里建四个「无界面格子」（uiAlive=false，
     * 只挂录像侧输出，不碰预览），先把四路补开，等 12 秒；一路都没出画就直说开不了。
     * 只在 dvrAutoBoot 开着时由 CastService 调。
     */
    public void dvrAutoStart() {
        // CamView 是 View，必须在主线程造；造完再回 bg 线程挂格子。
        main.post(new Runnable() {
            @Override public void run() {
                final HashMap<String, CamView> views = new HashMap<>();
                for (String[] t : TILES) views.put(t[0], new CamView(ctx, t[1], false));
                bg.post(new Runnable() {
                    @Override public void run() {
                        if (recording) return;
                        if (cells.isEmpty())
                            for (String[] t : TILES) {
                                Cell cell = new Cell(t[0], t[1], views.get(t[0]));
                                cell.uiAlive = false;
                                cells.add(cell);
                            }
                        for (Cell cell : cells) if (cell.device == null) openNow(cell);
                        bg.postDelayed(new Runnable() {
                            @Override public void run() {
                                int live = 0;
                                for (Cell c : cells) if (c.device != null) live++;
                                if (live == 0)
                                    main.post(new Runnable() {
                                        @Override public void run() {
                                            log.log("自动开录没成：四路都没出画（可能被原车占着）");
                                        }
                                    });
                                else recordAll();
                            }
                        }, 12_000);
                    }
                });
            }
        });
    }

    public void stopRecording() {
        bg.post(new Runnable() {
            @Override public void run() {
                if (!recording) {
                    main.post(new Runnable() {
                        @Override public void run() { log.log("当前没在录像"); }
                    });
                    return;
                }
                recording = false;
                wakeHold(false);
                emgRegister(false);
                bg.removeCallbacks(spin);
                final int n = stopComposite();
                beep(false);
                for (Cell cell : cells) if (cell.device != null) configure(cell);
                final long secs = (System.currentTimeMillis() - startedAt) / 1000;
                main.post(new Runnable() {
                    @Override public void run() {
                        log.log("录像已停，收尾 " + n + " 个文件（" + secs + "秒）");
                    }
                });
            }
        });
    }

    /**
     * 到点切段。v4.7.0 起照盯盯车（同包反汇编实证）的次序来：
     *   主路径 = 无缝换段（编码器/拼接层不动，IDR 边界上换 muxer）；
     *   它日志里的 "Seamless switch to segment" 是常态，"Encoder recreate segment
     *   switch timeout" / "Segment switch failed, quick retry in 5s" 是兜底。
     * 我们原样：无缝失败才退回整层重建，重建也失败才停录 —— 全程日志说人话。
     */
    private final Runnable spin = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            QuadRec q = quad;
            if (q != null) {
                final File f = new File(Storage.dir(ctx), "四宫格_" + stamp() + ".mp4");
                String err = q.switchSegment(f, 6000);
                if (err == null) {
                    compFile = f;
                    startedAt = System.currentTimeMillis();
                    bg.postDelayed(this, loopMinutes * 60_000L);
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("已无缝切新段 " + f.getName() + "（编码没断，旧段自动循环删）");
                        }
                    });
                    loopSweep();
                    return;
                }
                final String e2 = err;
                main.post(new Runnable() {
                    @Override public void run() { log.log("无缝换段没成就重来一遍：" + e2); }
                });
            }
            final int n = stopComposite();
            if (!startComposite()) {
                recording = false;
                wakeHold(false);
                main.post(new Runnable() {
                    @Override public void run() {
                        log.log("换段失败，录像已停（旧 " + n + " 个文件已收尾，原因见上）");
                    }
                });
                return;
            }
            for (Cell cell : cells) if (cell.device != null) configure(cell);
            bg.postDelayed(this, loopMinutes * 60_000L);
            main.post(new Runnable() {
                @Override public void run() {
                    log.log("已重建拼接层切新段（" + n + " 个文件收尾，旧段满了自动删）");
                }
            });
        }
    };

    /**
     * 录像健康巡检（盯盯车的两种探测照搬）：
     *   · "No write for …"：编码器超过 12 秒没吐过一帧 → 整层重建，绝不抱着黑帧装录；
     *   · "File size is 0! No frames received!"：段文件写到一半还是 0 字节 → 同上。
     * 重建 = 收当前段 + 起新段，镜头不撒手（openNow 那套继续握着格子）。
     * 一条日志说清触发原因，冷却 20 秒防止自己撞自己连环重建。
     */
    private volatile long lastHealAt = 0L;
    private final Runnable heal = new Runnable() {
        @Override public void run() {
            if (recording) {
                QuadRec q = quad;
                File f = compFile;
                String why = null;
                if (q != null) {
                    if (q.silentMs() > 12_000)
                        why = "编码器 12 秒没吐帧（已录 " + q.framesOut() + " 帧后停的）";
                    else if (q.framesOut() == 0L && f != null && f.exists() && f.length() == 0L)
                        why = "这一段写到 0 字节还没进一帧";
                }
                if (why != null && System.currentTimeMillis() - lastHealAt > 20_000) {
                    lastHealAt = System.currentTimeMillis();
                    final String w = why;
                    main.post(new Runnable() {
                        @Override public void run() {
                            log.log("录像卡死自检：" + w + "，重建编码/拼接层续录");
                        }
                    });
                    bg.post(new Runnable() {
                        @Override public void run() {
                            stopComposite();
                            if (recording && !startComposite()) {
                                recording = false;
                                wakeHold(false);
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        log.log("自愈重建没成功，这次真停了（原因见上）");
                                    }
                                });
                            }
                        }
                    });
                }
                if (recording) bg.postDelayed(this, 5000);
            }
        }
    };

    /** 换段成功后顺手清一次空间（循环删除逻辑不变，只挪了触发点）。 */
    private void loopSweep() {
        long need = bitrate / 8L * loopMinutes * 60L * 13 / 10;
        final int gone = Storage.makeRoom(Storage.dir(ctx), need);
        if (gone > 0) main.post(new Runnable() {
            @Override public void run() { log.log("存储不够，已自动删掉最旧 " + gone + " 段"); }
        });
        capSweep();   // 存储上限（盯盯车 video_storage_limit）也是换段时清一次
    }

    /**
     * 界面上当场换画面调节档时实时套用：编码中改的是拼接 shader 的系数，
     * 立即生效于新帧；曝光补偿那一路要重建 repeating request。
     * @return 一句话（用于 slog）
     */
    public String applyPicture() {
        int p = new Cfg(ctx).dvrPicture();
        Stitcher s = stitch;
        if (s != null) s.colorAdj = pictureAdj(p);
        bg.post(new Runnable() {
            @Override public void run() {
                for (Cell cell : cells) {
                    CameraCaptureSession ss = cell.session;
                    if (cell.device != null && ss != null) configured(cell, ss);  // 含还原：ev=0 就撤掉补偿
                }
            }
        });
        String[] names = {"标准", "明亮", "鲜艳", "柔和"};
        if (p < 0) p = 0;
        if (p > 3) p = 3;
        return "画面调节 -> " + names[p] + (new Cfg(ctx).dvrPicture() == 0
                || p == 0 ? "（已还原，不改变原画面）" : "（新帧立即生效）");
    }

    /**
     * 界面上当场改参录掩码时实时生效：勾上的路当场补开镜头，
     * 取消的路当场撒手（成片里那格从现在起是黑的，不装作还在录）。
     * @return 一句话（用于 slog）
     */
    public String applyMask(int mask) {
        recordMask = mask & 0xF;
        bg.post(new Runnable() {
            @Override public void run() {
                for (Cell cell : cells) {
                    if (maskOn(cell.id)) {
                        if (cell.device == null) openNow(cell);
                    } else if (cell.device != null) {
                        release(cell);
                        show(cell, "参录已停用");
                    }
                }
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String[] t : TILES)
            if (maskBit(tileIndexOf(t[0]), recordMask)) sb.append(t[1]);
        return "参录摄像头 -> " + (sb.length() == 0 ? "无（开录会被拒）" : sb);
    }

    // ---------- 紧急自动锁定（盯盯车 emergency_detection 自写行为） ----------

    /**
     * 加速度计探碰撞/急刹：扣掉 1g 重力后的速度突变超过阈值，就把正在录的这一段
     * 保护下来。纯传感器，不联网、不碰原车接口。只在录像中监听，触发后 5 秒冷却。
     *
     * 保护动作（bg 线程，emergencyLock）：先无缝切到新段把旧段落尾（写完 moov，
     * 变成可播放的完整文件），再把那个已封口的旧段「移动」进 锁定 目录 ——
     * 直接复制正在写的文件会得到没有 moov 的坏文件，所以必须封口后再挪。
     */
    private SensorManager sensorMgr = null;
    private Sensor accel = null;
    private volatile long lastEmgAt = 0L;
    private final SensorEventListener accelLst = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float[] g = e.values;
            if (g.length < 3) return;
            float a = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) - 9.81f;
            if (a < new Cfg(ctx).dvrEmergencyThreshold()) return;
            if (!recording) return;
            long now = System.currentTimeMillis();
            if (now - lastEmgAt < 5000) return;
            lastEmgAt = now;
            final float aa = a;
            bg.post(new Runnable() {
                @Override public void run() { emergencyLock(aa); }
            });
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    /** 封口当前段并把它移进锁定目录（bg 线程）。 */
    private void emergencyLock(float a) {
        if (!recording) return;
        File old = compFile;
        QuadRec q = quad;
        File sealed = old;
        if (q != null && old != null) {
            File f = new File(Storage.dir(ctx), "四宫格_" + stamp() + ".mp4");
            if (q.switchSegment(f, 6000) == null) { compFile = f; startedAt = System.currentTimeMillis(); }
            else sealed = null;      // 没切成，旧文件仍在写，不敢动它
        }
        if (sealed == null || !sealed.exists() || sealed.length() == 0L) {
            main.post(new Runnable() {
                @Override public void run() {
                    log.log("紧急锁定没成：这一段刚在写又没能封口，先不复制坏文件");
                }
            });
            return;
        }
        File to = new File(Storage.dir(ctx), "锁定");
        if (!to.exists() && !to.mkdirs()) {
            main.post(new Runnable() {
                @Override public void run() {
                    log.log("紧急锁定没成：建 锁定 目录失败 " + to.getAbsolutePath());
                }
            });
            bg.postDelayed(spin, loopMinutes * 60_000L);
            return;
        }
        final File dst = new File(to, sealed.getName());
        boolean moved = sealed.renameTo(dst);
        if (!moved) {
            try {
                InputStream in = new java.io.FileInputStream(sealed);
                OutputStream out = new java.io.FileOutputStream(dst);
                copyAll(in, out);
                in.close();
                out.close();
                sealed.delete();
                moved = true;
            } catch (Throwable t) { moved = false; }
        }
        final boolean mo = moved;
        final float aa = a;
        main.post(new Runnable() {
            @Override public void run() {
                log.log("检测到速度突变 " + String.format(Locale.US, "%.1f", aa) + "m/s²，紧急锁定："
                        + (mo ? "已封口并移入 锁定/" + dst.getName()
                        : "封口了但移动失败（文件在原处）"));
            }
        });
    }

    private static void copyAll(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    /** 录像起停时挂/摘加速度计监听（开关关着就不注册）。 */
    private void emgRegister(boolean on) {
        boolean want = on && recording && new Cfg(ctx).dvrEmergency();
        if (want && accel == null) {
            Object o = ctx.getSystemService(Context.SENSOR_SERVICE);
            sensorMgr = (o instanceof SensorManager) ? (SensorManager) o : null;
            if (sensorMgr != null) accel = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        Sensor s = accel;
        if (s == null) return;
        if (want) sensorMgr.registerListener(accelLst, s, SensorManager.SENSOR_DELAY_GAME);
        else sensorMgr.unregisterListener(accelLst);
    }

    /** 界面开关紧急锁：@return null=可用，否则真实原因（这台机没有加速度计）。 */
    public String setEmergency(boolean on) {
        if (!on) {
            if (sensorMgr != null) sensorMgr.unregisterListener(accelLst);
            return null;
        }
        Object o = ctx.getSystemService(Context.SENSOR_SERVICE);
        sensorMgr = (o instanceof SensorManager) ? (SensorManager) o : null;
        accel = sensorMgr != null ? sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (accel == null) return "这台机器没有加速度计";
        if (recording) sensorMgr.registerListener(accelLst, accel, SensorManager.SENSOR_DELAY_GAME);
        return null;
    }

    /**
     * 锁定当前录像：把这一轮在录的文件复制进 锁定 子目录，循环删除只动根目录的
     * .mp4，锁住的不会被自动清掉。返回一句人话（几个文件、多大）。
     */
    public void lockCurrent(final StrCb cb) {
        bg.post(new Runnable() {
            @Override public void run() {
                File dir = Storage.dir(ctx);
                ArrayList<File> live = new ArrayList<>();
                File f0 = compFile;
                if (f0 != null && f0.exists() && f0.length() > 0) live.add(f0);
                if (live.isEmpty()) {
                    File old = null;
                    File[] all = dir.listFiles();
                    if (all != null)
                        for (File f : all)
                            if (f.isFile() && f.getName().endsWith(".mp4")
                                    && f.getName().startsWith("四宫格")
                                    && (old == null || f.lastModified() > old.lastModified()))
                                old = f;
                    if (old == null) {
                        main.post(new Runnable() {
                            @Override public void run() { cb.accept("没有可锁定的录像文件"); }
                        });
                        return;
                    }
                    live.add(old);
                }
                final File to = new File(dir, "锁定");
                if (!to.exists() && !to.mkdirs()) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            cb.accept("建 锁定 目录失败：" + to.getAbsolutePath());
                        }
                    });
                    return;
                }
                int n = 0;
                long bytes = 0L;
                for (File f : live) {
                    final File d = new File(to, f.getName());
                    if (d.exists()) continue;
                    try {
                        InputStream in = new java.io.FileInputStream(f);
                        OutputStream out = new java.io.FileOutputStream(d);
                        copyAll(in, out);
                        in.close();
                        out.close();
                        n++;
                        bytes += d.length();
                    } catch (Throwable t) {
                        final String nm = f.getName();
                        final String cn = t.getClass().getSimpleName();
                        main.post(new Runnable() {
                            @Override public void run() { cb.accept("复制 " + nm + " 失败：" + cn); }
                        });
                    }
                }
                final int nn = n;
                final long bb = bytes;
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.accept(nn == 0 ? "没锁到新文件（已经在锁定目录里了）"
                                : "已锁定 " + nn + " 个文件 " + (bb / 1024 / 1024) + "MB → "
                                + to.getAbsolutePath());
                    }
                });
            }
        });
    }

    /** 界面上报这一路的真实状态。 */
    public String tileState(String id) { return stateOf(id); }

    /**
     * 录制期间防休眠（EVCam keep awake 同语义）：PARTIAL 锁只保 CPU，
     * 屏幕亮灭随车机。开录拿到、停录/自愈失败放下，别的地方不碰。
     */
    private android.os.PowerManager.WakeLock wake = null;

    private void wakeHold(boolean on) {
        if (on) {
            if (wake == null) {
                Object pm = ctx.getSystemService(Context.POWER_SERVICE);
                if (!(pm instanceof android.os.PowerManager)) return;
                wake = ((android.os.PowerManager) pm).newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "ClusterCast:dvr");
            }
            try { if (!wake.isHeld()) wake.acquire(); } catch (Throwable t) { }
        } else {
            try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable t) { }
        }
    }

    /** MainActivity 用 CarCtl.LogCallback 这一形状接一句话结果。 */
    public void snapshot(final CamView box, final CarCtl.LogCallback cb) {
        snapshot(box, new StrCb() {
            @Override public void accept(String s) { cb.log(s); }
        });
    }

    public void snapshotQuad(final CarCtl.LogCallback cb) {
        snapshotQuad(new StrCb() {
            @Override public void accept(String s) { cb.log(s); }
        });
    }

    public void lockCurrent(final CarCtl.LogCallback cb) {
        lockCurrent(new StrCb() {
            @Override public void accept(String s) { cb.log(s); }
        });
    }

    // ---------- 拍照 / 四宫格 ----------

    /** 抓某一格当前预览帧存 PNG；没出画回调 null，不造假图。 */
    public void snapshot(final CamView box, final StrCb cb) {
        Bitmap bmp;
        try { bmp = box.texture.getBitmap(); } catch (Throwable t) { bmp = null; }
        if (bmp == null) {
            main.post(new Runnable() {
                @Override public void run() { cb.accept(null); }
            });
            return;
        }
        final Bitmap b = bmp;
        final File f = new File(Storage.dir(ctx), "拍照_" + stamp() + ".png");
        bg.post(new Runnable() {
            @Override public void run() {
                final String path = write(b, f);
                photoSweep();
                b.recycle();
                main.post(new Runnable() {
                    @Override public void run() { cb.accept(path); }
                });
            }
        });
    }

    /** 一对（名字, 位图），snapshotQuad 用。 */
    private static class Named {
        final String name; final Bitmap bmp;
        Named(String n, Bitmap b) { name = n; bmp = b; }
    }

    /**
     * 四宫格一张图：前/右/后/左 各占一格并标字，输出单个 PNG。
     * 「成像就一个」目前能立刻做到的形态 —— 视频那版还要加拼接层。
     * @return 一句话结果（含成功路数），走日志。
     */
    public void snapshotQuad(final StrCb cb) {
        bg.post(new Runnable() {
            @Override public void run() {
                final ArrayList<Named> parts = new ArrayList<>();
                for (Cell cell : cells) {
                    Bitmap b;
                    try { b = cell.box.texture.getBitmap(); } catch (Throwable t) { b = null; }
                    if (b != null) parts.add(new Named(cell.name, b));
                }
                if (parts.isEmpty()) {
                    main.post(new Runnable() {
                        @Override public void run() { cb.accept("四路都没出画，拍不了"); }
                    });
                    return;
                }
                int cw = 640, ch = 400;
                Bitmap out = Bitmap.createBitmap(cw * 2, ch * 2, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(out);
                cv.drawColor(Color.BLACK);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.WHITE);
                p.setTextSize(28f);
                int[] pos = {0, 1, 2, 3};   // 界面顺序就是 前 后 左 右，各占一格
                for (int i = 0; i < parts.size(); i++) {
                    int slot = parts.size() == 4 ? pos[i] : i;
                    int x = (slot % 2) * cw;
                    int y = (slot / 2) * ch;
                    Named nb = parts.get(i);
                    cv.drawBitmap(nb.bmp, null, new Rect(x, y, x + cw, y + ch), null);
                    cv.drawText(nb.name + "（" + cellOfIdOf(nb.name) + "）",
                            (float) (x + 12), (float) (y + 34), p);
                    nb.bmp.recycle();
                }
                File f = new File(Storage.dir(ctx), "四宫格_" + stamp() + ".png");
                final String path = write(out, f);
                photoSweep();
                out.recycle();
                final int cnt = parts.size();
                final String nm = f.getName();
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.accept(path == null ? "拼图写文件失败（" + Storage.describe(ctx) + "）"
                                : "四宫格已存 " + cnt + "/4 路：" + nm);
                    }
                });
            }
        });
    }

    private String cellOfIdOf(String name) {
        for (Cell c : cells) if (c.name.equals(name)) return c.id;
        return "?";
    }

    private String write(Bitmap bmp, File f) {
        try {
            FileOutputStream os = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            return f.getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    /** 这一路的实际取流尺寸；还没出画就直说，界面不写假分辨率。 */
    public String sizeOf(String id) {
        Cell c = cellOf(id);
        return c != null && !c.size.isEmpty() ? c.size : "未取流";
    }

    public String stateOf(String id) {
        Cell c = cellOf(id);
        return c != null ? c.state : "没接这路";
    }

    // ---------- 尺寸 / 权限 ----------

    private Size pickSize(String id, SizeMap map) {
        Size[] ss;
        try {
            StreamConfigurationMap m = mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            ss = m != null ? map.get(m) : null;
        } catch (Throwable t) {
            ss = null;
        }
        if (ss == null || ss.length == 0) return new Size(1280, 800);
        for (Size s : ss) if (s.getWidth() == 1280 && s.getHeight() == 800) return s;
        Size best = null;
        for (Size s : ss)
            if (s.getWidth() <= 1280 && s.getHeight() <= 800)
                if (best == null || (long) s.getWidth() * s.getHeight()
                        > (long) best.getWidth() * best.getHeight()) best = s;
        return best != null ? best : ss[0];
    }

    public boolean granted() {
        return ctx.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /** 录音权限单独问（带声音那条腿用）；没给只是不录声音，不影响画面。 */
    public boolean micGranted() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /** 系统里到底有哪几路：原样回报，界面用它解释「为什么这路打不开」。 */
    public String cameraIds() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : mgr.getCameraIdList()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
            return sb.toString();
        } catch (Throwable t) { return "枚举失败：" + t.getClass().getSimpleName(); }
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    // ---------- camera 回调：本项目写法 = 嵌套类 + 显式宿主引用 ----------

    class Surf implements TextureView.SurfaceTextureListener {
        final Cell cell;
        Surf(Cell cell) { this.cell = cell; }
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            // 后台录像中切回本页：镜头还开着，只是换了个新视图 —— 重建会话把预览面挂回去。
            if (cell.device != null) { cell.uiAlive = true; configure(cell); }
            else open(cell);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    }

    class CamCb extends CameraDevice.StateCallback {
        final Cell cell;
        CamCb(Cell cell) { this.cell = cell; }
        @Override public void onOpened(CameraDevice d) {
            cell.device = d;
            configure(cell);
        }
        @Override public void onDisconnected(CameraDevice d) { releaseAndRetry(cell, 1200); }
        @Override public void onError(CameraDevice d, int e) {
            // IN_USE：原车 360 那一路是间歇让位的，等它撒手就能进 —— 慢一点重试，
            // 别 800ms 一次去撞（取证 Q8：AVM 活动窗口里必然失败，快重试只是刷失败次数）。
            if (e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE)
                releaseAndRetry(cell, 3000);
            else if (e == ERROR_CAMERA_DEVICE) releaseAndRetry(cell);
            else release(cell);
            String why;
            if (e == ERROR_CAMERA_IN_USE) why = "IN_USE（被占用，等它撒手）";
            else if (e == ERROR_MAX_CAMERAS_IN_USE) why = "MAX_IN_USE（同时开太多）";
            else if (e == ERROR_CAMERA_DISABLED) why = "DISABLED（被系统策略禁用）";
            else if (e == ERROR_CAMERA_DEVICE) why = "DEVICE（HAL 挂了，重开中）";
            else why = "code" + e;
            show(cell, "打不开 " + why);
        }
    }

    class SessCb extends CameraCaptureSession.StateCallback {
        final Cell cell;
        SessCb(Cell cell) { this.cell = cell; }
        @Override public void onConfigured(CameraCaptureSession s) { configured(cell, s); }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            // 360 黑屏的常见形态就是会话配不上：不等用户发现，直接重开这一路。
            releaseAndRetry(cell);
            Cell c = cellOf(cell.id);
            show(c != null ? c : cell, "会话配置失败，重开中");
        }
    }

    class FrameCb extends CameraCaptureSession.CaptureCallback {
        final Cell cell;
        FrameCb(Cell cell) { this.cell = cell; }
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                CaptureRequest request, TotalCaptureResult result) { tick(cell); }
        /**
         * 会话被抢走/中止（原车 360 或别的应用把镜头拿走时就是这个）：
         * 这是「摄像头老被关掉」最直接的信号，不等黑屏被人发现，直接重开。
         */
        @Override public void onCaptureSequenceAborted(CameraCaptureSession session, int requestId) {
            log.log(cell.name + "这一路取流序列被中止（第 " + requestId + " 个请求），重开中");
            releaseAndRetry(cell);
        }
    }

    static final String ID_FRONT = "4";
    static final String ID_RIGHT = "5";
    static final String ID_LEFT = "6";
    static final String ID_BACK = "7";

    /** 界面顺序：前、后、左、右（和四宫格一致）。 */
    static final String[][] TILES = {
            {ID_FRONT, "前"}, {ID_BACK, "后"}, {ID_LEFT, "左"}, {ID_RIGHT, "右"}};

    /** 循环时长可选值（分钟）。1 分钟档是 EVCam 分段录制带来的。 */
    public static final int[] LOOP_MIN = {1, 3, 5, 10};

    /** 四宫格成片尺寸：每格 640x400，正好吃掉一路 1280x800 的取流。 */
    static final int CW = 1280;
    static final int CH = 800;
}
