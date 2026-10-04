package com.termi.app.terminal;

import android.util.Log;

import java.util.Arrays;

public final class TerminalBuffer {
    private TerminalRow[] mLines;
    private final int mColumns;
    private int mRows;
    private int mTotalRows;
    private int mHistoryRows;
    private int mActiveTranscriptIndex;
    private int mScreenTopRow;
    /** 视口向上偏移的行数：0=跟随最新（底部），正数=用户正在查看更早的历史。 */
    private int mViewportOffset;
    /** 累计写入行数：用于限制视口最多能回滚多少行（防止滚到空白历史区）。 */
    private int mWrittenRows;

    public TerminalBuffer(int columns, int rows, int maxHistoryRows) {
        mColumns = columns;
        mRows = rows;
        mHistoryRows = maxHistoryRows;
        mTotalRows = rows + maxHistoryRows;
        mLines = new TerminalRow[mTotalRows];
        long defaultStyle = TextStyle.encode(256, 257, 0);
        for (int i = 0; i < mTotalRows; i++) {
            mLines[i] = new TerminalRow(columns, defaultStyle);
        }
        mActiveTranscriptIndex = maxHistoryRows;
        mScreenTopRow = maxHistoryRows;
    }

    public int getColumns() { return mColumns; }
    public int getRows() { return mRows; }
    public int getTotalRows() { return mTotalRows; }

    /**
     * 调整屏幕行数（终端可视高度变化时调用）。
     *
     * <p><b>关键：必须保留历史。</b>把旧缓冲"从可见底部往上数"的内容整体搬到新缓冲底部，
     * 这样扩容/缩容都不会丢失已输出的行。
     *
     * <p>旧实现只拷贝"可见屏"的行、并清空历史区，导致"切后台再回前台后，
     * 上面的内容全部消失"。改为按底部对齐搬迁后，历史与可见内容都能保住。
     */
    /**
     * 调整屏幕行数（终端可视高度变化时调用）。
     *
     * <p>本实现与基线版本保持一致：只把"旧可见区"逐行搬到新缓冲的可见区顶部，
     * 历史回滚区重置，并<b>不修改 mWrittenRows</b>。
     *
     * <p>这样 {@code maxViewportOffset()} 始终与内容一致，
     * 不会出现"下方空白"（视口偏移大于实际内容）的问题。
     */
    public void resize(int newRows) {
        if (newRows < 1) newRows = 1;
        if (newRows == mRows) return;
        com.termi.app.LogStore.getInstance().debug("TermBuffer",
                "[resize] rows " + mRows + " → " + newRows
                        + " written=" + mWrittenRows);

        final int newTotal = newRows + mHistoryRows;
        long defaultStyle = TextStyle.encode(256, 257, 0);
        TerminalRow[] newLines = new TerminalRow[newTotal];
        for (int i = 0; i < newTotal; i++) {
            newLines[i] = new TerminalRow(mColumns, defaultStyle);
        }

        // ① 可见区：与基线完全一致 —— 从旧可见区顶部逐行拷到新可见区顶部。
        final int copyRows = Math.min(mRows, newRows);
        for (int i = 0; i < copyRows; i++) {
            int oldIdx = (mScreenTopRow + i) % mTotalRows;
            newLines[mHistoryRows + i].copyFrom(mLines[oldIdx]);
        }

        // ② 历史区：额外把"可见区之上"的历史也搬过来（基线只清空，导致旧内容丢失）。
        //    放在新缓冲的历史区尾部，紧邻可见区上方，保持"越早越靠前"的顺序。
        final int keepHist = Math.min(mHistoryRows, mWrittenRows);
        for (int j = 0; j < keepHist; j++) {
            int oldIdx = (mScreenTopRow - 1 - j + mTotalRows * 2) % mTotalRows;
            int newIdx = (mHistoryRows - 1 - j + newTotal * 2) % newTotal;
            newLines[newIdx].copyFrom(mLines[oldIdx]);
        }

        mLines = newLines;
        mRows = newRows;
        mTotalRows = newTotal;
        mScreenTopRow = mHistoryRows;
        mActiveTranscriptIndex = mHistoryRows;
        mViewportOffset = 0;
        mFullRedrawNeeded = true;
    }

    /**
     * 读取"从最底行往上数第 back 行"的文本（back=0 为最底一行）。
     *
     * <p>诊断用：可查看屏幕之外的历史区里到底有没有内容，
     * 从而区分"内容被清空"与"内容只是滚出了视野"。
     * back 允许超过屏幕行数，直接读到历史区。
     *
     * @return 该行文本（已去尾部空白）；越界返回 null
     */
    public String getLineFromBottom(int back) {
        if (back < 0 || back >= mTotalRows) return null;
        int idx = (mScreenTopRow + mRows - 1 - back + mTotalRows * 2) % mTotalRows;
        TerminalRow r = mLines[idx];
        if (r == null) return null;
        char[] txt = r.getText();
        int end = mColumns;
        while (end > 0 && (txt[end - 1] == ' ' || txt[end - 1] == 0)) end--;
        return new String(txt, 0, end);
    }

    /** 已写入（滚出屏幕）的行数。 */
    public int getWrittenRows() {
        return mWrittenRows;
    }

    public char getCharAt(int col, int row) {
        if (col < 0 || col >= mColumns || row < 0 || row >= mRows) return ' ';
        int idx = (mScreenTopRow - mViewportOffset + row + mTotalRows) % mTotalRows;
        return mLines[idx].getText()[col];
    }

    public long getStyleAt(int col, int row) {
        if (col < 0 || col >= mColumns || row < 0 || row >= mRows) {
            return TextStyle.encode(256, 257, 0);
        }
        int idx = (mScreenTopRow - mViewportOffset + row + mTotalRows) % mTotalRows;
        return mLines[idx].getStyle()[col];
    }

    public void setCharAt(int col, int row, char c, long style) {
        if (col < 0 || col >= mColumns || row < 0 || row >= mRows) return;
        int idx = (mScreenTopRow + row) % mTotalRows;
        mLines[idx].setChar(col, c, style);
    }

    public void scrollDown(int lines) {
        if (lines <= 0) return;
        if (lines > mRows) lines = mRows;
        for (int i = 0; i < lines; i++) {
            // 必须清空"新进入底部的行"，而不是推进后的顶部行。
            // 推进后 mScreenTopRow 指向新的顶部（仍在屏幕内），清它会摧毁可见内容。
            int newBottom = (mScreenTopRow + mRows) % mTotalRows;
            mLines[newBottom].clear(TextStyle.encode(256, 257, 0));
            mScreenTopRow = (mScreenTopRow + 1) % mTotalRows;
        }
        // 记录已写入行数，用于限制视口回滚上限（防止滚到空白历史区）
        mWrittenRows += lines;
        // 用户正在查看历史时，补偿视口偏移，避免新输出把正在看的内容顶走
        if (mViewportOffset > 0) {
            mViewportOffset = Math.min(maxViewportOffset(), mViewportOffset + lines);
        }
    }

    /** 视口可回滚的最大行数：不超过历史区容量，也不超过实际写入的内容行数。 */
    private int maxViewportOffset() {
        return Math.min(mHistoryRows, mWrittenRows);
    }

    public void clearScreen() {
        long defaultStyle = TextStyle.encode(256, 257, 0);
        for (int i = 0; i < mRows; i++) {
            int idx = (mScreenTopRow + i) % mTotalRows;
            mLines[idx].clear(defaultStyle);
        }
    }

    public void eraseLine(int row, int mode) {
        if (row < 0 || row >= mRows) return;
        int idx = (mScreenTopRow + row) % mTotalRows;
        long defaultStyle = TextStyle.encode(256, 257, 0);
        switch (mode) {
            case 0:
                Arrays.fill(mLines[idx].getText(), 0, mColumns, ' ');
                Arrays.fill(mLines[idx].getStyle(), 0, mColumns, defaultStyle);
                break;
            case 1:
                Arrays.fill(mLines[idx].getText(), 0, mColumns, ' ');
                Arrays.fill(mLines[idx].getStyle(), 0, mColumns, defaultStyle);
                break;
            case 2:
                mLines[idx].clear(defaultStyle);
                break;
        }
    }

    /**
     * 标记整个缓冲区需要全量重绘。
     * 主题切换、字体变更等全局样式变动后调用，
     * 渲染层在下一帧绘制前应检查此标志并清除。
     */
    private boolean mFullRedrawNeeded = true;

    public void setFullRedrawNeeded() {
        mFullRedrawNeeded = true;
    }

    public boolean isFullRedrawNeeded() {
        return mFullRedrawNeeded;
    }

    public void clearFullRedrawFlag() {
        mFullRedrawNeeded = false;
    }

    /**
     * 移动可视窗口（上下滚动），不清空任何行内容，也不影响写入位置。
     * @param deltaLines 正数向下滚（看更早的历史），负数向上滚（回到最新输出）
     */
    public void scrollViewport(int deltaLines) {
        if (deltaLines == 0) return;
        com.termi.app.LogStore.getInstance().debug("TermBuffer",
                "[scrollViewport] delta=" + deltaLines + " offset "
                        + mViewportOffset + " → " + (mViewportOffset + deltaLines)
                        + " (max=" + maxViewportOffset() + ")"
                        + " caller=" + stackTraceHint());
        mViewportOffset += deltaLines;
        if (mViewportOffset < 0) mViewportOffset = 0;
        int maxOffset = maxViewportOffset();
        if (mViewportOffset > maxOffset) mViewportOffset = maxOffset;
        mFullRedrawNeeded = true;
    }

    /** 取调用栈中第一个"业务代码"帧（跳过本类自身），用于定位谁改了视口。 */
    private static String stackTraceHint() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            boolean selfSeen = false;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.equals(TerminalBuffer.class.getName())) {
                    selfSeen = true;      // 跳过本类（scrollViewport/stackTraceHint 自身）
                    continue;
                }
                if (selfSeen && cn.startsWith("com.termi.app")) {
                    return cn.substring(cn.lastIndexOf('.') + 1)
                            + "." + e.getMethodName() + ":" + e.getLineNumber();
                }
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    /** 视口是否停在底部（即跟随最新输出）。 */
    public boolean isViewportAtBottom() {
        return mViewportOffset == 0;
    }

    /** 当前视口向上偏移的行数。 */
    public int getViewportOffset() {
        return mViewportOffset;
    }

    /** 把视口拉回底部，跟随最新输出。 */
    public void scrollViewportToBottom() {
        if (mViewportOffset != 0) {
            mViewportOffset = 0;
            mFullRedrawNeeded = true;
        }
    }

    /**
     * 获取当前可视窗口起始行索引（用于调试/日志）。
     */
    public int getScreenTopRow() {
        return mScreenTopRow;
    }
}
