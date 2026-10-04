package com.termi.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingWindow {

    private static final String TAG = "FloatingWindow";

    private static FloatingWindow sInstance;

    private TextView tvPortInfo;

    public static synchronized FloatingWindow get(Context ctx) {
        if (sInstance == null) {
            sInstance = new FloatingWindow(ctx.getApplicationContext());
        }
        return sInstance;
    }

    public static boolean isShowing() {
        return sInstance != null && sInstance.shown;
    }

    public static void hide() {
        if (sInstance != null) {
            sInstance.hideInternal();
        }
    }
    private static final int COLOR_RUNNING = 0xFF4CAF50;
    private static final int COLOR_STOPPED = 0xFFE53935;
    private static final int BALL_SIZE_DP = 56;
    private static final int EDGE_MARGIN_DP = 8;
    public static final String PREFS_NAME = "termi_prefs";
    public static final String KEY_FLOATING_SCALE = "floating_scale";
    public static final float DEFAULT_SCALE = 1.0f;
    public static final float MIN_SCALE = 0.3f;
    public static final float MAX_SCALE = 1.2f;
    private static final float CLICK_SLOP_PX = 10f;
    /** 面板缩放阈值：floating_scale 小于该值时面板缩至 PANEL_MIN_SCALE。 */
    private static final float PANEL_SCALE_THRESHOLD = 0.70f;
    private static final float PANEL_MIN_SCALE = 0.70f;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams panelParams;
    private LinearLayout rootContainer;
    private TextView ballView;
    private LinearLayout panelView;

    private boolean shown = false;
    private boolean panelShown = false;
    private boolean running = false;
    private float scale = DEFAULT_SCALE;

    private float loadScale() {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float v = sp.getFloat(KEY_FLOATING_SCALE, DEFAULT_SCALE);
        if (v < MIN_SCALE) v = MIN_SCALE;
        if (v > MAX_SCALE) v = MAX_SCALE;
        return v;
    }

    private int ballSizePx() {
        return (int) (dp(BALL_SIZE_DP) * scale);
    }

    /** 设置缩放比例并立即生效（同时写入 prefs）。 */
    public void setScale(float newScale) {
        if (newScale < MIN_SCALE) newScale = MIN_SCALE;
        if (newScale > MAX_SCALE) newScale = MAX_SCALE;
        scale = newScale;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_FLOATING_SCALE, scale).apply();
        refreshSizeImmediate();
    }

    private void refreshSizeImmediate() {
        try {
            if (ballView == null || windowManager == null || !shown) return;
            int size = ballSizePx();
            android.view.ViewGroup.LayoutParams lp = ballView.getLayoutParams();
            if (lp != null) {
                lp.width = size;
                lp.height = size;
                ballView.setLayoutParams(lp);
            }
            ballView.setBackground(makeBallDrawable(running ? COLOR_RUNNING : COLOR_STOPPED));
            windowManager.updateViewLayout(rootContainer, ballParams);
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "refreshSizeImmediate: " + e);
        }
    }

    /** 重新计算悬浮球尺寸并立即应用（不改变窗口位置）。 */
    public void refreshSize() {
        mainHandler.post(() -> {
            try {
                float s = loadScale();
                if (Math.abs(s - scale) < 0.001f) return;
                scale = s;
                if (ballView == null || windowManager == null || !shown) return;
                int size = ballSizePx();
                android.view.ViewGroup.LayoutParams lp = ballView.getLayoutParams();
                if (lp != null) {
                    lp.width = size;
                    lp.height = size;
                    ballView.setLayoutParams(lp);
                }
                ballView.setBackground(makeBallDrawable(running ? COLOR_RUNNING : COLOR_STOPPED));
                windowManager.updateViewLayout(rootContainer, ballParams);
                LogStore.getInstance().append("INFO", TAG, "refreshSize scale=" + scale);
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "refreshSize: " + e);
            }
        });
    }

    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging = false;

    public FloatingWindow(Context context) {
        this.context = context.getApplicationContext();
    }

    public void show() {
        mainHandler.post(() -> {
            try {
                if (shown) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(context)) {
                    LogStore.getInstance().append("ERROR", TAG, "no overlay permission");
                    Toast.makeText(context, "未获得悬浮窗权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager == null) return;

                if (rootContainer == null) buildViews();

                ballParams = buildParams(WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                ballParams.x = dp(EDGE_MARGIN_DP);
                ballParams.y = dp(120);
                windowManager.addView(rootContainer, ballParams);
                shown = true;
                registerStateListener();
                updateStatus(McpService.running);
                LogStore.getInstance().append("INFO", TAG, "shown");
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "show: " + e);
                Toast.makeText(context, "悬浮窗失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void hideInternal() {
        mainHandler.post(() -> {
            try {
                unregisterStateListener();
                hidePanel();
                if (windowManager != null && rootContainer != null && shown) {
                    windowManager.removeView(rootContainer);
                }
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "hide: " + e);
            } finally {
                shown = false;
            }
        });
    }

    private void removeInternal() {
        mainHandler.post(() -> {
            try {
                unregisterStateListener();
                hidePanel();
                if (windowManager != null && rootContainer != null && shown) {
                    windowManager.removeView(rootContainer);
                }
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "release: " + e);
            } finally {
                shown = false;
                panelShown = false;
                rootContainer = null;
                ballView = null;
                panelView = null;
                synchronized (FloatingWindow.class) {
                    if (sInstance == this) sInstance = null;
                }
            }
        });
    }

    /** 订阅 MCP 服务状态，统一刷新悬浮球与面板。 */
    private final McpService.StateListener mcpStateListener =
            (running, host, port) -> mainHandler.post(() -> {
                try {
                    updateStatus(running);
                } catch (Exception e) {
                    LogStore.getInstance().append("ERROR", TAG, "stateListener: " + e);
                }
            });

    /** 供实例外部注册（show 时调用）。 */
    public void registerStateListener() {
        McpService.addStateListener(mcpStateListener);
    }

    /** 供实例外部注销（hide/release 时调用）。 */
    public void unregisterStateListener() {
        McpService.removeStateListener(mcpStateListener);
    }

    public void updateStatus(boolean running) {
        this.running = running;
        mainHandler.post(() -> {
            try {
                if (ballView != null) {
                    ballView.setBackground(makeBallDrawable(running ? COLOR_RUNNING : COLOR_STOPPED));
                }
                if (panelView != null) updatePanelButtonText();
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "updateStatus: " + e);
            }
        });
    }

    public void release() {
        removeInternal();
    }

    private WindowManager.LayoutParams buildParams(int w, int h, int gravity) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private void buildViews() {
        scale = loadScale();
        rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.HORIZONTAL);
        rootContainer.setGravity(Gravity.CENTER_VERTICAL);

        panelView = new LinearLayout(context);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setBackground(makePanelDrawable());
        int pad = dp(8);
        panelView.setPadding(pad, pad, pad, pad);
        panelView.setVisibility(View.GONE);

        Button btnToggle = new Button(context);
        btnToggle.setText(McpService.running ? "停止 MCP" : "启动 MCP");
        btnToggle.setOnClickListener(v -> {
            try {
                if (McpService.running) {
                    McpService.stop(context);
                    updateStatus(false);
                } else {
                    String host = SettingsStore.getHost(context);
                    int port = SettingsStore.getPort(context);
                    String token = SettingsStore.getToken(context);
                    McpService.start(context, host, port, token);
                    updateStatus(true);
                }
                updatePanelButtonText();
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "toggle: " + e);
                Toast.makeText(context, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        panelView.addView(btnToggle, buttonParams());
        panelView.setTag(R.id.tag_mcp_button, btnToggle);

        TextView tvPortInfo = new TextView(context);
        tvPortInfo.setTextColor(Color.WHITE);
        tvPortInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvPortInfo.setText(buildEndpointText());
        panelView.addView(tvPortInfo, buttonParams());
        panelView.setTag(R.id.tag_port_info, tvPortInfo);

        Button btnOpen = new Button(context);
        btnOpen.setText("打开 App");
        btnOpen.setOnClickListener(v -> {
            try {
                Intent i = context.getPackageManager()
                        .getLaunchIntentForPackage(context.getPackageName());
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                }
                hidePanel();
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "open app: " + e);
                Toast.makeText(context, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        panelView.addView(btnOpen, buttonParams());

        Button btnHide = new Button(context);
        btnHide.setText("隐藏面板");
        btnHide.setOnClickListener(v -> hidePanel());
        panelView.addView(btnHide, buttonParams());

        ballView = new TextView(context);
        ballView.setText("MCP");
        ballView.setTextColor(Color.WHITE);
        ballView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ballView.setGravity(Gravity.CENTER);
        int size = ballSizePx();
        LinearLayout.LayoutParams ballLp = new LinearLayout.LayoutParams(size, size);
        ballView.setLayoutParams(ballLp);
        ballView.setBackground(makeBallDrawable(running ? COLOR_RUNNING : COLOR_STOPPED));

        ballView.setOnTouchListener((v, event) -> {
            try {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = ballParams.x;
                        downY = ballParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > CLICK_SLOP_PX || Math.abs(dy) > CLICK_SLOP_PX) {
                            dragging = true;
                        }
                        if (dragging) {
                            ballParams.x = downX + (int) dx;
                            ballParams.y = downY + (int) dy;
                            windowManager.updateViewLayout(rootContainer, ballParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            togglePanel();
                        } else {
                            snapToEdge();
                        }
                        return true;
                    default:
                        return false;
                }
            } catch (Exception e) {
                LogStore.getInstance().append("ERROR", TAG, "touch: " + e);
                return false;
            }
        });

        rootContainer.addView(panelView);
        rootContainer.addView(ballView);
    }

    /** 自定义文案：显示/隐藏悬浮球。由 MainActivity 读取持久化配置后写入。 */
    private static volatile String sShowText = "显示悬浮球";
    private static volatile String sHideText = "隐藏悬浮球";
    /** 自定义文案：MCP 启动/停止。由 MainActivity 读取持久化配置后写入。 */
    private static volatile String sMcpStartText = "启动 MCP";
    private static volatile String sMcpStopText = "停止 MCP";
    /** 自定义文案：MCP 状态文字。由 MainActivity 读取持久化配置后写入。 */
    private static volatile String sMcpRunningText = "MCP Server 已启动";
    private static volatile String sMcpStoppedText = "MCP Server 停摆中";

    /** 供 MainActivity 应用自定义文案；已有实例会立即刷新界面。 */
    public static void setTexts(String showText, String hideText) {
        if (showText != null && !showText.trim().isEmpty()) {
            sShowText = showText.trim();
        }
        if (hideText != null && !hideText.trim().isEmpty()) {
            sHideText = hideText.trim();
        }
        FloatingWindow inst = sInstance;
        if (inst != null) {
            inst.mainHandler.post(inst::refreshTexts);
        }
    }

    /** 供 MainActivity 应用面板内自定义文案（MCP 按钮 / MCP 状态 / 端口信息）。 */
    public static void setPanelTexts(String mcpStart, String mcpStop,
                                     String mcpRunning, String mcpStopped) {
        if (mcpStart != null && !mcpStart.trim().isEmpty()) {
            sMcpStartText = mcpStart.trim();
        }
        if (mcpStop != null && !mcpStop.trim().isEmpty()) {
            sMcpStopText = mcpStop.trim();
        }
        if (mcpRunning != null && !mcpRunning.trim().isEmpty()) {
            sMcpRunningText = mcpRunning.trim();
        }
        if (mcpStopped != null && !mcpStopped.trim().isEmpty()) {
            sMcpStoppedText = mcpStopped.trim();
        }
        FloatingWindow inst = sInstance;
        if (inst != null) {
            inst.mainHandler.post(inst::updatePanelButtonText);
            inst.mainHandler.post(inst::refreshTexts);
        }
    }

    /** 把文案刷到悬浮球开关按钮上，并同步面板内 MCP 按钮 / 状态 / 端口信息。 */
    private void refreshTexts() {
        try {
            updatePanelButtonText();
            if (panelView != null && panelView.getTag(R.id.tag_mcp_state) instanceof TextView) {
                ((TextView) panelView.getTag(R.id.tag_mcp_state)).setText(getMcpStateText());
            }
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "refreshTexts: " + e);
        }
    }

    /** 当前应展示的 MCP 面板按钮文案（依运行状态）。 */
    public static String getMcpToggleText() {
        return McpService.running ? sMcpStopText : sMcpStartText;
    }

    /** 当前应展示的 MCP 状态文案（依运行状态）。 */
    public static String getMcpStateText() {
        return McpService.running ? sMcpRunningText : sMcpStoppedText;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private void togglePanel() {
        if (panelShown) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    private void showPanel() {
        try {
            if (panelView == null || panelShown) return;
            updatePanelButtonText();
            panelView.setVisibility(View.VISIBLE);
            panelShown = true;
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "showPanel: " + e);
        }
    }

    private void hidePanel() {
        try {
            if (panelView == null || !panelShown) return;
            panelView.setVisibility(View.GONE);
            panelShown = false;
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "hidePanel: " + e);
        }
    }

    private void updatePanelButtonText() {
        try {
            if (panelView != null && panelView.getTag(R.id.tag_mcp_button) instanceof Button) {
                ((Button) panelView.getTag(R.id.tag_mcp_button)).setText(getMcpToggleText());
            }
            if (panelView != null && panelView.getTag(R.id.tag_port_info) instanceof TextView) {
                ((TextView) panelView.getTag(R.id.tag_port_info)).setText(buildEndpointText());
            }
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "updatePanelButtonText: " + e);
        }
    }

    /** 当前是否正在显示悬浮球（供 MainActivity 刷新开关文案）。 */
    public static boolean isShown() {
        FloatingWindow inst = sInstance;
        return inst != null && inst.isShowing();
    }

    private String buildEndpointText() {
        String host = SettingsStore.getHost(context);
        int port = SettingsStore.getPort(context);
        return "端口: " + port + "  (" + host + ")";
    }

    private void snapToEdge() {
        try {
            if (windowManager == null) return;
            int screenWidth = windowManager.getDefaultDisplay().getWidth();
            int ballWidth = ballView.getWidth();
            int margin = dp(EDGE_MARGIN_DP);
            int centerX = ballParams.x + ballWidth / 2;
            if (centerX < screenWidth / 2) {
                ballParams.x = margin;
            } else {
                ballParams.x = screenWidth - ballWidth - margin;
            }
            windowManager.updateViewLayout(rootContainer, ballParams);
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", TAG, "snapToEdge: " + e);
        }
    }

    private GradientDrawable makeBallDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setAlpha(200);
        d.setStroke(dp(1), 0x66000000);
        return d;
    }

    private GradientDrawable makePanelDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(0xDD222222);
        d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
