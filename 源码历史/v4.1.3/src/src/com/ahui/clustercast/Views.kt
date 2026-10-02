package com.ahui.clustercast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 车窗页的车辆俯视轮廓：圆角车身 + 四扇窗 + 底部一排短线。 */
class CarTopView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFA6B6C6.toInt()
        strokeWidth = Ui.dp(context, 2.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = w * 0.30f
        val bh = h * 0.78f
        val l = (w - bw) / 2f; val t = (h - bh) / 2f
        val body = RectF(l, t, l + bw, t + bh)
        cv.drawRoundRect(body, bw * 0.32f, bw * 0.16f, line)
        val winW = bw * 0.30f; val winH = bh * 0.13f
        val gapX = bw * 0.10f; val gapY = bh * 0.08f
        val wx1 = l + bw / 2f - gapX / 2f - winW
        val wx2 = l + bw / 2f + gapX / 2f
        val wy1 = t + bh * 0.14f
        val wy2 = wy1 + winH + gapY
        cv.drawRoundRect(wx1, wy1, wx1 + winW, wy1 + winH, 6f, 6f, line)
        cv.drawRoundRect(wx2, wy1, wx2 + winW, wy1 + winH, 6f, 6f, line)
        cv.drawRoundRect(wx1, wy2, wx1 + winW, wy2 + winH, 6f, 6f, line)
        cv.drawRoundRect(wx2, wy2, wx2 + winW, wy2 + winH, 6f, 6f, line)
        val dy = t + bh - bh * 0.06f
        var x = l + bw * 0.18f
        val dash = bw * 0.045f
        while (x < l + bw * 0.82f) {
            cv.drawRect(x, dy - 1.5f, x + dash, dy + 1.5f, line)
            x += dash * 2.2f
        }
    }
}

/** 尾门页的梯形轮廓。 */
class TailgateView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF9DB0C3.toInt()
        strokeWidth = Ui.dp(context, 3.5f).toFloat()
        strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val p = Path()
        p.moveTo(w * 0.30f, h * 0.26f)
        p.lineTo(w * 0.70f, h * 0.26f)
        p.lineTo(w * 0.82f, h * 0.72f)
        p.lineTo(w * 0.18f, h * 0.72f)
        p.close()
        cv.drawPath(p, line)
    }
}
