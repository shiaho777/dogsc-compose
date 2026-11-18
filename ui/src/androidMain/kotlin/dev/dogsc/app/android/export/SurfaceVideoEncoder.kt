package dev.dogsc.app.android.export

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Surface-input H.264 encoder (stage 6a). Instead of converting every frame to YUV on the CPU
 * and queueing a byte buffer, the rendered bitmap is uploaded as a GL texture and drawn into the
 * encoder's input surface; the color conversion happens in hardware. This removes the per-frame
 * argb->YUV copy that dominates the CPU export path.
 *
 * Falls back to [CanvasVideoEncoder] when EGL/GLES setup or the surface-input configure fails,
 * so export never regresses on devices without a usable GL context.
 */
class SurfaceVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrate: Int,
    private val output: File,
) : VideoEncoder {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var inputSurface: Surface? = null
    private var textureId = 0
    private var program = 0
    private var vertexBuffer: FloatBuffer
    private var texBuffer: FloatBuffer

    // Full-screen quad (clip space) and matching UVs. Bitmap textures are top-left origin,
    // GL is bottom-left, so the V coordinate is flipped to keep the frame upright.
    private val vertices = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )
    private val uvs = floatArrayOf(
        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f,
    )

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).position(0) as FloatBuffer
        texBuffer = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(uvs).position(0) as FloatBuffer

        // Any failure below must release what was already acquired: the caller
        // swallows the exception via runCatching and falls back to the CPU encoder,
        // so a leaked started MediaCodec/Surface/EGL context here would accumulate
        // across retries and eventually exhaust hardware codec instances.
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            setupEgl(inputSurface!!)
            setupGl()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    private fun setupEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) { "no EGL config" }
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) { "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glViewport(0, 0, width, height)
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    // Duplicate frames reuse the previous bitmap instance; the texture already holds
    // those pixels, so skip the upload and just redraw with the new timestamp.
    private var lastUploadedBitmap: Bitmap? = null

    override fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (bitmap !== lastUploadedBitmap) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            lastUploadedBitmap = bitmap
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
        // The encoder reads the surface with this timestamp; set it before swapping.
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeUs * 1000)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        drain(false)
    }

    override fun finish() {
        try {
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            release()
        }
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        muxer?.let { active ->
            if (muxerStarted) runCatching { active.stop() }
            runCatching { active.release() }
            muxer = null
            muxerStarted = false
        }
        runCatching {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                // Do NOT eglTerminate the default display: it is shared process-wide with the
                // Compose RenderThread and other codecs. Terminating it raises EGL_NOT_INITIALIZED
                // in unrelated rendering and aborts the process. Only our own surface/context go.
                eglSurface = EGL14.EGL_NO_SURFACE
                eglContext = EGL14.EGL_NO_CONTEXT
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
        runCatching { inputSurface?.release() }
        if (textureId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 500) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val active = muxer
                    if (!muxerStarted && active != null) {
                        trackIndex = active.addTrack(codec.outputFormat)
                        active.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    idle = 0
                    val buffer = codec.getOutputBuffer(index)
                    val active = muxer
                    if (buffer != null && bufferInfo.size > 0 && muxerStarted && active != null) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        active.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val VERTEX_SRC = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SRC = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        /** True when a Surface-input encoder can likely be created (API 18+ has createInputSurface). */
        fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 18
    }
}