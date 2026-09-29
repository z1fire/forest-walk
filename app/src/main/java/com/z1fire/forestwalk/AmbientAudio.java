package com.z1fire.forestwalk;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Real-time synthesised forest ambience (gusting wind, rustling leaves, birdsong) plus game chimes. */
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

    private final Chime chime = new Chime();
    private int lastCatch, lastOver;

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
        lastCatch = ctl.catchCount;
        lastOver = ctl.overCount;
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
        int caught = ctl.catchCount;
        if (caught != lastCatch) {
            lastCatch = caught;
            chime.caught(ctl.score);
        }
        int over = ctl.overCount;
        if (over != lastOver) {
            lastOver = over;
            chime.lanternOut();
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

            float ch = chime.sample();
            l += ch;
            r += ch;

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

    /** Bell-like tones: a rising arpeggio for each caught wisp, a falling pair when the lantern dies. */
    private static final class Chime {
        private static final int VOICES = 8;
        private static final int[] PENTA = {0, 2, 4, 7, 9};
        final int[] delay = new int[VOICES];
        final float[] freq = new float[VOICES], amp = new float[VOICES], env = new float[VOICES],
                decay = new float[VOICES], phase = new float[VOICES];
        private int next;

        void caught(int score) {
            // Climb a pentatonic scale as the score goes up.
            int step = score % 10;
            float base = 523f * (float) Math.pow(2, (PENTA[step % 5] + 12 * (step / 5)) / 12.0);
            note(0, base, 0.09f, 0.35f);
            note((int) (SR * 0.07f), base * 1.25f, 0.08f, 0.35f);
            note((int) (SR * 0.14f), base * 1.5f, 0.08f, 0.6f);
        }

        void lanternOut() {
            note(0, 392f, 0.10f, 0.8f);
            note((int) (SR * 0.35f), 261.6f, 0.11f, 1.4f);
        }

        private void note(int d, float f, float a, float seconds) {
            int v = next;
            next = (next + 1) % VOICES;
            delay[v] = d;
            freq[v] = f;
            amp[v] = a;
            env[v] = 1f;
            phase[v] = 0f;
            decay[v] = (float) Math.exp(-1.0 / (seconds * SR));
        }

        float sample() {
            float s = 0f;
            for (int v = 0; v < VOICES; v++) {
                if (env[v] < 1e-4f) continue;
                if (delay[v] > 0) {
                    delay[v]--;
                    continue;
                }
                phase[v] += TWO_PI * freq[v] / SR;
                if (phase[v] > TWO_PI) phase[v] -= TWO_PI;
                float p = phase[v];
                s += ((float) Math.sin(p) + 0.2f * (float) Math.sin(p * 3f)) * env[v] * amp[v];
                env[v] *= decay[v];
            }
            return s;
        }
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
