package com.dislopik.ntrealy.protocol

enum class ScreenId {
    TOP,
    BOTTOM;

    companion object {
        /** Only bit 0 marks the top screen; NTR-HR's full-width mode also sets bits 1-2. */
        fun fromFrameHeader(nibble: Int): ScreenId = if ((nibble and 1) == 1) TOP else BOTTOM
    }
}
