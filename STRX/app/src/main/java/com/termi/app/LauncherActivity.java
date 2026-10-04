package com.termi.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 启动路由：决定进入引导页还是主界面。
 *
 * <p>作为 launcher 入口，本类不做任何界面渲染，只做判断后立即跳转：
 * <ul>
 *   <li>应显示引导（引导未完成且环境未安装）→ {@link OnboardingActivity}</li>
 *   <li>否则 → {@link MainActivity}</li>
 * </ul>
 *
 * <p>设计理由：把"启动分流"独立出来，避免改动 MainActivity 既有逻辑，
 * 也便于彩蛋"重温启动页"直接启动 {@link OnboardingActivity}（带 review=true）。
 */
public class LauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean showOnboarding;
        try {
            showOnboarding = EnvStore.shouldShowOnboarding(this);
        } catch (Throwable t) {
            // 判断失败时保守起见直接进主界面（不让用户卡住）
            showOnboarding = false;
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "Launcher",
                    "判断引导状态失败，直接进主界面: " + t);
        }

        Intent next = showOnboarding
                ? new Intent(this, OnboardingActivity.class)
                : new Intent(this, MainActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        LogStore.getInstance().append(LogStore.LEVEL_INFO, "Launcher",
                "启动分流 → " + (showOnboarding ? "引导页" : "主界面"));
        startActivity(next);
        finish();
    }
}
