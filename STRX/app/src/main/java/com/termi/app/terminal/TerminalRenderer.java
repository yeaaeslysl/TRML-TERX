package com.termi.app.terminal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * 终端渲染器：将 TerminalBuffer 的内容绘制到 Canvas 上。
 * 纯拉取模式，不持有回调，每次 draw() 同步读取 Emulator 状态。
 */
public final class TerminalRenderer {
    private final TerminalEmulator mEmulator;
    private final Paint mTextPaint;
    private final Paint mCursorPaint;
    private final Paint mBackgroundPaint;
    private float mCharWidth;
    private int mCharHeight;
    private int mFontSizePx;
    private boolean mCursorBlinkState;
    private long mLastCursorToggleMs;
    private static final long CURSOR_BLINK_INTERVAL_MS = 500;

    public TerminalRenderer(TerminalEmulator emulator, int fontSizePx) {
        mEmulator = emulator;
        mFontSizePx = fontSizePx;

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setTextSize(fontSizePx);
        mTextPaint.setTextAlign(Paint.Align.LEFT);

        mCursorPaint = new Paint();
        mCursorPaint.setStyle(Paint.Style.FILL);

        mBackgroundPaint = new Paint();
        mBackgroundPaint.setStyle(Paint.Style.FILL);

        measureCharSize();
    }

    private void measureCharSize() {
        // 使用等宽字体的固定宽度，取 'W' 作为基准
        mCharWidth = mTextPaint.measureText("W");
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        mCharHeight = (int) Math.ceil(fm.descent - fm.ascent + fm.leading);
    }

    public void setFontSize(int fontSizePx) {
        mFontSizePx = fontSizePx;
        mTextPaint.setTextSize(fontSizePx);
        measureCharSize();
        mEmulator.getBuffer().setFullRedrawNeeded();
    }

    public float getCharWidth() { return mCharWidth; }
    public int getCharHeight() { return mCharHeight; }

    /**
     * 核心绘制方法。由 TerminalView.onDraw() 调用。
     * @param canvas 目标画布
     * @param viewWidth View 宽度（用于裁剪）
     * @param viewHeight View 高度（用于裁剪）
     */
    public void draw(Canvas canvas, int viewWidth, int viewHeight, int scrollXOffset) {
        TerminalBuffer buffer = mEmulator.getBuffer();
        TerminalColors colors = mEmulator.getColors();

        // 检查是否需要全量重绘（主题切换等）
        if (buffer.isFullRedrawNeeded()) {
            buffer.clearFullRedrawFlag();
        }

        int cols = buffer.getColumns();
        int rows = buffer.getRows();

        // 计算可见行数：只绘制 View 实际能容纳的行，避免底部被 ExtraKeys/设置条遮挡
        int maxVisibleRows = (int) (viewHeight / mCharHeight);
        int drawRows = Math.min(rows, maxVisibleRows);

        // 绘制背景（铺满整个 View，避免底部露出父容器颜色形成色差"遮罩"）
        int defaultBg = colors.getColor(TerminalColors.COLOR_INDEX_BACKGROUND, false);
        mBackgroundPaint.setColor(defaultBg);
        canvas.drawRect(0, 0, viewWidth, viewHeight, mBackgroundPaint);

        // 计算可见列范围（支持水平滚动）
        int startCol = (int) (scrollXOffset / mCharWidth);
        int visibleCols = (int) Math.ceil(viewWidth / mCharWidth) + 1;
        int endCol = startCol + visibleCols;

        // 逐行逐列绘制字符（maxVisibleRows/drawRows 已在背景绘制前计算）
        for (int row = 0; row < drawRows; row++) {
            float y = row * mCharHeight - mTextPaint.getFontMetrics().ascent;
            for (int col = startCol; col < endCol; col++) {
                char ch = buffer.getCharAt(col, row);
                long style = buffer.getStyleAt(col, row);

                int fgIdx = TextStyle.decodeForeColor(style);
                int bgIdx = TextStyle.decodeBackColor(style);
                int fx = TextStyle.decodeEffect(style);

                // 处理反色
                if ((fx & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
                    int tmp = fgIdx;
                    fgIdx = bgIdx;
                    bgIdx = tmp;
                }

                // 不可见字符跳过
                if ((fx & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) continue;

                // 绘制单元格背景
                int bgColor = colors.getColor(bgIdx, false);
                if (bgColor != defaultBg) {
                    mBackgroundPaint.setColor(bgColor);
                    float cellW = mCharWidth * WcWidth.width(ch);
                    if (cellW <= 0) cellW = mCharWidth;
                    float drawX = col * mCharWidth - scrollXOffset;
                    canvas.drawRect(
                            drawX,
                            row * mCharHeight,
                            drawX + cellW,
                            (row + 1) * mCharHeight,
                            mBackgroundPaint
                    );
                }

                // 绘制字符
                if (ch != ' ' && ch != 0) {
                    int fgColor = colors.getColor(fgIdx, true);
                    mTextPaint.setColor(fgColor);

                    // 粗体
                    boolean bold = (fx & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0;
                    boolean italic = (fx & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
                    if (bold && italic) {
                        mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
                    } else if (bold) {
                        mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    } else if (italic) {
                        mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC));
                    } else {
                        mTextPaint.setTypeface(Typeface.MONOSPACE);
                    }

                    float drawX = col * mCharWidth - scrollXOffset;
                    canvas.drawText(String.valueOf(ch), drawX, y, mTextPaint);

                    // 下划线
                    if ((fx & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                        mTextPaint.setColor(fgColor);
                        float underlineY = y + mTextPaint.getFontMetrics().descent * 0.3f;
                        float drawX2 = col * mCharWidth - scrollXOffset;
                        canvas.drawLine(
                                drawX2, underlineY,
                                drawX2 + mCharWidth * WcWidth.width(ch), underlineY,
                                mTextPaint
                        );
                    }

                    // 删除线
                    if ((fx & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
                        mTextPaint.setColor(fgColor);
                        float strikeY = y + (mTextPaint.getFontMetrics().ascent + mTextPaint.getFontMetrics().descent) * 0.3f;
                        float drawX2 = col * mCharWidth - scrollXOffset;
                        canvas.drawLine(
                                drawX2, strikeY,
                                drawX2 + mCharWidth * WcWidth.width(ch), strikeY,
                                mTextPaint
                        );
                    }
                }
            }
        }

        // 绘制光标
        if (mEmulator.isCursorVisible()) {
            long now = System.currentTimeMillis();
            if (now - mLastCursorToggleMs >= CURSOR_BLINK_INTERVAL_MS) {
                mCursorBlinkState = !mCursorBlinkState;
                mLastCursorToggleMs = now;
            }
            if (mCursorBlinkState) {
                int cursorCol = mEmulator.getCursorCol();
                // 光标与内容同向移动：内容用 (screenTop - offset) 定位，
                // 光标位于 (screenTop + mCursorRow)，故屏幕行 = mCursorRow + offset。
                int cursorRow = mEmulator.getCursorRow()
                        + mEmulator.getBuffer().getViewportOffset();
                if (cursorRow >= 0 && cursorRow < drawRows) {
                    mCursorPaint.setColor(colors.getColor(TerminalColors.COLOR_INDEX_FOREGROUND, true));
                    float cursorDrawX = cursorCol * mCharWidth - scrollXOffset;
                    canvas.drawRect(
                            cursorDrawX,
                            cursorRow * mCharHeight,
                            cursorDrawX + mCharWidth,
                            (cursorRow + 1) * mCharHeight,
                            mCursorPaint
                    );
                }
            }
        }
    }

    /**
     * 获取当前终端背景色（供 TerminalView 填充底部多余区域）。
     */
    public int getBackgroundColor() {
        return mEmulator.getColors().getColor(TerminalColors.COLOR_INDEX_BACKGROUND, false);
    }
}
