package app.lukarne.globe.gl

import android.opengl.GLES20.*
import android.util.Log
import app.lukarne.data.Catalog
import app.lukarne.globe.GlobeGeometry
import app.lukarne.globe.GlobeNav
import app.lukarne.globe.Sphere
import app.lukarne.ui.theme.Palette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * What the GL thread draws, written by the UI thread every frame. Plain volatile fields: the GL thread only
 * needs the latest values, never a consistent history.
 */
class GlobeCamera {
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var zoom = 1f
    /** Globe centre as a fraction of the view size. */
    @Volatile var centreX = 0.62f
    @Volatile var centreY = 0.5f
    @Volatile var focusRegion = -1
    @Volatile var sunLat = 0.0
    @Volatile var sunLon = 0.0
    /** Still-only places: full lights when stills are on, faint embers otherwise. */
    @Volatile var stills = false
    /** 0..1 through the dive into a place (GlobeScreen): the lights bloom as the camera falls towards them. */
    @Volatile var dive = 0f
    /** Frames the GL thread has drawn: a fresh surface takes a few hundred ms to show its first. */
    @Volatile var drawn = 0
}

/**
 * The globe in GLES 2: geometry lives in VBOs uploaded once; a frame sets a handful of uniforms and issues four
 * draw calls (sky+ocean quad, land dots, coastline hairlines, lights). Projection is orthographic, done in the
 * vertex shader. The Canvas version of the same drawing cost 30–150 ms per frame on the Shield's RenderThread.
 */
class GlobeGl(private val geo: GlobeGeometry, catalog: Catalog, private val cam: GlobeCamera) : GlSurface.Renderer {
    private var width = 1; private var height = 1
    private var skyProg = 0; private var dotProg = 0; private var coastProg = 0; private var lightProg = 0
    private val vbo = IntArray(4)
    private var coastVerts = 0
    private var lightCount = 0

    private val lightData: FloatArray = run {
        val regionIndex = catalog.regions.withIndex().associate { (i, r) -> r.id to i }
        val out = FloatArray(catalog.places.size * 6)
        catalog.places.forEachIndexed { i, p ->
            out[i * 6] = Sphere.x(p.lat, p.lon).toFloat()
            out[i * 6 + 1] = Sphere.y(p.lat, p.lon).toFloat()
            out[i * 6 + 2] = Sphere.z(p.lat).toFloat()
            out[i * 6 + 3] = (1f + ln(p.cams.coerceAtLeast(1).toFloat()) * 0.45f) // weight
            out[i * 6 + 4] = if (p.live > 0) 1f else 0f
            out[i * 6 + 5] = (regionIndex[p.region] ?: -1).toFloat()
        }
        lightCount = catalog.places.size
        out
    }

    override fun onCreated() {
        skyProg = program(SKY_VS, SKY_FS)
        dotProg = program(DOT_VS, DOT_FS)
        coastProg = program(COAST_VS, COAST_FS)
        lightProg = program(LIGHT_VS, LIGHT_FS)
        glGenBuffers(4, vbo, 0)
        upload(vbo[0], floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        upload(vbo[1], geo.dots)
        upload(vbo[2], coastSegments().also { coastVerts = it.size / 3 })
        upload(vbo[3], lightData)
        Log.i(TAG, "GL ready: ${geo.dotCount} dots, ${coastVerts / 2} coast segments, $lightCount lights")
    }

    override fun onSize(width: Int, height: Int) {
        this.width = width; this.height = height
        glViewport(0, 0, width, height)
    }

    override fun onDestroyed() {
        glDeleteBuffers(4, vbo, 0)
        listOf(skyProg, dotProg, coastProg, lightProg).forEach(::glDeleteProgram)
    }

    override fun onDraw() {
        val lat0 = Sphere.rad(cam.lat); val lon0 = Sphere.rad(cam.lon)
        val e = floatArrayOf(-sin(lon0).toFloat(), cos(lon0).toFloat(), 0f)
        val n = floatArrayOf((-sin(lat0) * cos(lon0)).toFloat(), (-sin(lat0) * sin(lon0)).toFloat(), cos(lat0).toFloat())
        val u = floatArrayOf((cos(lat0) * cos(lon0)).toFloat(), (cos(lat0) * sin(lon0)).toFloat(), sin(lat0).toFloat())
        val sun = floatArrayOf(
            Sphere.x(cam.sunLat, cam.sunLon).toFloat(), Sphere.y(cam.sunLat, cam.sunLon).toFloat(), Sphere.z(cam.sunLat).toFloat(),
        )
        val zoom = cam.zoom
        val r = height / 2f * GlobeNav.GLOBE_FILL.toFloat() * zoom
        val cx = width * cam.centreX; val cy = height * cam.centreY

        glClearColor(INK[0], INK[1], INK[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)

        // Sky + ocean: one quad, per-pixel sphere normal for the day/night shading of the sea.
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        use(skyProg, e, n, u, sun, cx, cy, r)
        drawArrays(vbo[0], skyProg, 2, GL_TRIANGLE_STRIP, 4)

        // Land dots. Zoomed in they stay small and fade; the coastline takes over.
        val spacing = r * 0.0140f
        use(dotProg, e, n, u, sun, cx, cy, r)
        glUniform1f(glGetUniformLocation(dotProg, "uSize"), (r * 0.0062f).coerceIn(2.2f, 3.6f))
        glUniform1f(glGetUniformLocation(dotProg, "uFade"), (12f / spacing).coerceIn(0.25f, 1f))
        drawArrays(vbo[1], dotProg, 3, GL_POINTS, geo.dotCount)

        // Coastline hairlines, stronger when zoomed in.
        use(coastProg, e, n, u, sun, cx, cy, r)
        glUniform1f(glGetUniformLocation(coastProg, "uAlpha"), 0.22f + 0.38f * ((zoom - 1f) / 4f).coerceIn(0f, 1f))
        glLineWidth(1.3f)
        drawArrays(vbo[2], coastProg, 3, GL_LINES, coastVerts)

        // Lights, additive: overlapping cities bloom together.
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        use(lightProg, e, n, u, sun, cx, cy, r)
        glUniform1f(glGetUniformLocation(lightProg, "uScale"), (0.8f + zoom * 0.12f).coerceAtMost(1.8f) * (1f + cam.dive * 5f))
        glUniform1f(glGetUniformLocation(lightProg, "uFocus"), cam.focusRegion.toFloat())
        glUniform1f(glGetUniformLocation(lightProg, "uStills"), if (cam.stills) 1f else 0f)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[3])
        val aPos = glGetAttribLocation(lightProg, "aPos")
        val aMeta = glGetAttribLocation(lightProg, "aMeta")
        glEnableVertexAttribArray(aPos); glEnableVertexAttribArray(aMeta)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 24, 0)
        glVertexAttribPointer(aMeta, 3, GL_FLOAT, false, 24, 12)
        glDrawArrays(GL_POINTS, 0, lightCount)
        glDisableVertexAttribArray(aPos); glDisableVertexAttribArray(aMeta)
        cam.drawn++
    }

    private fun use(p: Int, e: FloatArray, n: FloatArray, u: FloatArray, sun: FloatArray, cx: Float, cy: Float, r: Float) {
        glUseProgram(p)
        glUniform3fv(glGetUniformLocation(p, "uE"), 1, e, 0)
        glUniform3fv(glGetUniformLocation(p, "uN"), 1, n, 0)
        glUniform3fv(glGetUniformLocation(p, "uU"), 1, u, 0)
        glUniform3fv(glGetUniformLocation(p, "uSun"), 1, sun, 0)
        glUniform2f(glGetUniformLocation(p, "uCentre"), cx, cy)
        glUniform2f(glGetUniformLocation(p, "uView"), width.toFloat(), height.toFloat())
        glUniform1f(glGetUniformLocation(p, "uR"), r)
    }

    private fun drawArrays(buffer: Int, prog: Int, comps: Int, mode: Int, count: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        val a = glGetAttribLocation(prog, "aPos")
        glEnableVertexAttribArray(a)
        glVertexAttribPointer(a, comps, GL_FLOAT, false, 0, 0)
        glDrawArrays(mode, 0, count)
        glDisableVertexAttribArray(a)
    }

    /** Coastline chunks (polylines) → independent segments for GL_LINES. */
    private fun coastSegments(): FloatArray {
        var segs = 0
        for (k in 0 until geo.chunks) segs += geo.chunkCount[k] - 1
        val out = FloatArray(segs * 6)
        var o = 0
        for (k in 0 until geo.chunks) {
            val s = geo.chunkStart[k] * 3
            for (i in 0 until geo.chunkCount[k] - 1) {
                System.arraycopy(geo.coast, s + i * 3, out, o, 6); o += 6
            }
        }
        return out
    }

    private fun upload(id: Int, data: FloatArray) {
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, id)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, GL_STATIC_DRAW)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also {
            glShaderSource(it, src); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: ${glGetShaderInfoLog(it)}")
        }
        return glCreateProgram().also {
            glAttachShader(it, shader(GL_VERTEX_SHADER, vs)); glAttachShader(it, shader(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(it)
            val ok = IntArray(1); glGetProgramiv(it, GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) error("link: ${glGetProgramInfoLog(it)}")
        }
    }

    private companion object {
        const val TAG = "Lukarne.Globe"
        val INK = floatArrayOf(Palette.Ink.red, Palette.Ink.green, Palette.Ink.blue)

        fun rgb(c: androidx.compose.ui.graphics.Color) = "vec3(${c.red}, ${c.green}, ${c.blue})"

        // Shared header: camera basis and the orthographic projection to clip space (screen y grows downward).
        val HEAD = """
            precision mediump float;
            uniform vec3 uE; uniform vec3 uN; uniform vec3 uU; uniform vec3 uSun;
            uniform vec2 uCentre; uniform vec2 uView; uniform float uR;
            vec4 clip(vec3 p) {
                vec2 s = uCentre + uR * vec2(dot(p, uE), -dot(p, uN));
                return vec4(s.x / uView.x * 2.0 - 1.0, 1.0 - s.y / uView.y * 2.0, 0.0, 1.0);
            }
        """.trimIndent()

        val SKY_VS = "attribute vec2 aPos; void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"
        val SKY_FS = """
            $HEAD
            void main() {
                vec2 px = vec2(gl_FragCoord.x, uView.y - gl_FragCoord.y);
                vec2 q = (px - uCentre) / uR;
                float d = length(q);
                if (d < 1.0) {
                    vec3 p = uE * q.x - uN * q.y + uU * sqrt(1.0 - d * d);
                    float day = smoothstep(-0.15, 0.25, dot(p, uSun));
                    float rim = smoothstep(0.75, 1.0, d);
                    vec3 sea = mix(${rgb(Palette.Deep)}, ${rgb(Palette.Ocean)} * 1.35, day);
                    gl_FragColor = vec4(mix(sea, ${rgb(Palette.Rim)} * 0.35, rim * 0.5), 1.0);
                } else {
                    float glow = exp(-(d - 1.0) * 28.0) * 0.32;
                    gl_FragColor = vec4(${rgb(Palette.Rim)}, glow);
                }
            }
        """.trimIndent()

        val DOT_VS = """
            $HEAD
            attribute vec3 aPos; uniform float uSize; varying float vB;
            void main() {
                float depth = dot(aPos, uU);
                float day = smoothstep(-0.12, 0.14, dot(aPos, uSun));
                vB = (0.30 + 0.70 * sqrt(max(depth, 0.0))) * (0.16 + 0.84 * day);
                gl_Position = depth > 0.0 ? clip(aPos) : vec4(3.0, 3.0, 3.0, 1.0);
                gl_PointSize = uSize;
            }
        """.trimIndent()
        val DOT_FS = """
            precision mediump float; varying float vB; uniform float uFade;
            void main() {
                vec3 night = ${rgb(Palette.Land)} * 0.55; vec3 day = vec3(0.73, 0.79, 0.89);
                gl_FragColor = vec4(mix(night, day, vB), (0.35 + 0.65 * vB) * uFade);
            }
        """.trimIndent()

        val COAST_VS = """
            $HEAD
            attribute vec3 aPos; varying float vD;
            void main() { vD = dot(aPos, uU); gl_Position = clip(aPos); }
        """.trimIndent()
        val COAST_FS = """
            precision mediump float; varying float vD; uniform float uAlpha;
            void main() { if (vD <= 0.0) discard; gl_FragColor = vec4(${rgb(Palette.Rim)}, uAlpha * smoothstep(0.0, 0.15, vD)); }
        """.trimIndent()

        val LIGHT_VS = """
            $HEAD
            attribute vec3 aPos; attribute vec3 aMeta; uniform float uScale; uniform float uFocus; uniform float uStills;
            varying float vA; varying float vLive; varying float vCore;
            void main() {
                float depth = dot(aPos, uU);
                float inFocus = abs(aMeta.z - uFocus) < 0.5 ? 1.0 : 0.55;
                float still = aMeta.y < 0.5 && uStills < 0.5 ? 1.0 : 0.0;   // still-only place, stills hidden
                vA = inFocus * (0.4 + 0.6 * max(depth, 0.0)) * (still > 0.5 ? 0.22 : 1.0);
                vLive = aMeta.y;
                float glow = (aMeta.y > 0.5 ? 15.0 : (still > 0.5 ? 5.0 : 9.0)) * aMeta.x * uScale;
                float core = (aMeta.y > 0.5 ? 2.6 : 1.7) * aMeta.x * uScale;
                vCore = core / glow;
                gl_Position = depth > 0.02 ? clip(aPos) : vec4(3.0, 3.0, 3.0, 1.0);
                gl_PointSize = glow * 2.0;
            }
        """.trimIndent()
        val LIGHT_FS = """
            precision mediump float; varying float vA; varying float vLive; varying float vCore;
            void main() {
                float r = length(gl_PointCoord - 0.5) * 2.0;
                if (r > 1.0) discard;
                float core = 1.0 - smoothstep(vCore * 0.7, vCore, r);
                float halo = pow(1.0 - r, 2.4) * 0.55;
                vec3 c = mix(${rgb(Palette.Light)}, ${rgb(Palette.LightHot)}, core * (0.5 + 0.5 * vLive));
                gl_FragColor = vec4(c, (halo + core) * vA);
            }
        """.trimIndent()
    }
}
