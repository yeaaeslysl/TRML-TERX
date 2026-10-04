package com.termi.app.terminal;

import java.util.Arrays;

public final class TerminalRow {
    private char[] mText;
    private long[] mStyle;
    private int mSpaceUsed;
    private boolean mHasNonSpace;

    public TerminalRow(int columns, long style) {
        mText = new char[columns];
        mStyle = new long[columns];
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
        mSpaceUsed = columns;
    }

    public int getSpaceUsed() { return mSpaceUsed; }
    public char[] getText() { return mText; }
    public long[] getStyle() { return mStyle; }

    public void setChar(int column, char c, long style) {
        if (column >= mText.length) return;
        mText[column] = c;
        mStyle[column] = style;
        if (c != ' ') mHasNonSpace = true;
    }

    public boolean isBlank() { return !mHasNonSpace; }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
        mHasNonSpace = false;
    }

    /** 从另一行复制内容（用于 buffer 尺寸变更时保留可视内容）。 */
    public void copyFrom(TerminalRow src) {
        if (src == null) return;
        int n = Math.min(mText.length, src.mText.length);
        System.arraycopy(src.mText, 0, mText, 0, n);
        System.arraycopy(src.mStyle, 0, mStyle, 0, n);
        mHasNonSpace = src.mHasNonSpace;
    }
}
