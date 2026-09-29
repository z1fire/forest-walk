package com.z1fire.forestwalk;

import java.util.Random;

/**
 * Deterministic, infinite procedural forest: terrain height, a winding trail, and placement of
 * trees, saplings, rocks, ferns and grass. Everything derives from the seed.
 */
final class World {
    static final float CHUNK = 32f;
    static final int RES = 32;
    static final int VARIANTS = 3;

    final long seed;
    private final Noise n;
    private final int salt;
    final float[] sunDir = new float[3];
    final float[] sunCol = new float[3];
    final float windX, windZ;

    World(long seed) {
        this.seed = seed;
        n = new Noise(seed);
        salt = (int) (seed * 2654435761L);
        Random r = new Random(seed * 31 + 7);
        double elevDeg = 24 + r.nextFloat() * 28;
        double elev = Math.toRadians(elevDeg);
        double az = r.nextFloat() * Math.PI * 2;
        sunDir[0] = (float) (Math.cos(elev) * Math.cos(az));
        sunDir[1] = (float) Math.sin(elev);
        sunDir[2] = (float) (Math.cos(elev) * Math.sin(az));
        float warm = (float) (1.0 - (elevDeg - 24) / 28.0);   // 1 = low, golden sun
        float i = 3.2f;
        sunCol[0] = i;
        sunCol[1] = i * (0.93f - 0.10f * warm);
        sunCol[2] = i * (0.84f - 0.20f * warm);
        double wa = r.nextFloat() * Math.PI * 2;
        windX = (float) Math.cos(wa);
        windZ = (float) Math.sin(wa);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    float hash(int x, int z, int k) {
        int h = x * 374761393 + z * 668265263 + k * 1442695041 + salt;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216f;
    }

    // ---------------------------------------------------------------- terrain & trail

    float pathCenter(float z) {
        return 28f * n.simplex(z * 0.0045f, 17.3f) + 7f * n.simplex(z * 0.021f, -41.7f);
    }

    float pathDist(float x, float z) {
        float slope = pathCenter(z + 0.5f) - pathCenter(z - 0.5f);
        return Math.abs(x - pathCenter(z)) / (float) Math.sqrt(1 + slope * slope);
    }

    private float pathHalfWidth(float z) {
        return 0.85f + 0.3f * n.simplex(z * 0.13f, 3.3f);
    }

    float pathMask(float x, float z) {
        float w = pathHalfWidth(z);
        return smoothstep(w + 0.7f, w - 0.25f, pathDist(x, z));
    }

    private float baseHeight(float x, float z) {
        return n.fbm(x * 0.0035f, z * 0.0035f, 4) * 24f
                + n.fbm(x * 0.013f + 71.3f, z * 0.013f - 12.9f, 3) * 4f;
    }

    float height(float x, float z) {
        float h = baseHeight(x, z) + n.simplex(x * 0.09f + 5.1f, z * 0.09f) * 0.3f;
        float pd = pathDist(x, z);
        if (pd < 5.5f) {
            // Cut a gently levelled bench for the trail.
            float hc = baseHeight(pathCenter(z), z);
            float m = smoothstep(5.5f, 1.4f, pd);
            h += (hc - h) * m * 0.85f - 0.08f * smoothstep(1.6f, 0.4f, pd);
        }
        return h;
    }

    /** 0 = clearing, 1 = dense forest. */
    float density(float x, float z) {
        float d = 0.64f + 0.75f * n.fbm(x * 0.012f + 50f, z * 0.012f - 20f, 3);
        return d < 0 ? 0 : (d > 1 ? 1 : d);
    }

    float moisture(float x, float z) {
        float m = 0.5f + 0.8f * n.fbm(x * 0.018f + 200f, z * 0.018f - 140f, 2);
        return m < 0 ? 0 : (m > 1 ? 1 : m);
    }

    /** 0 = broadleaf stand, 1 = conifer stand. */
    private float coniferness(float x, float z) {
        float c = 0.55f + 0.9f * n.fbm(x * 0.005f - 300f, z * 0.005f + 90f, 2);
        return c < 0.05f ? 0.05f : (c > 0.95f ? 0.95f : c);
    }

    // ---------------------------------------------------------------- chunk generation

    Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        buildTerrain(c);
        buildTrees(c);
        buildRocks(c);
        buildFerns(c);
        return c;
    }

    private void buildTerrain(Chunk c) {
        int N = RES + 1, B = N + 2;
        float step = CHUNK / RES;
        float[] h = new float[B * B];
        for (int j = -1; j <= N; j++)
            for (int i = -1; i <= N; i++)
                h[(j + 1) * B + (i + 1)] = height(c.x0 + i * step, c.z0 + j * step);
        float[] v = new float[N * N * Mesh.STRIDE];
        c.heights = new float[N * N];
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                float x = c.x0 + i * step, z = c.z0 + j * step;
                float y = h[(j + 1) * B + (i + 1)];
                float hl = h[(j + 1) * B + i], hr = h[(j + 1) * B + i + 2];
                float hd = h[j * B + i + 1], hu = h[(j + 2) * B + i + 1];
                float nx = hl - hr, ny = 2 * step, nz = hd - hu;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int o = (j * N + i) * Mesh.STRIDE;
                v[o] = x; v[o + 1] = y; v[o + 2] = z;
                v[o + 3] = nx / len; v[o + 4] = ny / len; v[o + 5] = nz / len;
                v[o + 6] = 0; v[o + 7] = 0;
                v[o + 8] = pathMask(x, z);
                v[o + 9] = moisture(x, z);
                v[o + 10] = 1f - 0.45f * density(x, z);
                v[o + 11] = 0;
                c.heights[j * N + i] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        c.terrain = v;
        c.minY = minY;
        c.maxY = maxY;
    }

    private void buildTrees(Chunk c) {
        FloatList t = new FloatList();
        FloatList col = new FloatList();
        // Canopy trees on a jittered grid.
        float cell = 4.3f;
        int gx0 = (int) Math.ceil(c.x0 / cell), gx1 = (int) Math.ceil((c.x0 + CHUNK) / cell) - 1;
        int gz0 = (int) Math.ceil(c.z0 / cell), gz1 = (int) Math.ceil((c.z0 + CHUNK) / cell) - 1;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                float x = gx * cell + (hash(gx, gz, 1) - 0.5f) * cell * 0.9f;
                float z = gz * cell + (hash(gx, gz, 2) - 0.5f) * cell * 0.9f;
                float d = density(x, z);
                if (hash(gx, gz, 3) > d * 0.92f) continue;
                int species;
                float hs = hash(gx, gz, 5);
                if (hash(gx, gz, 4) < coniferness(x, z)) species = hs < 0.28f ? TreeFactory.PINE : TreeFactory.SPRUCE;
                else species = hs < 0.32f ? TreeFactory.BIRCH : TreeFactory.BEECH;
                // Spruce skirts reach low and wide; keep them back so they don't hang over the trail.
                if (pathDist(x, z) < (species == TreeFactory.SPRUCE ? 4.2f : 2.4f)) continue;
                float scale = 0.72f + 0.5f * hash(gx, gz, 6);
                addTree(t, col, x, z, species, gx, gz, 10, scale);
            }
        }
        // Understory saplings, more of them where the canopy opens up.
        cell = 6.1f;
        gx0 = (int) Math.ceil(c.x0 / cell); gx1 = (int) Math.ceil((c.x0 + CHUNK) / cell) - 1;
        gz0 = (int) Math.ceil(c.z0 / cell); gz1 = (int) Math.ceil((c.z0 + CHUNK) / cell) - 1;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                float x = gx * cell + (hash(gx, gz, 21) - 0.5f) * cell * 0.9f;
                float z = gz * cell + (hash(gx, gz, 22) - 0.5f) * cell * 0.9f;
                float d = density(x, z);
                if (hash(gx, gz, 23) > 0.2f + 0.4f * (1f - d)) continue;
                if (pathDist(x, z) < 1.9f) continue;
                int species = hash(gx, gz, 24) < coniferness(x, z) ? TreeFactory.SPRUCE : TreeFactory.BEECH;
                float scale = 0.12f + 0.25f * hash(gx, gz, 26);
                addTree(t, col, x, z, species, gx, gz, 30, scale);
            }
        }
        c.trees = t.toArray();
        c.treeCount = t.n / InstanceBatch.FLOATS;
        c.colliders = col.toArray();
        c.colliderCount = col.n / 3;
    }

    private void addTree(FloatList t, FloatList col, float x, float z, int species, int gx, int gz, int k, float scale) {
        int variant = Math.min(VARIANTS - 1, (int) (hash(gx, gz, k) * VARIANTS));
        t.add(x);
        t.add(height(x, z) - 0.05f);
        t.add(z);
        t.add(hash(gx, gz, k + 1) * 6.2832f);
        t.add(scale);
        t.add(0.82f + 0.36f * hash(gx, gz, k + 2));
        t.add(hash(gx, gz, k + 3) * 6.2832f);
        t.add(species * VARIANTS + variant);
        col.add(x);
        col.add(z);
        col.add(Math.max(0.12f, TreeFactory.TRUNK_RADIUS[species] * scale * 1.3f));
    }

    private void buildRocks(Chunk c) {
        FloatList t = new FloatList();
        FloatList col = new FloatList();
        for (int i = 0; i < c.colliderCount * 3; i++) col.add(c.colliders[i]);
        float cell = 9f;
        int gx0 = (int) Math.ceil(c.x0 / cell), gx1 = (int) Math.ceil((c.x0 + CHUNK) / cell) - 1;
        int gz0 = (int) Math.ceil(c.z0 / cell), gz1 = (int) Math.ceil((c.z0 + CHUNK) / cell) - 1;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                if (hash(gx, gz, 41) > 0.2f) continue;
                float x = gx * cell + (hash(gx, gz, 42) - 0.5f) * cell;
                float z = gz * cell + (hash(gx, gz, 43) - 0.5f) * cell;
                if (pathDist(x, z) < 2.0f) continue;
                float hs = hash(gx, gz, 44);
                float scale = 0.3f + 1.6f * hs * hs;
                t.add(x);
                t.add(height(x, z) - 0.3f * scale);
                t.add(z);
                t.add(hash(gx, gz, 45) * 6.2832f);
                t.add(scale);
                t.add(0.85f + 0.25f * hash(gx, gz, 46));
                t.add(0f);
                t.add(Math.min(2, (int) (hash(gx, gz, 47) * 3)));
                if (scale > 0.45f) {
                    col.add(x);
                    col.add(z);
                    col.add(scale * 0.95f);
                }
            }
        }
        c.rocks = t.toArray();
        c.rockCount = t.n / InstanceBatch.FLOATS;
        c.colliders = col.toArray();
        c.colliderCount = col.n / 3;
    }

    private void buildFerns(Chunk c) {
        FloatList t = new FloatList();
        float cell = 2.1f;
        int gx0 = (int) Math.ceil(c.x0 / cell), gx1 = (int) Math.ceil((c.x0 + CHUNK) / cell) - 1;
        int gz0 = (int) Math.ceil(c.z0 / cell), gz1 = (int) Math.ceil((c.z0 + CHUNK) / cell) - 1;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                float x = gx * cell + (hash(gx, gz, 61) - 0.5f) * cell;
                float z = gz * cell + (hash(gx, gz, 62) - 0.5f) * cell;
                float p = moisture(x, z) * 0.75f * (0.35f + density(x, z) * 0.65f);
                if (hash(gx, gz, 63) > p) continue;
                if (pathDist(x, z) < 1.5f) continue;
                t.add(x);
                t.add(c.heightAt(x, z) - 0.05f);
                t.add(z);
                t.add(hash(gx, gz, 64) * 6.2832f);
                t.add(0.6f + 0.7f * hash(gx, gz, 65));
                t.add(0.8f + 0.35f * hash(gx, gz, 66));
                t.add(hash(gx, gz, 67) * 6.2832f);
                t.add(0f);
            }
        }
        c.ferns = t.toArray();
        c.fernCount = t.n / InstanceBatch.FLOATS;
    }

    /** Grass is dense, so it is only generated for chunks near the walker. */
    void buildGrass(Chunk c) {
        FloatList t = new FloatList();
        float cell = 0.72f;
        int gx0 = (int) Math.ceil(c.x0 / cell), gx1 = (int) Math.ceil((c.x0 + CHUNK) / cell) - 1;
        int gz0 = (int) Math.ceil(c.z0 / cell), gz1 = (int) Math.ceil((c.z0 + CHUNK) / cell) - 1;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                float x = gx * cell + (hash(gx, gz, 81) - 0.5f) * cell;
                float z = gz * cell + (hash(gx, gz, 82) - 0.5f) * cell;
                float g = smoothstep(0.3f, 0.75f, 0.5f + 0.6f * n.fbm(x * 0.05f + 900f, z * 0.05f, 2));
                g *= 1.1f - density(x, z) * 0.75f;
                float pd = pathDist(x, z);
                float w = pathHalfWidth(z);
                if (pd < w * 0.9f) continue;
                if (pd < w + 2.5f) g += 0.45f;
                if (hash(gx, gz, 83) > g) continue;
                t.add(x);
                t.add(c.heightAt(x, z) - 0.03f);
                t.add(z);
                t.add(hash(gx, gz, 84) * 6.2832f);
                t.add(0.55f + 0.6f * hash(gx, gz, 85));
                t.add(0.75f + 0.45f * hash(gx, gz, 86));
                t.add(hash(gx, gz, 87) * 6.2832f);
                t.add(0f);
            }
        }
        c.grass = t.toArray();
        c.grassCount = t.n / InstanceBatch.FLOATS;
    }
}
