package com.z1fire.forestwalk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

/**
 * Procedural plant and rock geometry. Trees are built from tapered bark tubes plus alpha-tested
 * foliage cards (needle sprays for conifers, leaf clusters for broadleaves), each in a detailed
 * (hi) and a far-distance (lo) level of detail.
 */
final class TreeFactory {
    static final int SPRUCE = 0, PINE = 1, BEECH = 2, BIRCH = 3;
    static final int SPECIES = 4;
    static final float[] TRUNK_RADIUS = {0.36f, 0.34f, 0.40f, 0.2f};

    private TreeFactory() {}

    static Mesh tree(int species, long seed, boolean hi) {
        switch (species) {
            case SPRUCE: return conifer(seed, hi, false);
            case PINE: return conifer(seed, hi, true);
            case BEECH: return beech(seed, hi);
            default: return birch(seed, hi);
        }
    }

    // ------------------------------------------------------------------ shared pieces

    private static float trunkR(float y, float h, float r) {
        float t = Math.max(0f, 1f - y / h);
        return r * (float) Math.pow(t, 0.85) + r * 0.45f * (float) Math.exp(-y * 2.2f) + 0.012f;
    }

    /** Straight, tapered trunk with a root flare, from slightly below ground to the tip. */
    private static void trunk(MeshBuilder b, float h, float r, int sides, int segs) {
        int ring = sides + 1;
        int base = b.count();
        for (int s = 0; s <= segs; s++) {
            float f = (float) s / segs;
            float y = h * (f * f * 0.6f + f * 0.4f) - (s == 0 ? 0.4f : 0f);
            float rad = trunkR(Math.max(0f, y), h, r);
            float yy = Math.max(0f, y);
            float sway = (yy / h) * (yy / h);
            float ao = 0.45f + 0.55f * Math.min(1f, yy / 2.5f);
            for (int k = 0; k <= sides; k++) {
                float a = (float) (k * 2 * Math.PI / sides);
                float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
                b.vert(cx * rad, y, cz * rad, cx, 0.15f, cz, (float) k / sides * 3f, y * 0.6f, sway, 0f, ao, 0f);
            }
        }
        for (int s = 0; s < segs; s++) {
            for (int k = 0; k < sides; k++) {
                int a = base + s * ring + k, c = a + ring;
                b.quad(a, c, c + 1, a + 1);
            }
        }
    }

    /** Tapered cylinder between two points (branches, limbs, curved trunks). */
    private static void tube(MeshBuilder b, float x0, float y0, float z0, float x1, float y1, float z1,
                             float r0, float r1, int sides, float h, float vOffset) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-4f) return;
        ax /= len; ay /= len; az /= len;
        // sink the start a little into the parent to hide the joint
        x0 -= ax * r0 * 0.6f; y0 -= ay * r0 * 0.6f; z0 -= az * r0 * 0.6f;
        len += r0 * 0.6f;
        float ux, uy, uz;
        if (Math.abs(ay) < 0.9f) { ux = -az; uy = 0; uz = ax; } else { ux = 0; uy = az; uz = -ay; }
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float vx = ay * uz - az * uy, vy = az * ux - ax * uz, vz = ax * uy - ay * ux;
        int base = b.count();
        for (int e = 0; e < 2; e++) {
            float cx = e == 0 ? x0 : x1, cy = e == 0 ? y0 : y1, cz = e == 0 ? z0 : z1;
            float rr = e == 0 ? r0 : r1;
            float sway = Math.max(0f, cy / h);
            sway *= sway;
            float ao = 0.5f + 0.5f * Math.min(1f, Math.max(0f, cy) / 3f);
            for (int k = 0; k <= sides; k++) {
                float a = (float) (k * 2 * Math.PI / sides);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float nx = ux * ca + vx * sa, ny = uy * ca + vy * sa, nz = uz * ca + vz * sa;
                b.vert(cx + nx * rr, cy + ny * rr, cz + nz * rr, nx, ny, nz,
                        (float) k / sides * (rr > 0.15f ? 2f : 1f), vOffset + (e == 0 ? 0f : len * 0.6f),
                        sway, 0f, ao, 0f);
            }
        }
        int ring = sides + 1;
        for (int k = 0; k < sides; k++) b.quad(base + k, base + ring + k, base + ring + k + 1, base + k + 1);
    }

    /**
     * A foliage strip following a polyline with a constant width vector. u runs across the strip,
     * v along it (0 at the base). Normals point away from the crown centre for soft canopy shading.
     */
    private static void strip(MeshBuilder b, float[] px, float[] py, float[] pz, float wx, float wy, float wz,
                              float cy, float h, float phase, float aoBase, float swayBoost, float upBias) {
        int n = px.length;
        int base = b.count();
        for (int i = 0; i < n; i++) {
            float s = (float) i / (n - 1);
            for (int side = 0; side < 2; side++) {
                float sg = side == 0 ? -1f : 1f;
                float x = px[i] + wx * sg, y = py[i] + wy * sg, z = pz[i] + wz * sg;
                float yy = Math.max(0f, y / h);
                float sway = Math.min(1f, yy * yy + swayBoost * s);
                float ao = aoBase + (1f - aoBase) * s;
                b.vert(x, y, z, x, (y - cy) * 0.7f + upBias, z, side, s, sway, 1f, ao, phase);
            }
        }
        for (int i = 0; i < n - 1; i++) {
            int a = base + i * 2;
            b.quad(a, a + 1, a + 3, a + 2);
        }
    }

    // ------------------------------------------------------------------ conifers

    private static Mesh conifer(long seed, boolean hi, boolean pine) {
        Random r = new Random(seed);
        MeshBuilder b = new MeshBuilder();
        float h = (pine ? 22f : 20f) * (0.9f + 0.2f * r.nextFloat());
        float rad = pine ? 0.3f : 0.32f;
        trunk(b, h, rad, hi ? 9 : 5, hi ? 8 : 3);
        float crownBase = h * (pine ? 0.55f + 0.12f * r.nextFloat() : 0.1f + 0.12f * r.nextFloat());
        float crownMid = crownBase + (h - crownBase) * (pine ? 0.5f : 0.3f);
        float maxLen = pine ? 3.4f : 4.4f;
        float spacing = (pine ? 0.7f : 0.4f) * (hi ? 1f : (pine ? 1.7f : 2.4f));
        int segs = hi ? 3 : 1;
        float y = crownBase;
        while (y < h - 0.6f) {
            float t = (y - crownBase) / (h - crownBase);
            float len = pine
                    ? maxLen * (float) Math.pow(Math.sin(Math.PI * Math.min(0.97, 0.2 + 0.8 * t)), 0.6) + 0.5f
                    : maxLen * (float) Math.pow(1f - t, 0.9) + 0.4f;
            int nb = hi ? (pine ? 4 : 5) + r.nextInt(2) : (pine ? 3 : 4);
            float a0 = r.nextFloat() * 6.2832f;
            for (int k = 0; k < nb; k++) {
                float a = a0 + k * 6.2832f / nb + (r.nextFloat() - 0.5f) * 0.7f;
                float l = len * (0.75f + 0.45f * r.nextFloat()) * (hi ? 1f : 1.12f);
                float by = y + (r.nextFloat() - 0.5f) * spacing * 0.5f;
                coniferBranch(b, r, by, a, l, h, rad, crownMid, segs, pine, t, hi);
            }
            y += spacing * (0.8f + 0.4f * r.nextFloat());
        }
        // leader
        float[] lx = {0f, 0f}, ly = {h - 1.3f, h + 0.25f}, lz = {0f, 0f};
        strip(b, lx, ly, lz, 0.4f, 0f, 0f, crownMid, h, 0f, 0.8f, 0f, 1.2f);
        strip(b, lx, ly, lz, 0f, 0f, 0.4f, crownMid, h, 0f, 0.8f, 0f, 1.2f);
        return b.build();
    }

    private static void coniferBranch(MeshBuilder b, Random r, float y, float a, float len, float h, float rad,
                                      float crownMid, int segs, boolean pine, float t, boolean hi) {
        float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
        float sx = dz, sz = -dx;
        float r0 = trunkR(y, h, rad) * 0.7f;
        float rise = pine ? 0.3f : 0.05f + 0.25f * t;
        float droop = pine ? 0.2f : 0.45f * (1f - t) + 0.1f;
        float[] px = new float[segs + 1], py = new float[segs + 1], pz = new float[segs + 1];
        for (int i = 0; i <= segs; i++) {
            float s = (float) i / segs;
            float d = r0 + len * s;
            px[i] = dx * d;
            pz[i] = dz * d;
            py[i] = y + len * (rise * s - droop * s * s);
        }
        float w = len * (pine ? 0.42f : 0.34f) * (hi ? 1f : 1.3f);
        float tilt = (r.nextFloat() - 0.5f) * 0.7f;
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        float phase = r.nextFloat() * 6.2832f;
        float ao = 0.35f + 0.3f * t;
        // near-horizontal spray
        strip(b, px, py, pz, sx * ct * w, st * w, sz * ct * w, crownMid, h, phase, ao, 0.3f, 1.2f);
        // near-vertical spray for volume when seen from the side
        float w2 = w * 0.7f;
        strip(b, px, py, pz, -sx * st * w2, ct * w2, -sz * st * w2, crownMid, h, phase, ao, 0.3f, 1.2f);
    }

    // ------------------------------------------------------------------ broadleaves

    private static Mesh beech(long seed, boolean hi) {
        Random r = new Random(seed);
        MeshBuilder b = new MeshBuilder();
        float h = 18f * (0.9f + 0.2f * r.nextFloat());
        ArrayList<float[]> clusters = new ArrayList<>();
        float trunkH = h * (0.3f + 0.08f * r.nextFloat());
        limb(b, r, 0f, -0.4f, 0f, (r.nextFloat() - 0.5f) * 0.1f, 1f, (r.nextFloat() - 0.5f) * 0.1f,
                trunkH + 0.4f, 0.38f, 0, 3, hi, h, clusters);
        leafCards(b, r, clusters, h, hi ? 4 : 1, hi ? 2.4f : 4.0f);
        return b.build();
    }

    private static void limb(MeshBuilder b, Random r, float x, float y, float z, float dx, float dy, float dz,
                             float len, float rad, int depth, int maxDepth, boolean hi, float h,
                             ArrayList<float[]> clusters) {
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l; dy /= l; dz /= l;
        float mx = x + dx * len * 0.5f, my = y + dy * len * 0.5f, mz = z + dz * len * 0.5f;
        float ex = dx + (r.nextFloat() - 0.5f) * 0.35f, ey = dy + (r.nextFloat() - 0.3f) * 0.3f, ez = dz + (r.nextFloat() - 0.5f) * 0.35f;
        l = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        ex /= l; ey /= l; ez /= l;
        float qx = mx + ex * len * 0.5f, qy = my + ey * len * 0.5f, qz = mz + ez * len * 0.5f;
        int sides = hi ? (depth < 2 ? 8 : 5) : (depth < 1 ? 5 : 3);
        if (hi || depth < 2) {
            tube(b, x, y, z, mx, my, mz, rad, rad * 0.85f, sides, h, 0f);
            tube(b, mx, my, mz, qx, qy, qz, rad * 0.85f, rad * 0.7f, sides, h, len * 0.3f);
        }
        if (depth >= maxDepth) {
            clusters.add(new float[]{qx, qy, qz});
            clusters.add(new float[]{mx, my, mz});
            return;
        }
        if (depth >= 1) clusters.add(new float[]{qx, qy, qz});
        if (depth >= 2) clusters.add(new float[]{mx, my, mz});
        int n = depth == 0 ? 3 + r.nextInt(2) : 2 + r.nextInt(2);
        float base = r.nextFloat() * 6.2832f;
        for (int i = 0; i < n; i++) {
            float ang = base + i * 6.2832f / n + (r.nextFloat() - 0.5f) * 0.8f;
            float spread = (depth == 0 ? 0.55f : 0.6f) + r.nextFloat() * 0.35f;
            float hx = (float) Math.cos(ang), hz = (float) Math.sin(ang);
            float cs = (float) Math.cos(spread), sn = (float) Math.sin(spread);
            float nx = ex * cs + hx * sn, ny = ey * cs + 0.4f, nz = ez * cs + hz * sn;
            float nl = len * (depth == 0 ? 0.75f : 0.7f) * (0.8f + 0.4f * r.nextFloat());
            limb(b, r, qx, qy, qz, nx, ny, nz, nl, rad * 0.55f, depth + 1, maxDepth, hi, h, clusters);
        }
    }

    private static Mesh birch(long seed, boolean hi) {
        Random r = new Random(seed);
        MeshBuilder b = new MeshBuilder();
        float h = 16f * (0.9f + 0.2f * r.nextFloat());
        float rad = 0.17f;
        int n = 6;
        float[] tx = new float[n + 1], ty = new float[n + 1], tz = new float[n + 1];
        float lx = (r.nextFloat() - 0.5f) * 0.9f, lz = (r.nextFloat() - 0.5f) * 0.9f;
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            ty[i] = h * f - (i == 0 ? 0.4f : 0f);
            tx[i] = lx * f * f + (i > 0 ? (r.nextFloat() - 0.5f) * 0.15f : 0f);
            tz[i] = lz * f * f + (i > 0 ? (r.nextFloat() - 0.5f) * 0.15f : 0f);
        }
        int sides = hi ? 7 : 4;
        for (int i = 0; i < n; i++) {
            tube(b, tx[i], ty[i], tz[i], tx[i + 1], ty[i + 1], tz[i + 1],
                    trunkR(Math.max(0f, ty[i]), h, rad), trunkR(ty[i + 1], h, rad), sides, h, Math.max(0f, ty[i]) * 0.6f);
        }
        ArrayList<float[]> clusters = new ArrayList<>();
        float y0 = h * (0.3f + 0.1f * r.nextFloat());
        float step = hi ? 0.55f : 1.1f;
        float ang = r.nextFloat() * 6.2832f;
        for (float y = y0; y < h - 0.4f; y += step * (0.7f + 0.6f * r.nextFloat())) {
            float t = (y - y0) / (h - y0);
            ang += 2.4f + (r.nextFloat() - 0.5f) * 0.5f;
            float len = 3.2f * (float) Math.sin(Math.PI * (0.12f + 0.88f * t)) * (0.7f + 0.5f * r.nextFloat()) + 0.6f;
            float elev = 0.55f + 0.3f * t;
            float f = y / h * n;
            int i = Math.min(n - 1, (int) f);
            float ft = f - i;
            float bx = tx[i] + (tx[i + 1] - tx[i]) * ft, bz = tz[i] + (tz[i + 1] - tz[i]) * ft;
            float dx = (float) (Math.cos(ang) * Math.cos(elev)), dy = (float) Math.sin(elev), dz = (float) (Math.sin(ang) * Math.cos(elev));
            if (hi) tube(b, bx, y, bz, bx + dx * len, y + dy * len, bz + dz * len, 0.05f * (1f - t) + 0.025f, 0.015f, 3, h, 0f);
            float[] ss = {0.5f, 0.85f};
            for (float s : ss) {
                clusters.add(new float[]{bx + dx * len * s, y + dy * len * s - 0.35f * s * len, bz + dz * len * s});
            }
        }
        leafCards(b, r, clusters, h, hi ? 3 : 1, hi ? 1.6f : 2.7f);
        return b.build();
    }

    private static void leafCards(MeshBuilder b, Random r, ArrayList<float[]> cl, float h, int perCluster, float size) {
        float cx = 0, cy = 0, cz = 0;
        for (float[] p : cl) { cx += p[0]; cy += p[1]; cz += p[2]; }
        cx /= cl.size(); cy /= cl.size(); cz /= cl.size();
        float rx = 0.5f, ry = 0.5f, rz = 0.5f;
        for (float[] p : cl) {
            rx = Math.max(rx, Math.abs(p[0] - cx));
            ry = Math.max(ry, Math.abs(p[1] - cy));
            rz = Math.max(rz, Math.abs(p[2] - cz));
        }
        rx += size * 0.5f; ry += size * 0.5f; rz += size * 0.5f;
        for (float[] p : cl) {
            for (int k = 0; k < perCluster; k++) {
                float ox = p[0] + (r.nextFloat() - 0.5f) * size * 0.6f;
                float oy = p[1] + (r.nextFloat() - 0.5f) * size * 0.5f;
                float oz = p[2] + (r.nextFloat() - 0.5f) * size * 0.6f;
                float nx = (ox - cx) / rx + (r.nextFloat() - 0.5f) * 1.8f;
                float ny = (oy - cy) / ry + (r.nextFloat() - 0.5f) * 1.8f;
                float nz = (oz - cz) / rz + (r.nextFloat() - 0.5f) * 1.8f;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-3f) { nx = 0; ny = 1; nz = 0; nl = 1; }
                nx /= nl; ny /= nl; nz /= nl;
                float ux, uy, uz;
                if (Math.abs(ny) < 0.9f) { ux = nz; uy = 0; uz = -nx; } else { ux = 0; uy = -nz; uz = ny; }
                float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                ux /= ul; uy /= ul; uz /= ul;
                float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
                float th = r.nextFloat() * 6.2832f, c = (float) Math.cos(th), s = (float) Math.sin(th);
                float Ux = ux * c + vx * s, Uy = uy * c + vy * s, Uz = uz * c + vz * s;
                float Vx = vx * c - ux * s, Vy = vy * c - uy * s, Vz = vz * c - uz * s;
                float half = size * (0.75f + 0.5f * r.nextFloat()) * 0.5f;
                float phase = r.nextFloat() * 6.2832f;
                int base = b.count();
                for (int corner = 0; corner < 4; corner++) {
                    float a = (corner == 1 || corner == 2) ? 1f : -1f;
                    float bb = corner >= 2 ? 1f : -1f;
                    float x = ox + (Ux * a + Vx * bb) * half;
                    float y = oy + (Uy * a + Vy * bb) * half;
                    float z = oz + (Uz * a + Vz * bb) * half;
                    float ex = (x - cx) / rx, ey = (y - cy) / ry, ez = (z - cz) / rz;
                    float e = Math.min(1f, (float) Math.sqrt(ex * ex + ey * ey + ez * ez));
                    float ao = 0.4f + 0.6f * (float) Math.pow(e, 1.5);
                    float yy = Math.max(0f, y / h);
                    b.vert(x, y, z, ex, ey + 0.3f, ez, (a + 1f) * 0.5f, (bb + 1f) * 0.5f,
                            Math.min(1f, yy * yy * 1.2f), 1f, ao, phase);
                }
                b.quad(base, base + 1, base + 2, base + 3);
            }
        }
    }

    // ------------------------------------------------------------------ ground cover

    static Mesh fern(long seed) {
        Random r = new Random(seed);
        MeshBuilder b = new MeshBuilder();
        int fronds = 9;
        for (int f = 0; f < fronds; f++) {
            float a = f * 6.2832f / fronds + (r.nextFloat() - 0.5f) * 0.5f;
            float len = 0.75f + 0.4f * r.nextFloat();
            float lift = 0.8f + 0.5f * r.nextFloat();
            float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
            float sx = dz, sz = -dx;
            int segs = 5;
            float[] px = new float[segs + 1], py = new float[segs + 1], pz = new float[segs + 1];
            for (int i = 0; i <= segs; i++) {
                float s = (float) i / segs;
                float d = len * s * 0.95f;
                px[i] = dx * d;
                pz[i] = dz * d;
                py[i] = len * (1.1f * s - 0.95f * s * s) * lift + 0.02f;
            }
            float tw = (r.nextFloat() - 0.5f) * 0.6f;
            float w = len * 0.2f;
            strip(b, px, py, pz, sx * (float) Math.cos(tw) * w, (float) Math.sin(tw) * w, sz * (float) Math.cos(tw) * w,
                    -0.6f, 1f, r.nextFloat() * 6.2832f, 0.45f, 1f, 0.8f);
        }
        return b.build();
    }

    static Mesh grass() {
        MeshBuilder b = new MeshBuilder();
        float w = 0.45f, h = 0.55f;
        for (int k = 0; k < 3; k++) {
            float a = (float) (k * Math.PI / 3);
            float dx = (float) Math.cos(a) * w, dz = (float) Math.sin(a) * w;
            int v0 = b.vert(-dx, 0f, -dz, 0, 1, 0, 0f, 1f, 0f, 1f, 0.55f, k);
            int v1 = b.vert(dx, 0f, dz, 0, 1, 0, 1f, 1f, 0f, 1f, 0.55f, k);
            int v2 = b.vert(dx, h, dz, 0, 1, 0, 1f, 0f, 1f, 1f, 1f, k);
            int v3 = b.vert(-dx, h, -dz, 0, 1, 0, 0f, 0f, 1f, 1f, 1f, k);
            b.quad(v0, v1, v2, v3);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ rocks

    static Mesh rock(long seed) {
        Random r = new Random(seed);
        Noise nz = new Noise(seed);
        ArrayList<float[]> vs = new ArrayList<>();
        float t = (float) ((1 + Math.sqrt(5)) / 2);
        float[][] ico = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t},
                {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        for (float[] p : ico) vs.add(norm(p));
        int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4},
                {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        for (int it = 0; it < 2; it++) {
            HashMap<Long, Integer> mid = new HashMap<>();
            int[][] nf = new int[faces.length * 4][];
            int k = 0;
            for (int[] f : faces) {
                int a = midpoint(vs, mid, f[0], f[1]);
                int bb = midpoint(vs, mid, f[1], f[2]);
                int c = midpoint(vs, mid, f[2], f[0]);
                nf[k++] = new int[]{f[0], a, c};
                nf[k++] = new int[]{f[1], bb, a};
                nf[k++] = new int[]{f[2], c, bb};
                nf[k++] = new int[]{a, bb, c};
            }
            faces = nf;
        }
        float sx = 1f + 0.4f * r.nextFloat(), sy = 0.55f + 0.25f * r.nextFloat(), sz = 1f + 0.3f * r.nextFloat();
        float ox = r.nextFloat() * 50f, oy = r.nextFloat() * 50f;
        int nv = vs.size();
        float[] pos = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            float[] p = vs.get(i);
            float d = 1f + 0.25f * n3(nz, p[0] * 1.2f + ox, p[1] * 1.2f + oy, p[2] * 1.2f)
                    + 0.08f * n3(nz, p[0] * 3.5f, p[1] * 3.5f + ox, p[2] * 3.5f);
            // flatten facets a little for a fractured look
            d = Math.round(d * 7f) / 7f * 0.35f + d * 0.65f;
            pos[i * 3] = p[0] * d * sx;
            pos[i * 3 + 1] = p[1] * d * sy;
            pos[i * 3 + 2] = p[2] * d * sz;
        }
        float[] nrm = new float[nv * 3];
        for (int[] f : faces) {
            int a = f[0] * 3, bb = f[1] * 3, c = f[2] * 3;
            float e1x = pos[bb] - pos[a], e1y = pos[bb + 1] - pos[a + 1], e1z = pos[bb + 2] - pos[a + 2];
            float e2x = pos[c] - pos[a], e2y = pos[c + 1] - pos[a + 1], e2z = pos[c + 2] - pos[a + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nzz = e1x * e2y - e1y * e2x;
            for (int v : f) { nrm[v * 3] += nx; nrm[v * 3 + 1] += ny; nrm[v * 3 + 2] += nzz; }
        }
        MeshBuilder b = new MeshBuilder();
        for (int i = 0; i < nv; i++) {
            float y = pos[i * 3 + 1];
            float ao = 0.55f + 0.45f * Math.max(0f, Math.min(1f, y / sy * 0.5f + 0.5f));
            b.vert(pos[i * 3], y, pos[i * 3 + 2], nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2],
                    0f, 0f, 0f, 0f, ao, 0f);
        }
        for (int[] f : faces) b.tri(f[0], f[1], f[2]);
        return b.build();
    }

    private static float n3(Noise n, float x, float y, float z) {
        return (n.simplex(x + y * 0.7f, z) + n.simplex(y - z * 0.6f, x + 11.1f)) * 0.5f;
    }

    private static float[] norm(float[] p) {
        float l = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        return new float[]{p[0] / l, p[1] / l, p[2] / l};
    }

    private static int midpoint(ArrayList<float[]> vs, HashMap<Long, Integer> cache, int a, int b) {
        long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        Integer found = cache.get(key);
        if (found != null) return found;
        float[] pa = vs.get(a), pb = vs.get(b);
        vs.add(norm(new float[]{(pa[0] + pb[0]) * 0.5f, (pa[1] + pb[1]) * 0.5f, (pa[2] + pb[2]) * 0.5f}));
        cache.put(key, vs.size() - 1);
        return vs.size() - 1;
    }
}
