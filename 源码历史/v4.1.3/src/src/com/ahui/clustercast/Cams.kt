package com.ahui.clustercast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date

/** 一路摄像头的画面格：TextureView + 左上角状态条。状态只来自真实回调，不画假画面。 */
class CamView(c: Context, val label: String, bordered: Boolean) : FrameLayout(c) {

    val texture = TextureView(c)
    private val tag: TextView = Ui.text(c, 11, Color.WHITE, Typeface.NORMAL, 2)

    init {
        selected(bordered)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tag.setBackgroundColor(0xB0000000.toInt())
        tag.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3))
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP
        addView(tag, lp)
        status("等待出画")
    }

    fun status(s: String) { tag.text = "$label · $s" }

    /** 蓝边=当前选中的那一路。 */
    fun selected(on: Boolean) {
        background = Ui.paint(context, if (on) Ui.R_DARK_B else Ui.R_DARK, 10)
    }
}

/**
 * 四路环视摄像头的取流 / 拍照 / 录像。
 * 方位实测钉死：id4=前、id5=右、id6=左、id7=后（协议说明 §9）。
 * CAMERA 靠运行时弹窗授权一次即可；产物只写应用私有目录，不要存储权限。
 * 同一时刻只录一路；拿不到帧就显示状态文字，界面绝不卡住。
 */
class CamCtl(c: Context) {

    private val ctx = c.applicationContext
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cam").apply { start() }
    internal val bg = Handler(thread.looper)
    internal val main = Handler(ctx.mainLooper)
    private val cells = ArrayList<Cell>()

    internal var recorder: MediaRecorder? = null
    internal var recCell: Cell? = null
    internal var recFile: File? = null
    private var loopMs = 3 * 60_000L

    internal class Cell(val id: String, val box: CamView) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var frames = 0L
        var state = "等待出画"
        var size = ""
    }

    // ---------- 挂载与生命周期 ----------

    /** 把一个摄像头格接到指定 cameraId。 */
    fun attach(box: CamView, id: String) {
        val cell = Cell(id, box)
        cells.add(cell)
        box.texture.surfaceTextureListener = Surf(this, cell)
    }

    /** 回前台或刚拿到授权后调：把已就绪的格子重新 open。 */
    fun restart() = bg.post {
        for (cell in cells) if (cell.device == null && cell.box.texture.isAvailable) openNow(cell)
    }

    /** 离开页面：断流并收尾录像，别让摄像头空转。 */
    fun stopAll() = bg.post {
        stopRecorder()
        for (cell in cells) release(cell)
    }

    fun destroy() {
        stopAll()
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ---------- 取流 ----------

    internal fun open(cell: Cell) { bg.post { openNow(cell) } }

    private fun openNow(cell: Cell) {
        if (cell.device != null) return
        if (!granted()) { show(cell, "等 CAMERA 授权"); return }
        try {
            val s = pickSize(cell.id) { it.getOutputSizes(SurfaceTexture::class.java) }
            cell.size = "${s.width}x${s.height}"
            cell.box.texture.surfaceTexture?.setDefaultBufferSize(s.width, s.height)
            show(cell, "打开中 ${s.width}x${s.height}")
            mgr.openCamera(cell.id, CamCb(this, cell), bg)
        } catch (t: Throwable) {
            show(cell, "open 失败 " + t.javaClass.simpleName)
        }
    }

    private fun release(cell: Cell) {
        try { cell.session?.close() } catch (t: Throwable) { }
        try { cell.device?.close() } catch (t: Throwable) { }
        cell.session = null
        cell.device = null
    }

    /** 重建会话：录像那一路多挂一个 MediaRecorder 面。 */
    internal fun configure(cell: Cell) {
        val d = cell.device ?: return
        val outs = ArrayList<Surface>()
        cell.box.texture.surfaceTexture?.let { outs.add(Surface(it)) }
        if (recCell === cell) recorder?.surface?.let { outs.add(it) }
        if (outs.isEmpty()) { show(cell, "画面还没准备好"); return }
        try {
            d.createCaptureSession(outs, SessCb(this, cell), bg)
        } catch (t: Throwable) {
            show(cell, "会话失败 " + t.javaClass.simpleName)
        }
    }

    internal fun configured(cell: Cell, s: CameraCaptureSession) {
        cell.session = s
        val d = cell.device ?: return
        val st = cell.box.texture.surfaceTexture ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(Surface(st))
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (recCell === cell) recorder?.surface?.let { b.addTarget(it) }
            s.setRepeatingRequest(b.build(), FrameCb(this, cell), bg)
            startRecorderIfPending(cell)
        } catch (t: Throwable) {
            show(cell, "出图失败 " + t.javaClass.simpleName)
        }
    }

    /** 录像面要等会话配好才能 start，否则拿到的是空文件。 */
    private fun startRecorderIfPending(cell: Cell) {
        val r = recorder
        if (r != null && recCell === cell && cell.state.startsWith("打开中")) {
            try {
                r.start(); show(cell, "出画中 · 录像中"); return
            } catch (t: Throwable) {
                show(cell, "录像启动失败 " + t.javaClass.simpleName)
                return
            }
        }
        show(cell, if (recCell === cell && r != null) "录像中" else "出画中")
    }

    internal fun show(cell: Cell, s: String) {
        cell.state = s
        cell.box.status(if (cell.frames > 0) "$s 帧=${cell.frames}" else s)
    }

    internal fun tick(cell: Cell) {
        cell.frames++
        if (cell.frames % 60L == 0L) show(cell, cell.state)
    }

    internal fun cellOf(id: String): Cell? = cells.firstOrNull { it.id == id }

    internal fun releaseAndRetry(cell: Cell) {
        release(cell)
        show(cell, "被系统收回，重开中")
        bg.postDelayed({ openNow(cell) }, 800)
    }

    // ---------- 拍照 ----------

    /** 抓当前预览帧存 PNG，回调回主线程给路径（拿不到画面给 null）。 */
    fun snapshot(box: CamView, cb: (String?) -> Unit) {
        val bmp = try { box.texture.bitmap } catch (t: Throwable) { null }
        if (bmp == null) { main.post { cb(null) }; return }
        val f = File(dir(), "拍照_" + stamp() + ".png")
        bg.post {
            val ok = try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            } catch (t: Throwable) { false }
            val path = if (ok) f.absolutePath else null
            main.post { cb(path) }
        }
    }

    // ---------- 录像 ----------

    /** 录这一路（同一时刻只录一路）；id 传 null 表示停止。回调给一句人话。 */
    fun record(id: String?, cb: (String) -> Unit) = bg.post {
        if (id == null) {
            val m = if (recorder == null) "当前没在录像" else "录像已存：" + stopRecorder()
            main.post { cb(m) }
            return@post
        }
        if (recorder != null) { main.post { cb("先停当前录像再换路") }; return@post }
        val cell = cellOf(id)
        if (cell == null || cell.device == null) {
            main.post { cb("这路还没出画，录不了") }
            return@post
        }
        val size = pickSize(cell.id) { it.getOutputSizes(MediaRecorder::class.java) }
        val r = prepareRecorder(File(dir(), "录像_" + stamp() + ".mp4"), size)
        if (r == null) { main.post { cb("录像器准备失败（尺寸 ${size.width}x${size.height} 不被支持）") }; return@post }
        recorder = r.first
        recFile = r.second
        recCell = cell
        configure(cell)
        main.post { cb("开始录像：${r.second.name} ${size.width}x${size.height}") }
    }

    fun recording(): Boolean = recorder != null
    fun setLoopMinutes(m: Int) { loopMs = m * 60_000L }

    /** @return 准备好的录像器及其文件，失败返回 null。 */
    private fun prepareRecorder(file: File, size: Size): Pair<MediaRecorder, File>? = try {
        val r = MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(file.absolutePath)
        r.setVideoEncodingBitRate(6000000)
        r.setVideoFrameRate(25)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.prepare()
        bg.postDelayed(spin, loopMs)
        Pair(r, file)
    } catch (t: Throwable) {
        try { recorder?.release() } catch (ignored: Throwable) { }
        null
    }

    /** 循环分段：到点把当前这段存好，紧接着开下一段。 */
    private val spin = Runnable {
        val cell = recCell ?: return@Runnable
        stopRecorder()
        if (cell.device == null) return@Runnable
        val size = pickSize(cell.id) { it.getOutputSizes(MediaRecorder::class.java) }
        val r = prepareRecorder(File(dir(), "录像_" + stamp() + ".mp4"), size)
        if (r != null) {
            recorder = r.first; recFile = r.second; recCell = cell
            configure(cell)
            show(cell, "打开中（换段）")
        }
    }

    private fun stopRecorder(): String {
        bg.removeCallbacks(spin)
        val r = recorder
        recorder = null
        val f = recFile
        recFile = null
        val cell = recCell
        recCell = null
        if (r == null) return ""
        try { r.stop() } catch (t: Throwable) { }
        try { r.release() } catch (t: Throwable) { }
        if (cell != null) { cell.state = "出画中"; configure(cell) }
        val kb = if (f != null && f.exists()) f.length() / 1024 else 0
        return (f?.name ?: "未命名") + "（${kb}KB）"
    }

    // ---------- 尺寸 / 目录 / 权限 ----------

    private fun pickSize(id: String, map: (StreamConfigurationMap) -> Array<Size>?): Size {
        val ss = try {
            mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let(map)
        } catch (t: Throwable) { null }
        if (ss == null || ss.isEmpty()) return Size(1280, 800)
        ss.firstOrNull { it.width == 1280 && it.height == 800 }?.let { return it }
        return ss.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height } ?: ss.first()
    }

    /** 产物落应用私有目录，MT 管理器能翻到，且不需要任何存储权限。 */
    fun dir(): File {
        val d = File(ctx.getExternalFilesDir(null), "环视")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun granted(): Boolean = ctx.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 这一路实际的取流尺寸；还没出画就直说，界面不写假分辨率。 */
    fun sizeOf(id: String): String = cellOf(id)?.size?.takeIf { it.isNotEmpty() } ?: "未取流"

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

    // ---------- camera 回调：按本项目一贯写法用嵌套类 + 显式宿主引用 ----------

    internal class Surf(val o: CamCtl, val cell: Cell) : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { o.open(cell) }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
    }

    internal class CamCb(val o: CamCtl, val cell: Cell) : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            cell.device = d
            o.configure(cell)
        }

        override fun onDisconnected(d: CameraDevice) = o.releaseAndRetry(cell)

        override fun onError(d: CameraDevice, e: Int) {
            o.release(cell)
            o.show(cell, "打不开 " + when (e) {
                ERROR_CAMERA_IN_USE -> "IN_USE（工程模式占着）"
                ERROR_MAX_CAMERAS_IN_USE -> "MAX_IN_USE（同时开太多）"
                ERROR_CAMERA_DISABLED -> "DISABLED（被系统策略禁用）"
                else -> "code$e"
            })
        }
    }

    internal class SessCb(val o: CamCtl, val cell: Cell) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) = o.configured(cell, s)
        override fun onConfigureFailed(s: CameraCaptureSession) = o.show(cell, "会话配置失败")
    }

    internal class FrameCb(val o: CamCtl, val cell: Cell)
        : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                                        result: TotalCaptureResult) = o.tick(cell)
    }

    companion object {
        const val ID_FRONT = "4"
        const val ID_RIGHT = "5"
        const val ID_LEFT = "6"
        const val ID_BACK = "7"
    }
}
