package com.z1fire.forestwalk;

/** Input and status shared between the UI thread, the GL thread and the audio thread. */
final class Controls {
    static final int READY = 0, PLAYING = 1, OVER = 2;

    volatile float moveX, moveY;        // joystick, -1..1 (moveY > 0 is forward)
    volatile boolean lookActive;
    volatile boolean autoWalk;
    volatile boolean soundOn = true;
    volatile boolean loading = true;
    volatile float fps;
    volatile long seed;

    // Wisp Hunt state, written by the GL thread
    volatile int gameState = READY;
    volatile int score, best;
    volatile float oil, maxOil = 1f;
    volatile float wispBearing, wispDist;   // nearest wisp: radians (0 ahead, + right) and metres
    volatile int catchCount, overCount;     // bumped on each catch / game over, for audio cues

    private float lookX, lookY;
    private boolean start;

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

    synchronized void requestStart() {
        start = true;
    }

    synchronized boolean takeStart() {
        boolean r = start;
        start = false;
        return r;
    }
}
