package com.termi.app.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * 终端额外按键栏：ESC / TAB / CTRL / ALT / 方向键 / ENTER
 * 键盘弹出时自动贴在键盘顶部，提供手机键盘缺失的终端专用键。
 */
public final class ExtraKeysView extends HorizontalScrollView {

    public interface OnExtraKeyListener {
        void onExtraKey(String key);
    }

    private OnExtraKeyListener listener;

    private static final String[] KEYS = {
            "ESC", "TAB", "CTRL", "ALT",
            "\u2191", "\u2193", "\u2190", "\u2192",
            "HOME", "END", "PGUP", "PGDN",
            "ENTER"
    };

    public ExtraKeysView(Context context) {
        super(context);
        init();
    }

    public ExtraKeysView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExtraKeysView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void setOnExtraKeyListener(OnExtraKeyListener l) {
        this.listener = l;
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(0xFF161B22);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));

        for (String key : KEYS) {
            row.addView(createKeyButton(key));
        }

        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    private TextView createKeyButton(String label) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(0xFFE6EDF3);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundColor(0xFF21262D);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> {
            if (listener != null) listener.onExtraKey(label);
        });

        tv.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    tv.setBackgroundColor(0xFF7FE3C4);
                    tv.setTextColor(0xFF0E1116);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    tv.setBackgroundColor(0xFF21262D);
                    tv.setTextColor(0xFFE6EDF3);
                    break;
            }
            return false;
        });

        return tv;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
