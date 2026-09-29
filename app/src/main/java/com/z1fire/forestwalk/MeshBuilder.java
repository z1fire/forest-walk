package com.z1fire.forestwalk;

import java.util.Arrays;

/** Accumulates vertices/indices in the {@link Mesh} layout. */
final class MeshBuilder {
    private float[] v = new float[Mesh.STRIDE * 1024];
    private short[] ix = new short[4096];
    private int nv, ni;

    int count() {
        return nv;
    }

    int vert(float x, float y, float z, float nx, float ny, float nz, float u, float w,
             float sway, float leaf, float ao, float phase) {
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l > 1e-6f) { nx /= l; ny /= l; nz /= l; } else { nx = 0f; ny = 1f; nz = 0f; }
        int o = nv * Mesh.STRIDE;
        if (o + Mesh.STRIDE > v.length) v = Arrays.copyOf(v, v.length * 2);
        v[o] = x; v[o + 1] = y; v[o + 2] = z;
        v[o + 3] = nx; v[o + 4] = ny; v[o + 5] = nz;
        v[o + 6] = u; v[o + 7] = w;
        v[o + 8] = sway; v[o + 9] = leaf; v[o + 10] = ao; v[o + 11] = phase;
        return nv++;
    }

    void tri(int a, int b, int c) {
        if (ni + 3 > ix.length) ix = Arrays.copyOf(ix, ix.length * 2);
        ix[ni++] = (short) a;
        ix[ni++] = (short) b;
        ix[ni++] = (short) c;
    }

    void quad(int a, int b, int c, int d) {
        tri(a, b, c);
        tri(a, c, d);
    }

    Mesh build() {
        if (nv > 65535) throw new IllegalStateException("Mesh too large: " + nv);
        return new Mesh(v, nv, ix, ni);
    }
}
