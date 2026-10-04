package com.termi.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * 纵向 ScrollView 的改良版：<b>横向滑动时不拦截触摸</b>。
 *
 * <h3>为什么需要它</h3>
 * 环境页的卡片堆叠需要"左右滑动切换"，但卡片嵌在 {@link ScrollView} 内时，
 * ScrollView 的 {@code onInterceptTouchEvent} 会抢先夺走手势
 * （它把横向位移也当作潜在的纵向滚动开始），导致卡片收不到滑动。
 *
 * <h3>做法</h3>
 * 记录按下点，在 {@code onInterceptTouchEvent} 中比较横纵位移：
 * 横向明显占优时返回 false（不拦截），交由子 View 处理；
 * 纵向占优或难以判断时按父类默认行为（允许滚动）。
 *
 * <p>注意：一旦判定为纵向滚动，本次手势后续都不再交给子 View（交回父类拦截），
 * 避免滚动到一半被卡片抢走。
 */
public class HorizontalFriendlyScrollView extends ScrollView {

    /** 判定方向所需的最小位移（px）。 */
    private static final int TOUCH_SLOP_DP = 8;

    private float downX, downY;
    private boolean decided = false;      // 本次手势方向是否已判定
    private boolean horizontal = false;   // 判定结果：横向

    private final int slop;

    public HorizontalFriendlyScrollView(Context context) {
        super(context);
        slop = (int) (TOUCH_SLOP_DP * context.getResources().getDisplayMetrics().density);
    }

    public HorizontalFriendlyScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = (int) (TOUCH_SLOP_DP * context.getResources().getDisplayMetrics().density);
    }

    public HorizontalFriendlyScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        slop = (int) (TOUCH_SLOP_DP * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                decided = false;
                horizontal = false;
                // 交给父类记录按下状态，但不立即拦截
                super.onInterceptTouchEvent(e);
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!decided) {
                    float dx = Math.abs(e.getX() - downX);
                    float dy = Math.abs(e.getY() - downY);
                    if (dx > slop || dy > slop) {
                        decided = true;
                        horizontal = dx > dy * 1.2f;   // 横向明显占优才算横向
                    }
                }
                if (horizontal) {
                    // 横向手势：不拦截，交给卡片
                    return false;
                }
                // 纵向或未定：交回父类判断（允许纵向滚动）
                return super.onInterceptTouchEvent(e);

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                decided = false;
                horizontal = false;
                return super.onInterceptTouchEvent(e);

            default:
                return super.onInterceptTouchEvent(e);
        }
    }
}
