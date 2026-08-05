package E2;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import com.ve3nea.morse_expert.MainActivity;
import com.ve3nea.morse_expert.ScaleView;

/**
 * Ported from Morse Expert 1.15 (com.ve3nea.morse_expert); obfuscated class name kept.
 * R8-merged class surgery: keeps only case 1 (waterfall touch-to-select frequency = app logic),
 * other cases (ad P4 / appcompat focus / dialogs) are library code, trimmed.
 */
public final class g implements View.OnTouchListener {
    public final /* synthetic */ int f;

    /* renamed from: g, reason: collision with root package name */
    public final /* synthetic */ Object f411g;

    public /* synthetic */ g(Object obj, int i4) {
        this.f = i4;
        this.f411g = obj;
    }

    @Override // android.view.View.OnTouchListener
    public final boolean onTouch(View view, MotionEvent motionEvent) {
        int i4 = this.f;
        Object obj = this.f411g;
        if (i4 == 1) {
            MainActivity mainActivity = (MainActivity) obj;
            if (motionEvent.getActionMasked() == 0 && mainActivity.f11037F != null) {
                // smali-verified: p1 is reassigned to f11035D.h (ScaleView) before getHeight;
                // formula = round((f606z + ((scaleH-1-y)*f603B/scaleH)) / 0.128f)
                ScaleView scaleView = (ScaleView) mainActivity.f11035D.f11889h;
                float y3 = motionEvent.getY();
                int round = Math.round((H2.b.f606z + ((((scaleView.getHeight() - 1) - y3) * H2.b.f603B) / scaleView.getHeight())) / 0.128f);
                mainActivity.f11037F.f622r.set(round);
                Log.i("Waterfall", String.format("Touch at %d Hz`", Integer.valueOf(round)));
            }
            return false;
        }
        return false;
    }
}
