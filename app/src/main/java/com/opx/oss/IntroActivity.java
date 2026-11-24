package com.opx.oss;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.airbnb.lottie.LottieAnimationView;

/**
 * Stryker's first-run welcome, kept to the four steps this app actually has: welcome, storage,
 * the bundled engine and the ready state. Each slide uses Stryker's own animation file.
 */
public class IntroActivity extends AppCompatActivity {

    private static final int[] SLIDES = {
            R.id.slide_welcome,
            R.id.slide_permissions,
            R.id.slide_engine,
            R.id.slide_ready,
    };

    private static final int[] ANIMS = {
            R.raw.waving_hand,
            R.raw.permissions_key,
            R.raw.settings_gears,
            R.raw.confetti,
    };

    private static final int[] TITLES = {
            R.string.intro_welcome_title,
            R.string.intro_permissions_title,
            R.string.intro_engine_title,
            R.string.intro_ready_title,
    };

    private static final int[] BODIES = {
            R.string.intro_welcome_body,
            R.string.intro_permissions_body,
            R.string.intro_engine_body,
            R.string.intro_ready_body,
    };

    private static final int[] DOTS = {R.id.dot_0, R.id.dot_1, R.id.dot_2, R.id.dot_3};

    private int index;
    private static volatile boolean showing;

    /**
     * The USB prompt waits until the intro is out of the way: the first launch belongs to these
     * slides, and an adapter plugged in behind them is picked up the moment the app is back.
     */
    public static boolean isShowing() {
        return showing;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
    }

    @Override
    protected void onPause() {
        showing = false;
        super.onPause();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);

        for (int i = 0; i < SLIDES.length; i++) {
            View slide = findViewById(SLIDES[i]);
            LottieAnimationView anim = slide.findViewById(R.id.slide_anim);
            ((TextView) slide.findViewById(R.id.slide_title)).setText(TITLES[i]);
            ((TextView) slide.findViewById(R.id.slide_body)).setText(BODIES[i]);
            anim.setAnimation(ANIMS[i]);
        }

        findViewById(R.id.intro_skip).setOnClickListener(v -> finish());
        findViewById(R.id.intro_next).setOnClickListener(v -> {
            if (index >= SLIDES.length - 1) {
                finish();
                return;
            }
            index++;
            show();
        });

        show();
    }

    private void show() {
        for (int i = 0; i < SLIDES.length; i++) {
            View slide = findViewById(SLIDES[i]);
            slide.setVisibility(i == index ? View.VISIBLE : View.GONE);
            if (i == index) {
                LottieAnimationView anim = slide.findViewById(R.id.slide_anim);
                anim.playAnimation();
            }
            View dot = findViewById(DOTS[i]);
            dot.setBackgroundResource(i == index
                    ? R.drawable.intro_dot_active
                    : R.drawable.intro_dot);
        }
        ((TextView) findViewById(R.id.intro_next)).setText(
                index >= SLIDES.length - 1 ? R.string.intro_start : R.string.intro_next);
    }
}
