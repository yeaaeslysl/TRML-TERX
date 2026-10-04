package com.termi.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端侧栏（多窗口 tab 栏）。
 *
 * <h3>几何（关键）</h3>
 * 左边线与右边线都是<b>竖直</b>的；只有各 tab 之间的<b>分隔线是斜的</b>，
 * 方向为 {@code /}（左下 → 右上，即由左向右抬升）。
 * <pre>
 * (0,0)────────────(W,0)     tab1 上边：水平
 * │                 │
 * │      tab1       │        左/右边均竖直；左边更长（含倾斜量 s）
 * │                 │
 * (0,H+s)           │
 *       ╲           │
 *         ╲────────(W,H)     分隔线 `/`
 * │                 │
 * │      tab2       │        平行四边形（上边与下边平行同向）
 * │                 │
 * (0,2H+s)─────────(W,2H)
 * │                 │
 * │       ＋        │        上边 `/`、下边水平、右边=下边、左边更短
 * └─────────────────┘
 * </pre>
 * 整体外轮廓是一个矩形，被若干平行斜线切开：tab1 为梯形，中间为平行四边形，
 * 末尾的 ＋ 为"切去左上角的正方形"。
 *
 * <h3>交互</h3>
 * <ul>
 *   <li>点击 tab → {@link Listener#onSelect(int)}（宿主置顶该窗口）</li>
 *   <li>点击 ＋ → {@link Listener#onAdd()}</li>
 *   <li>长按 tab → {@link Listener#onLongPress(int)}（打开选择页）</li>
 *   <li>text 过长时，点击该 tab 会滚动显示全文；3 秒无操作后停止</li>
 * </ul>
 */
public final class TerminalSideBar extends View {

    public interface Listener {
        void onSelect(int index);
        void onAdd();
        void onLongPress(int index);
    }

    /** 每个 tab 右边线的竖直高度。 */
    private static final float TAB_H_DP = 62f;
    /** 侧栏展开时的宽度（收起时归零，避免留黑条）。 */
    private static final float TAB_WIDTH_DP = 40f;
    /** 分隔线在整幅宽度上的抬升量（决定斜度）。 */
    private static final float SLANT_DP = 22f;
    private static final float TEXT_SIZE_DP = 13f;

    // ---- 莫兰迪配色（低调）----
    private static final int COLOR_TAB = 0xFF2F2D37;
    private static final int COLOR_TAB_TOP = 0xFF43404F;   // 当前窗口：略亮
    private static final int COLOR_TAB_ADD = 0xFF2A2831;
    private static final int COLOR_TEXT = 0xFFA8A4B2;
    private static final int COLOR_TEXT_TOP = 0xFFE0DCE8;

    /** 3 秒无操作后停止文字滚动。 */
    private static final long MARQUEE_IDLE_MS = 3000L;

    private final List<String> labels = new ArrayList<>();
    private Listener listener;
    private boolean collapsed = false;

    private float scrollY = 0f;
    private float contentHeight = 0f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // 手势
    private float downX, downY, lastY;
    private boolean dragging = false;
    private int pressedIndex = -1;
    private boolean longPressFired = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = () -> {
        longPressFired = true;
        if (pressedIndex >= 0 && pressedIndex < labels.size() && listener != null) {
            listener.onLongPress(pressedIndex);
        }
    };

    // 文字滚动（仅针对某个 tab）
    private int marqueeIndex = -1;
    private float marqueeOffset = 0f;
    private float marqueeMax = 0f;
    private final Runnable marqueeStep = new Runnable() {
        @Override public void run() {
            if (marqueeIndex < 0) return;
            marqueeOffset += dp(2f);
            if (marqueeOffset >= marqueeMax) {
                marqueeOffset = marqueeMax;
                invalidate();
                return;   // 已滚到全文可见，停止推进
            }
            invalidate();
            handler.postDelayed(this, 40L);
        }
    };
    private final Runnable marqueeReset = () -> {
        marqueeIndex = -1;
        marqueeOffset = 0f;
        invalidate();
    };

    public TerminalSideBar(Context context) {
        super(context);
        init();
    }

    public TerminalSideBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 设置窗口标签（索引 0 = 当前窗口）。 */
    public void setLabels(List<String> list) {
        labels.clear();
        if (list != null) labels.addAll(list);
        recomputeContentHeight();
        invalidate();
    }

    public void setCollapsed(boolean b) {
        if (collapsed != b) {
            collapsed = b;
            if (collapsed) scrollY = 0f;
            recomputeContentHeight();
            // 高度随收起/展开变化（onMeasure 依 collapsed 计算），
            // 必须 requestLayout，否则只 invalidate 不会重新测量。
            requestLayout();
            invalidate();
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    // ------------------------------------------------------------ 几何

    private float tabH() {
        return dp(TAB_H_DP);
    }

    private float slant() {
        return dp(SLANT_DP);
    }

    /** 内容总高：n 个 tab + 末尾的 ＋（＋ 的右边线高度 = 侧栏宽度）。 */
    private void recomputeContentHeight() {
        final float w = getWidth() > 0 ? getWidth() : dp(40f);
        if (collapsed) {
            contentHeight = tabH() + slant();
        } else {
            // tab 区高度（可滚动部分）；＋ 固定占用底部 w 高，不计入滚动内容
            contentHeight = labels.size() * tabH();
        }
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labels.isEmpty()) {
            LogStore.getInstance().debug("SideBar", "[onDraw] labels 为空，跳过绘制");
            return;
        }
        LogStore.getInstance().debug("SideBar", "[onDraw] 绘制 " + labels.size()
                + " 个 tab, w=" + getWidth() + " h=" + getHeight()
                + " scrollY=" + scrollY + " collapsed=" + collapsed);

        final float w = getWidth();
        final float h = tabH();
        final float s = slant();
        final int n = collapsed ? 1 : labels.size();

        // ＋ 固定在最底部：占用【侧栏宽度】作为高度
        final float plusTop = getHeight() - w;
        // tab 区可用高度（到 ＋ 之上为止）
        final float tabsAreaBottom = plusTop;

        // 侧栏整列底板：淡灰半透明，使"侧栏"作为一条竖栏可被看见。
        // 收起时<b>不绘制</b>底板与 ＋，只留当前 tab（避免下方残留一条半透明竖栏）。
        if (!collapsed) {
            backPaint.setStyle(Paint.Style.FILL);
            backPaint.setColor(0x33A8A4B2);      // 淡灰、约 20% 不透明
            backPaint.setShadowLayer(dp(6f), -dp(2f), 0, 0x55000000);
            canvas.drawRect(0f, 0f, w, getHeight(), backPaint);
            backPaint.clearShadowLayer();
        }

        canvas.save();
        canvas.translate(0, -scrollY);

        for (int i = 0; i < n; i++) {
            // 已打开的 tab：分隔线为 "\"（左上 → 右下，即左高右低）。
            // tab1 上边水平；其余 tab 的上边即前一条分隔线（同向，故为平行四边形）。
            float tl = (i == 0) ? 0f : (i * h);            // 左上：靠上
            float tr = (i == 0) ? 0f : (i * h + s);        // 右上：靠下
            float bl = (i + 1) * h;                        // 左下
            float br = (i + 1) * h + s;                    // 右下

            // 超出可视区（被 ＋ 压住）的 tab 不绘制
            if (i * h >= tabsAreaBottom) break;

            drawQuad(canvas, w, tl, tr, bl, br, i == 0 ? COLOR_TAB_TOP : COLOR_TAB);
            drawLabel(canvas, w, tl, tr, bl, br, labels.get(i), i == 0);
        }

        canvas.restore();

        // ＋ 钉在最底部，且倾斜方向与已打开 tab <b>相反</b>（"/"：左下 → 右上）
        if (!collapsed) {
            float tl = plusTop + s;      // 左上：靠下
            float tr = plusTop;          // 右上：靠上
            float bottomY = getHeight();
            drawQuad(canvas, w, tl, tr, bottomY, bottomY, COLOR_TAB_ADD);
            drawPlus(canvas, w, tl, tr, bottomY);
        }
    }

    /** 画一个四边形：左上/右上/左下/右下。 */
    private void drawQuad(Canvas canvas, float w,
                          float tl, float tr, float bl, float br, int color) {
        path.reset();
        path.moveTo(0f, tl);
        path.lineTo(w, tr);
        path.lineTo(w, br);
        path.lineTo(0f, bl);
        path.close();

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(color);
        canvas.drawPath(path, fillPaint);

        linePaint.setStrokeWidth(dp(1f));
        linePaint.setColor(0x22FFFFFF);
        canvas.drawPath(path, linePaint);
    }

    /** 在 tab 中绘制旋转 90° 的文字（沿 tab 长边排列）。 */
    private void drawLabel(Canvas canvas, float w,
                           float tl, float tr, float bl, float br,
                           String text, boolean isTop) {
        float cx = w / 2f;
        float cy = ((tl + tr) / 2f + (bl + br) / 2f) / 2f;

        canvas.save();
        canvas.rotate(90f, cx, cy);
        textPaint.setTextSize(dp(TEXT_SIZE_DP));
        textPaint.setColor(isTop ? COLOR_TEXT_TOP : COLOR_TEXT);

        float offset = (marqueeIndex >= 0 && text.equals(marqueeForText))
                ? marqueeOffset : 0f;
        float baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(text, cx + offset, baseline, textPaint);
        canvas.restore();
    }

    /** 当前正在滚动的文字（用于在绘制时判断是否施加偏移）。 */
    private String marqueeForText = "";

    /** 绘制 ＋。 */
    private void drawPlus(Canvas canvas, float w, float tl, float tr, float bottomY) {
        float cx = w / 2f;
        float cy = ((tl + tr) / 2f + bottomY) / 2f;
        textPaint.setTextSize(dp(18f));
        textPaint.setColor(COLOR_TEXT);
        canvas.drawText("+", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
    }

    // ------------------------------------------------------------ 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastY = e.getY();
                dragging = false;
                longPressFired = false;
                pressedIndex = indexAt(e.getY());
                handler.removeCallbacks(longPressRunnable);
                if (pressedIndex >= 0 && pressedIndex < labels.size()) {
                    handler.postDelayed(longPressRunnable, 500L);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(e.getY() - lastY) > dp(6)) {
                    dragging = true;
                    handler.removeCallbacks(longPressRunnable);
                    if (!collapsed) {
                        // 可滚动高度 = tab 区总高 - （侧栏高度 - ＋ 占用高度）
                        float tabsVisible = getHeight() - getWidth();
                        float max = Math.max(0f, contentHeight - tabsVisible);
                        scrollY -= (e.getY() - lastY);
                        if (scrollY < 0) scrollY = 0;
                        if (scrollY > max) scrollY = max;
                        invalidate();
                    }
                }
                lastY = e.getY();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                boolean isTap = !dragging && !longPressFired
                        && Math.abs(e.getY() - downY) < dp(10)
                        && Math.abs(e.getX() - downX) < dp(10);
                if (isTap && listener != null) {
                    int idx = indexAt(e.getY());
                    int n = labels.size();
                    if (idx >= 0 && idx < n) {
                        startMarqueeIfNeeded(idx);
                        listener.onSelect(idx);
                    } else if (idx == n) {
                        listener.onAdd();
                    }
                }
                pressedIndex = -1;
                dragging = false;
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 根据触点 Y 判断落点。
     *
     * @return 0..n-1 为 tab；n 为 ＋；-1 为空白
     */
    private int indexAt(float y) {
        if (y < 0 || y > getHeight()) return -1;
        final float w = getWidth();
        final float plusTop = getHeight() - w;

        // 落在底部 ＋ 区域内
        if (y >= plusTop) return labels.size();

        // tab 区（需加上滚动偏移还原到内容坐标）
        float realY = y + scrollY;
        if (realY < 0) return -1;
        float h = tabH();
        int idx = (int) (realY / h);
        if (idx < 0 || idx >= labels.size()) return -1;
        return idx;
    }

    // ------------------------------------------------------------ 文字滚动

    /** 若该 tab 文字超出可用长度，则启动滚动；并重置 3 秒空闲计时。 */
    private void startMarqueeIfNeeded(int index) {
        handler.removeCallbacks(marqueeStep);
        handler.removeCallbacks(marqueeReset);

        String text = labels.get(index);
        textPaint.setTextSize(dp(TEXT_SIZE_DP));
        float textW = textPaint.measureText(text);
        float avail = tabH() - dp(10f);   // 旋转后文字沿竖直方向可用长度

        if (textW <= avail) {
            marqueeIndex = -1;
            marqueeOffset = 0f;
            invalidate();
            return;
        }
        marqueeIndex = index;
        marqueeForText = text;
        marqueeOffset = 0f;
        marqueeMax = textW - avail;
        handler.post(marqueeStep);

        // 3 秒无操作则复位
        handler.postDelayed(marqueeReset, MARQUEE_IDLE_MS);
        invalidate();
    }

    // ------------------------------------------------------------ 辅助

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 宽度固定（展开与收起都是 40dp，保证 tab 始终可见可点）
        int w = (int) dp(TAB_WIDTH_DP);
        int h;
        if (collapsed) {
            // 收起：高度只占一个 tab，下方不留空白（避免视觉上的"黑条"）
            h = (int) (tabH() + slant());
        } else {
            // 展开：占满可用高度（＋ 钉在底部，中间为可滚动的 tab 区）。
            // 注意 wrap_content 时 heightMeasureSpec 可能给不出可用高度，
            // 此时退回"按内容估算"，避免被测成 0 而完全不可见。
            int avail = MeasureSpec.getSize(heightMeasureSpec);
            h = (avail > 0) ? avail : (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        }
        LogStore.getInstance().debug("SideBar", "[measure] collapsed=" + collapsed
                + " w=" + w + " h=" + h
                + " hSpec=" + MeasureSpec.toString(heightMeasureSpec));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeContentHeight();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
