package com.z1fire.forestwalk;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Real-time synthesised forest ambience: gusting wind, rustling leaves, birdsong, footsteps. */
final class AmbientAudio implements Runnable {
    private static final int SR = 22050;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private final Controls ctl;
    private volatile boolean running;
    private Thread thread;

    private int rng = 0x2545F491;
    private float brownL, brownR, windL, windR, lpL, lpR;
    private float gust = 0.5f, gustTarget = 0.5f;
    private int gustTimer;
    private float rustleMod;
    private float master;

    private final Bird[] birds = {new Bird(), new Bird(), new Bird(), new Bird()};
    private int birdTimer = SR;
    private final float[] echoL = new float[(int) (SR * 0.23f)];
    private final float[] echoR = new float[(int) (SR * 0.31f)];
    private int eiL, eiR;

    private int lastStep;
    private float stepEnv, stepLp, stepGain;

    AmbientAudio(Controls ctl) {
        this.ctl = ctl;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "forest-audio");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private float white() {
        rng ^= rng << 13;
        rng ^= rng >>> 17;
        rng ^= rng << 5;
        return (rng & 0xFFFFFF) / 8388608f - 1f;
    }

    private float rand() {
        return (white() + 1f) * 0.5f;
    }

    @Override
    public void run() {
        AudioTrack track;
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            return;   // no audio on this device; the walk still works
        }
        short[] buf = new short[1024 * 2];
        lastStep = ctl.stepCount;
        try {
            while (running) {
                fill(buf);
                track.write(buf, 0, buf.length);
            }
        } finally {
            track.pause();
            track.flush();
            track.release();
        }
    }

    private void fill(short[] buf) {
        int step = ctl.stepCount;
        if (step != lastStep) {
            lastStep = step;
            stepEnv = 1f;
            stepGain = 0.3f * Math.min(1.2f, ctl.speed / 1.6f) * (0.8f + 0.4f * rand());
        }
        float targetMaster = ctl.soundOn ? 1f : 0f;
        int frames = buf.length / 2;
        for (int i = 0; i < frames; i++) {
            master += (targetMaster - master) * 0.0005f;

            // wind: leaky-integrated noise through a gust-controlled low-pass
            if (--gustTimer <= 0) {
                float g = rand();
                gustTarget = 0.15f + 0.85f * g * g;
                gustTimer = (int) (SR * (2f + 4f * rand()));
            }
            gust += (gustTarget - gust) * 0.00003f;
            brownL = brownL * 0.995f + white() * 0.05f;
            brownR = brownR * 0.995f + white() * 0.05f;
            float cut = 0.03f + 0.12f * gust;
            windL += (brownL - windL) * cut;
            windR += (brownR - windR) * cut;
            float wamp = (0.2f + 0.7f * gust) * 0.45f;

            // leaf rustle: high-passed noise, strongest in gusts
            float nl = white(), nr = white();
            lpL += (nl - lpL) * 0.25f;
            lpR += (nr - lpR) * 0.25f;
            rustleMod += (rand() - rustleMod) * 0.0008f;
            float ramp = gust * gust * 0.05f * (0.4f + rustleMod);
            float l = windL * wamp + (nl - lpL) * ramp;
            float r = windR * wamp + (nr - lpR) * ramp;

            // birds
            if (--birdTimer <= 0) {
                spawnBird();
                birdTimer = (int) (SR * (1.5f + 6f * rand()));
            }
            float bl = 0f, br = 0f;
            for (Bird b : birds) {
                if (!b.active) continue;
                float s = b.sample();
                bl += s * b.gl;
                br += s * b.gr;
            }
            float el = echoL[eiL], er = echoR[eiR];
            echoL[eiL] = bl + el * 0.3f;
            echoR[eiR] = br + er * 0.3f;
            eiL = (eiL + 1) % echoL.length;
            eiR = (eiR + 1) % echoR.length;
            l += bl + er * 0.35f;
            r += br + el * 0.35f;

            // footsteps on leaf litter: thud plus crackle
            if (stepEnv > 0.001f) {
                float n = white();
                stepLp += (n - stepLp) * 0.2f;
                float crackle = white() > 0.9f ? white() * 0.6f : 0f;
                float s = (stepLp * 1.2f + crackle) * stepEnv * stepGain;
                l += s;
                r += s;
                stepEnv *= 0.99905f;
            }

            l *= master;
            r *= master;
            buf[i * 2] = (short) (Math.max(-1f, Math.min(1f, l)) * 30000f);
            buf[i * 2 + 1] = (short) (Math.max(-1f, Math.min(1f, r)) * 30000f);
        }
    }

    private void spawnBird() {
        Bird b = null;
        for (Bird x : birds) if (!x.active) { b = x; break; }
        if (b == null) return;
        int type = (int) (rand() * 4) & 3;
        float amp = 0.03f + 0.1f * rand() * rand();
        float pan = rand();
        b.gl = (float) Math.cos(pan * Math.PI / 2);
        b.gr = (float) Math.sin(pan * Math.PI / 2);
        b.type = type;
        b.notes = 0;
        int t = 0;
        switch (type) {
            case 0: { // descending whistles
                int n = 3 + (int) (rand() * 4);
                float f = 3200f + 1000f * rand();
                for (int i = 0; i < n; i++) {
                    int d = (int) (SR * (0.12f + 0.08f * rand()));
                    b.add(t, d, f, f * 0.85f, amp);
                    t += d + (int) (SR * 0.08f);
                    f *= 0.95f;
                }
                break;
            }
            case 1: { // trill
                float f = 4500f + 1000f * rand();
                b.add(0, (int) (SR * (0.6f + 0.6f * rand())), f, f * 0.97f, amp * 0.8f);
                break;
            }
            case 2: { // two-tone call
                int n = 2 + (int) (rand() * 2);
                float hi = 2600f + 600f * rand();
                for (int i = 0; i < n; i++) {
                    int d = (int) (SR * 0.18f);
                    b.add(t, d, hi, hi * 1.02f, amp);
                    t += d + (int) (SR * 0.06f);
                    b.add(t, d, hi * 0.8f, hi * 0.78f, amp);
                    t += d + (int) (SR * 0.25f);
                }
                break;
            }
            default: { // chirps
                int n = 4 + (int) (rand() * 6);
                for (int i = 0; i < n; i++) {
                    int d = (int) (SR * (0.04f + 0.03f * rand()));
                    float f = 3000f + 800f * rand();
                    b.add(t, d, f, f * 1.6f, amp);
                    t += d + (int) (SR * (0.04f + 0.05f * rand()));
                }
                break;
            }
        }
        b.pos = 0;
        b.cur = 0;
        b.phase = 0f;
        b.active = true;
    }

    private static final class Bird {
        final int[] start = new int[16], dur = new int[16];
        final float[] f0 = new float[16], f1 = new float[16], amp = new float[16];
        int notes, pos, cur, type;
        float phase, gl, gr;
        boolean active;

        void add(int s, int d, float a, float b, float am) {
            if (notes >= 16) return;
            start[notes] = s;
            dur[notes] = d;
            f0[notes] = a;
            f1[notes] = b;
            amp[notes] = am;
            notes++;
        }

        float sample() {
            while (cur < notes && pos >= start[cur] + dur[cur]) cur++;
            if (cur >= notes) {
                active = false;
                return 0f;
            }
            int p = pos++;
            if (p < start[cur]) return 0f;
            float t = (p - start[cur]) / (float) dur[cur];
            float f = f0[cur] + (f1[cur] - f0[cur]) * t;
            f += 60f * (float) Math.sin(p * 0.012f);
            phase += TWO_PI * f / SR;
            if (phase > TWO_PI) phase -= TWO_PI;
            float env = (float) Math.sin(Math.PI * t);
            env *= env;
            float s = (float) Math.sin(phase) * env * amp[cur];
            if (type == 1) s *= 0.55f + 0.45f * (float) Math.sin(p * TWO_PI * 26f / SR);
            return s;
        }
    }
}
