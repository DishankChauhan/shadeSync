package com.shadesync.app

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 lipstick renderer using a real 3D triangle mesh
 * mapped to MediaPipe face landmarks — the same technique ModiFace uses.
 *
 * Rendering pipeline:
 *   1. Build triangle mesh from outer + inner lip contours (80 triangles)
 *   2. Per-vertex attributes: position, edge factor, lip-region (upper/lower)
 *   3. Fragment shader: lipstick color with edge feathering, depth shading,
 *      specular gloss highlights, and skin-blending
 *   4. Alpha-blended onto transparent surface over camera preview
 */
class LipGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = LipMeshRenderer()
    private var glReady = false

    init {
        try {
            setEGLContextClientVersion(2)
            try {
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            } catch (e: Exception) {
                try {
                    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                } catch (e2: Exception) {
                    // Let the system pick any config
                }
            }
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
            setZOrderMediaOverlay(true)
            glReady = true
        } catch (e: Exception) {
            android.util.Log.e("LipGLView", "OpenGL init failed: ${e.message}", e)
            // View will remain but do nothing — app won't crash
        }
    }

    // ── Public API (mirrors FaceMeshOverlayView interface) ──

    fun setResults(result: FaceLandmarkerResult, imgW: Int, imgH: Int) {
        if (!glReady) return
        renderer.updateLandmarks(result, imgW, imgH, width, height)
        requestRender()
    }

    fun clear() {
        if (!glReady) return
        renderer.clearLandmarks()
        requestRender()
    }

    fun setLipColor(r: Int, g: Int, b: Int) {
        if (!glReady) return
        renderer.setColor(r, g, b)
        requestRender()
    }

    var isGlossy: Boolean
        get() = if (glReady) renderer.glossy else true
        set(value) { if (glReady) { renderer.glossy = value; requestRender() } }

    var brightness: Float
        get() = if (glReady) renderer.brightness else 1.0f
        set(value) { if (glReady) { renderer.brightness = value.coerceIn(0.3f, 1.8f); requestRender() } }

    var toneShift: Float
        get() = if (glReady) renderer.toneShift else 0f
        set(value) { if (glReady) { renderer.toneShift = value.coerceIn(-1f, 1f); requestRender() } }
}

// ═══════════════════════════════════════════════════════════════
//  OpenGL ES 2.0 Renderer
// ═══════════════════════════════════════════════════════════════

private class LipMeshRenderer : GLSurfaceView.Renderer {

    // ── Lip mesh topology ──
    // Outer lip contour (top edge), left to right
    private val UPPER_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
    // Upper lip inner contour (bottom of upper lip)
    private val UPPER_INNER = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308)
    // Upper lip mid-contour (between outer and inner for denser mesh)
    private val UPPER_MID = intArrayOf(76, 184, 74, 73, 72, 11, 302, 303, 304, 408, 306)
    // Lower lip inner contour (top of lower lip)
    private val LOWER_INNER = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308)
    // Lower lip mid-contour
    private val LOWER_MID = intArrayOf(77, 90, 180, 85, 16, 315, 404, 320, 307, 325, 292)
    // Outer lip contour (bottom edge), left to right
    private val LOWER_OUTER = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)

    // All unique landmark indices used in the mesh
    private val ALL_LANDMARKS: IntArray
    private val landmarkToVertex: Map<Int, Int>  // landmark index → vertex index

    // Triangle indices (referencing vertex indices, not landmark indices)
    private val triangleIndices: ShortArray

    // Per-vertex edge factor: 1.0 = outer edge, 0.0 = inner/mid
    private val vertexEdgeFactors: FloatArray

    // Per-vertex region: 0.0 = upper lip, 1.0 = lower lip (for gloss placement)
    private val vertexRegion: FloatArray

    init {
        // Collect all unique landmarks
        val allSet = linkedSetOf<Int>()
        for (arr in arrayOf(UPPER_OUTER, UPPER_MID, UPPER_INNER, LOWER_INNER, LOWER_MID, LOWER_OUTER)) {
            for (idx in arr) allSet.add(idx)
        }
        ALL_LANDMARKS = allSet.toIntArray()
        landmarkToVertex = ALL_LANDMARKS.withIndex().associate { (vi, li) -> li to vi }

        // Build triangle strips between contour pairs
        val indices = mutableListOf<Short>()
        fun addStrip(top: IntArray, bot: IntArray) {
            for (i in 0 until top.size - 1) {
                val t0 = landmarkToVertex[top[i]]!!.toShort()
                val t1 = landmarkToVertex[top[i + 1]]!!.toShort()
                val b0 = landmarkToVertex[bot[i]]!!.toShort()
                val b1 = landmarkToVertex[bot[i + 1]]!!.toShort()
                // Two triangles per quad
                indices.add(t0); indices.add(b0); indices.add(t1)
                indices.add(t1); indices.add(b0); indices.add(b1)
            }
        }
        // Upper lip: outer → mid → inner (2 strips)
        addStrip(UPPER_OUTER, UPPER_MID)
        addStrip(UPPER_MID, UPPER_INNER)
        // Lower lip: inner → mid → outer (2 strips)
        addStrip(LOWER_INNER, LOWER_MID)
        addStrip(LOWER_MID, LOWER_OUTER)
        triangleIndices = indices.toShortArray()

        // Edge factors
        vertexEdgeFactors = FloatArray(ALL_LANDMARKS.size)
        val outerSet = (UPPER_OUTER.toSet() + LOWER_OUTER.toSet())
        val innerSet = (UPPER_INNER.toSet() + LOWER_INNER.toSet())
        for ((li, vi) in landmarkToVertex) {
            vertexEdgeFactors[vi] = when (li) {
                in outerSet -> 1.0f
                in innerSet -> 0.15f  // slight feather for mouth opening edge
                else -> 0.0f         // mid contour = center of lip
            }
        }

        // Region: upper or lower
        vertexRegion = FloatArray(ALL_LANDMARKS.size)
        val lowerSet = (LOWER_INNER.toSet() + LOWER_MID.toSet() + LOWER_OUTER.toSet())
        for ((li, vi) in landmarkToVertex) {
            vertexRegion[vi] = if (li in lowerSet) 1.0f else 0.0f
        }
    }

    // ── Vertex data (updated each frame) ──
    // Per vertex: x, y, edgeFactor, region = 4 floats
    private val VERTEX_STRIDE = 4
    private val vertexData = FloatArray(ALL_LANDMARKS.size * VERTEX_STRIDE)
    private var vertexBuffer: FloatBuffer? = null
    private var indexBuffer: ShortBuffer? = null

    // ── Temporal smoothing ──
    private val smoothedX = FloatArray(478)
    private val smoothedY = FloatArray(478)
    private var hasSmoothed = false
    private val SMOOTH = 0.5f  // 0 = max smooth, 1 = raw

    // ── State ──
    @Volatile var glossy = true
    @Volatile var brightness = 1.0f
    @Volatile var toneShift = 0.0f
    private var lipR = 200; private var lipG = 30; private var lipB = 60
    @Volatile private var hasData = false

    // ── GL handles ──
    private var program = 0
    private var uColorLoc = 0
    private var uGlossyLoc = 0
    private var uCenterYLoc = 0
    private var aPositionLoc = 0
    private var aEdgeLoc = 0
    private var aRegionLoc = 0

    // ── Coordinate transform ──
    private var viewW = 1; private var viewH = 1
    private var imgW = 1; private var imgH = 1
    private var tScale = 1f; private var tOffX = 0f; private var tOffY = 0f

    // ── Expansion for edge-to-edge coverage ──
    private val EXPANSION = 0.08f

    fun setColor(r: Int, g: Int, b: Int) {
        lipR = r; lipG = g; lipB = b
    }

    fun updateLandmarks(result: FaceLandmarkerResult, iw: Int, ih: Int, vw: Int, vh: Int) {
        if (result.faceLandmarks().isEmpty()) { hasData = false; return }
        val lm = result.faceLandmarks()[0]
        imgW = iw; imgH = ih; viewW = vw; viewH = vh
        computeTransform()

        // Temporal smoothing init
        if (!hasSmoothed) {
            for (i in 0 until minOf(lm.size, 478)) {
                smoothedX[i] = lm[i].x(); smoothedY[i] = lm[i].y()
            }
            hasSmoothed = true
        }

        // Compute centroid of outer lip for expansion
        var cx = 0f; var cy = 0f; var count = 0
        for (idx in UPPER_OUTER) {
            val sx = smoothX(idx, lm); val sy = smoothY(idx, lm)
            cx += sx; cy += sy; count++
        }
        for (idx in LOWER_OUTER) {
            val sx = smoothX(idx, lm); val sy = smoothY(idx, lm)
            cx += sx; cy += sy; count++
        }
        cx /= count; cy /= count

        // Fill vertex data with expanded positions
        for (i in ALL_LANDMARKS.indices) {
            val li = ALL_LANDMARKS[i]
            var px = toScreenX(smoothX(li, lm))
            var py = toScreenY(smoothY(li, lm))

            // Expand outer vertices away from centroid
            val ef = vertexEdgeFactors[i]
            if (ef > 0.5f) {
                val cxs = toScreenX(cx); val cys = toScreenY(cy)
                val dx = px - cxs; val dy = py - cys
                px += dx * EXPANSION
                py += dy * EXPANSION
            }

            // Convert screen coords to GL NDC (-1..1)
            val ndcX = (px / viewW) * 2f - 1f
            val ndcY = 1f - (py / viewH) * 2f

            val base = i * VERTEX_STRIDE
            vertexData[base] = ndcX
            vertexData[base + 1] = ndcY
            vertexData[base + 2] = vertexEdgeFactors[i]
            vertexData[base + 3] = vertexRegion[i]
        }

        hasData = true
    }

    fun clearLandmarks() {
        hasData = false
        hasSmoothed = false
    }

    private fun smoothX(idx: Int, lm: List<NormalizedLandmark>): Float {
        smoothedX[idx] += SMOOTH * (lm[idx].x() - smoothedX[idx])
        return smoothedX[idx]
    }
    private fun smoothY(idx: Int, lm: List<NormalizedLandmark>): Float {
        smoothedY[idx] += SMOOTH * (lm[idx].y() - smoothedY[idx])
        return smoothedY[idx]
    }

    // FILL_CENTER transform (same as Canvas overlay)
    private fun computeTransform() {
        if (imgW.toFloat() / imgH > viewW.toFloat() / viewH) {
            tScale = viewH.toFloat() / imgH; tOffX = (viewW - imgW * tScale) / 2f; tOffY = 0f
        } else {
            tScale = viewW.toFloat() / imgW; tOffX = 0f; tOffY = (viewH - imgH * tScale) / 2f
        }
    }
    // Mirror for front camera
    private fun toScreenX(nx: Float): Float = (1f - nx) * imgW * tScale + tOffX
    private fun toScreenY(ny: Float): Float = ny * imgH * tScale + tOffY

    // ═══════════ GLSurfaceView.Renderer ═══════════

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aEdgeLoc = GLES20.glGetAttribLocation(program, "aEdgeFactor")
        aRegionLoc = GLES20.glGetAttribLocation(program, "aRegion")
        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")
        uGlossyLoc = GLES20.glGetUniformLocation(program, "uGlossy")
        uCenterYLoc = GLES20.glGetUniformLocation(program, "uCenterY")

        // Allocate index buffer (constant topology)
        indexBuffer = ByteBuffer.allocateDirect(triangleIndices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        indexBuffer!!.put(triangleIndices).position(0)

        // Allocate vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasData) return

        GLES20.glUseProgram(program)

        // Upload vertex data
        vertexBuffer!!.clear()
        vertexBuffer!!.put(vertexData).position(0)

        // Position attribute (x, y)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        vertexBuffer!!.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false,
            VERTEX_STRIDE * 4, vertexBuffer)

        // Edge factor attribute
        GLES20.glEnableVertexAttribArray(aEdgeLoc)
        vertexBuffer!!.position(2)
        GLES20.glVertexAttribPointer(aEdgeLoc, 1, GLES20.GL_FLOAT, false,
            VERTEX_STRIDE * 4, vertexBuffer)

        // Region attribute
        GLES20.glEnableVertexAttribArray(aRegionLoc)
        vertexBuffer!!.position(3)
        GLES20.glVertexAttribPointer(aRegionLoc, 1, GLES20.GL_FLOAT, false,
            VERTEX_STRIDE * 4, vertexBuffer)

        // Compute final lip color with brightness + tone shift
        var r = (lipR * brightness) / 255f
        var g = (lipG * brightness) / 255f
        var b = (lipB * brightness) / 255f
        if (toneShift > 0f) {
            r += 30f / 255f * toneShift; g += 8f / 255f * toneShift; b -= 20f / 255f * toneShift
        } else if (toneShift < 0f) {
            val t = -toneShift
            r -= 20f / 255f * t; g += 4f / 255f * t; b += 25f / 255f * t
        }
        r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)

        // Base alpha — translucent for skin-blending
        val alpha = if (glossy) 0.55f else 0.65f

        GLES20.glUniform4f(uColorLoc, r, g, b, alpha)
        GLES20.glUniform1f(uGlossyLoc, if (glossy) 1f else 0f)

        // Single-pass rendering — the fragment shader handles all effects
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, triangleIndices.size,
            GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aEdgeLoc)
        GLES20.glDisableVertexAttribArray(aRegionLoc)
    }

    // ═══════════ Shaders ═══════════

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute float aEdgeFactor;
            attribute float aRegion;
            varying float vEdge;
            varying float vRegion;
            varying vec2 vPos;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vEdge = aEdgeFactor;
                vRegion = aRegion;
                vPos = aPosition;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uGlossy;
            varying float vEdge;
            varying float vRegion;
            varying vec2 vPos;

            void main() {
                float alpha = uColor.a;

                // Smooth edge feathering — soft transition at outer lip boundary
                float edgeFade = smoothstep(1.0, 0.55, vEdge);
                alpha *= edgeFade;

                vec3 color = uColor.rgb;

                // Depth shading — darker towards edges for 3D contour
                float depth = mix(0.82, 1.0, 1.0 - vEdge * 0.5);
                color *= depth;

                // Subtle centre brightness (not white — just lighter lip color)
                float centre = (1.0 - vEdge) * 0.05;
                color = min(color + centre * uColor.rgb, vec3(1.0));

                // Glossy mode: very subtle specular on lower lip center
                if (uGlossy > 0.5) {
                    float lowerCenter = vRegion * pow(1.0 - vEdge, 3.0);
                    float spec = lowerCenter * 0.08;
                    color = min(color + spec, vec3(1.0));
                }

                gl_FragColor = vec4(color, alpha);
            }
        """

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private fun createProgram(vertSrc: String, fragSrc: String): Int {
            val vert = loadShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vert)
            GLES20.glAttachShader(prog, frag)
            GLES20.glLinkProgram(prog)
            return prog
        }
    }
}
