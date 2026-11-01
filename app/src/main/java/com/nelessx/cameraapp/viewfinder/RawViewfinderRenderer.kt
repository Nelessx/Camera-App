package com.nelessx.cameraapp.viewfinder

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.nelessx.cameraapp.camera.CameraSpecs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** How the viewfinder turns sensor values into screen pixels. Nothing here affects saved files. */
data class ViewParams(
    /** Show every photosite in its own filter colour instead of combining each 2x2 block into one pixel. */
    val mosaic: Boolean = false,
    /** Lift linear sensor values with a display gamma; without it most scenes look almost black. */
    val gamma: Boolean = true,
    /** Paint photosites at the sensor's saturation level magenta: detail there is lost for good. */
    val clipWarning: Boolean = true,
    /** White balance multipliers; all 1 shows the sensor's native colour response. */
    val gainR: Float = 1f,
    val gainG: Float = 1f,
    val gainB: Float = 1f,
    /** 1 shows the whole frame; larger values magnify around the centre point. */
    val zoom: Float = 1f,
    /** Centre of the visible area, in view coordinates (0..1, y down). */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
)

/**
 * Draws RAW frames straight from the sensor data: black level subtraction, then either the
 * photosite mosaic or 2x2 "superpixels" (one red, two green, one blue photosite per output pixel,
 * no interpolation between blocks).
 */
class RawViewfinderRenderer(private val mailbox: FrameMailbox, specs: CameraSpecs) : GLSurfaceView.Renderer {

    @Volatile
    var params = ViewParams()

    /** New sensor frames actually drawn per second, measured over roughly the last second. */
    @Volatile
    var drawnFps = 0f
        private set
    private var fpsWindowStartMs = 0L
    private var fpsFrames = 0

    private val rotation = specs.sensorOrientation
    private val cfa = specs.cfaColours
    private val black = specs.blackLevels.map { it.toFloat() }
    private val white = specs.whiteLevel.toFloat()

    private var program = 0
    private var texture = 0
    private var hasFrame = false
    private val uniforms = mutableMapOf<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        UNIFORM_NAMES.forEach { uniforms[it] = GLES30.glGetUniformLocation(program, it) }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, mailbox.width, mailbox.height)
        // Integer textures can't be filtered; the shader reads exact photosites with texelFetch
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        hasFrame = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        mailbox.take()?.let { frame ->
            frame.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, mailbox.width, mailbox.height,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, frame,
            )
            // glTexSubImage2D has copied the data by the time it returns
            mailbox.recycle(frame)
            hasFrame = true
            countFrame()
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) return

        val p = params
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniforms.getValue("uRaw"), 0)
        GLES30.glUniform4i(uniforms.getValue("uCfa"), cfa[0], cfa[1], cfa[2], cfa[3])
        GLES30.glUniform4f(uniforms.getValue("uBlack"), black[0], black[1], black[2], black[3])
        GLES30.glUniform1f(uniforms.getValue("uWhite"), white)
        GLES30.glUniform1i(uniforms.getValue("uRotation"), rotation)
        GLES30.glUniform1i(uniforms.getValue("uMosaic"), if (p.mosaic) 1 else 0)
        GLES30.glUniform1i(uniforms.getValue("uGamma"), if (p.gamma) 1 else 0)
        GLES30.glUniform1i(uniforms.getValue("uClip"), if (p.clipWarning) 1 else 0)
        GLES30.glUniform3f(uniforms.getValue("uGains"), p.gainR, p.gainG, p.gainB)
        GLES30.glUniform1f(uniforms.getValue("uZoom"), p.zoom)
        GLES30.glUniform2f(uniforms.getValue("uCenter"), p.centerX, p.centerY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        fpsFrames++
        if (now - fpsWindowStartMs >= 1000) {
            drawnFps = fpsFrames * 1000f / (now - fpsWindowStartMs)
            fpsFrames = 0
            fpsWindowStartMs = now
        }
    }

    private companion object {
        val UNIFORM_NAMES = listOf(
            "uRaw", "uCfa", "uBlack", "uWhite", "uRotation", "uMosaic", "uGamma", "uClip", "uGains", "uZoom",
            "uCenter",
        )

        fun buildProgram(vertexSource: String, fragmentSource: String): Int {
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, compileShader(GLES30.GL_VERTEX_SHADER, vertexSource))
            GLES30.glAttachShader(program, compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource))
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Shader link failed: ${GLES30.glGetProgramInfoLog(program)}" }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
            return shader
        }

        // Full-screen quad without vertex buffers; vView runs 0..1 with y pointing down like the screen
        const val VERTEX_SHADER = """#version 300 es
out vec2 vView;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vView = vec2(p.x, 1.0 - p.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D uRaw;
uniform ivec4 uCfa;
uniform vec4 uBlack;
uniform float uWhite;
uniform int uRotation;
uniform bool uMosaic;
uniform bool uGamma;
uniform bool uClip;
uniform vec3 uGains;
uniform float uZoom;
uniform vec2 uCenter;

in vec2 vView;
out vec4 outColor;

// Black-subtracted photosite value, 0 = black level, 1 = saturation
float level(ivec2 p) {
    int i = (p.y & 1) * 2 + (p.x & 1);
    return (float(texelFetch(uRaw, p, 0).r) - uBlack[i]) / (uWhite - uBlack[i]);
}

vec3 colourMask(int c) {
    return c < 3 ? vec3(c == 0, c == 1, c == 2) : vec3(1.0);
}

// The photosite reached the sensor's maximum value, so its true brightness is unknown
bool saturated(ivec2 p) {
    return float(texelFetch(uRaw, p, 0).r) >= uWhite;
}

const vec4 CLIPPED = vec4(1.0, 0.0, 1.0, 1.0);

void main() {
    vec2 v = uCenter + (vView - 0.5) / uZoom;
    if (any(lessThan(v, vec2(0.0))) || any(greaterThanEqual(v, vec2(1.0)))) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // Rotate from screen orientation back to the sensor's own orientation
    vec2 s = v;
    if (uRotation == 90) s = vec2(v.y, 1.0 - v.x);
    else if (uRotation == 180) s = vec2(1.0 - v.x, 1.0 - v.y);
    else if (uRotation == 270) s = vec2(1.0 - v.y, v.x);

    ivec2 size = textureSize(uRaw, 0);
    ivec2 p = min(ivec2(s * vec2(size)), size - 1);

    vec3 rgb;
    if (uMosaic) {
        if (uClip && saturated(p)) {
            outColor = CLIPPED;
            return;
        }
        rgb = level(p) * colourMask(uCfa[(p.y & 1) * 2 + (p.x & 1)]);
    } else {
        ivec2 block = p - (p & 1);
        vec3 sum = vec3(0.0);
        vec3 count = vec3(0.0);
        bool clipped = false;
        for (int i = 0; i < 4; i++) {
            ivec2 q = block + ivec2(i & 1, i >> 1);
            vec3 mask = colourMask(uCfa[i]);
            sum += level(q) * mask;
            count += mask;
            clipped = clipped || saturated(q);
        }
        if (uClip && clipped) {
            outColor = CLIPPED;
            return;
        }
        rgb = sum / max(count, vec3(1.0));
    }
    rgb = clamp(rgb * uGains, 0.0, 1.0);
    if (uGamma) rgb = pow(rgb, vec3(1.0 / 2.2));
    outColor = vec4(rgb, 1.0);
}
"""
    }
}
