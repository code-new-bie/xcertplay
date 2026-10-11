package com.shilapi.xcertplay.media

/** Fits the negotiated CarPlay canvas inside the current window without changing its aspect ratio. */
data class CarPlayVideoLayout(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    companion object {
        fun fit(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
            val scale = minOf(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return CarPlayVideoLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }

        /**
         * Lays out the canvas so that one of its view areas fits the window: the area keeps its
         * aspect ratio and is centred, and the rest of the canvas falls outside the window.
         * Touches map through the same rectangle, so they land in that area.
         */
        fun fitArea(
            canvasWidth: Int,
            canvasHeight: Int,
            areaX: Int,
            areaY: Int,
            areaWidth: Int,
            areaHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): CarPlayVideoLayout {
            val scale = minOf(viewWidth.toFloat() / areaWidth, viewHeight.toFloat() / areaHeight)
            return CarPlayVideoLayout(
                (viewWidth - areaWidth * scale) / 2f - areaX * scale,
                (viewHeight - areaHeight * scale) / 2f - areaY * scale,
                canvasWidth * scale,
                canvasHeight * scale,
            )
        }
    }
}
