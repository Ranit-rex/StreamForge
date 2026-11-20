package app.streamforge.stream

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.R
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stable saturation filter for screen capture.
 *
 * RootEncoder 2.8.1's SaturationFilterRender adds a second colour pass and can
 * clip screen-capture frames. This shader performs a conventional luma mix:
 * 0 = grayscale, 1 = neutral, 2 = double saturation.
 */
class SafeSaturationFilterRender : BaseFilterRender() {

    private var program = -1
    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMvpMatrixHandle = -1
    private var uStMatrixHandle = -1
    private var uSamplerHandle = -1
    private var uSaturationHandle = -1
    @Volatile private var saturation = 1.0f

    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
         1f, -1f, 0f, 1f, 0f,
        -1f,  1f, 0f, 0f, 1f,
         1f,  1f, 0f, 1f, 1f
    )

    override fun initGlFilter(context: Context) {
        squareVertex = ByteBuffer.allocateDirect(vertexData.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexData)
                position(0)
            }
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)

        val vertexShader = GlUtil.getStringFromRaw(context, R.raw.simple_vertex)
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uSampler;
            uniform float uSaturation;
            varying vec2 vTextureCoord;
            void main() {
                vec4 pixel = texture2D(uSampler, vTextureCoord);
                float luma = dot(pixel.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 graded = mix(vec3(luma), pixel.rgb, uSaturation);
                gl_FragColor = vec4(clamp(graded, 0.0, 1.0), pixel.a);
            }
        """.trimIndent()
        program = GlUtil.createProgram(vertexShader, fragmentShader)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(program, "uSampler")
        uSaturationHandle = GLES20.glGetUniformLocation(program, "uSaturation")
    }

    override fun drawFilter() {
        GLES20.glUseProgram(program)

        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 3, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(
            aTextureHandle, 2, GLES20.GL_FLOAT, false,
            SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex
        )
        GLES20.glEnableVertexAttribArray(aTextureHandle)

        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, STMatrix, 0)
        GLES20.glUniform1f(uSaturationHandle, saturation)
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTextureHandle, aPositionHandle)
    }

    override fun release() {
        if (program >= 0) {
            GLES20.glDeleteProgram(program)
            program = -1
        }
    }

    fun setSaturation(value: Float) {
        saturation = value.coerceIn(0f, 2f)
    }
}
