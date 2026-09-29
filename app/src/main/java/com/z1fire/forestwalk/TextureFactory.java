package com.z1fire.forestwalk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;

/**
 * Paints foliage textures with Android's Canvas. Each texture is painted twice with the same
 * random sequence: once in colour over a foliage-coloured background (so mipmaps don't bleed dark
 * fringes) and once in white over black to form the alpha mask.
 */
final class TextureFactory {
    private TextureFactory() {}

    interface Painter {
        void paint(Canvas c, Paint p, Random r, boolean mask, int s);
    }

    static int build(int size, int background, long seed, Painter painter) {
        Bitmap col = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(col);
        cc.drawColor(background);
        painter.paint(cc, newPaint(), new Random(seed), false, size);
        Bitmap msk = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(msk);
        mc.drawColor(Color.BLACK);
        painter.paint(mc, newPaint(), new Random(seed), true, size);

        int[] a = new int[size * size], m = new int[size * size];
        col.getPixels(a, 0, size, 0, 0, size, size);
        msk.getPixels(m, 0, size, 0, 0, size, size);
        col.recycle();
        msk.recycle();
        ByteBuffer bb = ByteBuffer.allocateDirect(size * size * 4);
        for (int i = 0; i < a.length; i++) {
            int c = a[i];
            bb.put((byte) ((c >> 16) & 255));
            bb.put((byte) ((c >> 8) & 255));
            bb.put((byte) (c & 255));
            bb.put((byte) ((m[i] >> 16) & 255));
        }
        bb.position(0);

        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static Paint newPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private static void color(Paint p, boolean mask, int c) {
        p.setColor(mask ? Color.WHITE : c);
    }

    private static int hsv(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, Math.max(0f, Math.min(1f, s)), Math.max(0f, Math.min(1f, v))});
    }

    // ------------------------------------------------------------------ conifer sprays

    /** Branch spray: base at the top edge (v = 0), tip at the bottom (v = 1). */
    static Painter needles(final boolean pine) {
        return (c, p, r, mask, s) -> {
            float cx = s * 0.5f;
            float k = s / 512f;
            ArrayList<float[]> twigs = new ArrayList<>();
            twigs.add(new float[]{cx, 0f, cx + (r.nextFloat() - 0.5f) * s * 0.05f, s * 0.97f, 7f * k});
            int sideTwigs = pine ? 4 : 8;
            for (int i = 0; i < sideTwigs; i++) {
                float t = 0.08f + 0.78f * (i + r.nextFloat()) / sideTwigs;
                float y0 = t * s;
                float dir = (i % 2 == 0) ? 1f : -1f;
                float reach = s * 0.44f * (1f - t * 0.65f) * (0.75f + 0.25f * r.nextFloat());
                float ang = (float) Math.toRadians(40 + 20 * r.nextFloat());
                float len = reach / (float) Math.sin(ang);
                twigs.add(new float[]{cx, y0, cx + dir * reach, y0 + len * (float) Math.cos(ang), 3.5f * k});
            }
            p.setStyle(Paint.Style.STROKE);
            for (float[] tw : twigs) {
                p.setStrokeWidth(tw[4]);
                color(p, mask, hsv(25, 0.45f, 0.22f));
                c.drawLine(tw[0], tw[1], tw[2], tw[3], p);
            }
            float needleLen = s * (pine ? 0.12f : 0.07f);
            float stepLen = (pine ? 4.5f : 3.0f) * k;
            p.setStrokeWidth((pine ? 2.6f : 2.1f) * k);
            for (float[] tw : twigs) {
                float dx = tw[2] - tw[0], dy = tw[3] - tw[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                float theta = (float) Math.atan2(dx, dy);
                int steps = (int) (len / stepLen);
                for (int i = 0; i < steps; i++) {
                    float f = (float) i / steps;
                    float x = tw[0] + dx * f, y = tw[1] + dy * f;
                    for (int side = -1; side <= 1; side += 2) {
                        float spread = (float) Math.toRadians(pine ? 20 + 25 * r.nextFloat() : 50 + 25 * r.nextFloat());
                        float a = theta + side * spread;
                        float nl = needleLen * (0.7f + 0.6f * r.nextFloat()) * (1f - 0.35f * f);
                        float hue, sat, val;
                        if (pine) {
                            hue = 88 + 22 * r.nextFloat(); sat = 0.45f + 0.2f * r.nextFloat(); val = 0.24f + 0.2f * r.nextFloat();
                        } else {
                            hue = 110 + 30 * r.nextFloat(); sat = 0.55f + 0.25f * r.nextFloat(); val = 0.15f + 0.2f * r.nextFloat();
                        }
                        if (f > 0.8f) { val += 0.12f; hue -= 12f; }   // fresh growth at the tips
                        color(p, mask, hsv(hue, sat, val));
                        c.drawLine(x, y, x + (float) Math.sin(a) * nl, y + (float) Math.cos(a) * nl, p);
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------ broadleaf clusters

    static Painter leaves(final boolean birch) {
        return (c, p, r, mask, s) -> {
            float k = s / 512f;
            float cx = s * 0.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * k);
            color(p, mask, hsv(28, 0.35f, 0.25f));
            for (int i = 0; i < 5; i++) {
                double a = r.nextFloat() * Math.PI * 2;
                float rr = s * (0.25f + 0.17f * r.nextFloat());
                c.drawLine(cx, cx, cx + (float) Math.cos(a) * rr, cx + (float) Math.sin(a) * rr, p);
            }
            Path leaf = new Path(), half = new Path();
            int n = birch ? 160 : 90;
            for (int i = 0; i < n; i++) {
                float rr = s * 0.45f * (float) Math.sqrt(r.nextFloat());
                double a = r.nextFloat() * Math.PI * 2;
                float x = cx + (float) Math.cos(a) * rr, y = cx + (float) Math.sin(a) * rr;
                float len = s * (birch ? 0.055f + 0.03f * r.nextFloat() : 0.085f + 0.05f * r.nextFloat());
                float w = len * (birch ? 0.45f : 0.34f);
                float rot = r.nextFloat() * 360f;
                float edge = rr / (s * 0.45f);
                float hue = birch ? 68 + 22 * r.nextFloat() : 82 + 30 * r.nextFloat();
                float sat = 0.55f + 0.3f * r.nextFloat();
                float val = 0.22f + 0.33f * r.nextFloat() + 0.12f * edge;
                c.save();
                c.translate(x, y);
                c.rotate(rot);
                leaf.reset();
                leaf.moveTo(0, -len * 0.5f);
                leaf.quadTo(w, -len * 0.1f, 0, len * 0.5f);
                leaf.quadTo(-w, -len * 0.1f, 0, -len * 0.5f);
                leaf.close();
                p.setStyle(Paint.Style.FILL);
                color(p, mask, hsv(hue, sat, val));
                c.drawPath(leaf, p);
                half.reset();
                half.moveTo(0, -len * 0.5f);
                half.quadTo(-w, -len * 0.1f, 0, len * 0.5f);
                half.close();
                color(p, mask, hsv(hue, sat, val * 0.8f));
                c.drawPath(half, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f * k);
                color(p, mask, hsv(hue - 10, sat * 0.5f, Math.min(1f, val * 1.35f)));
                c.drawLine(0, -len * 0.45f, 0, len * 0.45f, p);
                c.restore();
            }
        };
    }

    // ------------------------------------------------------------------ grass & fern

    /** Grass tuft: blade roots at the bottom edge (v = 1), tips toward the top (v = 0). */
    static Painter grass() {
        return (c, p, r, mask, s) -> {
            p.setStyle(Paint.Style.FILL);
            Path blade = new Path();
            for (int i = 0; i < 55; i++) {
                float x = s * (0.06f + 0.88f * r.nextFloat());
                float h = s * (0.35f + 0.6f * r.nextFloat());
                float lean = s * (r.nextFloat() - 0.5f) * 0.35f;
                float w = s * (0.011f + 0.012f * r.nextFloat());
                boolean dry = r.nextFloat() < 0.14f;
                int col = dry ? hsv(42 + 12 * r.nextFloat(), 0.35f + 0.15f * r.nextFloat(), 0.45f + 0.2f * r.nextFloat())
                        : hsv(72 + 38 * r.nextFloat(), 0.55f + 0.3f * r.nextFloat(), 0.25f + 0.32f * r.nextFloat());
                blade.reset();
                blade.moveTo(x - w, s);
                blade.quadTo(x + lean * 0.3f - w * 0.5f, s - h * 0.55f, x + lean, s - h);
                blade.quadTo(x + lean * 0.3f + w * 0.5f, s - h * 0.55f, x + w, s);
                blade.close();
                color(p, mask, col);
                c.drawPath(blade, p);
            }
        };
    }

    /** Fern frond: base at the top edge (v = 0), tip at the bottom (v = 1). */
    static Painter fern() {
        return (c, p, r, mask, s) -> {
            float k = s / 512f;
            float cx = s * 0.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(6f * k);
            color(p, mask, hsv(80, 0.5f, 0.3f));
            c.drawLine(cx, 0, cx, s * 0.98f, p);
            Path leaf = new Path();
            int n = 26;
            for (int i = 0; i < n; i++) {
                float t = (i + 0.5f) / n;
                float y = t * s * 0.96f;
                float prof = t < 0.25f ? 0.45f + 2.2f * t : 1f - (t - 0.25f) / 0.75f * 0.92f;
                float len = s * 0.46f * prof;
                for (int side = -1; side <= 1; side += 2) {
                    float ang = 60f + 12f * r.nextFloat();
                    float w = len * 0.16f;
                    float hue = 92 + 14 * r.nextFloat(), sat = 0.6f + 0.2f * r.nextFloat(), val = 0.28f + 0.22f * r.nextFloat();
                    c.save();
                    c.translate(cx, y);
                    c.rotate(-side * ang);
                    leaf.reset();
                    leaf.moveTo(0, 0);
                    leaf.quadTo(w, len * 0.45f, 0, len);
                    leaf.quadTo(-w, len * 0.45f, 0, 0);
                    leaf.close();
                    p.setStyle(Paint.Style.FILL);
                    color(p, mask, hsv(hue, sat, val));
                    c.drawPath(leaf, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * k);
                    color(p, mask, hsv(hue, sat * 0.7f, val * 0.75f));
                    c.drawLine(0, 0, 0, len * 0.9f, p);
                    c.restore();
                }
            }
        };
    }
}
