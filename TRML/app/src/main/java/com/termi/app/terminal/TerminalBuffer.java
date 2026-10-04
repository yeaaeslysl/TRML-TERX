package com.termi.app.terminal;

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
     * 保留原可视窗口内容到新缓冲的顶部，历史回滚区重置。
     */
    public void resize(int newRows) {
        if (newRows < 1) newRows = 1;
        if (newRows == mRows) return;
        int newTotal = newRows + mHistoryRows;
        long defaultStyle = TextStyle.encode(256, 257, 0);
        TerminalRow[] newLines = new TerminalRow[newTotal];
        for (int i = 0; i < newTotal; i++) {
            newLines[i] = new TerminalRow(mColumns, defaultStyle);
        }
        int copyRows = Math.min(mRows, newRows);
        for (int i = 0; i < copyRows; i++) {
            int oldIdx = (mScreenTopRow + i) % mTotalRows;
            newLines[mHistoryRows + i].copyFrom(mLines[oldIdx]);
        }
        mLines = newLines;
        mRows = newRows;
        mTotalRows = newTotal;
        mScreenTopRow = mHistoryRows;
        mActiveTranscriptIndex = mHistoryRows;
        mViewportOffset = 0;
        mFullRedrawNeeded = true;
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
        mViewportOffset += deltaLines;
        if (mViewportOffset < 0) mViewportOffset = 0;
        int maxOffset = maxViewportOffset();
        if (mViewportOffset > maxOffset) mViewportOffset = maxOffset;
        mFullRedrawNeeded = true;
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
