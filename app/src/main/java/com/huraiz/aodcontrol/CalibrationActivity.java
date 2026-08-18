package com.huraiz.aodcontrol;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class CalibrationActivity extends Activity {
    private CalibrationView calibrationView;
    private TextView instruction;
    private boolean saved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemUi();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        calibrationView = new CalibrationView(this);
        root.addView(calibrationView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        instruction = new TextView(this);
        instruction.setText("Tap the CENTER of your under-display fingerprint sensor");
        instruction.setTextColor(Color.WHITE);
        instruction.setTextSize(18);
        instruction.setGravity(android.view.Gravity.CENTER);
        instruction.setPadding(dp(24), dp(28), dp(24), dp(28));
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        textParams.gravity = android.view.Gravity.TOP;
        root.addView(instruction, textParams);

        TextView hint = new TextView(this);
        hint.setText("The screen is only used to record the sensor position. No fingerprint data is read or stored.");
        hint.setTextColor(Color.rgb(160, 160, 170));
        hint.setTextSize(13);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(dp(28), 0, dp(28), dp(34));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintParams.gravity = android.view.Gravity.BOTTOM;
        root.addView(hint, hintParams);

        root.setOnTouchListener((v, event) -> {
            if (saved || event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            savePoint(event.getX(), event.getY(), root.getWidth(), root.getHeight());
            return true;
        });

        setContentView(root);
    }

    private void savePoint(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        saved = true;
        float radiusPx = Math.min(width, height) * 0.085f;
        float nx = x / width;
        float ny = y / height;
        float rx = radiusPx / width;
        float ry = radiusPx / height;
        AppPrefs.saveFingerprintCalibration(this, nx, ny, rx, ry);
        calibrationView.setPoint(x, y, radiusPx);
        instruction.setText("Fingerprint area calibrated ✓");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 650);
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CalibrationView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x = -1;
        private float y = -1;
        private float radius;

        CalibrationView(Activity context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5f * getResources().getDisplayMetrics().density);
            ring.setColor(Color.rgb(139, 92, 246));
            dot.setColor(Color.WHITE);
        }

        void setPoint(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (x < 0 || y < 0) return;
            canvas.drawCircle(x, y, radius, ring);
            canvas.drawCircle(x, y, Math.max(5f, radius * 0.08f), dot);
        }
    }
}
