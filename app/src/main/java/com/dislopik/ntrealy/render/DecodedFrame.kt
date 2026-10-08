package com.dislopik.ntrealy.render

import android.graphics.Bitmap
import com.dislopik.ntrealy.protocol.ScreenId

data class DecodedFrame(
    val screen: ScreenId,
    val bitmap: Bitmap,
)
