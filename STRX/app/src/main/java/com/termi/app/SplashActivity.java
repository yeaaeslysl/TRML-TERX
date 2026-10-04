package com.termi.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/**
 * 启动动画页（每次启动均显示）。
 *
 * <h3>职责</h3>
 * 每次启动时先显示本页：S / T / R / X 四个字母依次从上方掉落并有轻微回弹，
 * 期间在后台完成 App 初始化；初始化完成且达到最短显示时长后，
 * 根据状态进入引导页或主界面。
 *
 * <h3>与引导页的区别</h3>
 * <ul>
 *   <li>本页：<b>每次启动</b>显示，是"过场"，不承载业务</li>
 *   <li>{@link OnboardingActivity}：<b>仅首次启动</b>，用于选择环境包</li>
 * </ul>
 *
 * <h3>动画节奏</h3>
 * 每个字母下落约 480ms，依次延迟 140ms；掉落后的回弹用
 * {@link BounceInterpolator}。整体时长可通过
 * {@link SettingsStore#getSplashSpeed} 调节（彩蛋中提供滑块）。
 */
public class SplashActivity extends Activity {

    private static final String TAG = "Splash";

    /** 最短显示时长：避免初始化极快时"一闪而过"（动画未播完也会按时跳走，这是允许的）。 */
    private static final long MIN_SHOW_MS = 800L;

    /** 字母下落基准时长（会被速度系数缩放）。 */
    private static final long FALL_DURATION_MS = 900L;
    /** 相邻字母的延迟。 */
    private static final long STAGGER_MS = 180L;

    private final TextView[] letters = new TextView[4];
    private View line;

    private long startMs;
    private boolean initDone = false;
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        startMs = System.currentTimeMillis();

        letters[0] = findViewById(R.id.tvSplash1);
        letters[1] = findViewById(R.id.tvSplash2);
        letters[2] = findViewById(R.id.tvSplash3);
        letters[3] = findViewById(R.id.tvSplash4);
        line = findViewById(R.id.vSplashLine);

        // 字母初始位置：移到屏幕顶部之外（掉入感）。
        // 字母最终位于屏幕中部，故需向上偏移约"半个屏高 + 自身高度"，才能完全移出可视区。
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float startOffsetY = -(screenH * 0.6f);
        for (TextView tv : letters) {
            if (tv == null) continue;
            tv.setAlpha(1f);            // 不做淡入：模拟真实"掉落物体"，从上方进入视野
            tv.setTranslationY(startOffsetY);
        }
        if (line != null) {
            line.setScaleX(0f);
            line.setVisibility(View.VISIBLE);
        }

        playDropAnimation();
        doBackgroundInit();
    }

    /** 四字母依次掉落 + 回弹；随后分割线展开。 */
    private void playDropAnimation() {
        float speed = SettingsStore.getSplashSpeed(this);   // 1.0 = 默认节奏
        if (speed <= 0f) speed = 1f;
        long fall = (long) (FALL_DURATION_MS / speed);
        long stagger = (long) (STAGGER_MS / speed);

        for (int i = 0; i < letters.length; i++) {
            final TextView tv = letters[i];
            if (tv == null) continue;
            final boolean last = (i == letters.length - 1);

            tv.animate()
                    .translationY(0f)
                    .setStartDelay(i * stagger)
                    .setDuration(fall)
                    .setInterpolator(new android.animation.TimeInterpolator() {
                        // 前 72%：重力加速下落（顶端慢、越落越快）——
                        // 这样字母会"明显从屏幕顶部进入视野"，
                        // 而不是像 BounceInterpolator 那样开头一瞬就冲到底。
                        // 后 28%：一次轻微回弹（落地后小弹一下）。
                        private static final float FALL = 0.72f;

                        @Override
                        public float getInterpolation(float t) {
                            if (t < FALL) {
                                float x = t / FALL;
                                return x * x;               // v ∝ t，匀速加速
                            }
                            float x = (t - FALL) / (1f - FALL);
                            return 1f - (float) (Math.sin(x * Math.PI) * 0.09 * (1 - x));
                        }
                    })
                    .setListener(last ? new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            expandLine(stagger, fall);
                        }
                    } : null)
                    .start();
        }
    }

    /** 分割线横向展开。 */
    private void expandLine(long stagger, long fall) {
        if (line == null) return;
        line.animate()
                .scaleX(1f)
                .setDuration(Math.max(200L, fall / 2))
                .setStartDelay(stagger)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * 后台初始化。
     *
     * <p>当前 App 的初始化主要在 {@code TermiApp.onCreate} 中完成（日志、崩溃捕获等），
     * 这里做一次轻量预热：确保日志就绪、并刷新 Shizuku 状态。
     * 完成后置位 {@link #initDone} 并尝试跳转。
     */
    private void doBackgroundInit() {
        new Thread(() -> {
            try {
                LogStore.getInstance().init(getApplicationContext());
            } catch (Throwable t) {
                LogStore.getInstance().append(LogStore.LEVEL_WARN, TAG,
                        "启动预热失败: " + t);
            }
            runOnUiThread(() -> {
                initDone = true;
                tryNavigate();
            });
        }, "splash-init").start();

        // 保险：即使初始化异常，也在足够时间后放行（避免卡在启动页）
        if (line != null) {
            line.postDelayed(() -> {
                initDone = true;
                tryNavigate();
            }, 2500L);
        }
    }

    /** 满足"初始化完成 + 达到最短显示时长"后跳转（只会执行一次）。 */
    private void tryNavigate() {
        if (navigated || !initDone) return;
        long elapsed = System.currentTimeMillis() - startMs;
        long wait = MIN_SHOW_MS - elapsed;
        if (wait > 0) {
            if (line != null) {
                line.postDelayed(this::tryNavigate, wait);
            }
            return;
        }
        navigated = true;

        Class<?> next;
        try {
            next = EnvStore.shouldShowOnboarding(this)
                    ? OnboardingActivity.class
                    : MainActivity.class;
        } catch (Throwable t) {
            next = MainActivity.class;
        }
        LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG,
                "启动动画完成 → " + next.getSimpleName());

        // 淡出过渡
        final Class<?> target = next;
        View root = findViewById(R.id.splashRoot);
        if (root != null) {
            ObjectAnimator fade = ObjectAnimator.ofFloat(root, "alpha", 1f, 0f);
            fade.setDuration(220);
            fade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    go(target);
                }
            });
            fade.start();
        } else {
            go(target);
        }
    }

    private void go(Class<?> target) {
        try {
            Intent i = new Intent(this, target);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Throwable t) {
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, TAG, "跳转失败: " + t);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // 启动动画期间不允许返回
    }
}
