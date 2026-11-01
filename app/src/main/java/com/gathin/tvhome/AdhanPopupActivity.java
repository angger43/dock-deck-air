package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/** Theme-aware, remote-first Cardflow surface shown during Adhan playback. */
public final class AdhanPopupActivity extends Activity {
    static final String EXTRA_NAME = "name";
    static final String EXTRA_TIME = "time";

    private FrameLayout root;
    private LinearLayout panel;
    private TextView name;
    private TextView time;
    private boolean registered;
    private boolean closing;
    private ThemePalette palette;
    private Typeface body;
    private Typeface bodyMedium;

    private final BroadcastReceiver playbackState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra("playing", false)) closeAnimated(false);
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API 26-32 have no receiver-flags overload.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.dimAmount = 0.58f;
        getWindow().setAttributes(window);

        resolveTheme();
        buildUi();
        update(getIntent());
        enterAnimated();

        IntentFilter filter = new IntentFilter(AdhanPlaybackService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(playbackState, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(playbackState, filter);
        registered = true;
    }

    private void resolveTheme() {
        SharedPreferences prefs = getSharedPreferences(AdhanScheduler.PREFS, MODE_PRIVATE);
        int mode = prefs.getInt("theme_mode", 1);
        Calendar now = Calendar.getInstance();
        boolean originalLight = mode == 0 || (mode == 2
                && now.get(Calendar.HOUR_OF_DAY) >= prefs.getInt("light_start", 6)
                && now.get(Calendar.HOUR_OF_DAY) < prefs.getInt("dark_start", 18));
        int preset = prefs.getInt("theme_preset", ThemePalette.DOCK_DARK);
        if (preset < ThemePalette.ORIGINAL || preset >= ThemePalette.COUNT)
            preset = ThemePalette.ORIGINAL;
        palette = ThemePalette.resolve(preset, originalLight);
        try { body = Typeface.createFromAsset(getAssets(), "font/outfit.ttf"); }
        catch (RuntimeException ignored) { body = Typeface.create("sans-serif", Typeface.NORMAL); }
        bodyMedium = Typeface.create(body, Typeface.BOLD);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(palette.lightSurface ? 0x52000000 : 0x70000000);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.START);
        panel.setPadding(dp(36), dp(28), dp(36), dp(30));
        panel.setBackground(surface(palette.panelAlt, palette.border, 18));
        panel.setElevation(dp(18));
        if (Build.VERSION.SDK_INT >= 28) {
            panel.setOutlineAmbientShadowColor(palette.shadowColor());
            panel.setOutlineSpotShadowColor(palette.shadowColor());
        }

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        PlayingIndicator indicator = new PlayingIndicator(this, palette.accent);
        status.addView(indicator, new LinearLayout.LayoutParams(dp(36), dp(22)));
        TextView playing = text("PLAYING ON TV", 12, palette.dim, bodyMedium, Gravity.START);
        playing.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams playingParams = new LinearLayout.LayoutParams(0, dp(28), 1f);
        playingParams.leftMargin = dp(10);
        status.addView(playing, playingParams);
        TextView brand = text("CARDFLOW", 11, palette.dim, bodyMedium, Gravity.END);
        brand.setLetterSpacing(0.16f);
        status.addView(brand, new LinearLayout.LayoutParams(dp(128), dp(28)));
        panel.addView(status, row(-1, dp(30)));

        View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams dividerParams = row(-1, dp(1));
        dividerParams.topMargin = dp(9);
        dividerParams.bottomMargin = dp(23);
        panel.addView(divider, dividerParams);

        TextView eyebrow = text("PRAYER TIME", 11, palette.dim, bodyMedium, Gravity.START);
        eyebrow.setLetterSpacing(0.1f);
        panel.addView(eyebrow, row(-1, dp(25)));

        name = text("Adhan", 38, palette.text, bodyMedium, Gravity.START);
        panel.addView(name, row(-1, dp(61)));

        time = text("", 19, palette.dim, body, Gravity.START);
        panel.addView(time, row(-1, dp(38)));

        Button stop = new Button(this);
        stop.setText("■   Stop Adhan");
        stop.setTextSize(18);
        stop.setTypeface(bodyMedium);
        stop.setAllCaps(false);
        stop.setGravity(Gravity.CENTER);
        stop.setIncludeFontPadding(false);
        stop.setPadding(dp(22), 0, dp(22), 0);
        stop.setFocusable(true);
        stop.setClickable(true);
        stop.setBackground(buttonBackground());
        stop.setTextColor(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{palette.focusText, palette.focusText, palette.text}));
        stop.setStateListAnimator(null);
        stop.setElevation(dp(3));
        stop.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f)
                    .setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
            view.setElevation(dp(focused ? 11 : 3));
        });
        stop.setOnClickListener(v -> closeAnimated(true));
        LinearLayout.LayoutParams stopParams = row(-1, dp(58));
        stopParams.topMargin = dp(20);
        panel.addView(stop, stopParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(600), LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelParams.leftMargin = dp(24);
        panelParams.rightMargin = dp(24);
        root.addView(panel, panelParams);
        setContentView(root);
        stop.requestFocus();
    }

    private StateListDrawable buttonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                surface(palette.focusFill, palette.focusFill, 14));
        states.addState(new int[]{android.R.attr.state_pressed},
                surface(palette.focusFill, palette.focusFill, 14));
        states.addState(new int[]{}, surface(palette.panel, palette.border, 14));
        return states;
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if ((stroke >>> 24) != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView text(String value, int size, int color, Typeface face, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL | gravity);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    private static LinearLayout.LayoutParams row(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private void enterAnimated() {
        root.setAlpha(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.965f);
        panel.setScaleY(0.965f);
        panel.setTranslationY(dp(18));
        root.post(() -> {
            root.animate().alpha(1f).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator()).start();
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private void closeAnimated(boolean stopPlayback) {
        if (closing) return;
        closing = true;
        if (stopPlayback) AdhanScheduler.stop(this);
        if (panel == null || root == null) { finishNow(); return; }
        root.animate().alpha(0f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
        panel.animate().alpha(0f).scaleX(0.975f).scaleY(0.975f).translationY(dp(10))
                .setDuration(170).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::finishNow).start();
    }

    private void finishNow() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        update(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!AdhanPlaybackService.isPlaying()) finishNow();
    }

    private void update(Intent intent) {
        if (intent == null || name == null) return;
        String prayer = intent.getStringExtra(EXTRA_NAME);
        name.setText(prayer == null || prayer.isEmpty() ? "Adhan" : prayer);
        String value = intent.getStringExtra(EXTRA_TIME);
        time.setText(value == null || value.isEmpty() ? "Audio is playing"
                : "Prayer time   •   " + value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() { closeAnimated(true); }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) { closeAnimated(true); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (registered) { unregisterReceiver(playbackState); registered = false; }
        super.onDestroy();
    }

    /** Three low-frequency bars: visible from a sofa, inexpensive on a TV GPU. */
    private static final class PlayingIndicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator motion = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        private float phase;

        PlayingIndicator(Context context, int color) {
            super(context);
            paint.setColor(color);
            motion.setDuration(1100L);
            motion.setRepeatCount(ValueAnimator.INFINITE);
            motion.setInterpolator(new LinearInterpolator());
            motion.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            motion.start();
        }

        @Override protected void onDetachedFromWindow() {
            motion.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float barWidth = width * 0.13f;
            float gap = width * 0.11f;
            float start = (width - (barWidth * 3 + gap * 2)) / 2f;
            for (int i = 0; i < 3; i++) {
                float wave = 0.5f + 0.5f * (float) Math.sin(phase + i * 1.65f);
                float barHeight = height * (0.34f + 0.52f * wave);
                float left = start + i * (barWidth + gap);
                float top = (height - barHeight) / 2f;
                canvas.drawRoundRect(left, top, left + barWidth, top + barHeight,
                        barWidth / 2f, barWidth / 2f, paint);
            }
        }
    }
}
