package com.z1fire.forestwalk;

/** Input and status shared between the UI thread, the GL thread and the audio thread. */
final class Controls {
    volatile float moveX, moveY;        // joystick, -1..1 (moveY > 0 is forward)
    volatile boolean lookActive;
    volatile boolean autoWalk;
    volatile boolean soundOn = true;
    volatile boolean loading = true;
    volatile int stepCount;
    volatile float speed;
    volatile float fps;
    volatile long seed;

    private float lookX, lookY;
    private boolean regenerate;

    /** Look deltas in radians. */
    synchronized void addLook(float dx, float dy) {
        lookX += dx;
        lookY += dy;
    }

    synchronized void consumeLook(float[] out) {
        out[0] = lookX;
        out[1] = lookY;
        lookX = 0f;
        lookY = 0f;
    }

    synchronized void requestRegenerate() {
        regenerate = true;
    }

    synchronized boolean takeRegenerate() {
        boolean r = regenerate;
        regenerate = false;
        return r;
    }
}
