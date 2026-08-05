package j1;

import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.ve3nea.morse_expert.DecodedTextView;
import com.ve3nea.morse_expert.ScaleView;
import com.ve3nea.morse_expert.WaterfallSurfaceView;

/**
 * Ported from Morse Expert 1.15 j1.C1646n; obfuscated class/field names kept.
 * R8-merged class surgery: keeps only the view holder (6-arg constructor + 5 View fields);
 * the remaining lines 158-1278 (ads/billing merged methods) were removed wholesale (user: no ad migration).
 */
public final class C1646n {

    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public Object f11888g;

    /* renamed from: h, reason: collision with root package name */
    public Object f11889h;

    /* renamed from: i, reason: collision with root package name */
    public Object f11890i;

    /* renamed from: j, reason: collision with root package name */
    public Object f11891j;

    /* renamed from: k, reason: collision with root package name */
    public Object f11892k;

    public C1646n(ConstraintLayout constraintLayout, DecodedTextView decodedTextView, ScaleView scaleView, TextView textView, LinearLayout linearLayout, WaterfallSurfaceView waterfallSurfaceView) {
        this.f = 1;
        this.f11888g = decodedTextView;
        this.f11889h = scaleView;
        this.f11890i = textView;
        this.f11891j = linearLayout;
        this.f11892k = waterfallSurfaceView;
    }
}
