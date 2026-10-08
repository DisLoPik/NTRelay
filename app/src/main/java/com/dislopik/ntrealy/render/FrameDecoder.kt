package com.dislopik.ntrealy.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.dislopik.ntrealy.protocol.AssembledFrame

class FrameDecoder(private val colorMode: ColorMode = ColorMode.REGULAR) {

    fun decode(frame: AssembledFrame): DecodedFrame? {
        val src = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return null
        val rotated = rotate90Ccw(src)
        src.recycle()
        val final = when (colorMode) {
            ColorMode.REGULAR -> rotated
            ColorMode.GRAYSCALE -> toGrayscale(rotated).also { rotated.recycle() }
        }
        return DecodedFrame(frame.screen, final)
    }

    private fun rotate90Ccw(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(h, w, src.config ?: Bitmap.Config.ARGB_8888)
        // (x, y) -> (y, w - x). Canvas maps pixel edges, so the offset is w, not w - 1
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    0f, 1f, 0f,
                    -1f, 0f, w.toFloat(),
                    0f, 0f, 1f,
                )
            )
        }
        Canvas(out).drawBitmap(src, matrix, null)
        return out
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
