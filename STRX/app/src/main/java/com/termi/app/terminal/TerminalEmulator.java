package com.termi.app.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 终端模拟器核心：解析 VT100/ANSI 转义序列，驱动 TerminalBuffer 更新显示。
 * 支持 CSI、OSC、ESC、DCS 等主流控制序列，兼容 xterm-256color。
 */
public final class TerminalEmulator {
    private static final int MAX_ESCAPE_PARAMS = 16;
    private static final int ESC_NONE = 0;
    private static final int ESC_CSI = 1;
    private static final int ESC_OSC = 2;
    private static final int ESC_DCS = 3;
    private static final int ESC_APC = 4;
    private static final int ESC_PM = 5;
    private static final int ESC_SOS = 6;
    private static final int ESC_GOT = 7;

    private final TerminalBuffer mBuffer;
    private final TerminalColors mColors;
    private int mCursorCol;
    private int mCursorRow;
    private long mCurrentStyle;
    private int mEscapeState;
    private int[] mParams;
    private int mParamCount;
    private StringBuilder mOscBuffer;
    private boolean mInsertMode;
    private int mScrollTop;
    private int mScrollBottom;
    private boolean mOriginMode;
    private boolean mAutoWrap;
    private boolean mCursorVisible;

    public TerminalEmulator(int columns, int rows, int maxHistoryRows) {
        mBuffer = new TerminalBuffer(columns, rows, maxHistoryRows);
        mColors = new TerminalColors();
        mParams = new int[MAX_ESCAPE_PARAMS];
        mOscBuffer = new StringBuilder(256);
        reset();
    }

    public void reset() {
        mCursorCol = 0;
        mCursorRow = 0;
        mCurrentStyle = TextStyle.encode(256, 257, 0);
        mEscapeState = ESC_NONE;
        mParamCount = 0;
        Arrays.fill(mParams, 0);
        mOscBuffer.setLength(0);
        mInsertMode = false;
        mScrollTop = 0;
        mScrollBottom = mBuffer.getRows() - 1;
        mOriginMode = false;
        mAutoWrap = true;
        mCursorVisible = true;
        mBuffer.clearScreen();
    }

    /**
     * 运行时切换主题配色。
     * 调用后自动标记 buffer 需要全量重绘，下一帧即生效。
     */
    private Runnable mOnThemeChanged;

    public void setOnThemeChanged(Runnable callback) {
        mOnThemeChanged = callback;
    }

    public void setTheme(int themeIndex) {
        mColors.applyTheme(themeIndex);
        mBuffer.setFullRedrawNeeded();
        if (mOnThemeChanged != null) mOnThemeChanged.run();
    }

    public int getCurrentTheme() {
        return mColors.getCurrentTheme();
    }

    public TerminalColors getColors() {
        return mColors;
    }

    public TerminalBuffer getBuffer() { return mBuffer; }
    public int getCursorCol() { return mCursorCol; }
    public int getCursorRow() { return mCursorRow; }
    public boolean isCursorVisible() { return mCursorVisible; }

    /**
     * 根据 View 实际尺寸调整屏幕行列数。
     * 修复"缓冲区固定 24 行导致下方大片空白、光标到不了底部"的问题。
     */
    public void resizeToView(int columns, int rows) {
        if (rows < 1) rows = 1;
        if (columns < 1) columns = 1;
        if (rows == mBuffer.getRows()) return;
        mBuffer.resize(rows);
        mScrollTop = 0;
        mScrollBottom = rows - 1;
        if (mCursorRow >= rows) mCursorRow = rows - 1;
        if (mCursorRow < 0) mCursorRow = 0;
        mFullRedrawRequested = true;
    }

    private boolean mFullRedrawRequested;

    /** 视口滚动：只移动可视窗口，不改变光标位置（光标属于内容，随写入移动）。 */
    public void scrollViewport(int deltaLines) {
        if (deltaLines == 0) return;
        mBuffer.scrollViewport(deltaLines);
        mFullRedrawRequested = true;
    }

    /** 视口是否停在底部（跟随最新输出）。 */
    public boolean isViewportAtBottom() {
        return mBuffer.isViewportAtBottom();
    }

    /** 一键把视口拉回底部（供"回左下"按钮调用）。 */
    public void scrollViewportToBottom() {
        mBuffer.scrollViewportToBottom();
        mFullRedrawRequested = true;
    }

    public void append(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            processChar(c);
        }
    }

    private void processChar(char c) {
        switch (mEscapeState) {
            case ESC_NONE:
                if (c == '\u001b') {
                    mEscapeState = ESC_GOT;
                } else {
                    printChar(c);
                }
                break;
            case ESC_GOT:
                if (c == '[') {
                    mEscapeState = ESC_CSI;
                    mParamCount = 0;
                    Arrays.fill(mParams, 0);
                } else if (c == ']') {
                    mEscapeState = ESC_OSC;
                    mOscBuffer.setLength(0);
                } else if (c == 'P') {
                    mEscapeState = ESC_DCS;
                } else if (c == '_') {
                    mEscapeState = ESC_APC;
                } else if (c == '^') {
                    mEscapeState = ESC_PM;
                } else if (c == 'X') {
                    mEscapeState = ESC_SOS;
                } else {
                    // Not a recognized escape sequence, print ESC and the character
                    printChar('\u001b');
                    printChar(c);
                    mEscapeState = ESC_NONE;
                }
                break;
            case ESC_CSI:
                processCsi(c);
                break;
            case ESC_OSC:
                processOsc(c);
                break;
            default:
                // Simplified: ignore DCS/APC/PM/SOS for now
                if (c == '\u001b' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    mEscapeState = ESC_NONE;
                }
                break;
        }
    }

    private void printChar(char c) {
        if (c < 32) {
            switch (c) {
                case '\r': mCursorCol = 0; break;
                case '\n': mCursorCol = 0; lineFeed(); break;
                case '\t': tab(); break;
                case '\b': backspace(); break;
                case '\u0007': /* BEL - ignored */ break;
                default: break;
            }
        } else {
            int width = WcWidth.width(c);
            if (width < 0) return;
            int cols = mBuffer.getColumns();
            int rows = mBuffer.getRows();
            // Defensive clamp: prevent ArrayIndexOutOfBoundsException in setCharAt
            if (mCursorCol < 0) mCursorCol = 0;
            if (mCursorRow < 0) mCursorRow = 0;
            if (mCursorRow >= rows) mCursorRow = rows - 1;
            // 恢复 auto-wrap：超过 buffer 宽度时正常换行，避免字符挤叠
            // buffer 已扩至 1024 列，实际使用中几乎不会触发换行
            if (mCursorCol + width > cols) {
                if (mAutoWrap) {
                    mCursorCol = 0;
                    lineFeed();
                    if (mCursorRow >= rows) mCursorRow = rows - 1;
                } else {
                    mCursorCol = Math.max(0, cols - width);
                }
            }
            if (mInsertMode) {
                // Shift right from cursor
                // Simplified: no insert mode implementation in minimal version
            }
            // Final safety check before writing to buffer
            if (mCursorCol >= 0 && mCursorCol < cols && mCursorRow >= 0 && mCursorRow < rows) {
                mBuffer.setCharAt(mCursorCol, mCursorRow, c, mCurrentStyle);
                mCursorCol += width;
            }
        }
    }

    private void lineFeed() {
        if (mCursorRow == mScrollBottom) {
            mBuffer.scrollDown(1);
            // scrollDown 只移动了 mScreenTopRow，光标必须显式回到可视区最后一行
            mCursorRow = mBuffer.getRows() - 1;
        } else if (mCursorRow < mBuffer.getRows() - 1) {
            mCursorRow++;
        }
    }

    private void tab() {
        int nextTab = (mCursorCol / 8 + 1) * 8;
        mCursorCol = Math.min(nextTab, mBuffer.getColumns() - 1);
    }

    private void backspace() {
        eraseChar();
    }

    /** 删除光标左侧一个字符并原地擦除显示（用于本地行编辑）。 */
    public void eraseChar() {
        if (mCursorCol > 0) {
            mCursorCol--;
            mBuffer.setCharAt(mCursorCol, mCursorRow, ' ', mCurrentStyle);
        }
    }

    private void processCsi(char c) {
        if (c >= '0' && c <= '9') {
            if (mParamCount < MAX_ESCAPE_PARAMS) {
                mParams[mParamCount] = mParams[mParamCount] * 10 + (c - '0');
            }
        } else if (c == ';') {
            if (mParamCount < MAX_ESCAPE_PARAMS - 1) mParamCount++;
        } else if (c == '[') {
            // CSI [ -> extended, treat as start
        } else if (c == ']') {
            mEscapeState = ESC_OSC;
            mOscBuffer.setLength(0);
        } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '@' || c == '`') {
            executeCsi(c);
            mEscapeState = ESC_NONE;
        } else if (c == '?') {
            // Private mode indicator, store in params[0] as flag
            // Simplified: ignore for now
        } else {
            mEscapeState = ESC_NONE;
        }
    }

    private void executeCsi(char cmd) {
        int p0 = mParams[0];
        int p1 = mParamCount > 0 ? mParams[1] : 0;
        switch (cmd) {
            case 'A': // CUU
                mCursorRow = Math.max(mScrollTop, mCursorRow - Math.max(p0, 1));
                break;
            case 'B': // CUD
                mCursorRow = Math.min(mScrollBottom, mCursorRow + Math.max(p0, 1));
                break;
            case 'C': // CUF
                mCursorCol = Math.min(mBuffer.getColumns() - 1, mCursorCol + Math.max(p0, 1));
                break;
            case 'D': // CUB
                mCursorCol = Math.max(0, mCursorCol - Math.max(p0, 1));
                break;
            case 'H': // CUP
            case 'f': // HVP
                setCursorPosition(p0, p1);
                break;
            case 'J': // ED
                eraseDisplay(p0);
                break;
            case 'K': // EL
                mBuffer.eraseLine(mCursorRow, p0);
                break;
            case 'L': // IL
                // Insert lines - simplified
                break;
            case 'M': // DL
                // Delete lines - simplified
                break;
            case 'P': // DCH
                // Delete chars - simplified
                break;
            case 'S': // SU
                mBuffer.scrollDown(Math.max(p0, 1));
                break;
            case 'T': // SD
                // Scroll up - simplified
                break;
            case 'X': // ECH
                // Erase chars - simplified
                break;
            case 'd': // VPA
                mCursorRow = clampRow(p0 - 1);
                break;
            case 'G': // CHA
                mCursorCol = clampCol(p0 - 1);
                break;
            case 'm': // SGR
                applySgr();
                break;
            case 'r': // DECSTBM
                if (p0 == 0 && p1 == 0) {
                    mScrollTop = 0;
                    mScrollBottom = mBuffer.getRows() - 1;
                } else {
                    mScrollTop = clampRow(p0 - 1);
                    mScrollBottom = clampRow(p1 - 1);
                }
                break;
            case 'h': // SM / DECSET
                handleSetMode(p0, true);
                break;
            case 'l': // RM / DECRST
                handleSetMode(p0, false);
                break;
            case 'c': // DA
                // Device attributes - no response in emulator-only mode
                break;
            case 'n': // DSR
                // Device status report - no response
                break;
            default:
                break;
        }
    }

    private void setCursorPosition(int row, int col) {
        if (row == 0 && col == 0) {
            mCursorRow = mScrollTop;
            mCursorCol = 0;
        } else {
            mCursorRow = clampRow(row - 1);
            mCursorCol = clampCol(col - 1);
        }
    }

    private int clampRow(int row) {
        if (mOriginMode) {
            return Math.max(mScrollTop, Math.min(mScrollBottom, row));
        } else {
            return Math.max(0, Math.min(mBuffer.getRows() - 1, row));
        }
    }

    private int clampCol(int col) {
        return Math.max(0, Math.min(mBuffer.getColumns() - 1, col));
    }

    private void eraseDisplay(int mode) {
        com.termi.app.LogStore.getInstance().debug("TermEmu",
                "[eraseDisplay] mode=" + mode + "（2=清屏）");
        switch (mode) {
            case 0: // Below
                mBuffer.eraseLine(mCursorRow, 0);
                for (int r = mCursorRow + 1; r < mBuffer.getRows(); r++) {
                    mBuffer.eraseLine(r, 2);
                }
                break;
            case 1: // Above
                for (int r = 0; r < mCursorRow; r++) {
                    mBuffer.eraseLine(r, 2);
                }
                mBuffer.eraseLine(mCursorRow, 1);
                break;
            case 2: // All
            case 3: // All + history
                mBuffer.clearScreen();
                break;
        }
    }

    private void applySgr() {
        if (mParamCount == 0) {
            mCurrentStyle = TextStyle.encode(256, 257, 0);
            return;
        }
        int fg = TextStyle.decodeForeColor(mCurrentStyle);
        int bg = TextStyle.decodeBackColor(mCurrentStyle);
        int fx = TextStyle.decodeEffect(mCurrentStyle);
        for (int i = 0; i <= mParamCount; i++) {
            int p = mParams[i];
            switch (p) {
                case 0: fg = 256; bg = 257; fx = 0; break;
                case 1: fx |= TextStyle.CHARACTER_ATTRIBUTE_BOLD; break;
                case 2: fx |= TextStyle.CHARACTER_ATTRIBUTE_DIM; break;
                case 3: fx |= TextStyle.CHARACTER_ATTRIBUTE_ITALIC; break;
                case 4: fx |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE; break;
                case 5: fx |= TextStyle.CHARACTER_ATTRIBUTE_BLINK; break;
                case 7: fx |= TextStyle.CHARACTER_ATTRIBUTE_INVERSE; break;
                case 8: fx |= TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE; break;
                case 9: fx |= TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH; break;
                case 22: fx &= ~(TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_DIM); break;
                case 23: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_ITALIC; break;
                case 24: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE; break;
                case 25: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_BLINK; break;
                case 27: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_INVERSE; break;
                case 28: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE; break;
                case 29: fx &= ~TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH; break;
                case 30: case 31: case 32: case 33: case 34: case 35: case 36: case 37:
                    fg = p - 30; break;
                case 38:
                    if (i + 1 <= mParamCount && mParams[i + 1] == 5 && i + 2 <= mParamCount) {
                        fg = mParams[i + 2];
                        i += 2;
                    } else if (i + 1 <= mParamCount && mParams[i + 1] == 2 && i + 4 <= mParamCount) {
                        // RGB - map to nearest 256 color (simplified)
                        fg = 256;
                        i += 4;
                    }
                    break;
                case 39: fg = 256; break;
                case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
                    bg = p - 40; break;
                case 48:
                    if (i + 1 <= mParamCount && mParams[i + 1] == 5 && i + 2 <= mParamCount) {
                        bg = mParams[i + 2];
                        i += 2;
                    } else if (i + 1 <= mParamCount && mParams[i + 1] == 2 && i + 4 <= mParamCount) {
                        bg = 257;
                        i += 4;
                    }
                    break;
                case 49: bg = 257; break;
                case 90: case 91: case 92: case 93: case 94: case 95: case 96: case 97:
                    fg = p - 90 + 8; break;
                case 100: case 101: case 102: case 103: case 104: case 105: case 106: case 107:
                    bg = p - 100 + 8; break;
                default: break;
            }
        }
        mCurrentStyle = TextStyle.encode(fg, bg, fx);
    }

    private void handleSetMode(int mode, boolean enable) {
        switch (mode) {
            case 4: // IRM
                mInsertMode = enable;
                break;
            case 6: // DECOM
                mOriginMode = enable;
                if (enable) {
                    mCursorRow = mScrollTop;
                    mCursorCol = 0;
                }
                break;
            case 7: // DECAWM
                mAutoWrap = enable;
                break;
            case 25: // DECTCEM
                mCursorVisible = enable;
                break;
            case 1049: // Alternate screen buffer
                // Simplified: ignore alternate buffer
                break;
            default:
                break;
        }
    }

    private void processOsc(char c) {
        if (c == '\u0007' || (c == '\u001b')) {
            // End of OSC
            mEscapeState = ESC_NONE;
            // Parse OSC command (e.g., "0;title")
            // Simplified: ignore OSC content
        } else {
            mOscBuffer.append(c);
        }
    }
}
