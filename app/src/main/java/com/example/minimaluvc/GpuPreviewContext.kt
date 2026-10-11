package com.example.minimaluvc

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EGL / GLES2 对象完全由专用线程拥有。离屏 Pbuffer 保证屏幕消失时仍能消费 UVC 纹理。 */
internal class GpuPreviewContext {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var windowEpoch = -1L
    private var program = 0
    private var position = 0
    private var coordinates = 0
    private var transform = 0
    private var sampler = 0
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display 不可用" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL 初始化失败" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL 配置不可用" }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL 上下文创建失败" }
            offscreen = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(offscreen != EGL14.EGL_NO_SURFACE) { "离屏 EGL 创建失败" }
            makeOffscreenCurrent()
            val vertex = shader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uTransform;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = (uTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """.trimIndent())
            val fragment = try { shader(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES uTexture;
                varying vec2 vTexCoord;
                void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
            """.trimIndent()) } catch (error: RuntimeException) { GLES20.glDeleteShader(vertex); throw error }
            try {
                program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vertex)
                GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            } finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
            position = GLES20.glGetAttribLocation(program, "aPosition")
            coordinates = GLES20.glGetAttribLocation(program, "aTexCoord")
            transform = GLES20.glGetUniformLocation(program, "uTransform")
            sampler = GLES20.glGetUniformLocation(program, "uTexture")
        } catch (error: RuntimeException) { release(); throw error }
    }

    fun makeOffscreenCurrent() {
        check(EGL14.eglMakeCurrent(display, offscreen, offscreen, context)) { "离屏 EGL 绑定失败" }
    }

    fun newExternalTexture(): Int {
        makeOffscreenCurrent()
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR && texture[0] != 0) { "外部纹理创建失败" }
        return texture[0]
    }

    fun deleteTexture(texture: Int) {
        makeOffscreenCurrent()
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private fun bindWindow(target: SurfacePreviewRenderer.Screen): Boolean {
        if (target.surface?.isValid != true) return false
        if (windowEpoch != target.epoch || window == EGL14.EGL_NO_SURFACE) {
            detachWindow()
            window = EGL14.eglCreateWindowSurface(display, config, target.surface,
                intArrayOf(EGL14.EGL_NONE), 0)
            if (window == EGL14.EGL_NO_SURFACE) return false
            windowEpoch = target.epoch
        }
        if (!EGL14.eglMakeCurrent(display, window, window, context)) { detachWindow(); return false }
        // 请求不额外等待刷新周期；最终排队和显示时机仍由系统及驱动决定。
        EGL14.eglSwapInterval(display, 0)
        val width = IntArray(1); val height = IntArray(1)
        if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, width, 0) ||
            !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, height, 0)) return false
        GLES20.glViewport(0, 0, width[0], height[0])
        return true
    }

    fun draw(target: SurfacePreviewRenderer.Screen, texture: Int, matrix: FloatArray, valid: () -> Boolean): Boolean {
        if (!valid() || !bindWindow(target)) return false
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0)
        positions.position(0); texCoords.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinates)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(coordinates, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinates)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR || !valid()) return false
        val submitted = EGL14.eglSwapBuffers(display, window)
        if (!submitted) detachWindow()
        return submitted && valid()
    }

    fun clear(target: SurfacePreviewRenderer.Screen) {
        if (!bindWindow(target)) return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!EGL14.eglSwapBuffers(display, window)) detachWindow()
    }

    fun detachWindow() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (offscreen != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT)
            EGL14.eglMakeCurrent(display, offscreen, offscreen, context)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE
        windowEpoch = -1
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        detachWindow()
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (offscreen != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, offscreen)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        display = EGL14.EGL_NO_DISPLAY
        offscreen = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(reason)
        }
        return shader
    }

    private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
