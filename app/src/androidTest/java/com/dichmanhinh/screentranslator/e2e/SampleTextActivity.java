package com.dichmanhinh.screentranslator.e2e;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "other app" the user wants translated. It is declared in the androidTest manifest, so it belongs
 * to the TEST package (com.dichmanhinh.screentranslator.test): its own process, its own task, its own
 * application label -> it appears as a separate entry in the MediaProjection app selector.
 *
 * Java + framework classes ONLY: this activity runs in the test package's own process, where the
 * tested app's APK is not on the classpath, and AGP does not package dependencies shared with the
 * app (kotlin-stdlib, AppCompat, ...) into the test APK. Kotlin code here could crash with
 * NoClassDefFoundError (kotlin.jvm.internal.Intrinsics, kotlin.collections.CollectionsKt, ...).
 *
 * Content is static by default (the real-world failing case: in single-app mode a static app
 * produces no new frames). Extra "tick"=true toggles a tiny swatch every 500 ms to force frames.
 */
public class SampleTextActivity extends Activity {

    /** Keep in sync with ScreenTranslateE2ETest.SAMPLE_LINES. */
    public static final String[] LINES = {
            "Hello world",
            "Good morning",
            "The weather is beautiful today",
            "Please open the door",
            "Thank you very much",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View swatch;
    private boolean flip;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            flip = !flip;
            swatch.setBackgroundColor(flip ? 0xFFF4F4F4 : 0xFFFFFFFF);
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);
        // Leave the left 72dp free: the floating bubble sits at x=0, y=35% of the screen height.
        root.setPadding(dp(72), dp(120), dp(24), dp(24));

        for (String line : LINES) {
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setTextColor(Color.BLACK);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(16), 0, dp(16));
            root.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        swatch = new View(this);
        swatch.setBackgroundColor(Color.WHITE);
        root.addView(swatch, new LinearLayout.LayoutParams(dp(6), dp(6)));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra("tick", false)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
