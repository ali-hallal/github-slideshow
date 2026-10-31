package com.procam.app

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** A TextureView that keeps the camera's aspect ratio instead of stretching the preview. */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        if (width == ratioWidth && height == ratioHeight) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
            return
        }
        val fitHeight = width * ratioHeight / ratioWidth
        if (fitHeight <= height || height == 0) {
            setMeasuredDimension(width, fitHeight)
        } else {
            setMeasuredDimension(height * ratioWidth / ratioHeight, height)
        }
    }
}
