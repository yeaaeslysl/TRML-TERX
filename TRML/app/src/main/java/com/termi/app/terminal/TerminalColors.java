package com.termi.app.terminal;

/**
 * 终端颜色管理器。
 * 维护 ANSI 16 色、xterm 256 色扩展调色板与真彩色直通逻辑，
 * 提供与 TextStyle 编码契约对齐的颜色查询接口。
 */
public final class TerminalColors {

    /** 默认前景色索引（对应 TextStyle.encode 中的 fg=256） */
    public static final int COLOR_INDEX_FOREGROUND = 256;
    /** 默认背景色索引（对应 TextStyle.encode 中的 bg=257） */
    public static final int COLOR_INDEX_BACKGROUND = 257;
    /** 调色板总大小：256 色 + 2 个默认色槽位 */
    private static final int PALETTE_SIZE = 258;

    /** RGB 调色板，每个元素为 0xFFRRGGBB 格式 */
    private final int[] mPalette = new int[PALETTE_SIZE];

    /** 当前配色方案名称，用于调试与日志 */
    private String mSchemeName = "default";

    public TerminalColors() {
        reset();
    }

    /**
     * 重置为标准 xterm 256 色调色板。
     * 包含：16 色 ANSI 基础色 + 216 色立方体 + 24 级灰度 + 默认前/背景色。
     */
    public void reset() {
        // === ANSI 标准 16 色（暗色 + 亮色变体）===
        // 黑、红、绿、黄、蓝、品红、青、白
        mPalette[0]  = 0xFF000000; mPalette[8]  = 0xFF7F7F7F;
        mPalette[1]  = 0xFFCC0000; mPalette[9]  = 0xFFFF0000;
        mPalette[2]  = 0xFF00CC00; mPalette[10] = 0xFF00FF00;
        mPalette[3]  = 0xFFCCCC00; mPalette[11] = 0xFFFFFF00;
        mPalette[4]  = 0xFF0000CC; mPalette[12] = 0xFF0000FF;
        mPalette[5]  = 0xFFCC00CC; mPalette[13] = 0xFFFF00FF;
        mPalette[6]  = 0xFF00CCCC; mPalette[14] = 0xFF00FFFF;
        mPalette[7]  = 0xFFCCCCCC; mPalette[15] = 0xFFFFFFFF;

        // === xterm 256 色：6×6×6 色立方体（索引 16-231）===
        int[] cubeValues = {0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF};
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int index = 16 + r * 36 + g * 6 + b;
                    mPalette[index] = 0xFF000000
                            | (cubeValues[r] << 16)
                            | (cubeValues[g] << 8)
                            | cubeValues[b];
                }
            }
        }

        // === 24 级灰度（索引 232-255）===
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            mPalette[232 + i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }

        // === 默认前景 / 背景色 ===
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFFCCCCCC; // 浅灰前景
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF000000; // 纯黑背景
    }

    /**
     * 设置单个调色板条目。
     *
     * @param index 颜色索引（0-257）
     * @param color 0xFFRRGGBB 格式的 ARGB 颜色值
     */
    public void setColor(int index, int color) {
        if (index >= 0 && index < PALETTE_SIZE) {
            mPalette[index] = color;
        }
    }

    /**
     * 获取指定索引对应的 ARGB 颜色值。
     * 若索引越界，返回默认前景或背景色。
     *
     * @param index      颜色索引
     * @param isForeground true 表示查询前景色，false 表示背景色
     * @return 0xFFRRGGBB 格式的颜色值
     */
    public int getColor(int index, boolean isForeground) {
        if (index >= 0 && index < PALETTE_SIZE) {
            return mPalette[index];
        }
        return isForeground ? mPalette[COLOR_INDEX_FOREGROUND] : mPalette[COLOR_INDEX_BACKGROUND];
    }

    /**
     * 从 packed style 中提取前景色索引并解析为 ARGB 颜色。
     * 与 TextStyle.encode(fg, bg, effect) 的编码契约完全对齐。
     *
     * @param packedStyle TextStyle.encode 生成的打包样式值
     * @return 0xFFRRGGBB 格式的前景色
     */
    public int getForegroundColor(long packedStyle) {
        int fgIndex = TextStyle.decodeForeColor(packedStyle);
        return getColor(fgIndex, true);
    }

    /**
     * 从 packed style 中提取背景色索引并解析为 ARGB 颜色。
     *
     * @param packedStyle TextStyle.encode 生成的打包样式值
     * @return 0xFFRRGGBB 格式的背景色
     */
    public int getBackgroundColor(long packedStyle) {
        int bgIndex = TextStyle.decodeBackColor(packedStyle);
        return getColor(bgIndex, false);
    }

    /**
     * 批量加载 16 色 ANSI 调色板。
     * 数组长度必须为 16，否则静默忽略。
     *
     * @param colors 16 个 0xFFRRGGBB 格式的颜色值
     */
    public void setAnsiColors(int[] colors) {
        if (colors == null || colors.length != 16) return;
        System.arraycopy(colors, 0, mPalette, 0, 16);
    }

    /**
     * 设置默认前景色。
     *
     * @param color 0xFFRRGGBB 格式的颜色值
     */
    public void setDefaultForeground(int color) {
        mPalette[COLOR_INDEX_FOREGROUND] = color;
    }

    /**
     * 设置默认背景色。
     *
     * @param color 0xFFRRGGBB 格式的颜色值
     */
    public void setDefaultBackground(int color) {
        mPalette[COLOR_INDEX_BACKGROUND] = color;
    }

    /**
     * 获取当前配色方案名称。
     */
    public String getSchemeName() {
        return mSchemeName;
    }

    /**
     * 设置配色方案名称（仅用于标识，不影响实际颜色）。
     *
     * @param name 方案名称
     */
    public void setSchemeName(String name) {
        mSchemeName = name != null ? name : "default";
    }

    /**
     * 应用预设主题。
     * 0: Default (Dark), 1: Light, 2: Solarized Dark, 3: Monokai
     *
     * @param themeIndex 主题索引
     */
    public void applyTheme(int themeIndex) {
        switch (themeIndex) {
            case 1: applyLightTheme(); break;
            case 2: applySolarizedDark(); break;
            case 3: applyMonokai(); break;
            case 4: applyDracula(); break;
            case 5: applyNord(); break;
            case 0: default: applyDefaultDark(); break;
        }
    }

    private void applyDefaultDark() {
        mSchemeName = "default-dark";
        reset();
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFFCCCCCC;
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF000000;
    }

    private void applyLightTheme() {
        mSchemeName = "light";
        reset();
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFFFFFFFF;
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFF000000;
        mPalette[0]  = 0xFF000000; mPalette[8]  = 0xFF555555;
        mPalette[1]  = 0xFFAA0000; mPalette[9]  = 0xFFFF0000;
        mPalette[2]  = 0xFF00AA00; mPalette[10] = 0xFF00FF00;
        mPalette[3]  = 0xFFAA5500; mPalette[11] = 0xFFFFAA00;
        mPalette[4]  = 0xFF0000AA; mPalette[12] = 0xFF0000FF;
        mPalette[5]  = 0xFFAA00AA; mPalette[13] = 0xFFFF00FF;
        mPalette[6]  = 0xFF00AAAA; mPalette[14] = 0xFF00FFFF;
        mPalette[7]  = 0xFFAAAAAA; mPalette[15] = 0xFFFFFFFF;
    }

    private void applySolarizedDark() {
        mSchemeName = "solarized-dark";
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF002B36;
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFF839496;
        mPalette[0]  = 0xFF073642; mPalette[8]  = 0xFF002B36;
        mPalette[1]  = 0xFFDC322F; mPalette[9]  = 0xFFCB4B16;
        mPalette[2]  = 0xFF859900; mPalette[10] = 0xFF586E75;
        mPalette[3]  = 0xFFB58900; mPalette[11] = 0xFF657B83;
        mPalette[4]  = 0xFF268BD2; mPalette[12] = 0xFF839496;
        mPalette[5]  = 0xFFD33682; mPalette[13] = 0xFFEEEEEE;
        mPalette[6]  = 0xFF2AA198; mPalette[14] = 0xFFFDF6E3;
        mPalette[7]  = 0xFFEEDDCC; mPalette[15] = 0xFFFFFFFF;
    }

    private void applyMonokai() {
        mSchemeName = "monokai";
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF272822;
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFFF8F8F2;
        mPalette[0]  = 0xFF272822; mPalette[8]  = 0xFF75715E;
        mPalette[1]  = 0xFFF92672; mPalette[9]  = 0xFFFE9E00;
        mPalette[2]  = 0xFFA6E22E; mPalette[10] = 0xFFBAEE00;
        mPalette[3]  = 0xFFF4BF75; mPalette[11] = 0xFFFFE71D;
        mPalette[4]  = 0xFF66D9EF; mPalette[12] = 0xFFAE81FF;
        mPalette[5]  = 0xFFAE81FF; mPalette[13] = 0xFFFF00FF;
        mPalette[6]  = 0xFFA1EFE4; mPalette[14] = 0xFF75715E;
        mPalette[7]  = 0xFFF8F8F2; mPalette[15] = 0xFFFFFFFF;
    }

    private void applyDracula() {
        mSchemeName = "dracula";
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF282A36;
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFFF8F8F2;
        mPalette[0]  = 0xFF21222C; mPalette[8]  = 0xFF6272A4;
        mPalette[1]  = 0xFFFF5555; mPalette[9]  = 0xFFFF6E6E;
        mPalette[2]  = 0xFF50FA7B; mPalette[10] = 0xFF50FA7B;
        mPalette[3]  = 0xFFF1FA8C; mPalette[11] = 0xFFF1FA8C;
        mPalette[4]  = 0xFFBD93F9; mPalette[12] = 0xFFBD93F9;
        mPalette[5]  = 0xFFFF79C6; mPalette[13] = 0xFFFF79C6;
        mPalette[6]  = 0xFF8BE9FD; mPalette[14] = 0xFF8BE9FD;
        mPalette[7]  = 0xFFF8F8F2; mPalette[15] = 0xFFFFFFFF;
    }

    private void applyNord() {
        mSchemeName = "nord";
        mPalette[COLOR_INDEX_BACKGROUND] = 0xFF2E3440;
        mPalette[COLOR_INDEX_FOREGROUND] = 0xFFD8DEE9;
        mPalette[0]  = 0xFF3B4252; mPalette[8]  = 0xFF4C566A;
        mPalette[1]  = 0xFFBF616A; mPalette[9]  = 0xFFBF616A;
        mPalette[2]  = 0xFFA3BE8C; mPalette[10] = 0xFFA3BE8C;
        mPalette[3]  = 0xFFEBCB8B; mPalette[11] = 0xFFEBCB8B;
        mPalette[4]  = 0xFF81A1C1; mPalette[12] = 0xFF81A1C1;
        mPalette[5]  = 0xFFB48EAD; mPalette[13] = 0xFFB48EAD;
        mPalette[6]  = 0xFF88C0D0; mPalette[14] = 0xFF8FBCBB;
        mPalette[7]  = 0xFFE5E9F0; mPalette[15] = 0xFFECEFF4;
    }

    public int getCurrentTheme() {
        switch (mSchemeName) {
            case "light": return 1;
            case "solarized-dark": return 2;
            case "monokai": return 3;
            case "dracula": return 4;
            case "nord": return 5;
            case "custom": return 6;
            default: return 0;
        }
    }

    /**
     * 应用自定义前景/背景色。
     * 将全部 ANSI 16 色映射到自定义前景色的暗/亮变体，
     * 确保所有文本（包括 ANSI 着色输出）都使用用户指定的色系。
     * 保留 xterm 256 色扩展调色板和灰度不变。
     *
     * @param fgColor 0xFFRRGGBB 格式前景色
     * @param bgColor 0xFFRRGGBB 格式背景色
     */
    public void applyCustomTheme(int fgColor, int bgColor) {
        mSchemeName = "custom";

        // 提取 RGB 分量用于生成变体
        int r = (fgColor >> 16) & 0xFF;
        int g = (fgColor >> 8) & 0xFF;
        int b = fgColor & 0xFF;

        // 默认前/背景色
        mPalette[COLOR_INDEX_FOREGROUND] = fgColor;
        mPalette[COLOR_INDEX_BACKGROUND] = bgColor;

        // 暗色变体（0-7）：降低亮度到 2/3
        int darkR = Math.max(0, r * 2 / 3);
        int darkG = Math.max(0, g * 2 / 3);
        int darkB = Math.max(0, b * 2 / 3);
        int darkFg = 0xFF000000 | (darkR << 16) | (darkG << 8) | darkB;

        // 亮色变体（8-15）：提高亮度
        int brightR = Math.min(255, r + (255 - r) / 3);
        int brightG = Math.min(255, g + (255 - g) / 3);
        int brightB = Math.min(255, b + (255 - b) / 3);
        int brightFg = 0xFF000000 | (brightR << 16) | (brightG << 8) | brightB;

        // 暗色组（0-7）
        mPalette[0] = bgColor;       // Black → 背景色
        mPalette[1] = darkFg;        // Red → 暗前景
        mPalette[2] = darkFg;        // Green → 暗前景
        mPalette[3] = darkFg;        // Yellow → 暗前景
        mPalette[4] = darkFg;        // Blue → 暗前景
        mPalette[5] = darkFg;        // Magenta → 暗前景
        mPalette[6] = darkFg;        // Cyan → 暗前景
        mPalette[7] = fgColor;       // White → 自定义前景色

        // 亮色组（8-15）
        mPalette[8] = bgColor;       // Bright Black → 背景色
        mPalette[9] = brightFg;      // Bright Red → 亮前景
        mPalette[10] = brightFg;     // Bright Green → 亮前景
        mPalette[11] = brightFg;     // Bright Yellow → 亮前景
        mPalette[12] = brightFg;     // Bright Blue → 亮前景
        mPalette[13] = brightFg;     // Bright Magenta → 亮前景
        mPalette[14] = brightFg;     // Bright Cyan → 亮前景
        mPalette[15] = brightFg;     // Bright White → 亮前景
    }
}
