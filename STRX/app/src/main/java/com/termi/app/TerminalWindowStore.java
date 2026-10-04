package com.termi.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端多窗口的状态存储（侧栏 tab 的数据层）。
 *
 * <h3>模型</h3>
 * 每个"窗口"= 一个独立的终端会话（自己的 TerminalEmulator 缓冲、自己的环境）。
 * 本类只负责<b>持久化窗口列表与顺序</b>；运行期的进程/缓冲由 MainActivity 持有。
 *
 * <h3>顺序语义</h3>
 * 列表<b>索引 0 = 最上方的 tab = 当前激活窗口</b>。
 * 点击中间某 tab 时把它移到索引 0（与最上方交换位置），即"最近使用排最上"。
 *
 * <h3>持久化内容</h3>
 * 窗口顺序 + 每个窗口的环境名（alpine/ubuntu/android）。
 * 终端输出历史<b>不</b>持久化（仅存活于进程内，与"浏览器标签页"语义一致）。
 */
public final class TerminalWindowStore {

    private static final String PREFS = "terx_windows";
    private static final String KEY_WINDOWS = "windows";   // 逗号分隔的环境名，索引 0 为当前

    /** 表示"Android 原生 shell"这一特殊"环境"。 */
    public static final String ENV_ANDROID = "__android__";

    private static final String KEY_SEQ = "next_seq";

    /** 一个窗口的持久化信息。 */
    public static final class Item {
        /** 环境名（ENV_ANDROID 或具体 Linux 环境名）。 */
        public String env;
        /** 创建序号（从 1 递增，与 tab 绑定，不随排序变化）。 */
        public int seq;

        public Item(String env, int seq) {
            this.env = env;
            this.seq = seq;
        }
    }

    private TerminalWindowStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 下个可用序号（全局递增，保证同环境多窗口编号不重复）。 */
    public static int newSeq(Context ctx) {
        int n = prefs(ctx).getInt(KEY_SEQ, 1);
        prefs(ctx).edit().putInt(KEY_SEQ, n + 1).apply();
        return n;
    }

    /**
     * 读取窗口列表（索引 0 = 当前窗口）。
     * 存储格式：每项为 {@code env|seq}，用逗号分隔。
     *
     * <p>若发现序号缺失或非法（{@code seq <= 0}），就地补一个新序号并回写，
     * 避免坏值继续传播（曾出现过 {@code seq=0} 导致标签异常）。
     */
    public static List<Item> list(Context ctx) {
        String raw = prefs(ctx).getString(KEY_WINDOWS, "");
        List<Item> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        boolean repaired = false;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            int bar = t.lastIndexOf('|');
            String env;
            int seq = 0;
            if (bar < 0) {
                env = t;                     // 旧格式：无序号
            } else {
                env = t.substring(0, bar);
                try { seq = Integer.parseInt(t.substring(bar + 1)); } catch (Throwable ignored) {}
            }
            if (seq <= 0) {
                seq = newSeq(ctx);          // 补一个合法序号
                repaired = true;
            }
            out.add(new Item(env, seq));
        }
        if (repaired) save(ctx, out);
        return out;
    }

    /** 保存窗口列表（索引 0 = 当前窗口）。 */
    public static void save(Context ctx, List<Item> windows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) sb.append(',');
            Item it = windows.get(i);
            sb.append(it.env).append('|').append(it.seq);
        }
        prefs(ctx).edit().putString(KEY_WINDOWS, sb.toString()).apply();
    }

    /** 删除指定索引的窗口。 */
    public static void remove(Context ctx, int index) {
        List<Item> list = list(ctx);
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        save(ctx, list);
    }

    /**
     * 把指定索引的窗口移到最前（索引 0）。
     *
     * <p>序号随 tab 一起移动（编号与窗口绑定，不因排序变化）。
     */
    public static void moveToFront(Context ctx, int index) {
        List<Item> list = list(ctx);
        if (index <= 0 || index >= list.size()) return;
        Item e = list.remove(index);
        list.add(0, e);
        save(ctx, list);
    }

    /** 修改指定窗口的环境（序号不变，仍与 tab 绑定）。 */
    public static void setEnv(Context ctx, int index, String envName) {
        List<Item> list = list(ctx);
        if (index < 0 || index >= list.size()) return;
        list.get(index).env = envName;
        save(ctx, list);
    }

    /** 环境名的展示文本。 */
    public static String envLabel(String envName) {
        if (ENV_ANDROID.equals(envName)) return "Android";
        return envName;
    }

    /** 是否是 Linux 环境（非 Android shell）。 */
    public static boolean isLinux(String envName) {
        return !ENV_ANDROID.equals(envName);
    }

    /**
     * 计算每个窗口的显示标签。
     *
     * <p>规则：<b>只有同一环境存在多个窗口时才附加序号</b>；
     * 环境唯一时只显示环境名（避免无意义的编号）。
     *
     * @return 与 items 等长的标签列表
     */
    public static List<String> buildLabels(List<Item> items) {
        // 统计每个环境出现次数
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (Item it : items) {
            count.put(it.env, count.getOrDefault(it.env, 0) + 1);
        }
        List<String> out = new ArrayList<>();
        for (Item it : items) {
            String base = envLabel(it.env);
            boolean dup = count.getOrDefault(it.env, 0) > 1;
            out.add(dup ? (base + " " + it.seq) : base);
        }
        return out;
    }
}
