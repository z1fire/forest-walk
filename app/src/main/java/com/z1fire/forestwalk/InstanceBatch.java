package com.z1fire.forestwalk;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * A mesh plus a per-frame list of instances (8 floats: x, y, z, rotation, scale, tint, phase, extra).
 */
final class InstanceBatch {
    static final int FLOATS = 8;
    final Mesh mesh;
    private final boolean ownsMesh;
    private final int vao, vbo;
    private float[] data = new float[FLOATS * 128];
    private FloatBuffer fb;
    private int n, count;

    InstanceBatch(Mesh mesh, boolean ownsMesh) {
        this.mesh = mesh;
        this.ownsMesh = ownsMesh;
        int[] a = new int[1];
        GLES30.glGenVertexArrays(1, a, 0);
        vao = a[0];
        GLES30.glGenBuffers(1, a, 0);
        vbo = a[0];
        GLES30.glBindVertexArray(vao);
        mesh.bindForVao();
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, FLOATS * 4, null, GLES30.GL_STREAM_DRAW);
        GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, FLOATS * 4, 0);
        GLES30.glVertexAttribDivisor(4, 1);
        GLES30.glEnableVertexAttribArray(5);
        GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, FLOATS * 4, 16);
        GLES30.glVertexAttribDivisor(5, 1);
        GLES30.glBindVertexArray(0);
    }

    void begin() {
        n = 0;
    }

    void addFrom(float[] src, int off) {
        int o = n * FLOATS;
        if (o + FLOATS > data.length) data = Arrays.copyOf(data, data.length * 2);
        System.arraycopy(src, off, data, o, FLOATS);
        n++;
    }

    int size() {
        return n;
    }

    void upload() {
        count = n;
        if (n == 0) return;
        int floats = n * FLOATS;
        if (fb == null || fb.capacity() < floats) {
            fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        fb.clear();
        fb.put(data, 0, floats).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, fb, GLES30.GL_STREAM_DRAW);
    }

    void draw() {
        if (count == 0) return;
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0, count);
    }

    void delete() {
        GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        GLES30.glDeleteBuffers(1, new int[]{vbo}, 0);
        if (ownsMesh) mesh.delete();
    }
}
