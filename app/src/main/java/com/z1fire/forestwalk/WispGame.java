package com.z1fire.forestwalk;

import java.util.HashMap;
import java.util.Random;

/**
 * Wisp Hunt: glowing wisps drift through the forest and shy away from the walker. Catch them
 * before the lantern burns out; every catch tops the lantern up a little. Runs on the GL thread.
 */
final class WispGame {
    static final int COUNT = 3;
    private static final float START_OIL = 45f, MAX_OIL = 60f;
    private static final float CATCH_RADIUS = 1.4f, FLEE_RADIUS = 7f, HOVER = 1.25f;

    final float[] x = new float[COUNT], y = new float[COUNT], z = new float[COUNT];
    final float[] fade = new float[COUNT];   // 0..1 fade-in after spawning
    private final float[] phase = new float[COUNT];
    private final Controls ctl;
    private final HashMap<Long, Chunk> chunks;
    private final Random rnd = new Random();
    private World world;

    WispGame(Controls ctl, HashMap<Long, Chunk> chunks) {
        this.ctl = ctl;
        this.chunks = chunks;
        ctl.maxOil = MAX_OIL;
    }

    void start(World w, float px, float pz, float yaw) {
        world = w;
        ctl.score = 0;
        ctl.oil = START_OIL;
        for (int i = 0; i < COUNT; i++) {
            // First wisps appear roughly ahead so the round starts with something to chase.
            spawn(i, px, pz, yaw + (i - 1) * 0.9f + (rnd.nextFloat() - 0.5f) * 0.6f, 16f + 8f * i);
        }
        ctl.gameState = Controls.PLAYING;
    }

    boolean active() {
        return ctl.gameState == Controls.PLAYING;
    }

    private void spawn(int i, float px, float pz, float angle, float dist) {
        x[i] = px + (float) Math.sin(angle) * dist;
        z[i] = pz + (float) Math.cos(angle) * dist;
        pushOut(i);
        phase[i] = rnd.nextFloat() * 6.2832f;
        fade[i] = 0f;
    }

    void update(float dt, float px, float pz, float yaw, float time) {
        if (!active()) return;
        int score = ctl.score;
        float oil = ctl.oil - dt * (1f + score * 0.025f);
        float best = Float.MAX_VALUE;
        float bdx = 0f, bdz = 0f;
        for (int i = 0; i < COUNT; i++) {
            fade[i] = Math.min(1f, fade[i] + dt * 0.8f);
            float dx = x[i] - px, dz = z[i] - pz;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < CATCH_RADIUS && fade[i] > 0.4f) {
                score++;
                oil = Math.min(MAX_OIL, oil + Math.max(4f, 9f - score * 0.15f));
                ctl.catchCount++;
                float far = Math.min(75f, 22f + score * 2.5f) * (0.75f + 0.5f * rnd.nextFloat());
                spawn(i, px, pz, rnd.nextFloat() * 6.2832f, far);
                dx = x[i] - px;
                dz = z[i] - pz;
                d = (float) Math.sqrt(dx * dx + dz * dz);
            } else {
                // Meander, and scurry away (slower than a walker) when approached.
                float ph = phase[i];
                float mx = (float) Math.sin(time * 0.37f + ph) * 0.5f;
                float mz = (float) Math.cos(time * 0.29f + ph * 1.7f) * 0.5f;
                if (d < FLEE_RADIUS && d > 1e-3f) {
                    float flee = (1f - d / FLEE_RADIUS) * (1.1f + Math.min(0.3f, 0.02f * score));
                    // Veer sideways a little so the chase curves through the trees.
                    float side = (float) Math.sin(time * 0.8f + ph) * 0.6f;
                    mx += (dx - dz * side) / d * flee * 1.6f;
                    mz += (dz + dx * side) / d * flee * 1.6f;
                }
                x[i] += mx * dt;
                z[i] += mz * dt;
                pushOut(i);
            }
            y[i] = world.height(x[i], z[i]) + HOVER + 0.25f * (float) Math.sin(time * 1.7f + phase[i]);
            if (d < best) {
                best = d;
                bdx = dx;
                bdz = dz;
            }
        }
        float fx = (float) Math.sin(yaw), fz = (float) Math.cos(yaw);
        ctl.wispBearing = (float) Math.atan2(-bdx * fz + bdz * fx, bdx * fx + bdz * fz);
        ctl.wispDist = best;
        ctl.score = score;
        if (oil <= 0f) {
            ctl.oil = 0f;
            if (score > ctl.best) ctl.best = score;
            ctl.gameState = Controls.OVER;
            ctl.overCount++;
        } else {
            ctl.oil = oil;
        }
    }

    /** Keeps a wisp out of tree trunks and boulders so it can always be reached. */
    private void pushOut(int i) {
        int pcx = (int) Math.floor(x[i] / World.CHUNK), pcz = (int) Math.floor(z[i] / World.CHUNK);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk c = chunks.get(Chunk.key(pcx + dx, pcz + dz));
                if (c == null) continue;
                float[] col = c.colliders;
                for (int k = 0; k < c.colliderCount; k++) {
                    float r = col[k * 3 + 2] + 0.9f;
                    float ex = x[i] - col[k * 3], ez = z[i] - col[k * 3 + 1];
                    float d2 = ex * ex + ez * ez;
                    if (d2 < r * r) {
                        if (d2 < 1e-6f) { ex = 1f; ez = 0f; d2 = 1f; }
                        float d = (float) Math.sqrt(d2);
                        x[i] = col[k * 3] + ex / d * r;
                        z[i] = col[k * 3 + 1] + ez / d * r;
                    }
                }
            }
        }
    }
}
