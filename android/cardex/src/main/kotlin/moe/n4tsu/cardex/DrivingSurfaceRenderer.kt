package moe.n4tsu.cardex

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns a relay input Surface and fits its frames inside the car host's safe area.
 * The host Surface is borrowed: its owner must call clearOutput before releasing it.
 * Input remains alive across output changes; callbacks always run on the main thread.
 */
internal class DrivingSurfaceRenderer(
    private val onReady: (Surface) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("DextopDrivingRenderer").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var failed = false

    // EGL and all fields below are confined to the renderer thread.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var borrowedOutput: Surface? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputArea = DrivingBounds(0, 0, 0, 0)
    private var inputWidth = 1
    private var inputHeight = 1
    private var textureId = 0
    private var program = 0
    private var positionLocation = -1
    private var coordinateLocation = -1
    private var matrixLocation = -1
    private var samplerLocation = -1
    private var texture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var hasFrame = false
    private var frameDrawScheduled = false
    private val frameDraw = Runnable {
        frameDrawScheduled = false
        guarded { draw(updateTexture = true) }
    }
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ))
            position(0)
        }

    init {
        handler.post { guarded { initialize() } }
    }

    /** Updates output geometry without replacing the relay input Surface. */
    fun setOutput(
        surface: Surface,
        width: Int,
        height: Int,
        area: DrivingBounds,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        if (closed || failed) return
        handler.post {
            guarded {
                val clipped = DrivingBounds(
                    area.left.coerceAtLeast(0), area.top.coerceAtLeast(0),
                    area.right.coerceAtMost(width), area.bottom.coerceAtMost(height),
                )
                if (!surface.isValid || width <= 0 || height <= 0 || clipped.isEmpty ||
                    inputWidth <= 0 || inputHeight <= 0) {
                    releaseOutput()
                    return@guarded
                }
                if (this.inputWidth != inputWidth || this.inputHeight != inputHeight) {
                    texture?.setDefaultBufferSize(inputWidth, inputHeight)
                    this.inputWidth = inputWidth
                    this.inputHeight = inputHeight
                    // A cached frame still belongs to the previous buffer geometry.
                    hasFrame = false
                }
                if (borrowedOutput !== surface || window == EGL14.EGL_NO_SURFACE) {
                    releaseOutput()
                    window = try {
                        EGL14.eglCreateWindowSurface(
                            display, checkNotNull(config), surface,
                            intArrayOf(EGL14.EGL_NONE), 0,
                        )
                    } catch (error: IllegalArgumentException) {
                        if (!surface.isValid) return@guarded
                        throw error
                    }
                    if (window == EGL14.EGL_NO_SURFACE) {
                        val eglError = EGL14.eglGetError()
                        if (!surface.isValid || isLostOutput(eglError)) return@guarded
                        error(eglFailure("create window surface", eglError))
                    }
                    borrowedOutput = surface
                }
                outputWidth = width
                outputHeight = height
                outputArea = clipped
                draw(updateTexture = false)
            }
        }
    }

    /** Detaches the borrowed host with a bounded barrier before the host releases it. */
    fun clearOutput() {
        if (closed || failed) return
        onRendererAndWait { releaseOutput() }
    }

    override fun close() {
        if (closed) return
        closed = true
        onRendererAndWait { releaseResources() }
        thread.quitSafely()
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { eglFailure("get display") }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { eglFailure("initialize") }
        val configurations = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, count, 0) &&
            count[0] > 0) { eglFailure("choose config") }
        config = checkNotNull(configurations[0])
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { eglFailure("create context") }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { eglFailure("create pbuffer") }
        makeCurrent(pbuffer)
        program = createProgram()
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        coordinateLocation = GLES20.glGetAttribLocation(program, "aCoordinate")
        matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        check(textureId != 0) { "Unable to allocate the relay texture" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputWidth, inputHeight)
            setOnFrameAvailableListener({
                if (!closed && !failed && !frameDrawScheduled) {
                    frameDrawScheduled = true
                    handler.post(frameDraw)
                }
            }, handler)
        }
        val readySurface = Surface(checkNotNull(texture))
        inputSurface = readySurface
        mainHandler.post { if (!closed && !failed) onReady(readySurface) }
    }

    private fun draw(updateTexture: Boolean) {
        if (borrowedOutput?.isValid == false) releaseOutput()
        if (window != EGL14.EGL_NO_SURFACE) {
            if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                val eglError = EGL14.eglGetError()
                if (borrowedOutput?.isValid == false || isLostOutput(eglError)) {
                    releaseOutput()
                    makeCurrent(pbuffer)
                } else {
                    error(eglFailure("make output current", eglError))
                }
            }
        } else {
            makeCurrent(pbuffer)
        }
        if (updateTexture) {
            checkNotNull(texture).updateTexImage()
            checkNotNull(texture).getTransformMatrix(textureMatrix)
            hasFrame = true
        }
        // Continue consuming input while the host is absent, so the producer cannot stall.
        if (window == EGL14.EGL_NO_SURFACE) return
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val fitted = DrivingViewport.fit(inputWidth, inputHeight, outputArea)
        if (hasFrame && !fitted.isEmpty) {
            // Car coordinates start at the top; EGL viewport coordinates start at the bottom.
            GLES20.glViewport(fitted.left, outputHeight - fitted.bottom, fitted.width, fitted.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(samplerLocation, 0)
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
            vertices.position(0)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(coordinateLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glEnableVertexAttribArray(coordinateLocation)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(coordinateLocation)
        }
        if (!EGL14.eglSwapBuffers(display, window)) {
            val eglError = EGL14.eglGetError()
            if (borrowedOutput?.isValid == false || isLostOutput(eglError)) {
                releaseOutput()
            } else {
                error(eglFailure("swap buffers", eglError))
            }
        }
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { eglFailure("make current") }
    }

    private fun releaseOutput() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        borrowedOutput = null
        outputWidth = 0
        outputHeight = 0
    }

    private fun releaseResources() {
        handler.removeCallbacks(frameDraw)
        frameDrawScheduled = false
        runCatching { texture?.setOnFrameAvailableListener(null) }
        releaseOutput()
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { texture?.release() }
        texture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        textureId = 0
        program = 0
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private inline fun guarded(action: () -> Unit) {
        if (closed || failed) return
        try {
            action()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        failed = true
        runCatching { releaseResources() }
        thread.quitSafely()
        mainHandler.post { if (!closed) onError(error) }
    }

    private fun onRendererAndWait(action: () -> Unit) {
        if (Looper.myLooper() === handler.looper) {
            action()
            return
        }
        val complete = CountDownLatch(1)
        if (!handler.post {
                try { action() } catch (error: Throwable) { fail(error) }
                finally { complete.countDown() }
            }) return
        try {
            complete.await(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isLostOutput(error: Int): Boolean = error == EGL14.EGL_BAD_SURFACE ||
        error == EGL14.EGL_BAD_NATIVE_WINDOW || error == EGL14.EGL_BAD_CURRENT_SURFACE

    private fun eglFailure(operation: String, error: Int = EGL14.eglGetError()) =
        "EGL $operation failed (0x${error.toString(16)})"

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPosition;
            attribute vec4 aCoordinate;
            uniform mat4 uTextureMatrix;
            varying vec2 vCoordinate;
            void main() {
                gl_Position = aPosition;
                vCoordinate = (uTextureMatrix * aCoordinate).xy;
            }
        """.trimIndent())
        try {
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                varying vec2 vCoordinate;
                void main() { gl_FragColor = texture2D(uTexture, vCoordinate); }
            """.trimIndent())
            try {
                val linked = GLES20.glCreateProgram()
                check(linked != 0) { "Unable to create the relay shader program" }
                GLES20.glAttachShader(linked, vertex)
                GLES20.glAttachShader(linked, fragment)
                GLES20.glLinkProgram(linked)
                val status = IntArray(1)
                GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val detail = GLES20.glGetProgramInfoLog(linked)
                    GLES20.glDeleteProgram(linked)
                    error("Unable to link the relay shader: $detail")
                }
                return linked
            } finally {
                GLES20.glDeleteShader(fragment)
            }
        } finally {
            GLES20.glDeleteShader(vertex)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create a relay shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val detail = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile the relay shader: $detail")
        }
        return shader
    }
}
