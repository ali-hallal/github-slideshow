package com.procam.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The video "darkroom": camera frames go through the GPU on their way to the encoder, so a
 * recording gets the look (colours, vignette, grain), a level horizon, and time-lapse.
 *
 * The camera draws into [inputSurface]; each frame is drawn with a shader into the encoder's
 * surface (only while recording). Runs on its own thread with its own EGL context.
 */
class VideoEffects(private val size: Size, private val encoderSurface: Surface) {

    /** Settings read every frame (set from any thread). */
    @Volatile var look: Looks.Look? = null
    /** Horizon correction: rotate the picture clockwise by this (radians, as seen in the video). */
    @Volatile var levelAngle = 0f
    /** Fixed enlargement while levelling, so rotated corners stay filled. */
    @Volatile var levelScale = 1f
    /** Time-lapse: keep one frame in [timelapse] (1 = normal video). */
    @Volatile var timelapse = 1
    /** Frames go to the encoder only while recording. */
    @Volatile var recording = false
        set(value) {
            if (value && !field) {
                frameIndex = 0L
                firstTimestamp = -1L
                kept = 0L
            }
            field = value
        }

    private val thread = HandlerThread("VideoEffects").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set
    private val texMatrix = FloatArray(16)
    private var frameIndex = 0L
    private var firstTimestamp = -1L
    private var kept = 0L

    /** Sets up GL; false if this phone can't (then video is recorded directly, without effects). */
    fun start(): Boolean {
        var ok = false
        val lock = Object()
        var done = false
        handler.post {
            ok = try {
                setup()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "video effects unavailable", e)
                false
            }
            synchronized(lock) {
                done = true
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            val end = System.currentTimeMillis() + 3000
            while (!done && System.currentTimeMillis() < end) lock.wait(200)
        }
        if (!ok) release()
        return ok
    }

    fun release() {
        handler.post {
            try {
                if (::surfaceTexture.isInitialized) surfaceTexture.release()
                if (::inputSurface.isInitialized) inputSurface.release()
                if (program != 0) GLES20.glDeleteProgram(program)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "release", e)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ GL

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener({ onFrame() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
        Log.i(TAG, "video effects ready ${size.width}x${size.height}")
    }

    private fun onFrame() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        surfaceTexture.updateTexImage()
        if (!recording) return
        val ts = surfaceTexture.timestamp
        val n = frameIndex++
        val step = max(1, timelapse)
        if (n % step != 0L) return
        if (firstTimestamp < 0) firstTimestamp = ts
        surfaceTexture.getTransformMatrix(texMatrix)
        draw()
        // Time-lapse: kept frames are packed 1/30 s apart; normal video keeps the real timing.
        val pts = if (step > 1) firstTimestamp + kept * FRAME_NS else ts
        kept++
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, pts)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun draw() {
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glUseProgram(program)
        val l = look
        val m = l?.matrix ?: IDENTITY
        // android ColorMatrix rows (r,g,b,a,offset) → GLSL mat4 (column-major) + offset vec4 in 0..1.
        val mat = floatArrayOf(
            m[0], m[5], m[10], m[15],
            m[1], m[6], m[11], m[16],
            m[2], m[7], m[12], m[17],
            m[3], m[8], m[13], m[18],
        )
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uColor"), 1, false, mat, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uOffset"), m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19] / 255f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVignette"), l?.vignette ?: 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGrain"), (l?.grain ?: 0f) / 255f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSeed"), (frameIndex % 1000) / 1000f)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        // Levelling: rotate the quad (in pixels, so it isn't distorted) and enlarge it to cover.
        val a = -levelAngle // negative in GL's y-up space = clockwise on screen
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRot"), cos(a), sin(a))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uScale"), levelScale)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uSize"), size.width.toFloat(), size.height.toFloat())

        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "ProCam"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FRAME_NS = 1_000_000_000L / 30
        private val IDENTITY = android.graphics.ColorMatrix().array

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

        /**
         * Enlargement needed so a w×h picture rotated by up to [maxAngle] radians still fills the
         * frame (no empty corners).
         */
        fun coverScale(w: Int, h: Int, maxAngle: Float): Float {
            val a = abs(maxAngle)
            val r = max(w, h).toFloat() / minOf(w, h)
            return cos(a) + r * sin(a)
        }

        /**
         * Phone tilt to undo, from the device's orientation angle (degrees clockwise from upright,
         * as OrientationEventListener reports) and the orientation the video was started in.
         * Returns radians, limited to ±[limit].
         */
        fun levelFor(deviceDegrees: Int, startedAt: Int, limit: Float): Float {
            var d = (deviceDegrees - startedAt) % 360
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return Math.toRadians(d.toDouble()).toFloat().coerceIn(-limit, limit)
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uTex;
            uniform vec2 uRot;
            uniform float uScale;
            uniform vec2 uSize;
            varying vec2 vTex;
            varying vec2 vPos;
            void main() {
                // Rotate and enlarge in pixel units (keeps the picture's proportions).
                vec2 p = aPos * uSize * 0.5;
                p = vec2(p.x * uRot.x - p.y * uRot.y, p.x * uRot.y + p.y * uRot.x) * uScale;
                gl_Position = vec4(p / (uSize * 0.5), 0.0, 1.0);
                vTex = (uTex * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
                vPos = gl_Position.xy; // vignette follows the output frame
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uColor;
            uniform vec4 uOffset;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uSeed;
            varying vec2 vTex;
            varying vec2 vPos;
            float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233)) + uSeed * 43.0) * 43758.5453); }
            void main() {
                vec4 c = texture2D(uTexture, vTex);
                vec3 rgb = (uColor * vec4(c.rgb, 0.0)).rgb + uOffset.rgb;
                if (uVignette > 0.0) {
                    float r = length(vPos) / 1.41421;
                    float t = clamp((r - 0.45) / 0.55, 0.0, 1.0);
                    rgb *= 1.0 - uVignette * t * t * (3.0 - 2.0 * t);
                }
                if (uGrain > 0.0) rgb += (rand(vTex) - 0.5) * 3.4 * uGrain;
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """
    }
}
