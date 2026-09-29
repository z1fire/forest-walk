package com.z1fire.forestwalk;

import android.opengl.GLES30;

/** A 32x32 m square of the world: terrain mesh plus everything that grows on it. */
final class Chunk {
    final int cx, cz;
    final float x0, z0;

    float[] terrain;          // vertex data, dropped after upload
    float[] heights;          // (RES+1)^2 height samples for fast placement
    float minY, maxY;

    // Instance records (InstanceBatch.FLOATS each). For trees/rocks the last float is the mesh index.
    float[] trees = new float[0];
    int treeCount;
    float[] rocks = new float[0];
    int rockCount;
    float[] ferns = new float[0];
    int fernCount;
    float[] grass;            // generated lazily when the player comes close
    int grassCount;

    float[] colliders = new float[0];   // x, z, radius
    int colliderCount;

    int vao, vbo;

    Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        this.x0 = cx * World.CHUNK;
        this.z0 = cz * World.CHUNK;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Bilinear height lookup inside this chunk (clamped to its bounds). */
    float heightAt(float x, float z) {
        float step = World.CHUNK / World.RES;
        float fx = Math.max(0f, Math.min(World.RES - 0.001f, (x - x0) / step));
        float fz = Math.max(0f, Math.min(World.RES - 0.001f, (z - z0) / step));
        int ix = (int) fx, iz = (int) fz;
        float tx = fx - ix, tz = fz - iz;
        int n = World.RES + 1;
        float h00 = heights[iz * n + ix], h10 = heights[iz * n + ix + 1];
        float h01 = heights[(iz + 1) * n + ix], h11 = heights[(iz + 1) * n + ix + 1];
        return (h00 * (1 - tx) + h10 * tx) * (1 - tz) + (h01 * (1 - tx) + h11 * tx) * tz;
    }

    void upload(int sharedIbo) {
        GLES30.glBindVertexArray(0);
        int[] a = new int[1];
        GLES30.glGenBuffers(1, a, 0);
        vbo = a[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, terrain.length * 4,
                Mesh.floatBuffer(terrain, terrain.length), GLES30.GL_STATIC_DRAW);
        GLES30.glGenVertexArrays(1, a, 0);
        vao = a[0];
        GLES30.glBindVertexArray(vao);
        Mesh.pointers(vbo);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, sharedIbo);
        GLES30.glBindVertexArray(0);
        terrain = null;
    }

    void release() {
        if (vao != 0) GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        if (vbo != 0) GLES30.glDeleteBuffers(1, new int[]{vbo}, 0);
        vao = 0;
        vbo = 0;
    }
}
