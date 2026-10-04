package com.termi.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import rikka.shizuku.Shizuku;

import com.termi.app.terminal.ExtraKeysView;
import com.termi.app.terminal.TerminalEmulator;
import com.termi.app.terminal.TerminalSession;
import com.termi.app.terminal.TerminalView;

import android.util.TypedValue;
import android.view.KeyEvent;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ViewFlipper;
import com.google.android.material.tabs.TabLayout;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvLog;
    private LogStore.Listener logListener;
    private EditText etHost;
    private EditText etPort;
    private EditText etToken;
    private CheckBox cbRoot;
    private Button btnToggle;
    private TextView tvMcpState;
    private Spinner spLevel;
    private final List<String> levelFilter = new ArrayList<>();
    private boolean showDebug = true;

    // 终端三件套
    private TerminalView terminalView;
    private TerminalEmulator terminalEmulator;
    private TerminalSession terminalSession;
    private PipedOutputStream ptyOutput;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> updateShizukuStatus();

    /** 统一的状态回调：主界面按钮文字与状态显示都在这里刷新。 */
    private final McpService.StateListener mcpStateListener =
            (running, host, port) -> runOnUiThread(() -> {
                refreshToggle();
                if (tvMcpState != null) {
                    SharedPreferences prefs = getSharedPreferences(PREFS_TEXT, MODE_PRIVATE);
                    String runningText = prefs.getString("tv_mcp_state_a", "MCP Server 已启动");
                    String stoppedText = prefs.getString("tv_mcp_state_b", "MCP Server 停摆中");
                    tvMcpState.setText(running
                            ? runningText + " @ " + host + ":" + port
                            : stoppedText);
                }
                LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                        "MCP 状态变化 running=" + running + " @ " + host + ":" + port);
            });

    /**
     * 若用户开启了「启动时自动开启 MCP」，则在进入主界面后自动启动 MCP 服务。
     *
     * <p>用于免去每次手动点击启动；也便于自动化测试（安装后启动 App 即可调 MCP）。
     */
    private void maybeAutoStartMcp(SharedPreferences prefs) {
        if (!prefs.getBoolean("auto_start", false)) return;
        if (McpService.running) return;
        // 延后一点启动：避免与 Shizuku 权限申请、界面初始化竞争
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                String host = prefs.getString("host", "127.0.0.1");
                int port = prefs.getInt("port", 8000);
                String token = prefs.getString("token", "");
                McpService.start(this, host, port, token);
                appendLog("自动启动 MCP @ " + host + ":" + port);
            } catch (Throwable t) {
                LogStore.getInstance().error("UI", "自动启动 MCP 失败: " + t);
            }
        }, 1200L);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvShizukuStatus);
        tvMcpState = findViewById(R.id.tvMcpState);
        tvLog = findViewById(R.id.tvLog);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etToken = findViewById(R.id.etToken);
        cbRoot = findViewById(R.id.cbRoot);
        btnToggle = findViewById(R.id.btnToggle);

        LogStore.getInstance().init(getApplicationContext());
        LogStore.restoreDevilLog(getApplicationContext());   // 恢复 Devil Log 开关
        CrashHandler.install(getApplicationContext());
        Shizuku.addRequestPermissionResultListener(shizukuListener);

        logListener = entry -> tvLog.post(() -> {
            tvLog.append(entry.format() + "\n");
            int cut = tvLog.getText().length() - 4000;
            if (cut > 0) tvLog.setText(tvLog.getText().subSequence(cut, tvLog.getText().length()));
        });
        LogStore.getInstance().addListener(logListener);
        McpService.addStateListener(mcpStateListener);
        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI", "MainActivity onCreate");
        LogStore.getInstance().flush();

        spLevel = findViewById(R.id.spLevel);
        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"全部", "DEBUG+", "INFO+", "WARN+", "ERROR"});
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLevel.setAdapter(levelAdapter);
        spLevel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                showDebug = position <= 1;
                rerenderLog();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        rerenderLog();
        findViewById(R.id.btnFlushLog).setOnClickListener(v -> {
            LogStore.getInstance().flush();
            Toast.makeText(this, "已强制落盘", Toast.LENGTH_SHORT).show();
        });

        SharedPreferences prefs = getSharedPreferences("mcp", MODE_PRIVATE);
        etHost.setText(prefs.getString("host", etHost.getText().toString()));
        etPort.setText(String.valueOf(prefs.getInt("port", 8000)));
        etToken.setText(prefs.getString("token", ""));

        // 「启动时自动开启 MCP」开关
        CheckBox cbAuto = findViewById(R.id.cbAutoStartMcp);
        if (cbAuto != null) {
            cbAuto.setChecked(prefs.getBoolean("auto_start", false));
            cbAuto.setOnCheckedChangeListener((b, checked) -> prefs.edit()
                    .putBoolean("auto_start", checked).apply());
        }
        // 「Devil Log」开关（详细调试日志）
        CheckBox cbDevil = findViewById(R.id.cbDevilLog);
        if (cbDevil != null) {
            cbDevil.setChecked(LogStore.isDevilLog());
            cbDevil.setOnCheckedChangeListener((b, checked) ->
                    LogStore.setDevilLog(this, checked));
        }
        maybeAutoStartMcp(prefs);

        findViewById(R.id.btnShizuku).setOnClickListener(v -> requestShizuku());

        findViewById(R.id.btnOverlayPermission).setOnClickListener(v -> requestOverlayPermission());

        findViewById(R.id.btnFloatingToggle).setOnClickListener(v -> {
            if (FloatingWindow.isShowing()) {
                FloatingWindow.hide();
                appendLog("隐藏悬浮球");
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                    requestOverlayPermission();
                    return;
                }
                FloatingWindow.get(this).show();
                appendLog("显示悬浮球");
            }
            refreshFloatingToggle();
        });

        setupFloatingScaleControls(prefs);

        btnToggle.setOnClickListener(v -> {
            if (McpService.running) {
                McpService.stop(this);
                appendLog("停止 MCP");
            } else {
                int port = 8000;
                try { port = Integer.parseInt(etPort.getText().toString()); } catch (Exception ignored) {}
                McpService.start(this, etHost.getText().toString(), port,
                        etToken.getText().toString());
                prefs.edit()
                        .putString("host", etHost.getText().toString())
                        .putInt("port", port)
                        .putString("token", etToken.getText().toString())
                        .apply();
                appendLog("启动 MCP @ " + etHost.getText() + ":" + port);
            }
            refreshToggle();
        });

        btnTestSelfCheck = findViewById(R.id.btnTest);
        tvTestHint = findViewById(R.id.tvTestHint);
        Button btnTestDeep = findViewById(R.id.btnTestDeep);
        Button btnCancelTest = findViewById(R.id.btnCancelTest);
        if (btnTestSelfCheck == null || tvTestHint == null
                || btnTestDeep == null || btnCancelTest == null) {
            // 横竖屏/多布局分支导致控件缺失时，绝不能继续绑定监听，否则点击即 NPE
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "UI",
                    "[UI] 测试相关控件缺失，跳过测试按钮绑定: btnTest=" + (btnTestSelfCheck != null)
                            + " tvTestHint=" + (tvTestHint != null)
                            + " btnTestDeep=" + (btnTestDeep != null)
                            + " btnCancelTest=" + (btnCancelTest != null));
        } else {
            btnTestSelfCheck.setOnClickListener(v -> runSelfCheck());
            btnTestDeep.setOnClickListener(v -> runDeepCheck());
            btnCancelTest.setOnClickListener(v -> cancelSelfCheck());
        }

        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            LogStore.getInstance().clear();
            tvLog.setText("");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnExportLog).setOnClickListener(v -> exportLogs());

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }

        updateShizukuStatus();
        refreshToggle();
        setupTitleCustomText();
        applyCustomTexts();

        // 控制面板：自定义终端配色
        EditText etCustomFg = findViewById(R.id.etCustomFg);
        EditText etCustomBg = findViewById(R.id.etCustomBg);
        Button btnApplyCustom = findViewById(R.id.btnApplyCustomTheme);
        if (btnApplyCustom != null && etCustomFg != null && etCustomBg != null) {
            SharedPreferences uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
            String savedFg = uiPrefs.getString("custom_term_fg", "");
            String savedBg = uiPrefs.getString("custom_term_bg", "");
            if (!savedFg.isEmpty()) etCustomFg.setText(savedFg);
            if (!savedBg.isEmpty()) etCustomBg.setText(savedBg);

            btnApplyCustom.setOnClickListener(v -> {
                String fgHex = etCustomFg.getText().toString().trim();
                String bgHex = etCustomBg.getText().toString().trim();
                if (fgHex.length() != 6 || bgHex.length() != 6) {
                    Toast.makeText(this, "请输入6位HEX颜色码", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    int fgColor = 0xFF000000 | Integer.parseInt(fgHex, 16);
                    int bgColor = 0xFF000000 | Integer.parseInt(bgHex, 16);
                    if (terminalEmulator != null) {
                        terminalEmulator.getColors().applyCustomTheme(fgColor, bgColor);
                        terminalEmulator.getBuffer().setFullRedrawNeeded();
                        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                                "[Theme] Terminal custom theme applied: fg=#" + fgHex + ", bg=#" + bgHex);
                    }
                    if (terminalView != null) terminalView.postInvalidate();
                    getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                            .putString("custom_term_fg", fgHex)
                            .putString("custom_term_bg", bgHex)
                            .apply();
                    // 将自定义配色应用到控制面板 UI
                    applyControlPanelTheme(fgColor, bgColor);
                    Toast.makeText(this, "自定义配色已应用", Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "无效的颜色码", Toast.LENGTH_SHORT).show();
                }
            });

            // 恢复上次保存的控制面板配色
            int savedControlFg = uiPrefs.getInt("control_fg", 0);
            int savedControlBg = uiPrefs.getInt("control_bg", 0);
            if (savedControlFg != 0 && savedControlBg != 0) {
                applyControlPanelTheme(savedControlFg, savedControlBg);
            }
        }

        // Tab + ViewFlipper 联动
        ViewFlipper flipper = findViewById(R.id.viewFlipper);
        TabLayout tabs = findViewById(R.id.tabLayout);
        if (flipper != null && tabs != null) {
            tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    int pos = tab.getPosition();
                    if (pos >= 0 && pos < flipper.getChildCount()) {
                        flipper.setDisplayedChild(pos);
                    }
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }

        // 终端初始化：80列 x 24行，500行历史缓冲
        initTerminal();

        // 环境页：绑定视图（状态 / 导入 / 卸载）
        try {
            android.view.View envAnchor = findViewById(R.id.btnImport);
            if (envAnchor != null) {
                envPage = new EnvPageController(this);
                envPage.bind(envAnchor.getRootView());
                LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI", "[Env] 环境页已绑定");
            }
        } catch (Throwable t) {
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "UI", "[Env] 绑定失败: " + t);
        }

        // MCP 命令回显到终端
        McpServer.setCommandEchoListener(cmd -> runOnUiThread(() ->
            writeTerminal("\r\n\033[36m[mcp]\033[0m " + cmd + "\r\n")));

        // MCP 输出回显到终端（让 AI 的每条命令结果在终端可见）
        McpServer.setOutputEchoListener(out -> runOnUiThread(() -> {
            if (out == null || out.isEmpty()) return;
            String normalized = out.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalized.endsWith("\n")) normalized += "\n";
            writeTerminal("\033[90m" + normalized + "\033[0m");
        }));

        // 注册 MCP 的终端诊断桥未在此处（在 setupSideBar 内注册）
    }

    // --- Terminal Settings Keys ---
    public static final String PREFS_UI = "ui_settings";
    public static final String KEY_TERM_THEME = "term_theme_index"; // 0=default(dark), 1=light, etc.
    public static final String KEY_TERM_FONT_SIZE_SP = "term_font_size_sp";
    private static final int DEFAULT_TERM_FONT_SIZE_SP = 14;

    private Process shellProcess;
    private java.io.BufferedWriter shellWriter;
    private Thread shellReaderThread;

    /** 环境页控制器（TERX 持久化环境）。 */
    private EnvPageController envPage;

    /** ExtraKeys 栏引用（用于更新 EXIT/LOGIN 按钮状态）。 */
    private ExtraKeysView extraKeysView;
    /** 长驻 Linux 会话（仅 Linux 模式使用；Android 模式为 null）。 */
    private TerminalSessionManager sessionManager;

    // ==================== 多窗口（侧栏 tab） ====================

    /** 一个终端窗口：独立 emulator + 独立 shell 进程/会话 + 所属环境。 */
    private static final class TermWindow {
        /** 环境名（TerminalWindowStore.ENV_ANDROID 或具体 Linux 环境名）。 */
        String env;
        /** 创建序号（与 tab 绑定，仅同环境多窗口时才显示）。 */
        int seq;
        /** 该窗口自己的终端缓冲与解析器。 */
        TerminalEmulator emulator;
        /** Linux 模式下的长驻会话（Android 模式为 null）。 */
        TerminalSessionManager session;
        /** Android 模式下的进程与写入器（Linux 模式为 null）。 */
        Process process;
        java.io.BufferedWriter writer;
        Thread readerThread;
        /** 该窗口是否已初始化（懒加载：切到它时才真正启动进程）。 */
        boolean started = false;
    }

    /** 全部窗口；索引 0 = 当前显示。 */
    private final java.util.List<TermWindow> windows = new java.util.ArrayList<>();
    /** 侧栏控件。 */
    private TerminalSideBar sideBar;
    /** 侧栏收起定时器（3 秒无操作）。 */
    private final android.os.Handler collapseHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable collapseRunnable = () -> {
        // 无条件收起（包括只有一个窗口的情况）：
        // 收起后只显示当前 tab，隐去半透明底板与 ＋，避免侧栏长期占位。
        if (sideBar != null) {
            sideBar.setCollapsed(true);
        }
    };
    /** 当前终端是否处于 Linux 环境内。 */
    private boolean terminalInLinuxEnv = false;
    /** 当前终端进程是否由"进入 Linux 环境"启动（用于 shell 退出后的处理）。 */
    private boolean shellLaunchedAsLinux = false;
    /**
     * shell 代际计数器。
     *
     * <p>每次 {@link #startShell} 递增。读取线程捕获启动时的代际，
     * 回调前比对：若已不是当前代，说明该线程属于被替换掉的旧进程，直接忽略。
     *
     * <p>解决的实际问题：{@code BufferedReader.readLine()} 不响应 interrupt，
     * 旧进程被杀后其读取线程仍会走到"进程结束"分支并回调，
     * 导致刚启动的新 shell 被误判为"已退出"而打回上一模式（LOGIN 点不上）。
     */
    private volatile int shellGeneration = 0;

    /** 终端模式：Android 原生 shell。 */
    private static final int SHELL_MODE_ANDROID = 0;
    /** 终端模式：Linux 环境（proot）。 */
    private static final int SHELL_MODE_LINUX = 1;

    private void initTerminal() {
        FrameLayout container = findViewById(R.id.terminal_container);
        if (container == null) {
            LogStore.getInstance().append(LogStore.LEVEL_WARN, "UI",
                    "[Terminal] terminal_container not found, skip init");
            return;
        }

        // Load settings
        SharedPreferences uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        int themeIndex = uiPrefs.getInt(KEY_TERM_THEME, 0);
        int fontSizeSp = uiPrefs.getInt(KEY_TERM_FONT_SIZE_SP, DEFAULT_TERM_FONT_SIZE_SP);

        // 1024列 buffer：实际使用中几乎不会触发 auto-wrap，等效于不换行
        terminalEmulator = new TerminalEmulator(1024, 24, 500);
        
        // Apply initial theme to emulator colors
        if (themeIndex != 0) {
             terminalEmulator.setTheme(themeIndex);
        }

        // 1. Create View first
        terminalView = new TerminalView(this);
        
        // 2. Set font size BEFORE attaching (so attach() picks it up for Renderer creation)
        float density = getResources().getDisplayMetrics().density;
        int fontSizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, fontSizeSp, getResources().getDisplayMetrics());
        terminalView.setFontSize(fontSizePx);

        // 3. Attach emulator to initialize Renderer with correct size and theme
        terminalView.attach(terminalEmulator);

        // 4. 注册垂直滚动监听器：内容与光标同向滚动（纯手动，不做任何自动归位）
        terminalView.setOnScrollListener(deltaLines -> {
            if (terminalEmulator != null) {
                // 取反：手指上滑 => 内容上移（内容跟随手指方向），与光标方向保持一致
                terminalEmulator.scrollViewport(-deltaLines);
                terminalView.postInvalidate();
            }
        });

        // 5. "回左下"按钮：一键把视口与横向偏移都归位（手动触发，无自动逻辑）
        Button btnHomeBottom = findViewById(R.id.btnHomeBottom);
        if (btnHomeBottom != null) {
            btnHomeBottom.setOnClickListener(v -> {
                if (terminalEmulator != null) {
                    terminalEmulator.scrollViewportToBottom();
                }
                terminalView.setScrollXOffset(0);
                terminalView.postInvalidate();
            });
        }

        // 将 TerminalView 挂入容器
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(terminalView, lp);

        // 侧栏与选择页必须浮在 TerminalView 之上（TerminalView 是后加的，
        // 否则会把它们盖住，导致"侧栏明明在绘制却看不见"）。
        View sb = findViewById(R.id.terminal_sidebar);
        if (sb != null) container.bringChildToFront(sb);
        View picker = findViewById(R.id.windowPickerPage);
        if (picker != null) container.bringChildToFront(picker);

        // --- SHELL PROCESS STARTUP ---
        // 由多窗口侧栏统一管理（见 setupSideBar → activateWindow），
        // 此处不再直接 startShell，避免"旧单窗口"与"新多窗口"双启动。

        // Keyboard Event Handler：与 IME 共用本地行编辑，避免两套逻辑打架
        terminalView.setOnKeyListener((keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    handleLocalInput(new byte[]{'\r'});
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                    handleLocalInput(new byte[]{0x7f});
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_SPACE) {
                    // 软键盘的空格可能不带 unicodeChar（getUnicodeChar()==0），
                    // 走 else 分支会被丢弃，故显式处理。
                    handleLocalInput(new byte[]{' '});
                    return true;
                } else {
                    char c = (char) event.getUnicodeChar();
                    if (c != 0) {
                        handleLocalInput(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                        return true;
                    }
                }
            }
            return false;
        });

        // === ExtraKeys 快捷键栏 ===
        ExtraKeysView extraKeys = findViewById(R.id.extra_keys);
        if (extraKeys != null) {
            extraKeysView = extraKeys;
            extraKeys.setEnvState(terminalInLinuxEnv);
            extraKeys.setOnExtraKeyListener(key -> {
                // 环境切换键：不走 shellWriter（由宿主切换进程）
                if (ExtraKeysView.KEY_EXIT.equals(key)) {
                    onEnvToggleKey(true);
                    return;
                }
                if (ExtraKeysView.KEY_LOGIN.equals(key)) {
                    onEnvToggleKey(false);
                    return;
                }
                if (shellWriter == null) return;
                try {
                    switch (key) {
                        case "ESC":    shellWriter.write("\u001b"); break;
                        case "TAB":    shellWriter.write("\t"); break;
                        case "CTRL":   /* 组合键状态标记，简化版先忽略 */ break;
                        case "ALT":    /* 同上 */ break;
                        case "\u2191": shellWriter.write("\u001b[A"); break;
                        case "\u2193": shellWriter.write("\u001b[B"); break;
                        case "\u2192": shellWriter.write("\u001b[C"); break;
                        case "\u2190": shellWriter.write("\u001b[D"); break;
                        case "HOME":   shellWriter.write("\u001b[H"); break;
                        case "END":    shellWriter.write("\u001b[F"); break;
                        case "PGUP":   shellWriter.write("\u001b[5~"); break;
                        case "PGDN":   shellWriter.write("\u001b[6~"); break;
                        case "ENTER":  shellWriter.write("\n"); shellWriter.flush(); break;
                        default:       shellWriter.write(key); break;
                    }
                    shellWriter.flush();
                } catch (Exception e) {
                    LogStore.getInstance().append("ERROR", "ExtraKeys", "write failed: " + e);
                }
            });
        }

        // === 终端内设置面板：主题切换 + 字体大小 ===
        String[] themeNames = {"Dark (Default)", "Light", "Solarized Dark", "Monokai", "Dracula", "Nord"};
        Spinner spTheme = findViewById(R.id.spTermTheme);
        SeekBar sbFontSize = findViewById(R.id.sbTermFontSize);

        if (spTheme != null) {
            ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, themeNames);
            themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spTheme.setAdapter(themeAdapter);
            spTheme.setSelection(themeIndex);
            spTheme.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    if (terminalEmulator != null) terminalEmulator.setTheme(pos);
                    getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                            .putInt(KEY_TERM_THEME, pos).apply();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
        }

        if (sbFontSize != null) {
            sbFontSize.setProgress(fontSizeSp - 10);
            sbFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser || terminalView == null) return;
                    int newSp = progress + 10;
                    float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, newSp,
                            getResources().getDisplayMetrics());
                    terminalView.setFontSize((int) px);
                    getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                            .putInt(KEY_TERM_FONT_SIZE_SP, newSp).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // === 多窗口侧栏初始化 ===
        setupSideBar();
        setupWindowPicker();

        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI", "[Terminal] Initialized with real shell");
    }

    // ==================== 多窗口（侧栏）实现 ====================

    /**
     * 初始化侧栏：挂回调、载入窗口列表（首个窗口按当前环境状态定）。
     *
     * <p>侧栏 tab 的语义：每个 tab = 一个独立终端窗口（自己的缓冲与 shell 会话），
     * 索引 0 为当前显示窗口。长按 tab 打开选择页，可给该窗口改环境。
     */
    private void setupSideBar() {
        sideBar = findViewById(R.id.terminal_sidebar);
        if (sideBar == null) return;

        sideBar.setListener(new TerminalSideBar.Listener() {
            @Override public void onSelect(int index) {
                // 收起状态下点 tab：先展开侧栏（便于查看/操作其它 tab）
                if (sideBar.isCollapsed()) {
                    sideBar.setCollapsed(false);
                    resetCollapseTimer();
                    return;
                }
                selectWindow(index);
                resetCollapseTimer();
            }
            @Override public void onAdd() {
                addWindow();
                resetCollapseTimer();
            }
            @Override public void onLongPress(int index) {
                openWindowPicker(index);
            }
        });

        // 载入已保存的窗口列表；没有则建一个默认窗口。
        //
        // 默认只建【一个】Android 窗口，不预建 Linux 环境窗口：
        // 环境窗口应由用户主动"新建 tab"或"切换环境"产生。
        // 已有记录则完整恢复（即"上次关闭前的 tab 布局"）。
        java.util.List<TerminalWindowStore.Item> saved = TerminalWindowStore.list(this);
        if (saved.isEmpty()) {
            TerminalWindowStore.Item it = new TerminalWindowStore.Item(
                    TerminalWindowStore.ENV_ANDROID, TerminalWindowStore.newSeq(this));
            saved = new java.util.ArrayList<>();
            saved.add(it);
            TerminalWindowStore.save(this, saved);
            LogStore.getInstance().debug("UI", "[窗口] 首次启动，初始化默认窗口 env=Android seq=" + it.seq);
        } else {
            LogStore.getInstance().debug("UI", "[窗口] 恢复上次的窗口布局，共 " + saved.size() + " 个");
        }

        for (TerminalWindowStore.Item it : saved) {
            TermWindow w = new TermWindow();
            w.env = it.env;
            // 兜底：历史数据可能缺序号（0），此时补发一个，保证编号可用
            w.seq = (it.seq > 0) ? it.seq : TerminalWindowStore.newSeq(this);
            windows.add(w);
        }
        // 索引 0 的窗口立即启动（其余懒加载）
        activateWindow(0, true);
        refreshSideBar();
        // 启动收起定时器：否则初始化后若用户不操作，侧栏永不收起
        // （表现为"只有一个 tab 时不收起"）。
        resetCollapseTimer();

        // 注册 MCP 的 tab 控制桥（让 MCP 能增/关/切 tab）
        McpServer.setTabBridge(new McpServer.TabBridge() {
            @Override public java.util.List<String> listTabs() {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < windows.size(); i++) {
                    TermWindow w = windows.get(i);
                    out.add(TerminalWindowStore.envLabel(w.env) + "  seq=" + w.seq
                            + (i == 0 ? "  ← 当前" : ""));
                }
                return out;
            }

            @Override public int openTab(String envName) {
                String env = (envName == null || envName.isEmpty())
                        ? TerminalWindowStore.ENV_ANDROID : envName;
                if (TerminalWindowStore.isLinux(env)
                        && !EnvStore.listEnvs(MainActivity.this).contains(env)) {
                    return -1;
                }
                TermWindow w = new TermWindow();
                w.env = env;
                w.seq = TerminalWindowStore.newSeq(MainActivity.this);
                windows.add(w);
                TerminalWindowStore.save(MainActivity.this, envList());
                activateWindow(windows.size() - 1, false);
                refreshSideBar();
                resetCollapseTimer();
                appendUiLog("[窗口] MCP 新建 tab：" + TerminalWindowStore.envLabel(env)
                        + " #" + w.seq);
                return w.seq;
            }

            @Override public boolean closeTab(int seq) {
                for (int i = 0; i < windows.size(); i++) {
                    if (windows.get(i).seq == seq) {
                        closeWindow(i);
                        return true;
                    }
                }
                return false;
            }

            @Override public boolean selectTab(int seq) {
                for (int i = 0; i < windows.size(); i++) {
                    if (windows.get(i).seq == seq) {
                        selectWindow(i);
                        resetCollapseTimer();
                        return true;
                    }
                }
                return false;
            }
        });

        // 注册 MCP 的终端诊断桥（读缓冲状态，排障用）
        McpServer.setTermBridge(() -> {
            if (terminalEmulator == null) return "(终端未初始化)";
            com.termi.app.terminal.TerminalBuffer buf = terminalEmulator.getBuffer();
            StringBuilder sb = new StringBuilder();
            sb.append("窗口数：").append(windows.size())
              .append("，当前窗口 env=")
              .append(windows.isEmpty() ? "?" : windows.get(0).env).append('\n');
            sb.append("缓冲：").append(buf.getColumns()).append(" 列 × ")
              .append(buf.getRows()).append(" 行")
              .append("，总行数=").append(buf.getTotalRows()).append('\n');
            sb.append("光标：row=").append(terminalEmulator.getCursorRow())
              .append(" col=").append(terminalEmulator.getCursorCol()).append('\n');
            sb.append("视口偏移：").append(buf.getViewportOffset())
              .append("（0=贴底）").append('\n');
            sb.append("screenTopRow=").append(buf.getScreenTopRow()).append('\n');
            sb.append("--- 屏幕可见文本 ---\n");
            for (int r = 0; r < buf.getRows(); r++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < buf.getColumns(); c++) {
                    char ch = buf.getCharAt(c, r);
                    line.append(ch == 0 ? ' ' : ch);
                }
                sb.append(String.format(java.util.Locale.US, "%2d| ", r))
                  .append(line.toString().replaceAll("\\s+$", ""))
                  .append('\n');
            }
            // 历史区（屏幕之上）：往上最多 80 行，用于判断内容是否真的被清空
            sb.append("--- 屏幕之上（历史区）---\n");
            for (int back = buf.getRows(); back < buf.getRows() + 80; back++) {
                String h = buf.getLineFromBottom(back);
                if (h == null) break;
                sb.append(String.format(java.util.Locale.US, "↑%d| ", back - buf.getRows() + 1))
                  .append(h).append('\n');
            }
            return sb.toString();
        });
    }

    /** 刷新侧栏标签（环境名；仅同环境多窗口时附加序号）。 */
    private void refreshSideBar() {
        if (sideBar == null) {
            LogStore.getInstance().warn("UI", "[侧栏] refreshSideBar: sideBar 为 null！");
            return;
        }
        java.util.List<TerminalWindowStore.Item> items = new java.util.ArrayList<>();
        for (TermWindow w : windows) {
            items.add(new TerminalWindowStore.Item(w.env, w.seq));
        }
        java.util.List<String> labels = TerminalWindowStore.buildLabels(items);
        LogStore.getInstance().debug("UI", "[侧栏] 设置标签 " + labels
                + "（窗口数=" + windows.size() + "）");
        sideBar.setLabels(labels);
    }

    /** 重设收起定时器：3 秒无操作后收起侧栏（收起为只显示当前 tab）。 */
    private void resetCollapseTimer() {
        if (sideBar == null) return;
        collapseHandler.removeCallbacks(collapseRunnable);
        sideBar.setCollapsed(false);
        // 单个窗口也收起（收起后只留那一个 tab，不占满整列）
        collapseHandler.postDelayed(collapseRunnable, 3000L);
    }

    /**
     * 选中某个窗口：把该窗口移到最前（与当前窗口交换位置），并激活它。
     */
    private void selectWindow(int index) {
        if (index <= 0 || index >= windows.size()) return;
        // 交换位置：把 index 处移到 0
        TermWindow w = windows.remove(index);
        windows.add(0, w);
        TerminalWindowStore.moveToFront(this, index);
        activateWindow(0, false);
        refreshSideBar();
        appendUiLog("[窗口] 切换到 " + TerminalWindowStore.envLabel(w.env) + "（已置顶）");
    }

    /** 新增窗口（默认 Android shell，可用长按改成 Linux 环境）。 */
    private void addWindow() {
        TermWindow w = new TermWindow();
        w.env = TerminalWindowStore.ENV_ANDROID;
        // 分配一个新序号（与 tab 绑定），随后统一由内存列表持久化，
        // 避免"持久化列表"与"内存列表"各自追加导致不同步。
        w.seq = TerminalWindowStore.newSeq(this);
        windows.add(w);
        TerminalWindowStore.save(this, envList());
        activateWindow(windows.size() - 1, false);
        refreshSideBar();
        resetCollapseTimer();
        appendUiLog("[窗口] 新建窗口（Android shell #" + w.seq + "）");
    }

    /** 取当前窗口列表（供持久化）。 */
    private java.util.List<TerminalWindowStore.Item> envList() {
        java.util.List<TerminalWindowStore.Item> out = new java.util.ArrayList<>();
        for (TermWindow w : windows) {
            out.add(new TerminalWindowStore.Item(w.env, w.seq));
        }
        return out;
    }

    /**
     * 激活第 index 个窗口。
     *
     * <p>关键：全局字段 {@code terminalEmulator} 始终代表"当前窗口"，
     * 切换时先把当前窗口的 emulator 存回其 TermWindow，再载入目标窗口的。
     * 未启动过的窗口在此懒加载（首次切到才起进程）。
     *
     * @param firstTime 是否为初始化时的首次激活（此时不需要保存旧窗口状态）
     */
    private void activateWindow(int index, boolean firstTime) {
        if (index < 0 || index >= windows.size()) return;
        final TermWindow target = windows.get(index);

        // 1) 保存当前窗口的 emulator 引用（对象本身一直存活，仅切换视图绑定）
        //    windows 里始终持有各自 emulator，故无需额外保存。

        // 2) 停掉当前正在跑的前台会话（进程保留在旧窗口对象里，
        //    但为简化与稳妥，这里切换时停掉旧会话，切回时重新起——见 §说明）
        if (!firstTime) {
            // 不销毁旧窗口的进程（保留其缓冲）；仅断开与 TerminalView 的绑定。
            // 由于 TerminalView.attach() 会替换渲染器，旧缓冲仍存于其 emulator 中。
        }

        // 3) 建立/载入目标窗口的 emulator
        if (target.emulator == null) {
            target.emulator = new TerminalEmulator(1024, 24, 500);
            int theme = getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_TERM_THEME, 0);
            target.emulator.setTheme(theme);
        }

        // 4) 把全局字段指向目标窗口，并重新 attach 到 TerminalView
        terminalEmulator = target.emulator;
        if (terminalView != null) {
            terminalView.attach(target.emulator);
            // 关键：把键盘输入接到本地行编辑（handleLocalInput）。
            // 该 sink 属于 TerminalView（全局唯一），必须在每次切换窗口时重设，
            // 否则新窗口收不到任何按键（表现为"输入无反应"）。
            terminalView.setOutputSink(data -> runOnUiThread(() -> handleLocalInput(data)));
        }

        // 5) 未启动过的窗口：现在启动其 shell
        if (!target.started) {
            target.started = true;
            startShellForWindow(target);
        } else {
            // 已启动：只把状态同步给 UI（EXIT/LOGIN 按钮等）
            syncModeFromWindow(target);
        }
        refreshSideBar();
    }

    /** 把某窗口的模式同步到全局 UI 状态。 */
    private void syncModeFromWindow(TermWindow w) {
        boolean linux = TerminalWindowStore.isLinux(w.env);
        terminalInLinuxEnv = linux;
        if (extraKeysView != null) {
            extraKeysView.setEnvState(linux);
        }
    }

    /** 为指定窗口启动 shell（不改动当前 UI 绑定，因为 emulator 已切好）。 */
    private void startShellForWindow(TermWindow w) {
        if (TerminalWindowStore.isLinux(w.env)) {
            // Linux 环境：长驻会话
            final TerminalSessionManager mgr = new TerminalSessionManager(this);
            mgr.setListener(new TerminalSessionManager.OutputListener() {
                @Override public void onOutput(String text) {
                    runOnUiThread(() -> {
                        markOutputArrived();      // 真实输出 → 取消"无输出提示"
                        if (w.emulator != null) {
                            w.emulator.append(text.getBytes(StandardCharsets.UTF_8));
                            if (w == windows.get(0) && terminalView != null) {
                                terminalView.postInvalidate();
                            }
                        }
                    });
                }
                @Override public void onEnded(String reason) {
                    runOnUiThread(() -> {
                        if (w.emulator != null) {
                            w.emulator.append(("\r\n\033[33m[会话结束] " + reason + "\033[0m\r\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            if (w == windows.get(0) && terminalView != null) terminalView.postInvalidate();
                        }
                    });
                }
            });
            w.session = mgr;
            writeToWindow(w, "\033[90m正在进入 Linux 环境…\033[0m\r\n");
            new Thread(() -> {
                final boolean ok = mgr.start();
                runOnUiThread(() -> {
                    if (ok) {
                        writeShellBannerTo(w);
                    } else {
                        writeToWindow(w, "\r\n\033[31m无法进入 Linux 环境："
                                + ShizukuFs.getLastError() + "\033[0m\r\n");
                    }
                    // 会话就绪（或失败）后关闭 Tab 栏加载指示条
                    setTabLoading(false, null);
                });
            }, "terx-window-start").start();
        } else {
            // Android shell
            try {
                ProcessBuilder pb = RuntimeManager.buildAndroidShellProcess(
                        RuntimeManager.ensureAndroidEnvRc(this));
                w.process = pb.start();
                w.writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        w.process.getOutputStream(), StandardCharsets.UTF_8));
                final java.io.InputStream in = w.process.getInputStream();
                w.readerThread = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            final byte[] chunk = new byte[n];
                            System.arraycopy(buf, 0, chunk, 0, n);
                            runOnUiThread(() -> {
                                markOutputArrived();      // 有输出 → 取消"无输出提示"
                                if (w.emulator != null) {
                                    w.emulator.append(chunk);
                                    if (w == windows.get(0) && terminalView != null) {
                                        terminalView.postInvalidate();
                                    }
                                }
                            });
                        }
                    } catch (Throwable ignored) {}
                }, "window-reader");
                w.readerThread.setDaemon(true);
                w.readerThread.start();
                writeShellBannerTo(w);
                setTabLoading(false, null);   // Android shell 就绪，关闭加载指示条
            } catch (Throwable t) {
                writeToWindow(w, "\r\n\033[31m无法启动 shell：" + t + "\033[0m\r\n");
                setTabLoading(false, null);   // 失败也要关闭加载指示条
            }
        }
        syncModeFromWindow(w);
    }

    /** 向指定窗口写入文本（不依赖当前视图绑定）。 */
    private void writeToWindow(TermWindow w, String text) {
        if (w == null || w.emulator == null || text == null) return;
        runOnUiThread(() -> {
            w.emulator.append(text.getBytes(StandardCharsets.UTF_8));
            if (!windows.isEmpty() && w == windows.get(0) && terminalView != null) {
                terminalView.postInvalidate();
            }
        });
    }

    /** 写欢迎语到指定窗口。 */
    private void writeShellBannerTo(TermWindow w) {
        boolean linux = TerminalWindowStore.isLinux(w.env);
        String modeLabel = linux ? RuntimeManager.terminalModeLabel(this)
                : "Android Shell (/system/bin/sh)";
        StringBuilder sb = new StringBuilder();
        sb.append("\033[1;32mSTerni Shell Ready (").append(modeLabel).append(")\033[0m\r\n");
        if (linux) {
            sb.append("\033[90m  已进入 Linux 环境。apt/apk 安装的软件重启后仍然可用。\033[0m\r\n");
            sb.append("\033[90m  左下角红色 EXIT 可退出到 Android shell。\033[0m\r\n");
        } else {
            sb.append("\033[90m  当前为 Android 原生 shell。\033[0m\r\n");
            sb.append("\033[90m  右侧侧栏可切换/新建窗口；长按 tab 可改环境。\033[0m\r\n");
        }
        sb.append("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
        sb.append("  \033[1mSTRX\033[0m — Android Local MCP Server\r\n");
        sb.append("  Initial Author: \033[33mKei-os\033[0m\r\n");
        sb.append("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
        writeToWindow(w, sb.toString());
    }

    /** 统一 UI 日志（写入终端当前窗口 + LogStore）。 */
    private void appendUiLog(String s) {
        LogStore.getInstance().debug("UI", s);
        if (terminalEmulator != null) {
            terminalEmulator.append(("\033[90m" + s + "\033[0m\r\n").getBytes(StandardCharsets.UTF_8));
            if (terminalView != null) terminalView.postInvalidate();
        }
    }

    // ==================== 窗口选择页（长按 tab） ====================

    /** 选择页所属的窗口索引（-1 表示未打开）。 */
    private int pickerIndex = -1;

    /** 选择页倒计时：剩余秒数（到 0 自动返回）。 */
    private int pickerRemainSec = 0;
    private final android.os.Handler pickerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pickerTick = new Runnable() {
        @Override public void run() {
            if (pickerIndex < 0) return;
            pickerRemainSec--;
            TextView tv = findViewById(R.id.tvPickerCountdown);
            if (pickerRemainSec <= 0) {
                if (tv != null) tv.setText("即将返回…");
                hideWindowPicker();
                return;
            }
            if (tv != null) {
                tv.setText("无操作 " + pickerRemainSec + " 秒后自动返回");
            }
            pickerHandler.postDelayed(this, 1000L);
        }
    };

    /** 重置选择页倒计时（任何操作都调用）。 */
    private void resetPickerCountdown() {
        pickerHandler.removeCallbacks(pickerTick);
        pickerRemainSec = 3;
        TextView tv = findViewById(R.id.tvPickerCountdown);
        if (tv != null) tv.setText("无操作 " + pickerRemainSec + " 秒后自动返回");
        pickerHandler.postDelayed(pickerTick, 1000L);
    }

    /** 初始化选择页的两个按钮。 */
    private void setupWindowPicker() {
        Button btnSwitch = findViewById(R.id.btnPickerSwitch);
        Button btnClose = findViewById(R.id.btnPickerClose);
        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> showEnvChooser());
        }
        if (btnClose != null) {
            // 「关闭」= 关闭该窗口（删掉这个 tab），而非仅隐藏本页
            btnClose.setOnClickListener(v -> closeWindow(pickerIndex));
        }
    }

    /**
     * 关闭一个窗口（删除对应 tab）。
     *
     * <p>若该窗口是当前显示窗口，关闭后自动切到剩余的第一个窗口；
     * 若关闭后一个窗口都不剩，则自动补一个 Android 窗口，避免"无窗口可用"。
     */
    private void closeWindow(int index) {
        if (index < 0 || index >= windows.size()) {
            hideWindowPicker();
            return;
        }
        final TermWindow w = windows.get(index);
        final String label = TerminalWindowStore.envLabel(w.env);
        final boolean wasCurrent = (index == 0);

        new android.app.AlertDialog.Builder(this)
                .setTitle("关闭窗口")
                .setMessage("确认关闭「" + label + "」窗口？\n\n"
                        + "该窗口的终端内容会丢失（环境本身不会被卸载）。")
                .setNegativeButton("否", null)
                .setPositiveButton("是", (d, x) -> {
                    hideWindowPicker();
                    stopWindow(w);
                    windows.remove(index);
                    appendUiLog("[窗口] 已关闭窗口：" + label);

                    if (windows.isEmpty()) {
                        // 兜底：至少保留一个 Android 窗口
                        TermWindow nw = new TermWindow();
                        nw.env = TerminalWindowStore.ENV_ANDROID;
                        nw.seq = TerminalWindowStore.newSeq(this);
                        windows.add(nw);
                        TerminalWindowStore.save(this, envList());
                        activateWindow(0, false);
                    } else {
                        TerminalWindowStore.save(this, envList());
                        if (wasCurrent) {
                            // 关掉的是当前窗口 → 切到第一个
                            activateWindow(0, false);
                        } else {
                            // 关掉的是后台窗口 → 当前窗口索引前移
                            activateWindow(0, false);
                        }
                    }
                    refreshSideBar();
                    resetCollapseTimer();
                })
                .show();
    }

    /** 打开选择页（古早 Windows 风格：一个大页面 + 两个按钮）。 */
    private void openWindowPicker(int index) {
        if (index < 0 || index >= windows.size()) return;
        pickerIndex = index;
        View page = findViewById(R.id.windowPickerPage);
        if (page == null) return;

        // 吃掉触摸：否则点击会穿透到下层终端/侧栏，导致"关闭关不上"或误触
        page.setClickable(true);
        page.setOnTouchListener((v, e) -> true);

        TermWindow w = windows.get(index);
        TextView title = findViewById(R.id.tvPickerTitle);
        TextView info = findViewById(R.id.tvPickerInfo);
        if (title != null) title.setText("窗口 " + (index + 1));
        if (info != null) {
            String envName = TerminalWindowStore.envLabel(w.env);
            info.setText("当前环境：" + envName
                    + "\n\n点击下方「切换环境」可为该窗口选择其它已导入的环境。");
        }
        page.setVisibility(View.VISIBLE);
        // 任何触摸都重置倒计时（避免"正在操作时被自动返回"）
        page.setOnTouchListener((v, e) -> {
            resetPickerCountdown();
            return true;   // 吃掉触摸，避免穿透到下层
        });
        resetPickerCountdown();
        appendUiLog("[窗口] 打开选择页（窗口 " + (index + 1) + "）");
    }

    /** 关闭选择页。 */
    private void hideWindowPicker() {
        View page = findViewById(R.id.windowPickerPage);
        if (page != null) page.setVisibility(View.GONE);
        pickerIndex = -1;
        pickerHandler.removeCallbacks(pickerTick);   // 停掉倒计时
    }

    /** 弹出下拉框：列出所有已导入环境 + Android shell 供选择。 */
    private void showEnvChooser() {
        final java.util.List<String> envs = EnvStore.listEnvs(this);
        java.util.List<String> options = new java.util.ArrayList<>();
        options.add("Android shell");
        for (String e : envs) options.add(e);

        new android.app.AlertDialog.Builder(this)
                .setTitle("选择环境")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    String chosen = (which == 0)
                            ? TerminalWindowStore.ENV_ANDROID
                            : envs.get(which - 1);
                    changeWindowEnv(chosen);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 把选择页所属窗口的环境改为 chosen。
     *
     * <p>若目标是当前显示窗口（索引 0），直接复用 {@link #switchCurrentWindowEnv}，
     * 以共享"加载指示条 + 会话重建"逻辑；否则仅改环境，等切到它时再启动。
     */
    private void changeWindowEnv(String chosen) {
        if (pickerIndex < 0 || pickerIndex >= windows.size()) return;
        TermWindow w = windows.get(pickerIndex);
        final String old = w.env;
        if (chosen.equals(old)) {
            appendUiLog("[窗口] 环境未变：" + TerminalWindowStore.envLabel(chosen));
            hideWindowPicker();
            return;
        }

        hideWindowPicker();

        // 当前显示窗口：走统一入口（含加载指示条）
        if (pickerIndex == 0) {
            switchCurrentWindowEnv(chosen);
            return;
        }

        // 非当前窗口：停掉旧会话，改环境，等用户切过去时再启动
        stopWindow(w);
        w.env = chosen;
        w.started = false;
        w.emulator = null;
        TerminalWindowStore.save(this, envList());
        appendUiLog("[窗口] 环境切换：" + TerminalWindowStore.envLabel(old)
                + " → " + TerminalWindowStore.envLabel(chosen));
        refreshSideBar();
    }

    /** 停止一个窗口的会话与进程。 */
    private void stopWindow(TermWindow w) {
        if (w == null) return;
        try {
            if (w.session != null) {
                w.session.stop();
                w.session = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (w.writer != null) {
                w.writer.close();
                w.writer = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (w.process != null) {
                w.process.destroyForcibly();
                w.process = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (w.readerThread != null) {
                w.readerThread.interrupt();
                w.readerThread = null;
            }
        } catch (Throwable ignored) {}
        w.started = false;
    }

    /** Tab 栏底部的加载指示条（indeterminate，自带动画）。 */
    private android.widget.ProgressBar pbTabLoading;

    /**
     * 控制 Tab 栏底部加载指示条的显示。
     *
     * <p>方案说明：Material 的 TabLayout 指示条并非独立 View
     * （在 {@code SlidingTabIndicator.draw()} 中绘制），无法单独驱动动画；
     * 而反射拿到的 {@code slidingTabIndicator} 是装载所有 Tab 的容器，
     * 缩放它会连文字一起缩放。故改用"紧贴 Tab 栏底边的 indeterminate 进度条"，
     * 视觉上与选中指示条同一条线，且自带往返滑动动画。
     *
     * @param show true 显示，false 隐藏
     */
    private void setTabLoading(boolean show, String text) {
        runOnUiThread(() -> {
            try {
                if (pbTabLoading == null) {
                    pbTabLoading = findViewById(R.id.pbTabLoading);
                }
                if (pbTabLoading != null) {
                    pbTabLoading.setVisibility(show ? android.view.View.VISIBLE
                            : android.view.View.GONE);
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * 启动（或重启）终端 shell 进程。
     *
     * @param mode {@link #SHELL_MODE_ANDROID} 或 {@link #SHELL_MODE_LINUX}
     */
    private void startShell(int mode) {
        // 先停掉旧进程，避免泄漏
        stopShellQuietly();

        // 代际 +1：使所有旧读取线程的回调失效
        final int gen = ++shellGeneration;

        terminalInLinuxEnv = (mode == SHELL_MODE_LINUX);
        shellLaunchedAsLinux = terminalInLinuxEnv;
        if (extraKeysView != null) {
            extraKeysView.setEnvState(terminalInLinuxEnv);
        }

        try {
            final int startedMode = mode;

            if (mode == SHELL_MODE_LINUX && RuntimeManager.isEnvUsable(this)) {
                // 【Linux 模式】走文件通道的长驻会话。
                //
                // 为何不用 Shizuku 的 Process 流：实测 Shizuku.newProcess 返回的 Process，
                // 其 getInputStream() 对长驻交互进程读不到任何数据（短命令可以）。
                // 故改用 cmd.txt / out.txt 两个文件通信，见 TerminalSessionManager。
                //
                // 启动会话涉及多次 Shizuku 调用（含 sleep 等待），必须放到后台线程，
                // 否则阻塞主线程会导致 Tab 栏的 loading 动画卡住不转。
                writeTerminal("\033[90m正在进入 Linux 环境…\033[0m\r\n");
                setTabLoading(true, "载入中…");
                LogStore.getInstance().debug("UI", "[Terminal] 启动长驻 Linux 会话…");
                new Thread(() -> {
                    final TerminalSessionManager mgr = new TerminalSessionManager(this);
                    mgr.setListener(new TerminalSessionManager.OutputListener() {
                        @Override public void onOutput(String text) {
                            if (terminalEmulator != null) {
                                terminalEmulator.append(text.getBytes(StandardCharsets.UTF_8));
                                if (terminalView != null) terminalView.postInvalidate();
                            }
                        }
                        @Override public void onEnded(String reason) {
                            runOnUiThread(() -> {
                                if (gen == shellGeneration) {
                                    writeTerminal("\r\n\033[33m[会话结束] " + reason + "\033[0m\r\n");
                                }
                            });
                        }
                    });
                    final boolean ok = mgr.start();
                    runOnUiThread(() -> {
                        if (gen != shellGeneration) {
                            // 期间已切换到别的模式，丢弃本次结果
                            mgr.stop();
                            return;
                        }
                        if (!ok) {
                            setTabLoading(false, null);
                            writeTerminal("\r\n\033[31m无法进入 Linux 环境："
                                    + ShizukuFs.getLastError() + "\033[0m\r\n");
                            // 回落 Android shell，保证终端可用
                            startShell(SHELL_MODE_ANDROID);
                            return;
                        }
                        sessionManager = mgr;
                        shellWriter = null;
                        terminalView.setOutputSink(data -> runOnUiThread(() -> handleLocalInput(data)));
                        setTabLoading(false, null);
                        writeShellBanner(SHELL_MODE_LINUX);
                        LogStore.getInstance().info("UI", "[Terminal] startShell 完成 mode=" + mode);
                    });
                }, "terx-session-start").start();
                return;
            }

            // 【Android 模式】本地进程（原有逻辑不变）
            ProcessBuilder pb = RuntimeManager.buildAndroidShellProcess(
                    RuntimeManager.ensureAndroidEnvRc(this));
            shellProcess = pb.start();
            shellWriter = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            shellProcess.getOutputStream(), StandardCharsets.UTF_8));
            LogStore.getInstance().info("UI", "[Terminal] shellWriter 就绪");

            // IME/软键盘输入统一走本地行编辑（shell 无 tty，不回显、不处理退格）
            terminalView.setOutputSink(data -> runOnUiThread(() -> handleLocalInput(data)));

            final java.io.InputStream in = shellProcess.getInputStream();
            shellReaderThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    // 逐块读取原始字节（不用 readLine）：
                    // readLine 只在遇到换行才返回，而提示符（如 "[TERX] ~ # "）不含换行，
                    // 会被一直缓冲，直到下一条命令产生输出才吐出，
                    // 表现为"提示符跑到输入后面"。
                    while ((n = in.read(buf)) > 0) {
                        final byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        runOnUiThread(() -> {
                            if (terminalEmulator != null) {
                                terminalEmulator.append(chunk);
                                if (terminalView != null) terminalView.postInvalidate();
                            }
                        });
                    }
                    // shell 退出（EOF）：仅在仍是当前代时才处理
                    runOnUiThread(() -> {
                        if (gen == shellGeneration) onShellExited(startedMode);
                    });
                } catch (Exception e) {
                    LogStore.getInstance().append(LogStore.LEVEL_ERROR, "Terminal",
                            "Read loop error: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (gen == shellGeneration) onShellExited(startedMode);
                    });
                }
            }, "shell-reader");
            shellReaderThread.setDaemon(true);
            shellReaderThread.start();

            LogStore.getInstance().info("UI", "[Terminal] 读取线程已启动，准备写欢迎语");
            // Android 模式已就绪，关闭 Tab 栏 loading
            setTabLoading(false, null);
            try {
                writeShellBanner(mode);
            } catch (Throwable bt) {
                LogStore.getInstance().error("UI", "[Terminal] 欢迎语异常: " + bt);
            }
            LogStore.getInstance().info("UI", "[Terminal] startShell 完成 mode=" + mode);
        } catch (Exception e) {
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "UI",
                    "[Terminal] Failed to start shell: " + e);
            setTabLoading(false, null);
            writeTerminal("\r\n\033[1;31mERROR: 无法启动 shell：" + e.getMessage() + "\033[0m\r\n");
        }
    }

    /** 写欢迎语（模式与发行版均为真实值）。 */
    private void writeShellBanner(int mode) {
        final String modeLabel = (mode == SHELL_MODE_LINUX)
                ? RuntimeManager.terminalModeLabel(this)
                : "Android Shell (/system/bin/sh)";
        writeTerminal("\033[1;32mSTerni Shell Ready (" + modeLabel + ")\033[0m\r\n");
        if (mode == SHELL_MODE_LINUX) {
            writeTerminal("\033[90m  已进入 Linux 环境。apt/apk 安装的软件重启后仍然可用。\033[0m\r\n");
            writeTerminal("\033[90m  左下角红色 EXIT 可退出到 Android shell。\033[0m\r\n");
        } else {
            if (RuntimeManager.isEnvUsable(this)) {
                writeTerminal("\033[90m  当前为 Android 原生 shell。\033[0m\r\n");
                writeTerminal("\033[90m  左下角蓝色 LOGIN 可进入 Linux 环境。\033[0m\r\n");
            } else {
                writeTerminal("\033[90m  当前为 Android 原生 shell（未安装 Linux 环境）。\033[0m\r\n");
                writeTerminal("\033[90m  到「环境」标签页导入环境包以启用持久化。\033[0m\r\n");
            }
        }
        writeTerminal("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
        writeTerminal("  \033[1mSTRX\033[0m — Android Local MCP Server\r\n");
        writeTerminal("  Initial Author: \033[33mKei-os\033[0m\r\n");
        writeTerminal("  Repository: \033[4;94mhttps://github.com/yeaaeslysl/TRML-TERX\033[0m\r\n");
        writeTerminal("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
        writeTerminal("  No official group chat is provided.\r\n");
        writeTerminal("  For compatibility or adaptation issues,\r\n");
        writeTerminal("    please fork the repository and resolve\r\n");
        writeTerminal("    them independently when possible.\r\n");
        writeTerminal("  Bug reports may be submitted via GitHub\r\n");
        writeTerminal("    Issues at your discretion.\r\n");
        writeTerminal("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
    }

    /**
     * shell 进程结束时的处理。
     *
     * <p>修复"exit 后终端静默无反应"：给出明确提示，并自动回到 Android shell
     * （若退出的是 Linux 环境）或提示可重新进入。
     */
    private void onShellExited(int exitedMode) {
        if (isFinishing() || isDestroyed()) return;

        // 关键：仅当"退出的模式"仍是当前模式时才处理。
        // 否则说明这是被替换掉的旧 shell 的迟到回调，
        // 若据此切换模式会把刚启动的新会话打回去。
        boolean stillCurrent = (exitedMode == SHELL_MODE_LINUX && sessionManager != null)
                || (exitedMode == SHELL_MODE_ANDROID && shellProcess != null);
        if (!stillCurrent) {
            LogStore.getInstance().info("UI", "[Terminal] 忽略过期的退出回调 mode=" + exitedMode);
            return;
        }

        if (exitedMode == SHELL_MODE_LINUX) {
            writeTerminal("\r\n\033[33m[已退出 Linux 环境]\033[0m\r\n");
            // 自动回落到 Android shell，保证终端始终可用
            startShell(SHELL_MODE_ANDROID);
        } else {
            writeTerminal("\r\n\033[33m[shell 已退出]\033[0m\r\n");
            writeTerminal("\033[90m  点左下角按钮可重新进入。\033[0m\r\n");
            if (extraKeysView != null) {
                extraKeysView.setEnvState(false);
            }
            shellWriter = null;
        }
    }

    /** 环境切换键处理（操作<b>当前窗口</b>：EXIT 退出到 Android，LOGIN 进入 Linux）。 */
    private void onEnvToggleKey(boolean isExitKey) {
        if (windows.isEmpty()) return;
        if (isExitKey) {
            // 红色 EXIT：当前窗口退出 Linux 环境 → Android shell
            if (!TerminalWindowStore.isLinux(windows.get(0).env)) return;
            writeTerminal("\r\n\033[90m正在退出 Linux 环境…\033[0m\r\n");
            switchCurrentWindowEnv(TerminalWindowStore.ENV_ANDROID);
        } else {
            // 蓝色 LOGIN：当前窗口进入 Linux 环境
            if (TerminalWindowStore.isLinux(windows.get(0).env)) return;
            if (!RuntimeManager.isEnvUsable(this)) {
                writeTerminal("\r\n\033[31m未安装 Linux 环境，无法进入。\033[0m\r\n");
                writeTerminal("\033[90m  请到「环境」标签页导入环境包。\033[0m\r\n");
                return;
            }
            switchCurrentWindowEnv(EnvStore.getActiveEnv(this));
        }
    }

    /** 当前窗口的环境名。 */
    private String currentWindowEnv() {
        return windows.isEmpty() ? TerminalWindowStore.ENV_ANDROID : windows.get(0).env;
    }

    /**
     * 把<b>当前窗口</b>的环境切换为 newEnv。
     *
     * <p>这是"窗口换环境"的唯一入口：EXIT / LOGIN / 选择页都走这里。
     * 做法：停掉该窗口现有会话与进程，清空其缓冲，按新环境重启，
     * 并同步更新侧栏标签（tab 名随环境变化）与持久化记录。
     */
    private void switchCurrentWindowEnv(String newEnv) {
        if (windows.isEmpty()) return;
        TermWindow w = windows.get(0);
        if (newEnv == null || newEnv.equals(w.env)) return;

        String old = w.env;
        stopWindow(w);
        w.env = newEnv;
        w.emulator = null;      // 清空旧输出
        w.started = false;

        // 切换环境需要重新启动 shell（Linux 约 2~3 秒），期间显示 Tab 栏加载指示条，
        // 待新 shell 就绪后由 startShellForWindow 关闭。
        if (TerminalWindowStore.isLinux(newEnv)) {
            setTabLoading(true, "载入中…");
        } else {
            setTabLoading(true, "退出中…");
        }

        // 持久化（序号随 tab 保留）
        TerminalWindowStore.save(this, envList());
        appendUiLog("[窗口] 环境切换：" + TerminalWindowStore.envLabel(old)
                + " → " + TerminalWindowStore.envLabel(newEnv));

        // 重建当前窗口并启动，同时刷新侧栏标签
        activateWindow(0, false);
        refreshSideBar();
        resetCollapseTimer();
    }

    /** 静默停止旧 shell 进程。 */
    private void stopShellQuietly() {
        try {
            // 长驻会话（Linux 模式）
            if (sessionManager != null) {
                try { sessionManager.stop(); } catch (Throwable ignored) {}
                sessionManager = null;
            }
            if (shellWriter != null) {
                try { shellWriter.close(); } catch (Throwable ignored) {}
                shellWriter = null;
            }
            if (shellProcess != null) {
                try { shellProcess.destroyForcibly(); } catch (Throwable ignored) {}
                shellProcess = null;
            }
            if (shellReaderThread != null) {
                shellReaderThread.interrupt();
                shellReaderThread = null;
            }
        } catch (Throwable ignored) {}
    }

    /** 向终端写入文本（直接喂给 emulator 显示）。 */
    public void writeTerminal(String text) {
        if (terminalEmulator != null && text != null) {
            terminalEmulator.append(text.getBytes(StandardCharsets.UTF_8));
            // 强制触发 View 重绘，确保 MCP 回显等非 shell-reader 来源的写入也能即时可见
            if (terminalView != null) terminalView.postInvalidate();
        }
    }

    /**
     * 写入"命令产生的输出"，并标记"有输出"（用于取消误报的"无输出提示"）。
     *
     * <p>与 {@link #writeTerminal} 的区别：本方法用于 shell 真实输出，
     * 会更新 {@code lastOutputMs}；而命令提交时的换行、本地回显等
     * 不应算作"命令有输出"。
     */
    public void writeCommandOutput(String text) {
        markOutputArrived();
        writeTerminal(text);
    }

    // ─── 本地行编辑（shell 无 tty，无法自行回显/退格，由 App 侧完成） ───

    /** 本地行缓冲：累积用户输入，回车时整行交给 shell。 */
    private final StringBuilder mLineBuffer = new StringBuilder();

    /**
     * 统一输入入口。可打印字符本地回显并缓存；退格本地擦除；回车整行提交给 shell。
     * 原因：ProcessBuilder("/system/bin/sh","-i") 无 tty，shell 不回显、不处理逐字符退格，
     * 因此回显与行编辑必须在 App 侧实现，且只能整行提交。
     */
    private void handleLocalInput(byte[] data) {
        if (data == null || data.length == 0) return;
        String s = new String(data, StandardCharsets.UTF_8);
        LogStore.getInstance().debug("UI", "[输入] 收到 " + data.length + " 字节: "
                + s.replace("\r", "\\r").replace("\n", "\\n"));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                submitLine();
            } else if (c == 0x7f || c == '\b') {
                localBackspace();
            } else if (c == '\t') {
                mLineBuffer.append('\t');
                writeTerminal("\t");
            } else if (c >= 0x20) {
                mLineBuffer.append(c);
                writeTerminal(String.valueOf(c));
            }
        }
    }

    /** 退格：删除行缓冲最后一个字符，并擦除屏幕上的对应字符。 */
    private void localBackspace() {
        if (mLineBuffer.length() == 0) return;
        mLineBuffer.deleteCharAt(mLineBuffer.length() - 1);
        if (terminalEmulator != null) terminalEmulator.eraseChar();
        if (terminalView != null) terminalView.postInvalidate();
    }

    /** 回车：把整行一次性写入<b>当前窗口</b>的 shell。 */
    private void submitLine() {
        String line = mLineBuffer.toString();
        mLineBuffer.setLength(0);
        writeTerminal("\r\n");
        LogStore.getInstance().debug("UI", "[Terminal] submitLine 被调用, 内容=[" + line + "]"
                + " 窗口数=" + windows.size());

        if (windows.isEmpty()) return;
        final TermWindow w = windows.get(0);   // 索引 0 = 当前窗口

        // 空行：不发送，也不提示（避免误触发）
        if (line.trim().isEmpty()) return;

        // 记录本次提交时间，用于"无输出提示"（见 scheduleSilentHint）
        final long mySubmitMs = System.currentTimeMillis();
        lastSubmitMs = mySubmitMs;
        lastOutputMs = mySubmitMs;

        // Linux 环境：走文件通道的长驻会话。
        // 注意：只要 session 存在就交给它，不要先判断 isRunning()——
        // 会话启动需 2~3 秒（多次 Shizuku 调用），此期间 isRunning() 仍为 false，
        // 若在此拦掉，"LOGIN 后第一条命令"会被静默丢弃。
        // 未就绪时的入队与补发由 TerminalSessionManager 负责。
        if (w.session != null) {
            final TerminalSessionManager mgr = w.session;
            LogStore.getInstance().debug("UI", "[Terminal] 提交到 Linux 会话: [" + line + "]");
            new Thread(() -> {
                if (!mgr.sendCommand(line)) {
                    LogStore.getInstance().error("UI",
                            "[Terminal] 发送命令失败: [" + line + "]");
                }
            }, "terx-send").start();
            scheduleSilentHint(mySubmitMs);
            return;
        }

        // Android shell：直接写该窗口进程的 stdin
        if (w.writer != null) {
            try {
                w.writer.write(line);
                w.writer.write("\n");
                w.writer.flush();
                LogStore.getInstance().debug("UI", "[Terminal] 已提交命令: [" + line + "]");
                scheduleSilentHint(mySubmitMs);
            } catch (Exception e) {
                LogStore.getInstance().append(LogStore.LEVEL_ERROR, "Terminal",
                        "submitLine failed: " + e);
            }
        } else {
            LogStore.getInstance().warn("UI", "[Terminal] 当前窗口无可用 shell: ["
                    + line + "]");
        }
    }

    /** 最近一次命令提交时间 / 最近一次终端有新内容的时间。 */
    private volatile long lastSubmitMs = 0L;
    private volatile long lastOutputMs = 0L;

    /**
     * 命令提交后若 1.5 秒内终端没有任何新内容，补一行提示。
     *
     * <p>原因：部分命令本身无输出（如 <code>ls</code> 遇到空目录、<code>cd</code>、
     * 成功的 <code>true</code> 等），终端上只多了一个提示符，用户会误以为"没反应"。
     *
     * <p>判定依据是"提交之后终端是否出现过新内容"（用时间戳比较），
     * 而不是"是否走了某条特定的输出回调" —— 因为命令输出可能来自
     * shell 读取线程，也可能来自本地回显，两条路径都算"有输出"。
     */
    private void scheduleSilentHint(final long submitMs) {
        if (terminalView == null) return;
        terminalView.postDelayed(() -> {
            if (submitMs != lastSubmitMs) return;      // 已有更新的命令，跳过
            if (lastOutputMs > submitMs) return;       // 提交后有新内容 → 不说"无输出"
            writeTerminal("\033[90m（命令已执行，无输出）\033[0m\r\n");
        }, 1500L);
    }

    /** 终端出现新内容时调用（任何写入路径），用于取消"无输出提示"。 */
    private void markOutputArrived() {
        lastOutputMs = System.currentTimeMillis();
    }

    /**
     * 运行时热更新终端设置。
     * @param themeIndex 主题索引 (0=default/dark, 1=light, etc.)
     * @param fontSizeSp 字体大小 (sp)
     */
    public void updateTerminalSettings(int themeIndex, int fontSizeSp) {
        // 1. 持久化
        SharedPreferences uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        uiPrefs.edit()
                .putInt(KEY_TERM_THEME, themeIndex)
                .putInt(KEY_TERM_FONT_SIZE_SP, fontSizeSp)
                .apply();

        // 2. 更新 Emulator 主题 (内部会触发 setFullRedrawNeeded)
        if (terminalEmulator != null) {
            terminalEmulator.setTheme(themeIndex);
        }

        // 3. 更新 View 字体
        if (terminalView != null) {
            int fontSizePx = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, fontSizeSp, getResources().getDisplayMetrics());
            terminalView.setFontSize(fontSizePx);
        }

        // 4. 兜底强制重绘——确保控制面板切主题时 View 一定刷新
        if (terminalView != null) {
            terminalView.postInvalidate();
        }

        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                "[Terminal] settings updated: theme=" + themeIndex + ", font=" + fontSizeSp + "sp");
    }

    /**
     * 将自定义前景/背景色应用到控制面板的按钮、EditText、背景等 UI 元素。
     */
    private void applyControlPanelTheme(int fgColor, int bgColor) {
        ScrollView scrollView = findViewById(R.id.control_scroll);
        if (scrollView != null) scrollView.setBackgroundColor(bgColor);

        int[] buttonIds = {
            R.id.btnShizuku, R.id.btnOverlayPermission, R.id.btnFloatingToggle,
            R.id.btnScaleSmall, R.id.btnScaleMedium, R.id.btnScaleLarge,
            R.id.btnToggle, R.id.btnTest, R.id.btnTestDeep, R.id.btnCancelTest,
            R.id.btnClearLog, R.id.btnFlushLog, R.id.btnExportLog,
            R.id.btnApplyCustomTheme
        };
        for (int id : buttonIds) {
            android.widget.Button btn = findViewById(id);
            if (btn != null) {
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(fgColor));
                int r = (fgColor >> 16) & 0xFF;
                int g = (fgColor >> 8) & 0xFF;
                int b = fgColor & 0xFF;
                float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                btn.setTextColor(lum > 0.5f ? 0xFF000000 : 0xFFFFFFFF);
            }
        }

        int[] editIds = {R.id.etHost, R.id.etPort, R.id.etToken, R.id.etCustomFg, R.id.etCustomBg};
        for (int id : editIds) {
            android.widget.EditText et = findViewById(id);
            if (et != null) {
                et.setTextColor(fgColor);
                et.setHintTextColor((fgColor & 0x00FFFFFF) | 0x80000000);
            }
        }

        int[] textIds = {R.id.tvTitle, R.id.tvShizukuStatus, R.id.tvMcpState, R.id.tvTestHint, R.id.tvScaleValue};
        for (int id : textIds) {
            android.widget.TextView tv = findViewById(id);
            if (tv != null) tv.setTextColor(fgColor);
        }

        android.widget.TextView tvLog = findViewById(R.id.tvLog);
        if (tvLog != null) {
            tvLog.setBackgroundColor(bgColor);
            tvLog.setTextColor(fgColor);
        }

        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                .putInt("control_fg", fgColor)
                .putInt("control_bg", bgColor)
                .apply();
        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                "[Theme] Control panel theme changed: fg=#" + String.format("%06X", fgColor & 0xFFFFFF) + ", bg=#" + String.format("%06X", bgColor & 0xFFFFFF));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogStore.getInstance().debug("UI", "[生命周期] onResume");
        updateShizukuStatus();
        refreshToggle();
        refreshFloatingToggle();
        // 回到前台时恢复悬浮球（若已授予权限）
        if (Settings.canDrawOverlays(this) && !FloatingWindow.isShowing()) {
            FloatingWindow.get(this).show();
            refreshFloatingToggle();
        }
        // 环境页状态刷新（可能在外部发生了变化）
        if (envPage != null) envPage.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogStore.getInstance().debug("UI", "[生命周期] onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogStore.getInstance().debug("UI", "[生命周期] onStop");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 转发 SAF 选择结果给环境页
        if (envPage != null) {
            envPage.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener);
        McpService.removeStateListener(mcpStateListener);
        LogStore.getInstance().removeListener(logListener);
        
        // Clean up Real Shell Process
        if (shellWriter != null) {
            try { shellWriter.close(); } catch (Exception ignored) {}
            shellWriter = null;
        }
        if (shellProcess != null) {
            // 注意：Shizuku 的 Process 实现在某些状态下会抛 IllegalArgumentException，
            // 必须用 Throwable 兜住，否则 onDestroy 崩溃。
            try { shellProcess.destroy(); } catch (Throwable ignored) {}
            shellProcess = null;
        }
        if (shellReaderThread != null) {
            shellReaderThread.interrupt();
            shellReaderThread = null;
        }
        
        // Legacy cleanup (if any remaining references)
        if (terminalSession != null) {
            terminalSession.stop();
            terminalSession = null;
        }
        try {
            if (ptyOutput != null) {
                ptyOutput.close();
                ptyOutput = null;
            }
        } catch (Exception ignored) {}
        
        super.onDestroy();
    }

    private void setupFloatingScaleControls(SharedPreferences prefs) {
        SeekBar sbScale = findViewById(R.id.sbScale);
        TextView tvScaleValue = findViewById(R.id.tvScaleValue);
        Button btnSmall = findViewById(R.id.btnScaleSmall);
        Button btnMedium = findViewById(R.id.btnScaleMedium);
        Button btnLarge = findViewById(R.id.btnScaleLarge);
        if (sbScale == null || tvScaleValue == null
                || btnSmall == null || btnMedium == null || btnLarge == null) {
            return;
        }

        final int[] presetProgress = {0, 70, 85, 100};
        boolean[] suppress = {false};

        float cur = prefs.getFloat(FloatingWindow.KEY_FLOATING_SCALE,
                FloatingWindow.DEFAULT_SCALE);
        int curProgress = Math.round(cur * 100f);
        curProgress = Math.max(30, Math.min(120, curProgress));
        sbScale.setMax(90);
        sbScale.setProgress(curProgress - 30);
        tvScaleValue.setText("当前：" + curProgress + "%");

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = 30 + progress;
                tvScaleValue.setText("当前：" + percent + "%");
                if (suppress[0]) return;
                applyScale(percent);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnSmall.setOnClickListener(v -> selectPreset(70, sbScale, tvScaleValue));
        btnMedium.setOnClickListener(v -> selectPreset(85, sbScale, tvScaleValue));
        btnLarge.setOnClickListener(v -> selectPreset(100, sbScale, tvScaleValue));
    }

    private void selectPreset(int percent, SeekBar sbScale, TextView tvScaleValue) {
        int progress = percent - 30;
        progress = Math.max(0, Math.min(90, progress));
        sbScale.setProgress(progress);
        tvScaleValue.setText("当前：" + percent + "%");
        applyScale(percent);
    }

    private void applyScale(int percent) {
        float scale = percent / 100f;
        SharedPreferences prefs = getSharedPreferences(FloatingWindow.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putFloat(FloatingWindow.KEY_FLOATING_SCALE, scale).apply();
        FloatingWindow.get(this).setScale(scale);
    }

    private void updateShizukuStatus() {
        boolean ready = ShellExecutor.isShizukuReady();
        boolean granted = ShellExecutor.hasPermission();
        String text;
        if (!ready) text = "Shizuku: 未运行";
        else if (granted) text = "Shizuku: 已授权";
        else text = "Shizuku: 未授权";
        tvStatus.setText(text);

        // 已授权时隐藏"请求 Shizuku 权限"按钮：状态文案已说明，再留按钮会显得自相矛盾
        Button btnShizuku = findViewById(R.id.btnShizuku);
        if (btnShizuku != null) {
            btnShizuku.setVisibility(granted ? View.GONE : View.VISIBLE);
        }

        // 悬浮窗权限：已授予时隐藏"申请悬浮窗权限"，只保留"显示/隐藏悬浮球"
        Button btnOverlay = findViewById(R.id.btnOverlayPermission);
        if (btnOverlay != null) {
            boolean hasOverlay = Settings.canDrawOverlays(this);
            btnOverlay.setVisibility(hasOverlay ? View.GONE : View.VISIBLE);
        }
    }

    private void refreshToggle() {
        SharedPreferences prefs = getSharedPreferences(PREFS_TEXT, MODE_PRIVATE);
        String startText = prefs.getString("btn_toggle_start", "启动 MCP Server");
        String stopText = prefs.getString("btn_toggle_stop", "停止 MCP Server");
        btnToggle.setText(McpService.running ? stopText : startText);
    }

    private void refreshFloatingToggle() {
        Button btn = findViewById(R.id.btnFloatingToggle);
        if (btn != null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_TEXT, MODE_PRIVATE);
            String showText = prefs.getString("btn_floating_toggle_show", "显示悬浮球");
            String hideText = prefs.getString("btn_floating_toggle_hide", "隐藏悬浮球");
            btn.setText(FloatingWindow.isShowing() ? hideText : showText);
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) {
            appendLog("Shizuku 未运行");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            appendLog("已授权");
            return;
        }
        Shizuku.requestPermission(0);
    }

    private volatile ShellExecutor.Handle activeShellHandle;
    private Button btnTestSelfCheck;
    private TextView tvTestHint;
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long testDeadlineMs = 0L;
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (activeShellHandle == null) return;
            long left = Math.max(0, (testDeadlineMs - System.currentTimeMillis()) / 1000);
            if (tvTestHint != null) {
                tvTestHint.setText("执行中… 剩余 " + left + " 秒，可点击「取消」终止");
            }
            if (left > 0) uiHandler.postDelayed(this, 1000L);
        }
    };

    /** 按钮：轻量自检。只探测 Shizuku binder 状态，不执行命令、不触发冷启动。 */
    private void runSelfCheck() {
        if (activeShellHandle != null) {
            Toast.makeText(this, "已有自检在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tvTestHint == null) {
            Toast.makeText(this, "测试提示控件缺失，请检查布局", Toast.LENGTH_SHORT).show();
            return;
        }
        tvTestHint.setText("轻量自检：仅读取 Shizuku binder 状态，约 1 秒内返回。");
        activeShellHandle = ShellExecutor.execAsync("__probe__", null, 5L, false, r -> uiHandler.post(() -> {
            onSelfCheckFinished("轻量自检", r);
        }));
        startCountdown(5);
    }

    /** 深度检测：真正执行 id; uname -a，会触发 Shizuku 冷启动，可能耗时数十秒。 */
    private void runDeepCheck() {
        if (activeShellHandle != null) {
            Toast.makeText(this, "已有检测在执行", Toast.LENGTH_SHORT).show();
            return;
        }
        // 前置校验：布局分支导致控件缺失时直接提示，不做后续 shell 调用，避免 NPE
        if (tvTestHint == null || cbRoot == null) {
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "UI",
                    "[UI] runDeepCheck 控件缺失: tvTestHint=" + (tvTestHint != null)
                            + " cbRoot=" + (cbRoot != null));
            Toast.makeText(this, "界面控件缺失，无法执行深度检测", Toast.LENGTH_SHORT).show();
            return;
        }
        final long timeoutSec = 30L;
        tvTestHint.setText("深度检测：将执行 id; uname -a，超时 " + timeoutSec
                + " 秒；Shizuku 冷启动时可能接近该耗时，可随时取消。");
        activeShellHandle = ShellExecutor.execAsync("id; uname -a", null, timeoutSec,
                cbRoot.isChecked(), r -> uiHandler.post(() -> onSelfCheckFinished("深度检测", r)));
        startCountdown(timeoutSec);
    }

    private void startCountdown(long timeoutSec) {
        testDeadlineMs = System.currentTimeMillis() + timeoutSec * 1000L;
        if (btnTestSelfCheck != null) btnTestSelfCheck.setEnabled(false);
        uiHandler.removeCallbacks(countdown);
        uiHandler.post(countdown);
    }

    private void cancelSelfCheck() {
        ShellExecutor.Handle h = activeShellHandle;
        if (h == null) {
            Toast.makeText(this, "当前没有正在执行的检测", Toast.LENGTH_SHORT).show();
            return;
        }
        h.cancel();
        appendLog("已请求取消，等待子进程终止…");
    }

    private void onSelfCheckFinished(String label, ShellExecutor.Result r) {
        activeShellHandle = null;
        uiHandler.removeCallbacks(countdown);
        if (btnTestSelfCheck != null) btnTestSelfCheck.setEnabled(true);
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("结束：");
        if (r.cancelled) sb.append("已取消");
        else if (r.timedOut) sb.append("超时(").append(r.costMs).append("ms)");
        else if (r.exitCode == 0) sb.append("成功(").append(r.costMs).append("ms)");
        else sb.append("失败 exit=").append(r.exitCode).append("(").append(r.costMs).append("ms)");
        if (r.truncated) sb.append("，输出已截断");
        if (tvTestHint != null) tvTestHint.setText(sb.toString());
        appendLog(sb.toString() + "\n" + r.stdout + r.stderr);
    }

    private void appendLog(String s) {
        tvLog.append(s + "\n");
    }

    /** 按当前筛选级别重绘日志视图（内存日志，不截断、不丢历史）。 */
    private void rerenderLog() {
        StringBuilder sb = new StringBuilder();
        List<LogStore.Entry> all = LogStore.getInstance().snapshot();
        int minLevel = currentMinLevel();
        for (LogStore.Entry e : all) {
            if (levelRank(e.level) >= minLevel) {
                sb.append(e.format()).append('\n');
            }
        }
        int cut = sb.length() - 4000;
        if (cut > 0) {
            tvLog.setText(sb.substring(cut));
        } else {
            tvLog.setText(sb.toString());
        }
    }

    /** 把下拉位置映射为最低日志级别（数值越大越严重）。 */
    private int currentMinLevel() {
        int pos = spLevel != null ? spLevel.getSelectedItemPosition() : 0;
        switch (pos) {
            case 1: return 0; // DEBUG+
            case 2: return 1; // INFO+
            case 3: return 2; // WARN+
            case 4: return 3; // ERROR
            default: return 0; // 全部
        }
    }

    /** 日志级别字符串 -> 可比较的数值等级。 */
    private static int levelRank(String level) {
        if (LogStore.LEVEL_ERROR.equals(level)) return 3;
        if (LogStore.LEVEL_WARN.equals(level)) return 2;
        if (LogStore.LEVEL_INFO.equals(level)) return 1;
        return 0; // DEBUG 及未知
    }

    // ==================== P2：标题连点 3 次自定义文案 ====================

    /** 自定义文案持久化用的 SharedPreferences 名称。 */
    public static final String PREFS_TEXT = "custom_text";

    /** 可自定义的按钮 key：原文字 -> SharedPreferences 存储键。 */
    private static final Map<String, String> CUSTOM_TEXT_KEYS = new HashMap<>();
    /** 每项文案的作用域标注：原文字 -> 作用域（悬浮窗 / 软件内）。 */
    private static final Map<String, String> CUSTOM_TEXT_SCOPES = new HashMap<>();
    /**
     * 全量可自定义控件清单。每一项对应界面上的一个可点击控件或常改文字。
     * entry = { 编号, 控件ID, 默认文字, 存储键, 作用域 }
     * 带切换状态的控件用「/」拆分，前段为状态 A 文案，后段为状态 B 文案，
     * 分别落在 <存储键>_a / <存储键>_b 两个键上。
     */
    private static final String[][] CUSTOM_TEXT_ITEMS = {
            {"①",  "btnShizuku",         "请求 Shizuku 权限",          "btn_shizuku",                "软件内"},
            {"②",  "btnOverlayPermission","申请悬浮窗权限",            "btn_overlay_permission",     "软件内"},
            {"③",  "btnFloatingToggle",   "显示悬浮球 / 隐藏悬浮球",     "btn_floating_toggle",        "软件内"},
            {"④",  "btnScaleSmall",       "小 70%",                     "btn_scale_small",            "软件内"},
            {"⑤",  "btnScaleMedium",      "中 85%",                     "btn_scale_medium",           "软件内"},
            {"⑥",  "btnScaleLarge",       "大 100%",                    "btn_scale_large",            "软件内"},
            {"⑦",  "btnToggle",           "启动 MCP Server / 停止 MCP Server", "btn_toggle",         "软件内 + 悬浮窗"},
            {"⑧",  "btnTest",             "轻量自检（不执行命令）",      "btn_test",                   "软件内"},
            {"⑨",  "btnTestDeep",         "深度自检 id; uname -a",       "btn_test_deep",              "软件内"},
            {"⑩",  "btnCancelTest",       "取消",                       "btn_cancel_test",            "软件内"},
            {"⑪",  "btnClearLog",         "清空日志",                   "btn_clear_log",              "软件内"},
            {"⑫",  "btnFlushLog",         "强制落盘",                   "btn_flush_log",              "软件内"},
            {"⑬",  "btnExportLog",        "导出日志",                   "btn_export_log",             "软件内"},
            {"—",  "tvTitle",             "Shizuku MCP",                "tv_title",                   "软件内"},
            {"—",  "tvMcpState",          "MCP Server 已启动 / MCP Server 停摆中", "tv_mcp_state",    "软件内 + 悬浮窗"},
    };

    /** 按编号取「状态 A / 状态 B」两段默认文字。无斜杠时两段相同。 */
    private static String[] splitDefault(String defaultText) {
        int slash = defaultText.indexOf(" / ");
        if (slash < 0) {
            return new String[]{defaultText, defaultText};
        }
        return new String[]{
                defaultText.substring(0, slash),
                defaultText.substring(slash + 3)
        };
    }

    private int titleClickCount = 0;
    private long lastTitleClickMs = 0L;

    /** 绑定标题连点 3 次监听，弹出自定义文案对话框。 */
    private void setupTitleCustomText() {
        TextView tvTitle = findViewById(R.id.tvTitle);
        if (tvTitle == null) return;
        tvTitle.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTitleClickMs > 800L) {
                titleClickCount = 0;
            }
            lastTitleClickMs = now;
            titleClickCount++;
            if (titleClickCount >= 3) {
                titleClickCount = 0;
                showCustomTextDialog();
            }
        });
    }

    /** 逐控件展示「编号/默认文字 + 新文字输入框 + 确认」，确认后落盘并即时应用。 */
    private void showCustomTextDialog() {
        final SharedPreferences prefs = getSharedPreferences(PREFS_TEXT, MODE_PRIVATE);
        final int count = CUSTOM_TEXT_ITEMS.length;
        final EditText[] inputs = new EditText[count];

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView tvCustomHeader = new TextView(this);
        tvCustomHeader.setText("  按钮文案自定义");
        android.graphics.drawable.Drawable editIcon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_edit_text);
        if (editIcon != null) {
            int size = (int)(20 * getResources().getDisplayMetrics().density);
            editIcon.setBounds(0, 0, size, size);
            tvCustomHeader.setCompoundDrawables(editIcon, null, null, null);
            tvCustomHeader.setCompoundDrawablePadding((int)(8 * getResources().getDisplayMetrics().density));
        }
        tvCustomHeader.setTextSize(16f);
        tvCustomHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(tvCustomHeader);
        // ======================================================================

        for (int i = 0; i < count; i++) {
            String[] item = CUSTOM_TEXT_ITEMS[i];
            String index = item[0];
            String viewId = item[1];
            String defaultText = item[2];
            String key = item[3];
            String scope = item[4];
            String[] defs = splitDefault(defaultText);
            boolean toggle = !defs[0].equals(defs[1]);

            TextView label = new TextView(this);
            label.setText(index + "  " + viewId
                    + "\n默认：" + defaultText
                    + "\n存储键：" + key
                    + (toggle ? "_a / " + key + "_b" : "")
                    + "　【作用域：" + scope + "】"
                    + (toggle ? "\n（带切换状态，用「/」分隔两段文字）" : ""));
            label.setTextSize(13f);
            container.addView(label);

            EditText input = new EditText(this);
            input.setHint("输入新文字");
            input.setText(readStoredText(prefs, key, defs, toggle));
            input.setTextSize(14f);
            container.addView(input);
            inputs[i] = input;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义按钮文案（全量 " + count + " 项）")
                .setView(scroll)
                .setPositiveButton("确认", (d, w) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    for (int i = 0; i < count; i++) {
                        String[] item = CUSTOM_TEXT_ITEMS[i];
                        String key = item[3];
                        String[] defs = splitDefault(item[2]);
                        boolean toggle = !defs[0].equals(defs[1]);
                        String value = inputs[i].getText().toString().trim();
                        if (toggle) {
                            // 带切换状态：按「/」拆成 A/B 两段，分别写入 _a / _b
                            String[] parts = value.split("/", 2);
                            String a = parts.length > 0 ? parts[0].trim() : "";
                            String b = parts.length > 1 ? parts[1].trim() : a;
                            if (a.isEmpty() && b.isEmpty()) {
                                editor.remove(key + "_a");
                                editor.remove(key + "_b");
                            } else {
                                editor.putString(key + "_a", a.isEmpty() ? defs[0] : a);
                                editor.putString(key + "_b", b.isEmpty() ? defs[1] : b);
                            }
                            LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                                    "[文案] 保存 " + key + "_a=" + a + " ; " + key + "_b=" + b);
                        } else {
                            if (value.isEmpty()) {
                                editor.remove(key);
                            } else {
                                editor.putString(key, value);
                            }
                            LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                                    "[文案] 保存 " + key + "=" + value);
                        }
                    }
                    editor.apply();
                    LogStore.getInstance().flush();
                    applyCustomTexts();
                    Toast.makeText(this, "文案已更新并落盘", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("重温启动页", (d, w) -> {
                    try {
                        Intent i = new Intent(this, OnboardingActivity.class);
                        i.putExtra("review", true);
                        startActivity(i);
                        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI",
                                "[彩蛋] 重温启动页");
                    } catch (Throwable t) {
                        Toast.makeText(this, "无法打开启动页: " + t.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 读取某控件当前应显示的文案：切换态拼成「A / B」，单态直接读键。 */
    private String readStoredText(SharedPreferences prefs, String key, String[] defs, boolean toggle) {
        if (toggle) {
            String a = prefs.getString(key + "_a", defs[0]);
            String b = prefs.getString(key + "_b", defs[1]);
            return a + " / " + b;
        }
        return prefs.getString(key, defs[0]);
    }

    /** 读取持久化文案并即时应用到悬浮球开关、MCP 按钮与状态文字。 */
    private void applyCustomTexts() {
        SharedPreferences prefs = getSharedPreferences(PREFS_TEXT, MODE_PRIVATE);

        // 键名与 CUSTOM_TEXT_ITEMS 中 splitDefault 写入的 _a/_b 后缀保持一致
        String showText = prefs.getString("btn_floating_toggle_a", "显示悬浮球");
        String hideText = prefs.getString("btn_floating_toggle_b", "隐藏悬浮球");
        FloatingWindow.setTexts(showText, hideText);

        String startText = prefs.getString("btn_toggle_a", "启动 MCP Server");
        String stopText = prefs.getString("btn_toggle_b", "停止 MCP Server");
        McpService.setTexts(startText, stopText);

        String mcpRunningText = prefs.getString("tv_mcp_state_a", "MCP Server 已启动");
        String mcpStoppedText = prefs.getString("tv_mcp_state_b", "MCP Server 停摆中");
        // 同步面板内 MCP 按钮 / 端口信息等文案，并立即刷新悬浮球面板
        FloatingWindow.setPanelTexts(startText, stopText, mcpRunningText, mcpStoppedText);

        // 主界面按钮：悬浮球开关 + MCP 开关 + MCP 状态文字
        refreshFloatingToggle();
        refreshToggle();
        if (tvMcpState != null) {
            tvMcpState.setText(McpService.running ? mcpRunningText : mcpStoppedText);
        }
    }

    private void exportLogs() {
        try {
            File dir = new File(getExternalFilesDir(null), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "无法创建日志目录", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "log_export_" + new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".txt";
            File out = new File(dir, name);
            boolean ok = LogStore.getInstance().exportFullToFile(out.getAbsolutePath());
            if (ok) {
                Toast.makeText(this, "已完整导出: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
