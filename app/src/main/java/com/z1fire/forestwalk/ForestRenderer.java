package com.z1fire.forestwalk;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Streams the world around the walker and renders it: shadow pass, then terrain, plants, sky. */
final class ForestRenderer implements GLSurfaceView.Renderer {
    private static final int LOAD_RADIUS = 5;
    private static final int UNLOAD_RADIUS = 7;
    private static final float TREE_DIST = 170f;
    private static final float LOD_DIST = 42f;
    private static final float FERN_DIST = 48f;
    private static final float GRASS_DIST = 30f;
    private static final int SHADOW_SIZE = 2048;
    private static final float SHADOW_RADIUS = 60f;
    private static final float EYE_HEIGHT = 1.68f;
    private static final int MESHES = TreeFactory.SPECIES * World.VARIANTS;

    private static final float[] BARK_COLOR = {
            0.30f, 0.24f, 0.20f,   // spruce
            0.40f, 0.27f, 0.18f,   // pine
            0.47f, 0.47f, 0.44f,   // beech
            0.86f, 0.85f, 0.81f};  // birch
    private static final int[] BARK_TYPE = {0, 3, 2, 1};
    private static final float[] SHADOW_BIAS = {
            0.5f, 0, 0, 0,
            0, 0.5f, 0, 0,
            0, 0, 0.5f, 0,
            0.5f, 0.5f, 0.5f, 1};

    private final Controls ctl;
    private World world;
    private final HashMap<Long, Chunk> chunks = new HashMap<>();

    private Program terrainProg, objectProg, shadowProg, skyProg, wispProg;
    private int terrainIbo, skyVao, quadVao;
    private final WispGame game;
    private float exposure = 1f;
    private int shadowFbo, shadowTex;
    private boolean msaa;
    private int width = 1, height = 1;

    private final InstanceBatch[][] treeMain = new InstanceBatch[MESHES][2];
    private final InstanceBatch[][] treeShadow = new InstanceBatch[MESHES][2];
    private final InstanceBatch[] rockMain = new InstanceBatch[3];
    private final InstanceBatch[] rockShadow = new InstanceBatch[3];
    private InstanceBatch fernBatch, grassBatch;
    private final int[] foliageTex = new int[TreeFactory.SPECIES];
    private int fernTex, grassTex;

    // walker state
    private float px, pz, camY, yaw, pitch, velX, velZ, stepPhase;
    private int autoDir = 1;
    private float time;
    private long lastNanos;
    private int frames;
    private long fpsStart;

    private final float[] proj = new float[16], view = new float[16], viewProj = new float[16], invViewProj = new float[16];
    private final float[] lightView = new float[16], lightProj = new float[16], lightVP = new float[16], shadowMat = new float[16];
    private final float[] planes = new float[24];
    private final float[] vin = new float[4], vout = new float[4], look = new float[2];
    private float shadowCx, shadowCz;
    private float windX, windZ;

    ForestRenderer(Controls ctl) {
        this.ctl = ctl;
        game = new WispGame(ctl, chunks);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        ctl.loading = true;
        int[] v = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_SAMPLES, v, 0);
        msaa = v[0] > 1;
        terrainProg = new Program(Shaders.TERRAIN_VS, Shaders.TERRAIN_FS);
        objectProg = new Program(Shaders.OBJECT_VS, Shaders.OBJECT_FS);
        shadowProg = new Program(Shaders.OBJECT_VS, Shaders.SHADOW_FS);
        skyProg = new Program(Shaders.SKY_VS, Shaders.SKY_FS);
        wispProg = new Program(Shaders.WISP_VS, Shaders.WISP_FS);
        buildTerrainIndices();
        buildSky();
        buildQuad();
        buildShadowTarget();
        buildTextures();
        // Any previous GL objects died with the old context; regenerate everything.
        chunks.clear();
        dropBatches(false);
        if (world == null) {
            newWorld(100000 + new Random().nextInt(900000));
        } else {
            buildMeshes();
        }
        lastNanos = System.nanoTime();
        fpsStart = lastNanos;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES30.glViewport(0, 0, width, height);
        Matrix.perspectiveM(proj, 0, 62f, (float) width / height, 0.1f, 400f);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;

        boolean start = ctl.takeStart();
        if (start && ctl.gameState == Controls.OVER) {
            // Every new round is played in a freshly grown forest.
            ctl.loading = true;
            newWorld(100000 + new Random().nextInt(900000));
        }
        updateChunks(chunks.isEmpty() ? 1000 : 2);
        if (start && ctl.gameState != Controls.PLAYING) game.start(world, px, pz, yaw);
        updatePlayer(dt);
        game.update(dt, px, pz, yaw, time);
        // The world dims as the lantern burns down, so the wisps stand out more.
        float targetExp = game.active() ? 0.4f + 0.6f * World.smoothstep(0f, 15f, ctl.oil) : 1f;
        exposure += (targetExp - exposure) * Math.min(1f, dt * 2f);
        setupCamera();
        setupShadow();
        float gust = 0.6f + 0.4f * (float) (Math.sin(time * 0.21) * Math.sin(time * 0.53 + 1.3));
        windX = world.windX * gust;
        windZ = world.windZ * gust;
        buildInstances();
        renderShadows();
        renderMain();

        frames++;
        if (now - fpsStart > 1_000_000_000L) {
            ctl.fps = frames * 1e9f / (now - fpsStart);
            frames = 0;
            fpsStart = now;
        }
        ctl.loading = false;
    }

    private void newWorld(long seed) {
        for (Chunk c : chunks.values()) c.release();
        chunks.clear();
        world = new World(seed);
        ctl.seed = seed;
        dropBatches(true);
        buildMeshes();
        pz = 0f;
        px = world.pathCenter(0f);
        yaw = (float) Math.atan2(world.pathCenter(6f) - px, 6f);
        pitch = -0.04f;
        velX = velZ = 0f;
        camY = world.height(px, pz) + EYE_HEIGHT;
        ctl.autoWalk = false;
    }

    // ------------------------------------------------------------------ GL resources

    private void buildTerrainIndices() {
        int n = World.RES, row = n + 1;
        short[] idx = new short[n * n * 6];
        int k = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * row + i, b = a + row, c = a + 1, d = b + 1;
                idx[k++] = (short) a; idx[k++] = (short) b; idx[k++] = (short) c;
                idx[k++] = (short) c; idx[k++] = (short) b; idx[k++] = (short) d;
            }
        }
        int[] t = new int[1];
        GLES30.glBindVertexArray(0);
        GLES30.glGenBuffers(1, t, 0);
        terrainIbo = t[0];
        ShortBuffer sb = ByteBuffer.allocateDirect(idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(idx).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, terrainIbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, sb, GLES30.GL_STATIC_DRAW);
    }

    private void buildSky() {
        float[] tri = {-1f, -1f, 3f, -1f, -1f, 3f};
        int[] t = new int[1];
        GLES30.glGenVertexArrays(1, t, 0);
        skyVao = t[0];
        GLES30.glBindVertexArray(skyVao);
        GLES30.glGenBuffers(1, t, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, t[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.length * 4, Mesh.floatBuffer(tri, tri.length), GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glBindVertexArray(0);
    }

    private void buildQuad() {
        float[] q = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        int[] t = new int[1];
        GLES30.glGenVertexArrays(1, t, 0);
        quadVao = t[0];
        GLES30.glBindVertexArray(quadVao);
        GLES30.glGenBuffers(1, t, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, t[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.length * 4, Mesh.floatBuffer(q, q.length), GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glBindVertexArray(0);
    }

    private void buildShadowTarget() {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        shadowTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, SHADOW_SIZE, SHADOW_SIZE, 0,
                GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL);
        GLES30.glGenFramebuffers(1, t, 0);
        shadowFbo = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0);
        GLES30.glDrawBuffers(1, new int[]{GLES30.GL_NONE}, 0);
        GLES30.glReadBuffer(GLES30.GL_NONE);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void buildTextures() {
        foliageTex[TreeFactory.SPRUCE] = TextureFactory.build(512, 0xFF1B3318, 11, TextureFactory.needles(false));
        foliageTex[TreeFactory.PINE] = TextureFactory.build(512, 0xFF33452A, 12, TextureFactory.needles(true));
        foliageTex[TreeFactory.BEECH] = TextureFactory.build(512, 0xFF2C4A1C, 13, TextureFactory.leaves(false));
        foliageTex[TreeFactory.BIRCH] = TextureFactory.build(512, 0xFF45602A, 14, TextureFactory.leaves(true));
        fernTex = TextureFactory.build(512, 0xFF2F5A1E, 15, TextureFactory.fern());
        grassTex = TextureFactory.build(256, 0xFF3D5A22, 16, TextureFactory.grass());
    }

    /** Forgets all instance batches; GL objects are only deleted if the context is still alive. */
    private void dropBatches(boolean delete) {
        for (int m = 0; m < MESHES; m++) {
            for (int l = 0; l < 2; l++) {
                if (delete && treeMain[m][l] != null) treeMain[m][l].delete();
                if (delete && treeShadow[m][l] != null) treeShadow[m][l].delete();
                treeMain[m][l] = null;
                treeShadow[m][l] = null;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (delete && rockMain[i] != null) rockMain[i].delete();
            if (delete && rockShadow[i] != null) rockShadow[i].delete();
            rockMain[i] = null;
            rockShadow[i] = null;
        }
        if (delete && fernBatch != null) fernBatch.delete();
        if (delete && grassBatch != null) grassBatch.delete();
        fernBatch = null;
        grassBatch = null;
    }

    private void buildMeshes() {
        long s = world.seed;
        for (int sp = 0; sp < TreeFactory.SPECIES; sp++) {
            for (int v = 0; v < World.VARIANTS; v++) {
                int m = sp * World.VARIANTS + v;
                long ms = s * 7919 + m * 104729L;
                for (int l = 0; l < 2; l++) {
                    Mesh mesh = TreeFactory.tree(sp, ms, l == 0);
                    treeMain[m][l] = new InstanceBatch(mesh, true);
                    treeShadow[m][l] = new InstanceBatch(mesh, false);
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            Mesh rock = TreeFactory.rock(s + 31L * i);
            rockMain[i] = new InstanceBatch(rock, true);
            rockShadow[i] = new InstanceBatch(rock, false);
        }
        fernBatch = new InstanceBatch(TreeFactory.fern(s + 5), true);
        grassBatch = new InstanceBatch(TreeFactory.grass(), true);
    }

    // ------------------------------------------------------------------ world streaming

    private void updateChunks(int maxNew) {
        int pcx = (int) Math.floor(px / World.CHUNK), pcz = (int) Math.floor(pz / World.CHUNK);
        Iterator<Chunk> it = chunks.values().iterator();
        while (it.hasNext()) {
            Chunk c = it.next();
            if (Math.abs(c.cx - pcx) > UNLOAD_RADIUS || Math.abs(c.cz - pcz) > UNLOAD_RADIUS) {
                c.release();
                it.remove();
            }
        }
        int made = 0;
        float lim = (LOAD_RADIUS + 0.5f) * (LOAD_RADIUS + 0.5f);
        outer:
        for (int ring = 0; ring <= LOAD_RADIUS; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx * dx + dz * dz > lim) continue;
                    long key = Chunk.key(pcx + dx, pcz + dz);
                    if (chunks.containsKey(key)) continue;
                    Chunk c = world.generate(pcx + dx, pcz + dz);
                    c.upload(terrainIbo);
                    chunks.put(key, c);
                    if (++made >= maxNew) break outer;
                }
            }
        }
    }

    // ------------------------------------------------------------------ walker

    private static float wrapAngle(float a) {
        while (a > Math.PI) a -= (float) (2 * Math.PI);
        while (a < -Math.PI) a += (float) (2 * Math.PI);
        return a;
    }

    private void updatePlayer(float dt) {
        ctl.consumeLook(look);
        yaw -= look[0];
        pitch = Math.max(-1.3f, Math.min(1.3f, pitch - look[1]));

        float mx = ctl.moveX, my = ctl.moveY;
        boolean manual = mx * mx + my * my > 0.01f;
        if (manual && ctl.autoWalk) ctl.autoWalk = false;
        float tvx, tvz;
        if (ctl.autoWalk) {
            // Follow the trail: aim at a point on it a few metres ahead.
            float tz = pz + autoDir * 7f;
            float tx = world.pathCenter(tz);
            float heading = (float) Math.atan2(tx - px, tz - pz);
            float speed = 1.35f;
            tvx = (float) Math.sin(heading) * speed;
            tvz = (float) Math.cos(heading) * speed;
            if (!ctl.lookActive) {
                yaw += wrapAngle(heading - yaw) * Math.min(1f, dt * 1.2f);
                pitch += (-0.04f - pitch) * Math.min(1f, dt * 0.8f);
            }
        } else {
            autoDir = Math.cos(yaw) >= 0 ? 1 : -1;
            float fx = (float) Math.sin(yaw), fz = (float) Math.cos(yaw);
            float rx = -fz, rz = fx;
            float speed = 2.4f;
            tvx = (fx * my + rx * mx) * speed;
            tvz = (fz * my + rz * mx) * speed;
        }
        float k = Math.min(1f, dt * 6f);
        velX += (tvx - velX) * k;
        velZ += (tvz - velZ) * k;
        px += velX * dt;
        pz += velZ * dt;
        collide();

        float speed = (float) Math.sqrt(velX * velX + velZ * velZ);
        stepPhase += speed * dt / 0.78f;
        float bob = ((float) Math.abs(Math.sin(stepPhase * Math.PI)) - 0.5f) * 0.05f * Math.min(1f, speed / 1.4f);
        float target = world.height(px, pz) + EYE_HEIGHT + bob;
        camY += (target - camY) * Math.min(1f, dt * 12f);
    }

    private void collide() {
        int pcx = (int) Math.floor(px / World.CHUNK), pcz = (int) Math.floor(pz / World.CHUNK);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk c = chunks.get(Chunk.key(pcx + dx, pcz + dz));
                if (c == null) continue;
                float[] col = c.colliders;
                for (int i = 0; i < c.colliderCount; i++) {
                    float r = col[i * 3 + 2] + 0.3f;
                    float ex = px - col[i * 3], ez = pz - col[i * 3 + 1];
                    float d2 = ex * ex + ez * ez;
                    if (d2 < r * r && d2 > 1e-8f) {
                        float d = (float) Math.sqrt(d2);
                        float push = (r - d) / d;
                        px += ex * push;
                        pz += ez * push;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ cameras

    private void setupCamera() {
        float cp = (float) Math.cos(pitch);
        float fx = (float) Math.sin(yaw) * cp, fy = (float) Math.sin(pitch), fz = (float) Math.cos(yaw) * cp;
        Matrix.setLookAtM(view, 0, px, camY, pz, px + fx, camY + fy, pz + fz, 0f, 1f, 0f);
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0);
        Matrix.invertM(invViewProj, 0, viewProj, 0);
        float[] m = viewProj;
        for (int p = 0; p < 6; p++) {
            int row = p / 2;
            float sign = (p % 2 == 0) ? 1f : -1f;
            float a = m[3] + sign * m[row];
            float b = m[7] + sign * m[4 + row];
            float c = m[11] + sign * m[8 + row];
            float d = m[15] + sign * m[12 + row];
            float l = (float) Math.sqrt(a * a + b * b + c * c);
            planes[p * 4] = a / l;
            planes[p * 4 + 1] = b / l;
            planes[p * 4 + 2] = c / l;
            planes[p * 4 + 3] = d / l;
        }
    }

    private boolean visible(float x, float y, float z, float r) {
        for (int p = 0; p < 5; p++) {   // skip far plane; distance limits handle it
            int o = p * 4;
            if (planes[o] * x + planes[o + 1] * y + planes[o + 2] * z + planes[o + 3] < -r) return false;
        }
        return true;
    }

    private void setupShadow() {
        float fx = (float) Math.sin(yaw), fz = (float) Math.cos(yaw);
        float cx = px + fx * SHADOW_RADIUS * 0.6f, cz = pz + fz * SHADOW_RADIUS * 0.6f;
        float cy = world.height(cx, cz);
        float[] s = world.sunDir;
        Matrix.setLookAtM(lightView, 0, cx + s[0] * 200f, cy + s[1] * 200f, cz + s[2] * 200f, cx, cy, cz, 0f, 1f, 0f);
        Matrix.orthoM(lightProj, 0, -SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 1f, 420f);
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0);
        // Snap to whole shadow texels so shadows don't shimmer while walking.
        vin[0] = 0; vin[1] = 0; vin[2] = 0; vin[3] = 1;
        Matrix.multiplyMV(vout, 0, lightVP, 0, vin, 0);
        float half = SHADOW_SIZE * 0.5f;
        float sx = vout[0] * half, sy = vout[1] * half;
        lightVP[12] += (Math.round(sx) - sx) / half;
        lightVP[13] += (Math.round(sy) - sy) / half;
        Matrix.multiplyMM(shadowMat, 0, SHADOW_BIAS, 0, lightVP, 0);
        shadowCx = cx;
        shadowCz = cz;
    }

    private boolean inShadowBox(float x, float y, float z, float r) {
        float[] m = lightVP;
        float lx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float ly = m[1] * x + m[5] * y + m[9] * z + m[13];
        float rr = 1f + r / SHADOW_RADIUS;
        return Math.abs(lx) < rr && Math.abs(ly) < rr;
    }

    // ------------------------------------------------------------------ per-frame instance lists

    private void buildInstances() {
        for (int m = 0; m < MESHES; m++) {
            for (int l = 0; l < 2; l++) {
                treeMain[m][l].begin();
                treeShadow[m][l].begin();
            }
        }
        for (int i = 0; i < 3; i++) {
            rockMain[i].begin();
            rockShadow[i].begin();
        }
        fernBatch.begin();
        grassBatch.begin();
        int grassBudget = 1;
        float lod2 = LOD_DIST * LOD_DIST, tree2 = TREE_DIST * TREE_DIST;
        float fern2 = FERN_DIST * FERN_DIST, grass2 = GRASS_DIST * GRASS_DIST;

        for (Chunk c : chunks.values()) {
            float ccx = c.x0 + World.CHUNK * 0.5f, ccz = c.z0 + World.CHUNK * 0.5f;
            float ccy = (c.minY + c.maxY) * 0.5f + 10f;
            float cr = 23f + (c.maxY - c.minY) * 0.5f + 12f;
            float cdx = ccx - px, cdz = ccz - pz;
            float cd = (float) Math.sqrt(cdx * cdx + cdz * cdz);
            boolean vis = cd - 23f < TREE_DIST && visible(ccx, ccy, ccz, cr);
            boolean shadowRel = inShadowBox(ccx, ccy, ccz, cr);
            if (!vis && !shadowRel) continue;

            float[] t = c.trees;
            for (int i = 0; i < c.treeCount; i++) {
                int o = i * InstanceBatch.FLOATS;
                float x = t[o], y = t[o + 1], z = t[o + 2], s = t[o + 4];
                int m = (int) t[o + 7];
                float dx = x - px, dz = z - pz;
                float d2 = dx * dx + dz * dz;
                int lod = d2 < lod2 ? 0 : 1;
                if (vis && d2 < tree2 && visible(x, y + 9f * s, z, 12f * s + 1f)) treeMain[m][lod].addFrom(t, o);
                if (shadowRel && inShadowBox(x, y + 9f * s, z, 12f * s)) treeShadow[m][lod].addFrom(t, o);
            }
            float[] r = c.rocks;
            for (int i = 0; i < c.rockCount; i++) {
                int o = i * InstanceBatch.FLOATS;
                float x = r[o], y = r[o + 1], z = r[o + 2], s = r[o + 4];
                int m = (int) r[o + 7];
                float dx = x - px, dz = z - pz;
                if (vis && dx * dx + dz * dz < tree2 && visible(x, y, z, 1.6f * s)) rockMain[m].addFrom(r, o);
                if (shadowRel && inShadowBox(x, y, z, 1.6f * s)) rockShadow[m].addFrom(r, o);
            }
            if (vis && cd < FERN_DIST + 23f) {
                float[] f = c.ferns;
                for (int i = 0; i < c.fernCount; i++) {
                    int o = i * InstanceBatch.FLOATS;
                    float dx = f[o] - px, dz = f[o + 2] - pz;
                    if (dx * dx + dz * dz < fern2 && visible(f[o], f[o + 1] + 0.4f, f[o + 2], 1.4f)) fernBatch.addFrom(f, o);
                }
            }
            if (cd < GRASS_DIST + 23f) {
                if (c.grass == null && grassBudget-- > 0) world.buildGrass(c);
                if (vis && c.grass != null) {
                    float[] g = c.grass;
                    for (int i = 0; i < c.grassCount; i++) {
                        int o = i * InstanceBatch.FLOATS;
                        float dx = g[o] - px, dz = g[o + 2] - pz;
                        if (dx * dx + dz * dz < grass2 && visible(g[o], g[o + 1] + 0.3f, g[o + 2], 0.8f)) grassBatch.addFrom(g, o);
                    }
                }
            }
        }

        for (int m = 0; m < MESHES; m++) {
            for (int l = 0; l < 2; l++) {
                treeMain[m][l].upload();
                treeShadow[m][l].upload();
            }
        }
        for (int i = 0; i < 3; i++) {
            rockMain[i].upload();
            rockShadow[i].upload();
        }
        fernBatch.upload();
        grassBatch.upload();
    }

    // ------------------------------------------------------------------ rendering

    private void setObjectUniforms(Program p, float[] vp) {
        p.setMat4("uViewProj", vp);
        p.set1f("uTime", time);
        p.set2f("uWind", windX, windZ);
        p.set3f("uCamPos", px, camY, pz);
        p.set1f("uFadeDist", 0f);
        p.set1i("uLeafTex", 0);
    }

    private void renderShadows() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo);
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glPolygonOffset(1.5f, 3f);
        shadowProg.use();
        setObjectUniforms(shadowProg, lightVP);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        for (int sp = 0; sp < TreeFactory.SPECIES; sp++) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foliageTex[sp]);
            shadowProg.set1f("uSwayAmp", 0.35f);
            for (int v = 0; v < World.VARIANTS; v++) {
                int m = sp * World.VARIANTS + v;
                treeShadow[m][0].draw();
                treeShadow[m][1].draw();
            }
        }
        shadowProg.set1f("uSwayAmp", 0f);
        for (int i = 0; i < 3; i++) rockShadow[i].draw();
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        GLES30.glBindVertexArray(0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void setCommon(Program p) {
        p.set3f("uSunDir", world.sunDir);
        p.set3f("uSunCol", world.sunCol);
        p.set3f("uSkyAmb", 0.19f, 0.25f, 0.33f);
        p.set3f("uGroundAmb", 0.07f, 0.065f, 0.045f);
        p.set3f("uFogCol", 0.50f, 0.60f, 0.68f);
        p.set3f("uCamPos", px, camY, pz);
        p.set1f("uFogDensity", 0.0088f);
        p.set1f("uTime", time);
        p.set1f("uExposure", exposure);
        p.setMat4("uShadowMat", shadowMat);
        p.set1i("uShadowMap", 1);
    }

    private void renderMain() {
        GLES30.glViewport(0, 0, width, height);
        GLES30.glClearColor(0.5f, 0.6f, 0.68f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

        // terrain
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glFrontFace(GLES30.GL_CCW);
        terrainProg.use();
        setCommon(terrainProg);
        terrainProg.setMat4("uViewProj", viewProj);
        int indexCount = World.RES * World.RES * 6;
        for (Chunk c : chunks.values()) {
            float ccx = c.x0 + World.CHUNK * 0.5f, ccz = c.z0 + World.CHUNK * 0.5f;
            float ccy = (c.minY + c.maxY) * 0.5f;
            float r = 23f + (c.maxY - c.minY) * 0.5f;
            if (!visible(ccx, ccy, ccz, r)) continue;
            GLES30.glBindVertexArray(c.vao);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0);
        }
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        // plants and rocks
        objectProg.use();
        setCommon(objectProg);
        setObjectUniforms(objectProg, viewProj);
        objectProg.set1f("uMsaa", msaa ? 1f : 0f);
        objectProg.set3f("uLeafTint", 1f, 1f, 1f);
        objectProg.set1f("uTranslucency", 0.6f);
        if (msaa) GLES30.glEnable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE);

        objectProg.set1i("uBarkType", 4);
        objectProg.set1f("uSwayAmp", 0f);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grassTex);
        for (int i = 0; i < 3; i++) rockMain[i].draw();

        objectProg.set1f("uSwayAmp", 0.35f);
        for (int sp = 0; sp < TreeFactory.SPECIES; sp++) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foliageTex[sp]);
            objectProg.set3f("uBarkCol", BARK_COLOR[sp * 3], BARK_COLOR[sp * 3 + 1], BARK_COLOR[sp * 3 + 2]);
            objectProg.set1i("uBarkType", BARK_TYPE[sp]);
            for (int v = 0; v < World.VARIANTS; v++) {
                int m = sp * World.VARIANTS + v;
                treeMain[m][0].draw();
                treeMain[m][1].draw();
            }
        }

        objectProg.set1f("uSwayAmp", 0.06f);
        objectProg.set1f("uFadeDist", FERN_DIST);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fernTex);
        fernBatch.draw();

        objectProg.set1f("uSwayAmp", 0.12f);
        objectProg.set1f("uFadeDist", GRASS_DIST);
        objectProg.set3f("uLeafTint", 0.95f, 1f, 0.9f);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grassTex);
        grassBatch.draw();
        if (msaa) GLES30.glDisable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE);

        // sky fills whatever is left at the far plane
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        skyProg.use();
        setCommon(skyProg);
        skyProg.setMat4("uInvViewProj", invViewProj);
        GLES30.glBindVertexArray(skyVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        renderWisps();
        GLES30.glBindVertexArray(0);
    }

    private void renderWisps() {
        if (!game.active()) return;
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glDepthMask(false);
        wispProg.use();
        wispProg.setMat4("uViewProj", viewProj);
        wispProg.set3f("uRight", view[0], view[4], view[8]);
        wispProg.set3f("uUp", view[1], view[5], view[9]);
        wispProg.set3f("uCamPos", px, camY, pz);
        GLES30.glBindVertexArray(quadVao);
        for (int i = 0; i < WispGame.COUNT; i++) {
            float dx = game.x[i] - px, dz = game.z[i] - pz;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float flicker = 0.85f + 0.15f * (float) Math.sin(time * 9.1f + i * 2.3f) * (float) Math.sin(time * 5.3f + i);
            wispProg.set3f("uCenter", game.x[i], game.y[i], game.z[i]);
            wispProg.set1f("uSize", 0.8f + d * 0.012f);   // grow a little with distance so far wisps stay visible
            wispProg.set3f("uColor", 0.75f, 0.95f, 0.45f);
            wispProg.set1f("uAlpha", game.fade[i] * flicker);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }
}
