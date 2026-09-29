package com.z1fire.forestwalk;

import java.util.Random;

/** Seeded 2D simplex noise with a fractal (fBm) helper. */
final class Noise {
    private static final float F2 = 0.36602540378f;
    private static final float G2 = 0.21132486540f;
    private final int[] perm = new int[512];

    Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static int fastFloor(float x) {
        int i = (int) x;
        return x < i ? i - 1 : i;
    }

    private static float grad(int h, float x, float y) {
        switch (h & 7) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x * 1.4142f;
            case 5: return -x * 1.4142f;
            case 6: return y * 1.4142f;
            default: return -y * 1.4142f;
        }
    }

    /** Simplex noise, roughly in [-1, 1]. */
    float simplex(float xin, float yin) {
        float s = (xin + yin) * F2;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);
        float t = (i + j) * G2;
        float x0 = xin - (i - t);
        float y0 = yin - (j - t);
        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; } else { i1 = 0; j1 = 1; }
        float x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        float x2 = x0 - 1f + 2f * G2, y2 = y0 - 1f + 2f * G2;
        int ii = i & 255, jj = j & 255;
        float n = 0f;
        float t0 = 0.5f - x0 * x0 - y0 * y0;
        if (t0 > 0) { t0 *= t0; n += t0 * t0 * grad(perm[ii + perm[jj]], x0, y0); }
        float t1 = 0.5f - x1 * x1 - y1 * y1;
        if (t1 > 0) { t1 *= t1; n += t1 * t1 * grad(perm[ii + i1 + perm[jj + j1]], x1, y1); }
        float t2 = 0.5f - x2 * x2 - y2 * y2;
        if (t2 > 0) { t2 *= t2; n += t2 * t2 * grad(perm[ii + 1 + perm[jj + 1]], x2, y2); }
        return 64f * n;
    }

    /** Fractal Brownian motion, normalised to roughly [-1, 1]. */
    float fbm(float x, float y, int octaves) {
        float sum = 0f, amp = 1f, norm = 0f;
        for (int o = 0; o < octaves; o++) {
            sum += amp * simplex(x, y);
            norm += amp;
            amp *= 0.5f;
            x = x * 2.03f + 17.1f;
            y = y * 2.03f - 9.7f;
        }
        return sum / norm;
    }
}
