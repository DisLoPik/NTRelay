package com.dislopik.ntrealy.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import com.dislopik.ntrealy.protocol.ScreenId

class FrameRenderer {

    private val thread = HandlerThread("NTR-Renderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scheduleLock = Any()
    private val surfaceLock = Any()

    @Volatile
    private var holder: SurfaceHolder? = null

    @Volatile
    private var topBitmap: Bitmap? = null

    @Volatile
    private var bottomBitmap: Bitmap? = null

    @Volatile
    private var drawScheduled = false

    fun attach(holder: SurfaceHolder) {
        this.holder = holder
        scheduleDraw()
    }

    fun redraw() {
        scheduleDraw()
    }

    /** Blocks until any draw in progress ends: the surface dies once surfaceDestroyed returns. */
    fun detach() {
        synchronized(surfaceLock) {
            holder = null
        }
    }

    fun submit(frame: DecodedFrame) {
        when (frame.screen) {
            ScreenId.TOP -> topBitmap = frame.bitmap
            ScreenId.BOTTOM -> bottomBitmap = frame.bitmap
        }
        scheduleDraw()
    }

    fun shutdown() {
        thread.quitSafely()
    }

    private fun scheduleDraw() {
        synchronized(scheduleLock) {
            if (drawScheduled) return
            drawScheduled = true
        }
        handler.post {
            drawScheduled = false
            draw()
        }
    }

    private fun draw() {
        synchronized(surfaceLock) { drawLocked() }
    }

    private fun drawLocked() {
        val holder = holder ?: return
        val top = topBitmap
        val bottom = bottomBitmap
        if (top == null && bottom == null) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            when {
                top != null && bottom != null -> {
                    val half = ch / 2f
                    drawCentered(canvas, top, RectF(0f, 0f, cw, half))
                    drawCentered(canvas, bottom, RectF(0f, half, cw, ch))
                }
                top != null -> drawCentered(canvas, top, RectF(0f, 0f, cw, ch))
                bottom != null -> drawCentered(canvas, bottom, RectF(0f, 0f, cw, ch))
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawCentered(canvas: Canvas, bitmap: Bitmap, region: RectF) {
        val scale = minOf(region.width() / bitmap.width, region.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = region.centerX() - w / 2f
        val top = region.centerY() - h / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
    }
}
