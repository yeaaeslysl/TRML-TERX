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
    }

    // --- Terminal Settings Keys ---
    public static final String PREFS_UI = "ui_settings";
    public static final String KEY_TERM_THEME = "term_theme_index"; // 0=default(dark), 1=light, etc.
    public static final String KEY_TERM_FONT_SIZE_SP = "term_font_size_sp";
    private static final int DEFAULT_TERM_FONT_SIZE_SP = 14;

    private Process shellProcess;
    private java.io.BufferedWriter shellWriter;
    private Thread shellReaderThread;

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

        // --- REAL SHELL PROCESS STARTUP ---
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
            pb.redirectErrorStream(true); // Merge stderr into stdout
            shellProcess = pb.start();
            
            // Setup Writer for Input
            shellWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(shellProcess.getOutputStream(), StandardCharsets.UTF_8));

            // IME/软键盘输入统一走本地行编辑（shell 无 tty，不回显、不处理退格）
            terminalView.setOutputSink(data -> runOnUiThread(() -> handleLocalInput(data)));
            
            // Setup Reader Loop for Output
            final java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8));
            
            shellReaderThread = new Thread(() -> {
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        // Append newline manually since BufferedReader strips it
                        final String text = line + "\r\n";
                        runOnUiThread(() -> {
                            if (terminalEmulator != null) {
                                terminalEmulator.append(text.getBytes(StandardCharsets.UTF_8));
                            }
                        });
                    }
                } catch (Exception e) {
                    LogStore.getInstance().append(LogStore.LEVEL_ERROR, "Terminal", "Read loop error: " + e.getMessage());
                }
            }, "shell-reader");
            shellReaderThread.setDaemon(true);
            shellReaderThread.start();

            // Welcome message
            writeTerminal("\033[1;32mTermi Shell Ready (/system/bin/sh)\033[0m\r\n");
            writeTerminal("\033[36m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m\r\n");
            writeTerminal("  \033[1mTRML / TERX\033[0m — Android Local MCP Server\r\n");
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
            
        } catch (Exception e) {
            LogStore.getInstance().append(LogStore.LEVEL_ERROR, "UI",
                    "[Terminal] Failed to start shell process: " + e.getMessage());
            // Fallback or just show error in terminal
            writeTerminal("\r\n\033[1;31mERROR: Cannot spawn shell. Check permissions/environment.\033[0m\r\n");
        }

        // Keyboard Event Handler：与 IME 共用本地行编辑，避免两套逻辑打架
        terminalView.setOnKeyListener((keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    handleLocalInput(new byte[]{'\r'});
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                    handleLocalInput(new byte[]{0x7f});
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
            extraKeys.setOnExtraKeyListener(key -> {
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

        LogStore.getInstance().append(LogStore.LEVEL_INFO, "UI", "[Terminal] Initialized with real shell");
    }

    /** 向终端写入文本（直接喂给 emulator 显示）。 */
    public void writeTerminal(String text) {
        if (terminalEmulator != null && text != null) {
            terminalEmulator.append(text.getBytes(StandardCharsets.UTF_8));
            // 强制触发 View 重绘，确保 MCP 回显等非 shell-reader 来源的写入也能即时可见
            if (terminalView != null) terminalView.postInvalidate();
        }
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

    /** 回车：把整行一次性写入 shell。 */
    private void submitLine() {
        String line = mLineBuffer.toString();
        mLineBuffer.setLength(0);
        writeTerminal("\r\n");
        if (shellWriter != null) {
            try {
                shellWriter.write(line);
                shellWriter.write("\n");
                shellWriter.flush();
            } catch (Exception e) {
                LogStore.getInstance().append(LogStore.LEVEL_ERROR, "Terminal",
                        "submitLine failed: " + e.getMessage());
            }
        }
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
        updateShizukuStatus();
        refreshToggle();
        refreshFloatingToggle();
        // 回到前台时恢复悬浮球（若已授予权限）
        if (Settings.canDrawOverlays(this) && !FloatingWindow.isShowing()) {
            FloatingWindow.get(this).show();
            refreshFloatingToggle();
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
            shellProcess.destroy();
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
