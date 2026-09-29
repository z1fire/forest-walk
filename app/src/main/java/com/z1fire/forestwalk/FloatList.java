package com.z1fire.forestwalk;

import java.util.Arrays;

/** Minimal growable float array. */
final class FloatList {
    float[] a = new float[64];
    int n;

    void add(float v) {
        if (n == a.length) a = Arrays.copyOf(a, n * 2);
        a[n++] = v;
    }

    float[] toArray() {
        return Arrays.copyOf(a, n);
    }
}
