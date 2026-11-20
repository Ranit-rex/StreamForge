package app.streamforge.stream

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLUtils
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.R
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-pass colour grading for the outgoing screen-capture frames.
 * Keeping all adjustments in one shader avoids rebuilding/chaining GL filters
 * while MediaProjection is actively feeding the encoder.
 */
class ColorGradingFilterRender : BaseFilterRender() {
    private var program = -1
    private var aPosition = -1
    private var aTexture = -1
    private var uMvp = -1
    private var uSt = -1
    private var uSampler = -1
    private var uBrightness = -1
    private var uContrast = -1
    private var uSaturation = -1
    private var uSharpness = -1
    private var uResolution = -1
    private var uOverlay = -1
    private var overlayTextureId = -1
    @Volatile private var sceneOverlayBitmap: Bitmap? = null
    @Volatile private var overlayVersion = 0
    private var uploadedOverlayVersion = -1
    private var uploadedBitmap: Bitmap? = null

    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f
    @Volatile private var sharpness = 0f

    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
         1f, -1f, 0f, 1f, 0f,
        -1f,  1f, 0f, 0f, 1f,
         1f,  1f, 0f, 1f, 1f
    )

    override fun initGlFilter(context: Context) {
        squareVertex = ByteBuffer.allocateDirect(vertices.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)

        val vertexShader = GlUtil.getStringFromRaw(context, R.raw.simple_vertex)
        val fragmentShader = """
            precision highp float;
            uniform sampler2D uSampler;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uSharpness;
            uniform vec2 uResolution;
            uniform sampler2D uOverlay;
            varying vec2 vTextureCoord;

            void main() {
                vec2 texel = 1.0 / max(uResolution, vec2(1.0));
                vec4 center = texture2D(uSampler, vTextureCoord);
                vec3 up = texture2D(uSampler, vTextureCoord + vec2(0.0, texel.y)).rgb;
                vec3 down = texture2D(uSampler, vTextureCoord - vec2(0.0, texel.y)).rgb;
                vec3 left = texture2D(uSampler, vTextureCoord - vec2(texel.x, 0.0)).rgb;
                vec3 right = texture2D(uSampler, vTextureCoord + vec2(texel.x, 0.0)).rgb;

                vec3 rgb = center.rgb + uSharpness * (4.0 * center.rgb - up - down - left - right);
                float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = mix(vec3(luma), rgb, uSaturation);
                rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
                rgb = clamp(rgb + vec3(uBrightness), 0.0, 1.0);
                vec2 overlayCoord = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
                vec4 overlay = texture2D(uOverlay, overlayCoord);
                rgb = mix(rgb, overlay.rgb, overlay.a);
                gl_FragColor = vec4(rgb, center.a);
            }
        """.trimIndent()

        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexture = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSt = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uSharpness = GLES20.glGetUniformLocation(program, "uSharpness")
        uResolution = GLES20.glGetUniformLocation(program, "uResolution")
        uOverlay = GLES20.glGetUniformLocation(program, "uOverlay")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        overlayTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val transparent = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        transparent.put(byteArrayOf(0, 0, 0, 0))
        transparent.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, transparent)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)
        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aPosition)
        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(aTexture, 2, GLES20.GL_FLOAT, false, SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(aTexture)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSt, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uBrightness, brightness)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSaturation, saturation)
        GLES20.glUniform1f(uSharpness, sharpness)
        GLES20.glUniform2f(uResolution, getWidth().toFloat(), getHeight().toFloat())
        val requestedOverlayVersion = overlayVersion
        if (requestedOverlayVersion != uploadedOverlayVersion) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            val bitmap = sceneOverlayBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                val transparent = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                transparent.put(byteArrayOf(0, 0, 0, 0))
                transparent.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, transparent)
            }
            uploadedBitmap?.let { old -> if (old !== bitmap && !old.isRecycled) old.recycle() }
            uploadedBitmap = bitmap
            uploadedOverlayVersion = requestedOverlayVersion
        }
        GLES20.glUniform1i(uOverlay, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(uSampler, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTexture, aPosition)
    }

    override fun release() {
        if (program >= 0) {
            GLES20.glDeleteProgram(program)
            program = -1
        }
        if (overlayTextureId >= 0) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
            overlayTextureId = -1
        }
        uploadedBitmap?.let { if (!it.isRecycled) it.recycle() }
        uploadedBitmap = null
    }

    fun setSceneOverlayBitmap(bitmap: Bitmap?) {
        sceneOverlayBitmap = bitmap
        overlayVersion += 1
    }

    fun setColorTuning(brightness: Float, contrast: Float, saturation: Float, sharpness: Float) {
        this.brightness = brightness.coerceIn(-0.2f, 0.35f)
        this.contrast = contrast.coerceIn(0.8f, 1.4f)
        this.saturation = saturation.coerceIn(0f, 2f)
        this.sharpness = sharpness.coerceIn(0f, 0.8f)
    }
}
