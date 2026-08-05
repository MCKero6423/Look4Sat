package com.rtbishop.look4sat.feature.cw.suncompat;

/**
 * Compile-time stub: Android has no sun.misc.Unsafe (obtained via reflection at runtime; k3.r.f12109a is null on failure).
 * Ported from Morse Expert 1.15 (the k3 FFT library's >1GB native path never triggers; real FFT uses the float[] branch).
 */
public class Unsafe {
    public long allocateMemory(long bytes) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void freeMemory(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public byte getByte(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putByte(long address, byte value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public short getShort(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putShort(long address, short value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public int getInt(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putInt(long address, int value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public long getLong(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putLong(long address, long value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public float getFloat(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putFloat(long address, float value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public double getDouble(long address) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putDouble(long address, double value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void setMemory(long address, long bytes, byte value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void copyMemory(long src, long dst, long len) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public Object getObject(Object o, long offset) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public void putObject(Object o, long offset, Object value) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }

    public long objectFieldOffset(java.lang.reflect.Field field) {
        throw new UnsupportedOperationException("sun.misc.Unsafe not available on Android");
    }
}
