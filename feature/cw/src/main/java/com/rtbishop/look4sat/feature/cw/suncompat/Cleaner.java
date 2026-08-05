package com.rtbishop.look4sat.feature.cw.suncompat;

/**
 * Compile-time stub: Android has no sun.misc.Cleaner (ported from Morse Expert 1.15's k3 FFT library cleanup callback,
 * the real 1024-point FFT never hits the native path).
 */
public class Cleaner {
    public static Cleaner create(Object referent, Runnable thunk) {
        return null;
    }

    public void clean() {
    }
}
