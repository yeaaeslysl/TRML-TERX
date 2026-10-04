package com.termi.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.io.FileNotFoundException;
import java.io.InputStream;

import rikka.shizuku.Shizuku;
/**
 * 启动引导页（Onboarding）。
 *
 * <p>两页结构：
 * <ol>
 *   <li>品牌页：大 TERX + 属性动画 + 简介，上滑进入下一页</li>
 *   <li>环境页：上半选包（SAF），下半进度与日志；完成后进主界面</li>
 * </ol>
 *
 * <p>触发时机：首次启动（未安装环境且未看过引导）时作为 launcher；
 * 已装环境则直接进 {@link MainActivity}。彩蛋入口可"重温"。
 *
 * <p>零第三方依赖：滑动用 ViewFlipper + 手势检测，动画用 ObjectAnimator。
 */
public class OnboardingActivity extends Activity {

    private static final String TAG = "Onboarding";
    /** SAF 请求码。 */
    private static final int REQ_PICK = 0x0E01;
    /** 判定"上滑"的最小位移（px）。 */
    private static final int SWIPE_MIN_DISTANCE = 120;
    private static final int SWIPE_MAX_OFF_PATH = 250;
    private static final int SWIPE_THRESHOLD_VELOCITY = 200;

    private ViewFlipper flipper;
    private TextView tvLogo, tvSlogan, tvSlogan2, tvPickedName, tvStage, tvOnboardLog;
    private View vLogoLine, layoutSwipeHint;
    private ProgressBar progressBar;
    private Button btnPickEnv, btnEnter;

    /** 是否"重温"模式（从彩蛋进入，完成后返回而非进主界面）。 */
    private boolean reviewMode = false;
    private volatile boolean busy = false;

    private float downX, downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        reviewMode = getIntent().getBooleanExtra("review", false);

        flipper = findViewById(R.id.onboardingFlipper);
        tvLogo = findViewById(R.id.tvLogo);
        tvSlogan = findViewById(R.id.tvSlogan);
        tvSlogan2 = findViewById(R.id.tvSlogan2);
        vLogoLine = findViewById(R.id.vLogoLine);
        layoutSwipeHint = findViewById(R.id.layoutSwipeHint);
        tvPickedName = findViewById(R.id.tvPickedName);
        tvStage = findViewById(R.id.tvOnboardStage);
        tvOnboardLog = findViewById(R.id.tvOnboardLog);
        progressBar = findViewById(R.id.onboardProgress);
        btnPickEnv = findViewById(R.id.btnPickEnv);
        btnEnter = findViewById(R.id.btnEnter);

        btnPickEnv.setOnClickListener(v -> pickEnv());
        btnEnter.setOnClickListener(v -> enterMain());

        // 重温模式：显示右上角关闭按钮
        TextView btnClose = findViewById(R.id.btnCloseReview);
        if (btnClose != null) {
            if (reviewMode) {
                btnClose.setVisibility(View.VISIBLE);
                btnClose.setOnClickListener(v -> finish());
            } else {
                btnClose.setVisibility(View.GONE);
            }
        }

        setupSwipe();
        playIntroAnimation();

        // 启动时即申请 Shizuku 权限：环境安装（写入 /data/local/tmp）必须走 Shizuku，
        // 而授权入口原本只在主界面，会导致"引导页要装环境但没有权限"的死锁。
        ensureShizukuPermission();

        LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG,
                "引导页打开 review=" + reviewMode);
    }

    /**
     * 确保 Shizuku 权限可用（引导页内直接申请）。
     *
     * <p>为何需要：环境安装要把 rootfs 写入 {@code /data/local/tmp}，
     * 而该位置 app 身份只读，必须经 Shizuku（shell 身份）。
     * 原本授权入口只在主界面控制面板，引导页无法授权 → 装环境必然失败。
     */
    private void ensureShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    appendLog("Shizuku 已授权");
                    return;
                }
                // 注册一次结果监听（授权弹窗返回后打印结果）
                Shizuku.addRequestPermissionResultListener(shizukuPermListener);
                appendLog("正在申请 Shizuku 权限…请在弹窗中允许");
                Shizuku.requestPermission(REQ_SHIZUKU_PERM);
            } else {
                appendLog("Shizuku 服务未运行：请先启动 Shizuku（或 Shizuku 类管理器）");
                appendLog("  否则无法安装环境（rootfs 需写入 data/local/tmp）");
                Toast.makeText(this, "请先启动 Shizuku，否则无法安装环境",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            appendLog("Shizuku 权限申请异常：" + t);
        }
    }

    private static final int REQ_SHIZUKU_PERM = 0x0E02;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                boolean ok = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                appendLog(ok ? "Shizuku 授权成功" : "Shizuku 授权被拒绝");
                if (!ok) {
                    Toast.makeText(this, "未授权 Shizuku，无法安装环境", Toast.LENGTH_LONG).show();
                }
            };

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------ 第 1 页动画

    /** 品牌页入场动画：logo 淡入缩放、分隔线横向展开、文案上浮。 */
    private void playIntroAnimation() {
        // logo：缩放 + 淡入
        tvLogo.setAlpha(0f);
        tvLogo.setScaleX(0.6f);
        tvLogo.setScaleY(0.6f);
        tvLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(700)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 分隔线：横向展开
        vLogoLine.setScaleX(0f);
        vLogoLine.animate().scaleX(1f).setDuration(600).setStartDelay(300).start();

        // 文案：上浮淡入
        for (View v : new View[]{tvSlogan, tvSlogan2}) {
            v.setAlpha(0f);
            v.setTranslationY(24f);
        }
        tvSlogan.animate().alpha(1f).translationY(0f)
                .setDuration(600).setStartDelay(450).start();
        tvSlogan2.animate().alpha(1f).translationY(0f)
                .setDuration(600).setStartDelay(600).start();

        // 上滑提示：循环呼吸
        if (layoutSwipeHint != null) {
            ObjectAnimator breath = ObjectAnimator.ofFloat(
                    layoutSwipeHint, "alpha", 0.35f, 1f);
            breath.setDuration(1100);
            breath.setRepeatCount(ValueAnimator.INFINITE);
            breath.setRepeatMode(ValueAnimator.REVERSE);
            breath.start();
        }
    }

    // ------------------------------------------------------------ 手势

    /** 上滑切到下一页（仅第 1 页生效）。 */
    private void setupSwipe() {
        View root = findViewById(R.id.onboardingRoot);
        if (root == null) return;
        root.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getX() - downX);
                    float dy = event.getY() - downY;
                    if (dx < SWIPE_MAX_OFF_PATH
                            && Math.abs(dy) > SWIPE_MIN_DISTANCE
                            && dy < 0) {   // 向上
                        goToEnvPage();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void goToEnvPage() {
        if (flipper == null) return;
        if (flipper.getDisplayedChild() == 0) {
            // 竖向滑动：新页从下方进入，旧页向上离开
            flipper.setInAnimation(this, R.anim.slide_in_bottom);
            flipper.setOutAnimation(this, R.anim.slide_out_top);
            flipper.showNext();
            LogStore.getInstance().append(LogStore.LEVEL_INFO, TAG, "进入环境选择页");
            // 重温模式：进入第 2 页后自动演示
            if (reviewMode) {
                flipper.postDelayed(this::startReviewDemo, 400);
            }
        }
    }

    // ------------------------------------------------------------ 重温自动演示

    /**
     * 重温模式的"无实物表演"：自动演示 选包 → 安装 → 进度 → 完成。
     *
     * <p>不触碰真实文件与磁盘，只驱动界面：按钮按压效果、文件名出现、
     * 进度条按真实节奏推进、日志逐行滚动。目的是让用户重看一遍流程动画。
     */
    private void startReviewDemo() {
        if (!reviewMode) return;
        final String demoName = "debian-bookworm-arm64.tar.xz";

        // 1) 模拟按钮按下
        btnPickEnv.setEnabled(false);
        btnPickEnv.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120)
                .withEndAction(() -> btnPickEnv.animate()
                        .scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();

        // 2) 延迟后"选好文件"
        btnPickEnv.postDelayed(() -> {
            if (isFinishing()) return;
            tvPickedName.setText(demoName);
            appendLog("已选择：" + demoName);
            startDemoProgress();
        }, 700);
    }

    /** 模拟解压进度推进（节奏贴近真实：先快后慢再收尾）。 */
    private void startDemoProgress() {
        final int[] steps = {0, 3, 9, 18, 27, 38, 47, 55, 61, 66, 70, 73, 76,
                78, 80, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 100};
        final int[] entries = {0, 120, 680, 1900, 3400, 5200, 7100, 8900, 10200,
                11300, 12100, 12700, 13200, 13600, 13900, 14100, 14300, 14450,
                14600, 14700, 14800, 14880, 14950, 15010, 15060, 15100, 15130,
                15150, 15160, 15170, 15074};

        if (progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
        }
        if (tvStage != null) tvStage.setText("解压中：0 条目，0 MB");

        final int total = steps.length;
        for (int i = 0; i < total; i++) {
            final int idx = i;
            long delay = 120L + i * 55L;   // 约 1.8 秒走完
            btnPickEnv.postDelayed(() -> {
                if (isFinishing()) return;
                int p = steps[idx];
                if (progressBar != null) progressBar.setProgress(p);
                if (tvStage != null) {
                    tvStage.setText("解压中：" + entries[idx] + " 条目，"
                            + (int) (entries[idx] * 0.0286) + " MB");
                }
                // 关键节点写日志，营造真实感
                if (idx == 3) appendLog("检测到已有环境，清理中…");
                if (idx == 6) appendLog("旧环境已清理");
                if (idx == 12) appendLog("解压中… " + entries[idx] + " 条目");
                if (idx == 22) appendLog("解压中… " + entries[idx] + " 条目");
                if (idx == total - 1) {
                    appendLog("解压完成：15074 个条目，431 MB");
                    appendLog("环境安装完成：Debian GNU/Linux 12 (bookworm)");
                    if (tvStage != null) {
                        tvStage.setText("安装完成：Debian GNU/Linux 12 (bookworm)");
                    }
                    toast("重温完毕");
                    if (btnEnter != null) btnEnter.setVisibility(View.VISIBLE);
                }
            }, delay);
        }
    }

    // ------------------------------------------------------------ 选包与安装

    private void pickEnv() {
        if (busy) {
            toast("正在安装中，请稍候");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/gzip", "application/x-gzip",
                    "application/x-xz", "application/x-tar",
                    "application/octet-stream", "*/*"
            });
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            appendLog("打开文件选择器失败：" + t);
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            appendLog("已取消选择");
            return;
        }
        Uri uri = data.getData();
        String name = queryName(uri);
        if (tvPickedName != null) tvPickedName.setText(name);
        appendLog("已选择：" + name);
        startInstall(uri, name);
    }

    private void startInstall(Uri uri, String fileName) {
        if (busy) return;
        busy = true;
        if (btnPickEnv != null) btnPickEnv.setEnabled(false);
        if (progressBar != null) progressBar.setIndeterminate(true);
        if (tvStage != null) tvStage.setText("正在解压…");
        appendLog("开始安装：" + fileName);

        new Thread(() -> {
            RuntimeManager.InstallResult r;
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) throw new FileNotFoundException("无法打开所选文件");
                r = RuntimeManager.install(this, in, fileName,
                        suggestEnvName(fileName),
                        (stage, bytes, total, entries) -> runOnUiThread(() -> {
                            if (tvStage == null) return;
                            tvStage.setText(stage + "：" + entries + " 条目，"
                                    + (bytes / 1024 / 1024) + " MB");
                        }));
            } catch (Throwable t) {
                r = new RuntimeManager.InstallResult();
                r.error = String.valueOf(t.getMessage());
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
            final RuntimeManager.InstallResult fr = r;
            runOnUiThread(() -> {
                busy = false;
                if (btnPickEnv != null) btnPickEnv.setEnabled(true);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(fr.ok ? 100 : 0);
                }
                appendLog(fr.summary());
                if (fr.ok) {
                    if (tvStage != null) tvStage.setText("安装完成：" + fr.distro);
                    if (btnEnter != null) btnEnter.setVisibility(View.VISIBLE);
                    toast("环境安装完成");
                } else {
                    if (tvStage != null) tvStage.setText("安装失败");
                    toast("安装失败，请查看下方日志");
                }
            });
        }, "onboard-install").start();
    }

    // ------------------------------------------------------------ 进入主界面

    private void enterMain() {
        // 标记引导已完成（下次启动直接进主界面）
        EnvStore.markOnboardDone(this);
        if (reviewMode) {
            // 重温模式：返回原界面
            finish();
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 从包文件名推断环境名（目录名）。
     *
     * <p>例：{@code alpine-minirootfs-3.24.2-aarch64.tar.gz} → {@code alpine}
     * 仅保留字母数字与连字符，其余转为连字符；为空时用默认名。
     */
    private String suggestEnvName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return EnvStore.DEFAULT_ENV;
        String n = fileName.toLowerCase();
        // 去掉常见后缀
        for (String ext : new String[]{".tar.gz", ".tar.xz", ".tgz", ".txz", ".tar"}) {
            if (n.endsWith(ext)) {
                n = n.substring(0, n.length() - ext.length());
                break;
            }
        }
        // 取第一个连字符前的主体（如 alpine-minirootfs-... → alpine）
        int dash = n.indexOf('-');
        if (dash > 0) n = n.substring(0, dash);
        // 只保留安全字符
        n = n.replaceAll("[^a-z0-9]", "");
        return n.isEmpty() ? EnvStore.DEFAULT_ENV : n;
    }

    private void appendLog(String s) {
        LogStore.getInstance().info(TAG, s);
        if (tvOnboardLog == null) return;
        String text = tvOnboardLog.getText() == null ? "" : tvOnboardLog.getText().toString();
        text = text + s + "\n";
        if (text.length() > 3000) text = text.substring(text.length() - 3000);
        tvOnboardLog.setText(text);
    }

    private String queryName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {}
        if (name == null || name.isEmpty()) {
            String p = uri.getLastPathSegment();
            name = p == null ? "rootfs.tar.gz" : p;
        }
        return name;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        // 引导页不允许后退退出（首次启动必须走完）；重温模式可退
        if (reviewMode) {
            super.onBackPressed();
            return;
        }
        if (flipper != null && flipper.getDisplayedChild() == 1
                && !EnvStore.isInstalled(this)) {
            // 第 2 页且未安装：退回第 1 页（竖向动画）
            flipper.setInAnimation(this, R.anim.slide_in_top);
            flipper.setOutAnimation(this, R.anim.slide_out_bottom);
            flipper.showPrevious();
            return;
        }
        super.onBackPressed();
    }
}
