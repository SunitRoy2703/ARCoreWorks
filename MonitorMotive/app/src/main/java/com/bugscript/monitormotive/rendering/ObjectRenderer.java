package com.bugscript.monitormotive.rendering;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import com.bugscript.monitormotive.R;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjData;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class ObjectRenderer {
    private static final String TAG = ObjectRenderer.class.getSimpleName();

    public enum BlendMode {
        Shadow,
        Grid
    }

    private static final int COORDS_PER_VERTEX = 3;

    private static final float[] LIGHT_DIRECTION = new float[] {0.250f, 0.866f, 0.433f, 0.0f};
    private final float[] viewLightDirection = new float[4];

    private int vertexBufferId;
    private int verticesBaseAddress;
    private int texCoordsBaseAddress;
    private int normalsBaseAddress;
    private int indexBufferId;
    private int indexCount;

    private int program;
    private final int[] textures = new int[1];

    private int modelViewUniform;
    private int modelViewProjectionUniform;

    private int positionAttribute;
    private int normalAttribute;
    private int texCoordAttribute;

    private int textureUniform;

    private int lightingParametersUniform;

    private int materialParametersUniform;

    private BlendMode blendMode = null;

    private final float[] modelMatrix = new float[16];
    private final float[] modelViewMatrix = new float[16];
    private final float[] modelViewProjectionMatrix = new float[16];

    private float ambient = 0.3f;
    private float diffuse = 1.0f;
    private float specular = 1.0f;
    private float specularPower = 6.0f;

    public ObjectRenderer() {}

    public void createOnGlThread(Context context, String objAssetName, String diffuseTextureAssetName)
            throws IOException {

        final int vertexShader =
                ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, R.raw.object_vertex);
        final int fragmentShader =
                ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, R.raw.object_fragment);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glUseProgram(program);

        ShaderUtil.checkGLError(TAG, "Program creation");

        Bitmap textureBitmap =
                BitmapFactory.decodeStream(context.getAssets().open(diffuseTextureAssetName));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGenTextures(textures.length, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);

        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textureBitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        textureBitmap.recycle();

        ShaderUtil.checkGLError(TAG, "Texture loading");

        InputStream objInputStream = context.getAssets().open(objAssetName);
        Obj obj = ObjReader.read(objInputStream);

        obj = ObjUtils.convertToRenderable(obj);

        IntBuffer wideIndices = ObjData.getFaceVertexIndices(obj, 3);
        FloatBuffer vertices = ObjData.getVertices(obj);
        FloatBuffer texCoords = ObjData.getTexCoords(obj, 2);
        FloatBuffer normals = ObjData.getNormals(obj);

        ShortBuffer indices =
                ByteBuffer.allocateDirect(2 * wideIndices.limit())
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer();
        while (wideIndices.hasRemaining()) {
            indices.put((short) wideIndices.get());
        }
        indices.rewind();

        int[] buffers = new int[2];
        GLES20.glGenBuffers(2, buffers, 0);
        vertexBufferId = buffers[0];
        indexBufferId = buffers[1];

        verticesBaseAddress = 0;
        texCoordsBaseAddress = verticesBaseAddress + 4 * vertices.limit();
        normalsBaseAddress = texCoordsBaseAddress + 4 * texCoords.limit();
        final int totalBytes = normalsBaseAddress + 4 * normals.limit();

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, totalBytes, null, GLES20.GL_STATIC_DRAW);
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, verticesBaseAddress, 4 * vertices.limit(), vertices);
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, texCoordsBaseAddress, 4 * texCoords.limit(), texCoords);
        GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, normalsBaseAddress, 4 * normals.limit(), normals);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
        indexCount = indices.limit();
        GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER, 2 * indexCount, indices, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        ShaderUtil.checkGLError(TAG, "OBJ buffer load");

        modelViewUniform = GLES20.glGetUniformLocation(program, "u_ModelView");
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection");

        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position");
        normalAttribute = GLES20.glGetAttribLocation(program, "a_Normal");
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord");

        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");

        lightingParametersUniform = GLES20.glGetUniformLocation(program, "u_LightingParameters");
        materialParametersUniform = GLES20.glGetUniformLocation(program, "u_MaterialParameters");

        ShaderUtil.checkGLError(TAG, "Program parameters");

        Matrix.setIdentityM(modelMatrix, 0);
    }

    public void setBlendMode(BlendMode blendMode) {
        this.blendMode = blendMode;
    }

    public void updateModelMatrix(float[] modelMatrix, float scaleFactor) {
        float[] scaleMatrix = new float[16];
        Matrix.setIdentityM(scaleMatrix, 0);
        scaleMatrix[0] = scaleFactor;
        scaleMatrix[5] = scaleFactor;
        scaleMatrix[10] = scaleFactor;
        Matrix.multiplyMM(this.modelMatrix, 0, modelMatrix, 0, scaleMatrix, 0);
    }

    public void setMaterialProperties(
            float ambient, float diffuse, float specular, float specularPower) {
        this.ambient = ambient;
        this.diffuse = diffuse;
        this.specular = specular;
        this.specularPower = specularPower;
    }

    public void draw(float[] cameraView, float[] cameraPerspective, float lightIntensity) {

        ShaderUtil.checkGLError(TAG, "Before draw");

        Matrix.multiplyMM(modelViewMatrix, 0, cameraView, 0, modelMatrix, 0);
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraPerspective, 0, modelViewMatrix, 0);

        GLES20.glUseProgram(program);

        Matrix.multiplyMV(viewLightDirection, 0, modelViewMatrix, 0, LIGHT_DIRECTION, 0);
        normalizeVec3(viewLightDirection);
        GLES20.glUniform4f(
                lightingParametersUniform,
                viewLightDirection[0],
                viewLightDirection[1],
                viewLightDirection[2],
                lightIntensity);

        GLES20.glUniform4f(materialParametersUniform, ambient, diffuse, specular, specularPower);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glUniform1i(textureUniform, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId);

        GLES20.glVertexAttribPointer(
                positionAttribute, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, verticesBaseAddress);
        GLES20.glVertexAttribPointer(normalAttribute, 3, GLES20.GL_FLOAT, false, 0, normalsBaseAddress);
        GLES20.glVertexAttribPointer(
                texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, texCoordsBaseAddress);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glUniformMatrix4fv(modelViewUniform, 1, false, modelViewMatrix, 0);
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0);

        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glEnableVertexAttribArray(normalAttribute);
        GLES20.glEnableVertexAttribArray(texCoordAttribute);

        if (blendMode != null) {
            GLES20.glDepthMask(false);
            GLES20.glEnable(GLES20.GL_BLEND);
            switch (blendMode) {
                case Shadow:
                    GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    break;
                case Grid:
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    break;
            }
        }

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        if (blendMode != null) {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDepthMask(true);
        }

        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(normalAttribute);
        GLES20.glDisableVertexAttribArray(texCoordAttribute);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        ShaderUtil.checkGLError(TAG, "After draw");
    }

    private static void normalizeVec3(float[] v) {
        float reciprocalLength = 1.0f / (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] *= reciprocalLength;
        v[1] *= reciprocalLength;
        v[2] *= reciprocalLength;
    }
}
