package com.dislopik.ntrealy.ui

import com.dislopik.ntrealy.protocol.NTRCommands
import com.dislopik.ntrealy.protocol.ScreenId
import com.dislopik.ntrealy.render.ColorMode

data class StreamConfig(
    val host: String = "",
    val viewerPort: Int = NTRCommands.UDP_PORT,
    val quality: Int = 70,
    val priority: Int = 4,
    val qos: Int = 16,
    val priorityScreen: ScreenId = ScreenId.TOP,
    val colorMode: ColorMode = ColorMode.REGULAR,
)
