package com.termi.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * 环境页控制器：把 {@link RuntimeManager} 的能力接到 {@code fragment_env.xml} 上。
 *
 * <p>职责：
 * <ul>
 *   <li>展示状态（proot / 发行版 / 位置 / 路径 / 占用）</li>
 *   <li>安装位置切换</li>
 *   <li>SAF 选择环境包 → 后台解压 → 刷新</li>
 *   <li>卸载</li>
 * </ul>
 *
 * <p>设计：独立于 MainActivity，减少对既有代码的侵入。
 */
public final class EnvPageController {

    private static final String TAG = "EnvPage";
    /** SAF 请求码。 */
    public static final int REQ_PICK_ROOTFS = 0x7E01;

    private final Activity activity;

    /** 卡片堆叠容器。 */
    private android.widget.FrameLayout cardStack;
    /** 手势提示（指示点已移入卡片内部，见 tvCardDots）。 */
    private TextView tvCardHint;
    /** 进度与日志（沿用）。 */
    private TextView tvProgress;
    private TextView tvEnvLog;
    private View layoutProgress;
    private ProgressBar progressBar;
    private Button btnImport, btnUninstall;

    /** 已创建的环境卡片（与 listEnvs 顺序一致）。 */
    private final java.util.List<View> envCards = new java.util.ArrayList<>();
    private final java.util.List<String> envNames = new java.util.ArrayList<>();
    /** 当前显示的卡片索引。 */
    private int cardIndex = 0;
    /** 呼吸灯动画（作用于当前卡片的"使用中"标记）。 */
    private android.animation.ValueAnimator breathAnim;

    private volatile boolean busy = false;

    public EnvPageController(Activity activity) {
        this.activity = activity;
    }

    /** 绑定视图并初始化。 */
    public void bind(View root) {
        if (root == null) return;
        cardStack = root.findViewById(R.id.cardStack);
        tvCardHint = root.findViewById(R.id.tvCardHint);
        tvProgress = root.findViewById(R.id.tvProgress);
        tvEnvLog = root.findViewById(R.id.tvEnvLog);
        layoutProgress = root.findViewById(R.id.layoutProgress);
        progressBar = root.findViewById(R.id.progressBar);
        btnImport = root.findViewById(R.id.btnImport);
        btnUninstall = root.findViewById(R.id.btnUninstall);

        btnImport.setOnClickListener(v -> pickRootfs());
        btnUninstall.setOnClickListener(v -> doUninstall());

        setupCardGestures();

        // 首次进入：带自检日志
        refresh(true);
    }

    /**
     * 手势：卡片区<b>左右滑动</b>切换环境。
     *
     * <p>为避免误触，要求横向位移超过 48dp 且明显大于纵向位移。
     * 外层使用 {@link HorizontalFriendlyScrollView}，横向手势不会被它抢走。
     */
    /**
     * 给一张卡片挂上左右滑动切换手势。
     *
     * <p>为何挂在卡片而非 cardStack：cardStack 的子 View（卡片自身）会消费触摸，
     * 容器上的 OnTouchListener 收不到事件（实测无任何回调）。
     * 故逐张卡片挂载。
     *
     * <p><b>本页只做「查看」，不做「切换激活环境」</b>：
     * 切换终端/MCP 使用哪个环境是<b>终端 Tab</b>的职责（长按侧栏切换），
     * 环境 Tab 仅用于浏览已安装的环境及其占用、状态。
     *
     * <p>滑动仅在<b>抬手时判定一次</b>（不随 MOVE 反复触发），
     * 且只切换"当前浏览的卡片"，不修改 activeEnv。
     */
    private void attachSwipeGesture(final View card) {
        if (card == null) return;
        final float threshold = 48 * activity.getResources().getDisplayMetrics().density;

        card.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0f, downY = 0f;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        return false;      // 返回 false：让点击事件仍能派发
                    case android.view.MotionEvent.ACTION_UP: {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        // 抬手时判定一次；横向占优才翻页
                        if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            int n = envCards.size();
                            if (n > 0) {
                                int next = dx < 0 ? cardIndex + 1 : cardIndex - 1;
                                next = ((next % n) + n) % n;
                                showCard(next);      // 只翻看，不改 activeEnv
                                LogStore.getInstance().debug("EnvPage",
                                        "[查看] 翻到卡片 " + next + " / " + envNames.get(next));
                            }
                            return true;      // 消费抬起，避免误触发点击
                        }
                        return false;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /** 兼容旧调用（改为给现有卡片挂手势）。 */
    private void setupCardGestures() {
        for (View c : envCards) {
            attachSwipeGesture(c);
        }
    }

    private float dp(float v) {
        return v * activity.getResources().getDisplayMetrics().density;
    }

    /** 刷新界面状态（后台采集，主线程更新）。 */
    public void refresh() {
        refresh(false);
    }

    /**
     * 刷新界面状态。
     *
     * @param withLog 是否把检测过程写入环境日志（进入页面时为 true，让用户有底；
     *                频繁刷新如 onResume 时为 false，避免刷屏）
     */
    public void refresh(final boolean withLog) {
        new Thread(() -> {
            final RuntimeManager.Status s = RuntimeManager.status(activity);
            activity.runOnUiThread(() -> {
                applyStatus(s);
                if (withLog) logDetection(s);
            });
        }, "env-refresh").start();
    }

    /** 把一次完整检测过程写入环境日志，让用户看到"系统确实检查过了"。 */
    private void logDetection(RuntimeManager.Status s) {
        appendLog("──────── 环境自检 ────────");
        appendLog("proot：" + (s.prootReady
                ? "就绪（" + s.prootVersion + "）"
                : "不可用"));
        appendLog("安装位置：" + s.locationLabel);
        appendLog("rootfs 路径：" + s.rootfsPath);
        if (s.envInstalled) {
            appendLog("发行版：" + (s.distro.isEmpty() ? "未知" : s.distro));
            appendLog("占用空间：" + s.sizeText());
            appendLog("状态：环境就绪");
        } else {
            appendLog("状态：尚未安装环境");
            appendLog("提示：点上方「导入环境包」选择 Linux 环境包（.tar.gz / .tar.xz）");
        }
        appendLog("────────────────────────");
    }

    /**
     * 刷新界面：重建卡片堆叠并应用状态。
     *
     * <p>重建策略：环境数量或名称变化时重建卡片；否则只更新当前卡片内容。
     * 简化起见这里每次全量重建（环境数量少，开销可忽略）。
     */
    private void applyStatus(RuntimeManager.Status s) {
        if (cardStack == null) return;

        java.util.List<String> names = EnvStore.listEnvs(activity);
        if (names.isEmpty()) {
            // 尚未登记任何环境：显示一张"未安装"占位卡
            envNames.clear();
            envCards.clear();
            cardStack.removeAllViews();
            View card = buildCard("未安装", false, s);
            cardStack.addView(card);
            envCards.add(card);
            cardIndex = 0;
            updateIndicator();
            btnUninstall.setEnabled(false);
            btnImport.setEnabled(!busy);
            return;
        }

        // 名称集合变化 → 重建
        if (!names.equals(envNames)) {
            envNames.clear();
            envNames.addAll(names);
            envCards.clear();
            cardStack.removeAllViews();
            for (String n : envNames) {
                View card = buildCard(n, n.equals(EnvStore.getActiveEnv(activity)), s);
                cardStack.addView(card);
                envCards.add(card);
            }
            // 索引对齐当前激活环境
            int activeIdx = envNames.indexOf(EnvStore.getActiveEnv(activity));
            cardIndex = activeIdx >= 0 ? activeIdx : 0;
        } else {
            // 只更新当前卡片内容
            applyCardContent(envCards.get(cardIndex), envNames.get(cardIndex), s);
        }

        // 使当前卡片浮到最上层
        showCard(cardIndex, false);
        updateIndicator();
        boolean installed = s.envInstalled;
        btnUninstall.setEnabled(installed && !busy);
        btnImport.setEnabled(!busy);
    }

    /** 创建一张环境卡片并填充内容。 */
    private View buildCard(String name, boolean active, RuntimeManager.Status s) {
        View card = activity.getLayoutInflater().inflate(R.layout.item_env_card, cardStack, false);
        applyCardContent(card, name, s);
        TextView tvActive = card.findViewById(R.id.tvCardActive);
        if (tvActive != null) {
            tvActive.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        // 非当前卡片的初始层级/缩放由 showCard 统一设置
        return card;
    }

    /** 把状态数据写入一张卡片（各卡片显示各自的数据）。 */
    private void applyCardContent(View card, String name, RuntimeManager.Status s) {
        if (card == null) return;
        boolean isActive = name.equals(EnvStore.getActiveEnv(activity));
        boolean installed = EnvStore.isInstalledOf(activity, name);

        TextView t;
        t = card.findViewById(R.id.tvCardTitle);
        if (t != null) t.setText(name);

        t = card.findViewById(R.id.tvCardState);
        if (t != null) t.setText("状态：" + (installed ? "已安装" : "未安装"));

        t = card.findViewById(R.id.tvCardDistro);
        if (t != null) {
            String distro = EnvStore.getDistroOf(activity, name);
            t.setText("发行版：" + (distro == null || distro.isEmpty() ? "—" : distro));
        }

        t = card.findViewById(R.id.tvCardLocation);
        if (t != null) t.setText("位置：" + s.locationLabel);

        t = card.findViewById(R.id.tvCardPath);
        if (t != null) {
            t.setText("路径：" + EnvStore.rootfsDir(name).getAbsolutePath());
        }

        // 占用：每张卡片独立统计各自目录（互不影响）
        t = card.findViewById(R.id.tvCardSize);
        if (t != null) {
            if (installed) {
                long bytes = RuntimeManager.envSizeOf(activity, name);
                t.setText("占用：" + formatSize(bytes));
            } else {
                t.setText("占用：—");
            }
        }

        t = card.findViewById(R.id.tvCardProot);
        if (t != null) {
            t.setText(s.prootReady
                    ? "proot：就绪（" + s.prootVersion + "）"
                    : "proot：不可用");
        }

        // "查看已装软件包"：未安装时禁用；已安装则弹窗列出包名与大小
        Button btnPkgs = card.findViewById(R.id.btnCardPkgs);
        if (btnPkgs != null) {
            btnPkgs.setEnabled(installed);
            final String envForPkgs = name;
            btnPkgs.setOnClickListener(v -> showPackageList(envForPkgs));
        }
    }

    /**
     * 弹窗展示某环境内已安装的软件包及大小。
     *
     * <p>查询在后台线程执行（会起 proot 进程），完成后回到主线程展示。
     */
    private void showPackageList(final String envName) {
        if (busy) return;
        appendLog("查询软件包：" + envName);

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(activity);
        pd.setMessage("正在读取 " + envName + " 的软件包…");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            final RuntimeManager.PkgListResult r =
                    RuntimeManager.listPackages(activity, envName);
            activity.runOnUiThread(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}

                if (!r.ok || r.packages.isEmpty()) {
                    appendLog("软件包查询失败或为空：" + r.error);
                    new android.app.AlertDialog.Builder(activity)
                            .setTitle("已装软件包")
                            .setMessage("无法获取软件包列表"
                                    + (r.error.isEmpty() ? "" : "：" + r.error))
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }

                // 组装展示文本：包名 + 大小（大→小）
                StringBuilder sb = new StringBuilder();
                sb.append("共 ").append(r.packages.size()).append(" 个包");
                if (r.totalKb() > 0) {
                    sb.append("，合计 ").append(r.totalText());
                }
                sb.append("（").append(r.manager).append("）\n\n");
                for (RuntimeManager.PkgInfo p : r.packages) {
                    sb.append(p.name);
                    if (p.sizeKb > 0) {
                        sb.append("   ").append(p.sizeText());
                    }
                    sb.append('\n');
                }

                android.widget.TextView tv = new android.widget.TextView(activity);
                tv.setText(sb.toString());
                tv.setTextSize(12f);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                int pad = (int) dp(16);
                tv.setPadding(pad, pad, pad, pad);

                android.widget.ScrollView sv = new android.widget.ScrollView(activity);
                sv.addView(tv);

                appendLog("软件包查询完成：" + r.packages.size() + " 个，"
                        + r.totalText());

                new android.app.AlertDialog.Builder(activity)
                        .setTitle(envName + " 的软件包")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }, "env-pkgs").start();
    }

    /** 字节数 → 可读文本。 */
    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB",
                    bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.2f GB",
                bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /**
     * 显示指定索引的卡片（堆叠效果：当前卡在最上、完整；其余依次缩放下移、变暗）。
     */
    private void showCard(int index) {
        showCard(index, true);
    }

    private void showCard(int index, boolean animate) {
        if (envCards.isEmpty()) return;
        int n = envCards.size();
        // 循环
        int target = ((index % n) + n) % n;
        cardIndex = target;

        for (int i = 0; i < n; i++) {
            View c = envCards.get(i);
            int depth = ((i - target) % n + n) % n;   // 0 = 当前, 1 = 下一张...
            // 只让当前 + 后面 2 张参与堆叠（其余藏起来，避免无限叠）
            boolean visible = depth <= 2;
            c.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) continue;

            float scale = 1f - depth * 0.05f;
            float transY = depth * 14f * activity.getResources().getDisplayMetrics().density;
            float alpha = 1f - depth * 0.35f;

            c.setScaleX(scale);
            c.setScaleY(scale);
            c.setTranslationY(transY);
            c.setAlpha(alpha);
            c.setZ(100f - depth);         // 保证当前卡在最上层
            c.setClickable(depth == 0);
            c.setFocusable(depth == 0);
        }

        // 逐张卡片挂"左右滑动翻看"。
        // 注意：必须挂在卡片自身而非 cardStack —— 卡片会消费触摸，
        // 容器上的 OnTouchListener 收不到任何事件（实测确认）。
        // 本页只做查看：点击当前卡片不做任何"切换激活环境"的动作。
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final View c = envCards.get(i);
            attachSwipeGesture(c);
            c.setOnClickListener(v -> {
                if (idx != cardIndex) {
                    showCard(idx, true);       // 翻到该卡（仅查看）
                }
                // 已是当前卡：无操作（切换激活环境在终端 Tab 做）
            });
        }
        updateIndicator();
        startBreathing();
    }

    /**
     * 更新指示点：写入<b>每张卡片内部</b>的 tvCardDots
     * （有几个环境就画几个点，当前项实心）。
     */
    private void updateIndicator() {
        int n = envCards.size();
        if (n == 0) return;

        // 每张卡片都显示完整点串（保证左右滑动时位置一致，不跳动）
        String dots = buildDots(n, cardIndex);

        for (int i = 0; i < n; i++) {
            View card = envCards.get(i);
            TextView tvDots = card.findViewById(R.id.tvCardDots);
            if (tvDots != null) {
                tvDots.setText(dots);
                // 仅当前卡片高亮显示，其余卡片的点更暗（层次感）
                tvDots.setAlpha(i == cardIndex ? 1f : 0.35f);
            }
        }

        if (tvCardHint != null) {
            tvCardHint.setText(n > 1
                    ? "← 左右滑动切换环境 →"
                    : "（当前仅一个环境）");
        }
    }

    /** 生成点串：当前项 ●，其余 ○。 */
    private String buildDots(int total, int activeIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(i == activeIdx ? "●" : "○");
            if (i < total - 1) sb.append(' ');
        }
        return sb.toString();
    }

    /** 当前卡片的"使用中"标记做呼吸灯（边框明暗循环）。 */
    private void startBreathing() {
        if (breathAnim != null) {
            breathAnim.cancel();
            breathAnim = null;
        }
        if (envCards.isEmpty()) return;
        View card = envCards.get(cardIndex);
        TextView tvActive = card.findViewById(R.id.tvCardActive);
        if (tvActive == null || tvActive.getVisibility() != View.VISIBLE) return;

        breathAnim = android.animation.ValueAnimator.ofFloat(0.35f, 1f);
        breathAnim.setDuration(1100);
        breathAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breathAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breathAnim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            tvActive.setAlpha(v);
        });
        breathAnim.start();
    }

    // ------------------------------------------------------------ 导入

    private void pickRootfs() {
        if (busy) return;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            // 常见压缩包 MIME
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/gzip", "application/x-gzip",
                    "application/x-xz", "application/x-tar",
                    "application/octet-stream", "*/*"
            });
            activity.startActivityForResult(i, REQ_PICK_ROOTFS);
        } catch (Throwable t) {
            appendLog("打开文件选择器失败：" + t);
            toast("无法打开文件选择器");
        }
    }

    /** 处理 SAF 返回结果（由 MainActivity.onActivityResult 转发）。 */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_ROOTFS) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            appendLog("已取消选择");
            return;
        }
        Uri uri = data.getData();
        String name = queryName(uri);
        appendLog("已选择：" + name);
        startInstall(uri, name);
    }

    private void startInstall(Uri uri, String fileName) {
        if (busy) {
            toast("正在安装中，请稍候");
            return;
        }
        String loc = EnvStore.getLocation(activity);
        busy = true;
        setBusyUi(true);
        appendLog("开始安装：" + fileName + " → " + EnvStore.locationLabel(loc));

        new Thread(() -> {
            RuntimeManager.InstallResult r;
            InputStream in = null;
            try {
                in = activity.getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new FileNotFoundException("无法打开所选文件");
                }
                // 环境名由文件名推断（如 alpine-minirootfs-... → alpine）
                r = RuntimeManager.install(activity, in, fileName, suggestEnvName(fileName),
                        (stage, bytes, total, entries) -> activity.runOnUiThread(() -> {
                            if (tvProgress == null) return;
                            tvProgress.setText(stage + "：" + entries + " 条目，"
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
            activity.runOnUiThread(() -> {
                busy = false;
                setBusyUi(false);
                appendLog(fr.summary());
                toast(fr.ok ? "环境安装完成" : "环境安装失败");
                refresh();
            });
        }, "env-install").start();
    }

    /**
     * 卸载<b>当前正在查看的卡片</b>对应的环境（不是"当前激活环境"）。
     *
     * <p>环境 Tab 只做查看，故卸载目标是用户此刻翻到的那张卡片；
     * 激活环境由终端 Tab 管理，两者互不影响。
     *
     * <p>卸载前弹确认框，明确告知环境内的内容会一并删除。
     */
    private void doUninstall() {
        if (busy) return;
        if (envNames.isEmpty()) {
            toast("当前没有可卸载的环境");
            return;
        }
        final String target = envNames.get(cardIndex);
        final boolean isActive = target.equals(EnvStore.getActiveEnv(activity));

        String msg = "确认卸载「" + target + "」环境？\n\n"
                + "该环境内的全部内容（含通过 apt/apk 安装的软件、"
                + "Python 等运行环境、以及环境内的所有文件）都会一并删除，且无法恢复。"
                + (isActive ? "\n\n注意：这是当前正在使用的环境。" : "");

        new android.app.AlertDialog.Builder(activity)
                .setTitle("卸载环境")
                .setMessage(msg)
                .setNegativeButton("否", null)
                .setPositiveButton("是", (d, w) -> performUninstall(target))
                .show();
    }

    /** 执行卸载（确认后调用）。 */
    private void performUninstall(final String envName) {
        busy = true;
        setBusyUi(true);
        appendLog("开始卸载环境：" + envName);
        new Thread(() -> {
            boolean ok = RuntimeManager.uninstall(activity, envName);
            activity.runOnUiThread(() -> {
                busy = false;
                setBusyUi(false);
                appendLog(ok ? ("环境已卸载：" + envName) : ("卸载失败：" + envName));
                toast(ok ? ("已卸载 " + envName) : "卸载失败");
                cardIndex = 0;
                refresh();
            });
        }, "env-uninstall").start();
    }

    // ------------------------------------------------------------ UI 辅助

    private void setBusyUi(boolean b) {
        if (layoutProgress != null) {
            layoutProgress.setVisibility(b ? View.VISIBLE : View.GONE);
        }
        if (progressBar != null) {
            progressBar.setIndeterminate(b);
        }
        if (btnImport != null) btnImport.setEnabled(!b);
        if (btnUninstall != null) btnUninstall.setEnabled(!b);
        if (b && tvProgress != null) tvProgress.setText("准备中…");
    }

    /** 追加一行环境日志（界面可见，便于无 logcat 时排查）。 */
    public void appendLog(String s) {
        LogStore.getInstance().debug(TAG, s);
        if (tvEnvLog == null) return;
        CharSequence cur = tvEnvLog.getText();
        String text = (cur == null ? "" : cur.toString());
        // 清掉初始占位符
        if (text.contains("(暂无日志)")) text = "";
        text = text + s + "\n";
        // 保留尾部，避免无限增长
        if (text.length() > 4000) {
            text = text.substring(text.length() - 4000);
        }
        tvEnvLog.setText(text);
    }

    /**
     * 从包文件名推断环境名（目录名）。
     *
     * <p>例：{@code alpine-minirootfs-3.24.2-aarch64.tar.gz} → {@code alpine}
     */
    private String suggestEnvName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return EnvStore.DEFAULT_ENV;
        String n = fileName.toLowerCase();
        for (String ext : new String[]{".tar.gz", ".tar.xz", ".tgz", ".txz", ".tar"}) {
            if (n.endsWith(ext)) {
                n = n.substring(0, n.length() - ext.length());
                break;
            }
        }
        int dash = n.indexOf('-');
        if (dash > 0) n = n.substring(0, dash);
        n = n.replaceAll("[^a-z0-9]", "");
        return n.isEmpty() ? EnvStore.DEFAULT_ENV : n;
    }

    private String queryName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = activity.getContentResolver()
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
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
