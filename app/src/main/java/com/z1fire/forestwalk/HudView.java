package com.z1fire.forestwalk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Transparent overlay: floating joystick (left), look-drag area (right), buttons and the Wisp Hunt HUD. */
final class HudView extends View {
    private static final long HINT_MS = 10000;
    private static final long OVER_LOCK_MS = 1200;   // ignore taps briefly so a frantic swipe doesn't restart

    private final Controls c;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF autoBtn = new RectF(), soundBtn = new RectF(), bar = new RectF();
    private final Path arrow = new Path();
    private long playStart, overAt;
    private int lastState = Controls.READY;

    private int joyId = -1, lookId = -1;
    private float joyCx, joyCy, joyX, joyY, lastX, lastY;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 33);
        }
    };

    HudView(Context ctx, Controls controls) {
        super(ctx);
        c = controls;
        dp = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * dp);
        text.setColor(Color.WHITE);
        text.setShadowLayer(3f * dp, 0, dp, 0xAA000000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float bw = 132 * dp, bh = 38 * dp, m = 14 * dp, gap = 10 * dp;
        autoBtn.set(w - m - bw, m, w - m, m + bh);
        soundBtn.set(autoBtn.left, autoBtn.bottom + gap, autoBtn.right, autoBtn.bottom + gap + bh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int id = e.getPointerId(i);
                float x = e.getX(i), y = e.getY(i);
                if (soundBtn.contains(x, y)) {
                    c.soundOn = !c.soundOn;
                } else if (c.gameState != Controls.PLAYING) {
                    if (!c.loading && SystemClock.uptimeMillis() - overAt > OVER_LOCK_MS) c.requestStart();
                } else if (autoBtn.contains(x, y)) {
                    c.autoWalk = !c.autoWalk;
                } else if (x < getWidth() * 0.4f && joyId < 0) {
                    joyId = id;
                    joyCx = joyX = x;
                    joyCy = joyY = y;
                } else if (lookId < 0) {
                    lookId = id;
                    lastX = x;
                    lastY = y;
                    c.lookActive = true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float lookScale = 0.0055f / dp;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (id == joyId) {
                        float r = 60 * dp;
                        float dx = (x - joyCx) / r, dy = (y - joyCy) / r;
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len > 1f) {
                            dx /= len;
                            dy /= len;
                        }
                        joyX = joyCx + dx * r;
                        joyY = joyCy + dy * r;
                        c.moveX = dx;
                        c.moveY = -dy;
                    } else if (id == lookId) {
                        c.addLook((x - lastX) * lookScale, (y - lastY) * lookScale);
                        lastX = x;
                        lastY = y;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(e.getActionIndex());
                if (id == joyId) releaseJoystick();
                if (id == lookId) {
                    lookId = -1;
                    c.lookActive = false;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseJoystick();
                lookId = -1;
                c.lookActive = false;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void releaseJoystick() {
        joyId = -1;
        c.moveX = 0f;
        c.moveY = 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (c.loading) {
            canvas.drawColor(0xFF1E2A22);
            text.setTextSize(20 * dp);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Growing your forest…", w * 0.5f, h * 0.5f, text);
            return;
        }

        int state = c.gameState;
        long now = SystemClock.uptimeMillis();
        if (state != lastState) {
            if (state == Controls.PLAYING) playStart = now;
            if (state == Controls.OVER) {
                overAt = now;
                releaseJoystick();
                c.autoWalk = false;
            }
            lastState = state;
        }

        if (state == Controls.PLAYING) {
            drawJoystick(canvas, w, h);
            button(canvas, autoBtn, c.autoWalk ? "AUTO-WALK: ON" : "AUTO-WALK: OFF", c.autoWalk);
            drawGameHud(canvas, w, h, now);
        }
        button(canvas, soundBtn, c.soundOn ? "SOUND: ON" : "SOUND: OFF", false);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(11 * dp);
        text.setColor(0x99FFFFFF);
        canvas.drawText("Forest #" + c.seed + "   " + Math.round(c.fps) + " fps", 14 * dp, h - 12 * dp, text);
        text.setColor(Color.WHITE);

        if (state == Controls.READY) {
            drawCard(canvas, w, h, "WISP HUNT", new String[]{
                    "Glowing wisps are hiding in the forest.",
                    "Catch them before your lantern burns out.",
                    "Every wisp you catch refuels the lantern.",
            }, "Tap to start", true);
        } else if (state == Controls.OVER) {
            int score = c.score;
            boolean record = score > 0 && score >= c.best;
            drawCard(canvas, w, h, "YOUR LANTERN WENT OUT", new String[]{
                    "Wisps caught: " + score,
                    record ? "New best!" : "Best: " + c.best,
            }, "Tap to play again in a new forest", now - overAt > OVER_LOCK_MS);
        }
    }

    private void drawJoystick(Canvas canvas, int w, int h) {
        float jr = 60 * dp;
        if (joyId >= 0) {
            fill.setColor(0x33FFFFFF);
            canvas.drawCircle(joyCx, joyCy, jr, fill);
            line.setColor(0x88FFFFFF);
            canvas.drawCircle(joyCx, joyCy, jr, line);
            fill.setColor(0xAAFFFFFF);
            canvas.drawCircle(joyX, joyY, 24 * dp, fill);
        } else {
            line.setColor(0x44FFFFFF);
            canvas.drawCircle(w * 0.13f, h * 0.72f, jr, line);
            fill.setColor(0x22FFFFFF);
            canvas.drawCircle(w * 0.13f, h * 0.72f, 24 * dp, fill);
        }
    }

    private void drawGameHud(Canvas canvas, int w, int h, long now) {
        // score and lantern oil
        float m = 14 * dp;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(18 * dp);
        canvas.drawText("Wisps: " + c.score, m, m + 18 * dp, text);
        float oil = c.oil;
        float frac = Math.max(0f, Math.min(1f, oil / c.maxOil));
        bar.set(m, m + 28 * dp, m + 150 * dp, m + 38 * dp);
        fill.setColor(0x66000000);
        canvas.drawRoundRect(bar, 5 * dp, 5 * dp, fill);
        int barColor = oil < 10f ? 0xFFE0533A : 0xFFF2B33D;
        if (oil < 5f && (now / 250) % 2 == 0) barColor = 0xFFFF8A6A;
        fill.setColor(barColor);
        bar.right = bar.left + bar.width() * frac;
        canvas.drawRoundRect(bar, 5 * dp, 5 * dp, fill);
        text.setTextSize(11 * dp);
        text.setColor(0xCCFFFFFF);
        canvas.drawText("LANTERN  " + (int) Math.ceil(oil) + "s", m, m + 54 * dp, text);
        text.setColor(Color.WHITE);

        // compass: arrow at the top centre pointing at the nearest wisp
        float cx = w * 0.5f, cy = m + 34 * dp, r = 22 * dp;
        fill.setColor(0x55000000);
        canvas.drawCircle(cx, cy, r + 6 * dp, fill);
        canvas.save();
        canvas.rotate((float) Math.toDegrees(c.wispBearing), cx, cy);
        arrow.reset();
        arrow.moveTo(cx, cy - r);
        arrow.lineTo(cx + r * 0.55f, cy + r * 0.6f);
        arrow.lineTo(cx, cy + r * 0.25f);
        arrow.lineTo(cx - r * 0.55f, cy + r * 0.6f);
        arrow.close();
        fill.setColor(0xFFD8F57A);
        canvas.drawPath(arrow, fill);
        canvas.restore();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12 * dp);
        canvas.drawText(Math.round(c.wispDist) + " m", cx, cy + r + 22 * dp, text);

        long age = now - playStart;
        if (age < HINT_MS) {
            int alpha = (int) (255 * Math.min(1f, (HINT_MS - age) / 2000f));
            text.setAlpha(alpha);
            text.setTextSize(15 * dp);
            canvas.drawText("Drag on the left to walk  ·  drag on the right to look around", w * 0.5f, h - 48 * dp, text);
            text.setTextSize(13 * dp);
            canvas.drawText("Follow the arrow and walk into a wisp to catch it. They'll try to slip away!", w * 0.5f, h - 26 * dp, text);
            text.setAlpha(255);
        }
    }

    private void drawCard(Canvas canvas, int w, int h, String title, String[] lines, String action, boolean showAction) {
        canvas.drawColor(0x88000000);
        float cy = h * 0.5f - (lines.length * 24 * dp + 60 * dp) * 0.5f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(30 * dp);
        text.setColor(0xFFD8F57A);
        canvas.drawText(title, w * 0.5f, cy, text);
        text.setColor(Color.WHITE);
        text.setTextSize(16 * dp);
        float y = cy + 40 * dp;
        for (String l : lines) {
            canvas.drawText(l, w * 0.5f, y, text);
            y += 24 * dp;
        }
        if (showAction) {
            text.setTextSize(15 * dp);
            text.setAlpha((int) (160 + 95 * Math.sin(SystemClock.uptimeMillis() * 0.004)));
            canvas.drawText(action, w * 0.5f, y + 22 * dp, text);
            text.setAlpha(255);
        }
    }

    private void button(Canvas canvas, RectF r, String label, boolean active) {
        fill.setColor(active ? 0x994E7A45 : 0x66000000);
        canvas.drawRoundRect(r, 19 * dp, 19 * dp, fill);
        line.setColor(0x88FFFFFF);
        canvas.drawRoundRect(r, 19 * dp, 19 * dp, line);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * dp);
        canvas.drawText(label, r.centerX(), r.centerY() + 4.5f * dp, text);
    }
}
