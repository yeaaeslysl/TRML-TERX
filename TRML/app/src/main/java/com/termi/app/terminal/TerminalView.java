package com.termi.app.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端视图：普通 View + InputConnection + GestureDetector。
 * 使用 invalidate() 触发 onDraw，由 TerminalRenderer 绘制内容。
 */
public final class TerminalView extends View {
    private TerminalEmulator mEmulator;
    private TerminalRenderer mRenderer;
    private GestureDetector mGestureDetector;
    private int mFontSizePx = 36; // 默认 18dp @ 2x
    private OnKeyListener mExternalKeyListener;
    private OnScrollListener mScrollListener;

    // ─── 文本选择高亮 ──────────────────────────────────────────
    private boolean mHasSelection = false;
    private int mSelRow = -1;
    private final Paint mHighlightPaint = new Paint();

    public interface OnKeyListener {
        boolean onKey(int keyCode, KeyEvent event);
    }

    public interface OnScrollListener {
        void onScroll(int deltaLines);
    }

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);

        mGestureDetector = new GestureDetector(getContext(), new GestureHandler());
        mGestureDetector.setIsLongpressEnabled(true);
        mHighlightPaint.setColor(0x447FE3C4); // moke_jade 半透明
        mHighlightPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 绑定模拟器实例。
     */
    private int mScrollXOffset = 0;

    public void attach(TerminalEmulator emulator) {
        mEmulator = emulator;
        mRenderer = new TerminalRenderer(emulator, mFontSizePx);
        // 主题变更时自动触发重绘
        emulator.setOnThemeChanged(this::postInvalidate);
        postInvalidate();
    }

    /**
     * 设置水平滚动偏移（像素），用于长命令行左右滑动。
     */
    public void setScrollXOffset(int offset) {
        mScrollXOffset = Math.max(0, offset);
        postInvalidate();
    }

    public int getScrollXOffset() { return mScrollXOffset; }

    public void setFontSize(int fontSizePx) {
        mFontSizePx = fontSizePx;
        if (mRenderer != null) {
            mRenderer.setFontSize(fontSizePx);
            postInvalidate();
        }
    }

    public void setOnKeyListener(OnKeyListener listener) {
        mExternalKeyListener = listener;
    }

    public void setOnScrollListener(OnScrollListener listener) {
        mScrollListener = listener;
    }

    /**
     * 外部通知数据已追加到 Emulator，触发重绘。
     */
    public void notifyDataChanged() {
        postInvalidate();
    }

    /** View 尺寸变化时，按实际可容纳的行列数调整缓冲区，消除下方空白并让光标能到底部。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mEmulator == null || mRenderer == null || w <= 0 || h <= 0) return;
        int cols = Math.max(1, (int) (w / mRenderer.getCharWidth()));
        int rows = Math.max(1, h / mRenderer.getCharHeight());
        mEmulator.resizeToView(cols, rows);
    }

    // ─── 绘制 ────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mRenderer == null || mEmulator == null) return;
        mRenderer.draw(canvas, getWidth(), getHeight(), mScrollXOffset);
        // 绘制选区高亮
        if (mHasSelection && mSelRow >= 0) {
            float y = mSelRow * mRenderer.getCharHeight();
            canvas.drawRect(0, y, getWidth(), y + mRenderer.getCharHeight(), mHighlightPaint);
        }
    }

    // ─── 输入连接 ─────────────────────────────────────────────

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new TerminalInputConnection();
    }

    private final class TerminalInputConnection extends BaseInputConnection {
        TerminalInputConnection() {
            super(TerminalView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (text == null || mEmulator == null) return false;
            byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dispatchBytesToSession(bytes);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (mExternalKeyListener != null && mExternalKeyListener.onKey(event.getKeyCode(), event)) {
                return true;
            }
            byte[] seq = keyEventToAnsi(event);
            if (seq != null) {
                dispatchBytesToSession(seq);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength > 0) {
                byte[] bs = new byte[beforeLength];
                java.util.Arrays.fill(bs, (byte) 0x08); // BS
                dispatchBytesToSession(bs);
            }
            return true;
        }
    }

    /**
     * 将字节分发到外部会话管道。
     */
    private void dispatchBytesToSession(byte[] data) {
        if (mSessionOutputSink != null) {
            mSessionOutputSink.write(data);
        } else if (mEmulator != null) {
            mEmulator.append(data);
            notifyDataChanged();
        }
    }

    private OutputSink mSessionOutputSink;

    public interface OutputSink {
        void write(byte[] data);
    }

    public void setOutputSink(OutputSink sink) {
        mSessionOutputSink = sink;
    }

    /** 统一输入入口：物理键/IME 都走这里，交由 OutputSink 处理。 */
    public void feedInput(byte[] data) {
        dispatchBytesToSession(data);
    }

    // ─── 按键 → ANSI 映射 ────────────────────────────────────

    private static byte[] keyEventToAnsi(KeyEvent event) {
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:    return ESC_SEQ_A;
            case KeyEvent.KEYCODE_DPAD_DOWN:  return ESC_SEQ_B;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ESC_SEQ_C;
            case KeyEvent.KEYCODE_DPAD_LEFT:  return ESC_SEQ_D;
            case KeyEvent.KEYCODE_MOVE_HOME:  return ESC_SEQ_H;
            case KeyEvent.KEYCODE_MOVE_END:   return ESC_SEQ_F;
            case KeyEvent.KEYCODE_DEL:        return BS_BYTES;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return CR_BYTES;
            case KeyEvent.KEYCODE_TAB:        return TAB_BYTES;
            case KeyEvent.KEYCODE_ESCAPE:     return ESC_BYTES;
            default:
                int uc = event.getUnicodeChar();
                if (uc > 0) {
                    String s = String.valueOf((char) uc);
                    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return null;
        }
    }

    private static final byte[] ESC_SEQ_A = {0x1b, '[', 'A'};
    private static final byte[] ESC_SEQ_B = {0x1b, '[', 'B'};
    private static final byte[] ESC_SEQ_C = {0x1b, '[', 'C'};
    private static final byte[] ESC_SEQ_D = {0x1b, '[', 'D'};
    private static final byte[] ESC_SEQ_H = {0x1b, '[', 'H'};
    private static final byte[] ESC_SEQ_F = {0x1b, '[', 'F'};
    private static final byte[] BS_BYTES  = {0x08};
    private static final byte[] CR_BYTES  = {'\r'};
    private static final byte[] TAB_BYTES = {'\t'};
    private static final byte[] ESC_BYTES = {0x1b};

    // ─── 手势处理 ─────────────────────────────────────────────

    private final class GestureHandler extends GestureDetector.SimpleOnGestureListener {
        private static final float SCROLL_THRESHOLD_PX = 20f;
        private float mAccumulatedY;

        @Override
        public boolean onDown(MotionEvent e) {
            mAccumulatedY = 0;
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (Math.abs(distanceX) > Math.abs(distanceY)) {
                // 水平滚动：长命令行左右滑动
                int newOffset = mScrollXOffset + (int) distanceX;
                setScrollXOffset(newOffset);
            } else {
                // 垂直滚动：翻历史
                mAccumulatedY += distanceY;
                if (Math.abs(mAccumulatedY) >= SCROLL_THRESHOLD_PX) {
                    int lines = (int) (mAccumulatedY / SCROLL_THRESHOLD_PX);
                    mAccumulatedY -= lines * SCROLL_THRESHOLD_PX;
                    if (mScrollListener != null) {
                        mScrollListener.onScroll(lines);
                    }
                }
            }
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (mEmulator != null && mRenderer != null) {
                String url = getUrlAtPosition(e.getX(), e.getY());
                if (url != null) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        getContext().startActivity(intent);
                    } catch (Exception ignored) {}
                    return true;
                }
            }
            requestFocus();
            showKeyboard();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (mEmulator == null || mRenderer == null) return;
            int row = (int) (e.getY() / mRenderer.getCharHeight());
            TerminalBuffer buffer = mEmulator.getBuffer();
            if (row < 0 || row >= buffer.getRows()) return;

            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < buffer.getColumns(); col++) {
                char ch = buffer.getCharAt(col, row);
                if (ch == 0) ch = ' ';
                sb.append(ch);
            }
            String lineText = sb.toString().trim();
            if (lineText.isEmpty()) return;

            // 设置高亮状态并重绘
            mHasSelection = true;
            mSelRow = row;
            postInvalidate();

            PopupMenu menu = new PopupMenu(getContext(), TerminalView.this);
            menu.getMenu().add("复制该行");
            menu.getMenu().add("复制全部");
            String url = extractFirstUrl(lineText);
            if (url != null) {
                menu.getMenu().add("打开链接");
            }
            menu.setOnMenuItemClickListener(item -> {
                String title = item.getTitle().toString();
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if ("复制该行".equals(title)) {
                    cm.setPrimaryClip(ClipData.newPlainText("terminal", lineText));
                } else if ("复制全部".equals(title)) {
                    cm.setPrimaryClip(ClipData.newPlainText("terminal", getAllVisibleText()));
                } else if ("打开链接".equals(title) && url != null) {
                    try {
                        getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                }
                return true;
            });
            menu.setOnDismissListener(m -> {
                mHasSelection = false;
                mSelRow = -1;
                postInvalidate();
            });
            menu.show();
        }
    }

    /**
     * 强制拉起软键盘（解决 Android 12+ 仅 requestFocus 无效的问题）。
     */
    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mGestureDetector.onTouchEvent(event)) return true;
        return super.onTouchEvent(event);
    }

    // ─── 尺寸协商 ─────────────────────────────────────────────

    /**
     * 根据当前 View 尺寸与字体大小，计算可容纳的行列数。
     */
    public int[] computeGridSize() {
        if (mRenderer == null) return new int[]{80, 24};
        int cols = Math.max(1, (int) (getWidth() / mRenderer.getCharWidth()));
        int rows = Math.max(1, getHeight() / mRenderer.getCharHeight());
        return new int[]{cols, rows};
    }

    // ─── URL 检测与文本提取 ─────────────────────────────────────

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");

    /**
     * 根据点击坐标提取所在行的文本。
     */
    private String getLineAtPosition(float y) {
        if (mRenderer == null || mEmulator == null) return null;
        int row = (int) (y / mRenderer.getCharHeight());
        TerminalBuffer buffer = mEmulator.getBuffer();
        if (row < 0 || row >= buffer.getRows()) return null;
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < buffer.getColumns(); col++) {
            char ch = buffer.getCharAt(col, row);
            if (ch == 0) ch = ' ';
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /**
     * 根据点击坐标检测是否命中 URL，命中则返回完整 URL 字符串。
     */
    private String getUrlAtPosition(float x, float y) {
        String line = getLineAtPosition(y);
        if (line == null) return null;
        Matcher m = URL_PATTERN.matcher(line);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    /**
     * 从文本中提取第一个 URL。
     */
    private String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) return m.group();
        return null;
    }

    /**
     * 获取当前可见区域的全部文本。
     */
    private String getAllVisibleText() {
        if (mEmulator == null) return "";
        TerminalBuffer buffer = mEmulator.getBuffer();
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < buffer.getRows(); row++) {
            for (int col = 0; col < buffer.getColumns(); col++) {
                char ch = buffer.getCharAt(col, row);
                if (ch == 0) ch = ' ';
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
