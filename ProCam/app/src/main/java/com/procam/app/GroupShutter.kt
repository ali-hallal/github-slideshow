package com.procam.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * The "Group" smart shutter: watches the viewfinder for faces, knows when everyone's eyes are
 * open and they're facing the camera, and picks the best photo out of a burst.
 * Everything runs on the phone (ML Kit, bundled model).
 */
class GroupShutter {

    /** One person in the viewfinder; [box] is 0..1 in preview coordinates. */
    data class Person(val box: RectF, val ready: Boolean, val eyesOpen: Boolean, val facing: Boolean)

    private val previewDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build(),
    )
    private val photoDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build(),
    )
    private val busy = AtomicBoolean(false)

    /** Analyses a viewfinder snapshot (upright). Skips the frame if the previous one is still running. */
    fun analyzePreview(bitmap: Bitmap, onResult: (List<Person>) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        previewDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                onResult(faces.map { f ->
                    val b = f.boundingBox
                    val eyes = eyesOpen(f)
                    val facing = facing(f)
                    Person(RectF(b.left / w, b.top / h, b.right / w, b.bottom / h), eyes && facing, eyes, facing)
                })
            }
            .addOnFailureListener { Log.w(TAG, "preview face detection failed", it) }
            .addOnCompleteListener { busy.set(false) }
    }

    /**
     * Picks the best photo of a burst: everyone's eyes open and facing the camera first, then
     * smiles, then sharpness. Blocking — call off the main thread.
     */
    fun pickBest(frames: List<ByteArray>): Pick {
        if (frames.size <= 1) return Pick(0, -1, -1)
        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        var bestReady = -1
        var faceCount = -1
        frames.forEachIndexed { i, bytes ->
            try {
                val bmp = decode(bytes)
                val faces = Tasks.await(photoDetector.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
                val ready = faces.count { eyesOpen(it) && facing(it) }
                val eyeScore = if (faces.isEmpty()) 0.5f else faces.minOf { eyeProb(it) }
                val smile = if (faces.isEmpty()) 0f else faces.map { it.smilingProbability ?: 0f }.average().toFloat()
                val sharp = sharpness(bmp)
                val score = (if (faces.isNotEmpty() && ready == faces.size) 2f else 0f) + eyeScore + 0.3f * smile + 0.4f * sharp
                Log.i(TAG, "burst frame $i: faces=${faces.size} ready=$ready eyes=$eyeScore smile=$smile sharp=$sharp score=$score")
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                    bestReady = ready
                    faceCount = faces.size
                }
                bmp.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "scoring frame $i failed", e)
            }
        }
        return Pick(bestIndex, faceCount, bestReady)
    }

    /** [faces] = people found in the chosen photo (-1 if not analysed), [ready] = how many had eyes open. */
    data class Pick(val index: Int, val faces: Int, val ready: Int)

    fun close() {
        previewDetector.close()
        photoDetector.close()
    }

    private fun eyeProb(f: Face) = min(f.leftEyeOpenProbability ?: 1f, f.rightEyeOpenProbability ?: 1f)
    private fun eyesOpen(f: Face) = eyeProb(f) >= EYES_OPEN
    private fun facing(f: Face) = abs(f.headEulerAngleY) < MAX_TURN && abs(f.headEulerAngleX) < MAX_TURN

    /** Decodes a JPEG at about 1000 px, with its EXIF rotation applied so faces are upright. */
    private fun decode(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 1000) {
                val scale = 1000f / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }

    /** 0..1 sharpness estimate (mean absolute Laplacian of luma), to break ties toward less blur. */
    private fun sharpness(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun luma(i: Int): Int {
            val p = px[i]
            return (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        var sum = 0L
        var n = 0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                sum += abs(4 * luma(i) - luma(i - 1) - luma(i + 1) - luma(i - w) - luma(i + w))
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0f else (sum.toFloat() / n / 40f).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "GroupShutter"
        private const val EYES_OPEN = 0.55f
        private const val MAX_TURN = 25f
    }
}
