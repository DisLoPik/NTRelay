package com.dislopik.ntrealy.render

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

class StreamSurfaceView(context: Context) : SurfaceView(context) {

    val renderer = FrameRenderer()

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                renderer.attach(holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                renderer.redraw()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.detach()
            }
        })
    }

    fun shutdown() {
        renderer.shutdown()
    }
}
