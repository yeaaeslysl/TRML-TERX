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

    /** 环境切换按钮的标签（点击回调给宿主，由宿主决定行为）。 */
    public static final String KEY_EXIT = "EXIT";
    public static final String KEY_LOGIN = "LOGIN";

    private static final String[] KEYS = {
            "ESC", "TAB", "CTRL", "ALT",
            "\u2191", "\u2193", "\u2190", "\u2192",
            "HOME", "END", "PGUP", "PGDN",
            "ENTER"
    };

    /** 环境切换按钮（始终在最前，颜色随状态变化）。 */
    private TextView envButton;
    /** 当前是否处于 Linux 环境（决定按钮文字与颜色）。 */
    private boolean inLinuxEnv = false;

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

    /**
     * 更新环境切换按钮状态。
     *
     * @param inLinux true = 当前在 Linux 环境内（显示红色 EXIT）
     *                false = 当前在 Android shell（显示蓝色 LOGIN）
     */
    public void setEnvState(boolean inLinux) {
        this.inLinuxEnv = inLinux;
        if (envButton == null) return;
        if (inLinux) {
            envButton.setText(KEY_EXIT);
            envButton.setBackgroundColor(0xFFB3261E); // 红底
            envButton.setTextColor(0xFFFFFFFF);       // 白字
        } else {
            envButton.setText(KEY_LOGIN);
            envButton.setBackgroundColor(0xFF1A73E8); // 蓝底
            envButton.setTextColor(0xFFFFFFFF);       // 白字
        }
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(0xFF161B22);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(4));

        // 环境切换按钮放最前（醒目、便于点按）
        envButton = createKeyButton(KEY_LOGIN);
        row.addView(envButton);
        setEnvState(inLinuxEnv);

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
            if (listener != null) {
                // 用「当前文本」而非创建时的 label 参数：
                // 环境按钮的文字会在 LOGIN / EXIT 之间切换，
                // 若捕获 label 会一直上报创建时的旧值（导致点 EXIT 实际发送 LOGIN）。
                listener.onExtraKey(tv.getText().toString());
            }
        });

        tv.setOnTouchListener((v, event) -> {
            boolean isEnvBtn = (tv == envButton);
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (isEnvBtn) {
                        // 环境按钮：按下时加深自身配色，不用统一的薄荷绿
                        tv.setBackgroundColor(inLinuxEnv ? 0xFF8C1D18 : 0xFF0B57D0);
                        tv.setTextColor(0xFFFFFFFF);
                    } else {
                        tv.setBackgroundColor(0xFF7FE3C4);
                        tv.setTextColor(0xFF0E1116);
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (isEnvBtn) {
                        // 环境按钮：恢复红/蓝配色（不能用默认灰）
                        setEnvState(inLinuxEnv);
                    } else {
                        tv.setBackgroundColor(0xFF21262D);
                        tv.setTextColor(0xFFE6EDF3);
                    }
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
