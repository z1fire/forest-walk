package com.z1fire.forestwalk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Transparent overlay: floating joystick (left), look-drag area (right) and a few buttons. */
final class HudView extends View {
    private static final long HINT_MS = 12000;

    private final Controls c;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF autoBtn = new RectF(), newBtn = new RectF(), soundBtn = new RectF();
    private final long start = SystemClock.uptimeMillis();

    private int joyId = -1, lookId = -1;
    private float joyCx, joyCy, joyX, joyY, lastX, lastY;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 250);
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
        newBtn.set(autoBtn.left, autoBtn.bottom + gap, autoBtn.right, autoBtn.bottom + gap + bh);
        soundBtn.set(autoBtn.left, newBtn.bottom + gap, autoBtn.right, newBtn.bottom + gap + bh);
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
                if (autoBtn.contains(x, y)) {
                    c.autoWalk = !c.autoWalk;
                } else if (newBtn.contains(x, y)) {
                    c.requestRegenerate();
                } else if (soundBtn.contains(x, y)) {
                    c.soundOn = !c.soundOn;
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

        // joystick
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

        // buttons
        button(canvas, autoBtn, c.autoWalk ? "AUTO-WALK: ON" : "AUTO-WALK: OFF", c.autoWalk);
        button(canvas, newBtn, "NEW FOREST", false);
        button(canvas, soundBtn, c.soundOn ? "SOUND: ON" : "SOUND: OFF", false);

        // status
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(12 * dp);
        text.setColor(0xCCFFFFFF);
        canvas.drawText("Forest #" + c.seed + "   " + Math.round(c.fps) + " fps", 14 * dp, 24 * dp, text);
        text.setColor(Color.WHITE);

        long age = SystemClock.uptimeMillis() - start;
        if (age < HINT_MS) {
            int alpha = (int) (255 * Math.min(1f, (HINT_MS - age) / 2000f));
            text.setAlpha(alpha);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(15 * dp);
            canvas.drawText("Drag on the left to walk  ·  drag on the right to look around", w * 0.5f, h - 48 * dp, text);
            text.setTextSize(13 * dp);
            canvas.drawText("Tap AUTO-WALK to stroll along the trail", w * 0.5f, h - 26 * dp, text);
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
