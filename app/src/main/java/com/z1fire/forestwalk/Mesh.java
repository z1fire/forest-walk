package com.z1fire.forestwalk;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Static indexed mesh. Vertex layout (12 floats):
 * position(3) normal(3) uv(2) attr(4: sway, isLeaf, ambientOcclusion, phase).
 */
final class Mesh {
    static final int STRIDE = 12;
    final int vbo, ibo, indexCount;

    Mesh(float[] verts, int vertexCount, short[] indices, int indexCount) {
        GLES30.glBindVertexArray(0);
        int[] b = new int[2];
        GLES30.glGenBuffers(2, b, 0);
        vbo = b[0];
        ibo = b[1];
        this.indexCount = indexCount;
        FloatBuffer fb = floatBuffer(verts, vertexCount * STRIDE);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexCount * STRIDE * 4, fb, GLES30.GL_STATIC_DRAW);
        ShortBuffer sb = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(indices, 0, indexCount).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 2, sb, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
    }

    static FloatBuffer floatBuffer(float[] data, int count) {
        FloatBuffer fb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data, 0, count).position(0);
        return fb;
    }

    /** Sets up vertex attributes 0-3 from the given VBO (call with the target VAO bound). */
    static void pointers(int vbo) {
        int s = STRIDE * 4;
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, s, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, s, 24);
        GLES30.glEnableVertexAttribArray(3);
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, s, 32);
    }

    void bindForVao() {
        pointers(vbo);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo);
    }

    void delete() {
        GLES30.glDeleteBuffers(2, new int[]{vbo, ibo}, 0);
    }
}
