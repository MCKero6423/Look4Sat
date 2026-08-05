package I0;

/**
 * Ported from Morse Expert 1.15 I0.d; obfuscated class name kept.
 * R8-merged class surgery: keeps only the (i3.d, int, int, float[]) FFT task constructor;
 * run() restored verbatim per smali: R8 already optimized the body into dead code (aget then throw null,
 * never executed); original semantics kept (behavior identical to the original APK).
 * The SystemForegroundService (work library) case was trimmed.
 */
public final class d implements Runnable {
    public final /* synthetic */ int f = 0;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ int f653g;

    /* renamed from: h, reason: collision with root package name */
    public final /* synthetic */ int f654h;

    /* renamed from: i, reason: collision with root package name */
    public final /* synthetic */ Object f655i;

    /* renamed from: j, reason: collision with root package name */
    public final /* synthetic */ Object f656j;

    public d(i3.d dVar, int i4, int i5, float[] fArr) {
        this.f656j = dVar;
        this.f653g = i4;
        this.f654h = i5;
        this.f655i = fArr;
    }

    @Override // java.lang.Runnable
    public final void run() {
        int i6 = this.f654h;
        int i7 = this.f653g;
        if (i7 >= i6) {
            return;
        }
        float f = ((float[]) this.f655i)[i7 * 2];
        // Original R8 code: aget then throw null (dead code, never runs) - semantics ported, no computation executes
    }
}
