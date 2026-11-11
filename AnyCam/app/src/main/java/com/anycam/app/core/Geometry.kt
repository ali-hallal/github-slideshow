package com.anycam.app.core

/** Small pieces of arithmetic the camera screen needs; kept free of Android so they can be tested anywhere. */
object Geometry {

    /**
     * A point on the upright viewfinder (0..1 across, 0..1 down) → the same point on the sensor's
     * own frame (0..1), given how the sensor is mounted and whether the view is mirrored (selfies).
     */
    fun viewToSensor(nx: Float, ny: Float, sensorOrientation: Int, front: Boolean): Pair<Float, Float> {
        val x = if (front) 1f - nx else nx
        return when (sensorOrientation) {
            90 -> ny to 1f - x
            270 -> 1f - ny to x
            180 -> 1f - x to 1f - ny
            else -> x to ny
        }
    }

    /** Clockwise degrees to turn a frame so the photo is upright, for a phone held at [deviceDegrees]. */
    fun photoRotation(sensorOrientation: Int, deviceDegrees: Int, front: Boolean): Int =
        if (front) (sensorOrientation - deviceDegrees + 360) % 360 else (sensorOrientation + deviceDegrees) % 360

    /** The zoom buttons for a camera whose zoom goes from [min] to [max]: familiar steps within it. */
    fun zoomStops(min: Float, max: Float): List<Float> {
        val stops = ArrayList<Float>()
        if (min < 0.95f) stops += min
        stops += listOf(1f, 2f, 5f, 10f).filter { it >= min - 0.01f && it <= max + 0.01f }
        return stops.distinct()
    }

    /**
     * How many shots to merge: the dimmer the scene (the higher the [iso] the camera is using),
     * the noisier each shot and the more of them are worth taking. Null = the phone doesn't say.
     */
    fun burstFrames(iso: Int?): Int = when {
        iso == null -> 4
        iso < 200 -> 3
        iso < 800 -> 5
        else -> 7
    }

    /**
     * As many of [wanted] frames of [pixels] each as fit comfortably in [memoryBytes].
     * [bytesPerPixel]: what one frame takes (1.5 for ordinary frames, 2 for sensor frames);
     * [workBytesPerPixel]: what the merge needs on top, whatever the number of frames.
     */
    fun framesThatFit(wanted: Int, pixels: Long, memoryBytes: Long, bytesPerPixel: Float = 1.5f, workBytesPerPixel: Float = 0f): Int {
        val room = memoryBytes * 0.4 - pixels * workBytesPerPixel.toDouble()
        val fit = (room / (pixels * bytesPerPixel.toDouble()).coerceAtLeast(1.0)).toInt()
        return wanted.coerceAtMost(fit).coerceAtLeast(1)
    }
}
