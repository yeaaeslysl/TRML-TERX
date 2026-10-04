package com.termi.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 持久化环境的存储与路径解析（支持多环境）。
 *
 * <h3>目录结构</h3>
 * <pre>
 * /data/local/tmp/terx/            ← shell_data_file：app 与 shell 均可读
 * ├── lib/                         ← proot 依赖软链（共用，两身份可读）
 * ├── tmp/                         ← PROOT_TMP_DIR（共用）
 * └── envs/
 *     ├── alpine/                  ← 环境 1
 *     │   ├── rootfs/
 *     │   └── resolv.conf
 *     └── ubuntu/                  ← 环境 2
 *         ├── rootfs/
 *         └── resolv.conf
 * </pre>
 *
 * <h3>为什么放 /data/local/tmp</h3>
 * 这是<b>唯一</b>终端（app 身份）与 MCP（shell 身份）都能读的位置。
 * App 私有目录的 SELinux 标签是 {@code app_data_file}，shell 域无权访问，
 * 导致 MCP 无法使用环境。详见 {@link ShizukuFs}。
 *
 * <p>注意：app 身份对 {@code /data/local/tmp} <b>只读</b>，
 * 所有写入必须经 {@link ShizukuFs}。
 */
public final class EnvStore {

    public static final String PREFS_NAME = "terx_env";

    /** 环境根目录（与 {@link ShizukuFs#ENV_ROOT} 保持一致）。 */
    public static final String ENV_ROOT = ShizukuFs.ENV_ROOT;

    // ---- 键 ----
    private static final String KEY_DISTRO = "distro";           // 当前激活环境的发行版
    private static final String KEY_INSTALLED = "installed";     // 当前激活环境是否就绪
    private static final String KEY_SOURCE_NAME = "source_name"; // 上次导入的包名
    private static final String KEY_ACTIVE = "active_env";       // 当前激活环境名
    private static final String KEY_ONBOARD_DONE = "onboard_done";
    /** 环境名列表（逗号分隔）。 */
    private static final String KEY_ENV_LIST = "env_list";

    /** 默认环境名。 */
    public static final String DEFAULT_ENV = "default";

    private EnvStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 路径

    /** 环境根目录（File 形式，仅用于展示；写入需走 ShizukuFs）。 */
    public static File envRoot() {
        return new File(ENV_ROOT);
    }

    /** 所有环境的父目录。 */
    public static File envsDir() {
        return new File(ENV_ROOT, "envs");
    }

    /** proot 依赖软链目录（两身份可读）。 */
    public static File libDir() {
        return new File(ENV_ROOT, "lib");
    }

    /** proot 临时目录。 */
    public static File tmpDir() {
        return new File(ENV_ROOT, "tmp");
    }

    /** 指定环境的目录。 */
    public static File envDir(String envName) {
        return new File(envsDir(), envName);
    }

    /** 当前激活环境的目录。 */
    public static File activeEnvDir(Context ctx) {
        return envDir(getActiveEnv(ctx));
    }

    /** 指定环境的 rootfs 目录。 */
    public static File rootfsDir(String envName) {
        return new File(envDir(envName), "rootfs");
    }

    /** 当前激活环境的 rootfs 目录。 */
    public static File rootfsDir(Context ctx) {
        return rootfsDir(getActiveEnv(ctx));
    }

    /** 指定环境的 resolv.conf。 */
    public static File resolvConf(String envName) {
        return new File(envDir(envName), "resolv.conf");
    }

    /** 当前激活环境的 resolv.conf。 */
    public static File resolvConf(Context ctx) {
        return resolvConf(getActiveEnv(ctx));
    }

    // ------------------------------------------------------------ 激活环境

    /** 当前激活的环境名。 */
    public static String getActiveEnv(Context ctx) {
        String s = prefs(ctx).getString(KEY_ACTIVE, "");
        if (s == null || s.isEmpty()) {
            // 兼容旧版：若存在单环境标记，使用默认名
            return DEFAULT_ENV;
        }
        return s;
    }

    public static void setActiveEnv(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_ACTIVE, name).apply();
        // 切换后同步该环境的元信息
        String distro = prefs(ctx).getString(KEY_DISTRO + "_" + name, "");
        boolean installed = prefs(ctx).getBoolean(KEY_INSTALLED + "_" + name, false);
        String src = prefs(ctx).getString(KEY_SOURCE_NAME + "_" + name, "");
        prefs(ctx).edit()
                .putString(KEY_DISTRO, distro)
                .putBoolean(KEY_INSTALLED, installed)
                .putString(KEY_SOURCE_NAME, src)
                .apply();
    }

    // ------------------------------------------------------------ 环境列表

    /** 已登记的环境名列表（按登记顺序）。 */
    public static List<String> listEnvs(Context ctx) {
        String raw = prefs(ctx).getString(KEY_ENV_LIST, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 登记一个新环境（幂等）。 */
    public static void registerEnv(Context ctx, String name) {
        List<String> list = listEnvs(ctx);
        if (!list.contains(name)) {
            list.add(name);
            prefs(ctx).edit().putString(KEY_ENV_LIST, join(list)).apply();
        }
    }

    /** 注销一个环境。 */
    public static void unregisterEnv(Context ctx, String name) {
        List<String> list = listEnvs(ctx);
        list.remove(name);
        prefs(ctx).edit()
                .putString(KEY_ENV_LIST, join(list))
                .remove(KEY_DISTRO + "_" + name)
                .remove(KEY_INSTALLED + "_" + name)
                .remove(KEY_SOURCE_NAME + "_" + name)
                .apply();
        // 若注销的是当前激活环境，回退到默认
        if (name.equals(getActiveEnv(ctx))) {
            String next = list.isEmpty() ? DEFAULT_ENV : list.get(0);
            setActiveEnv(ctx, next);
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 元信息（按环境）

    public static String getDistro(Context ctx) {
        return prefs(ctx).getString(KEY_DISTRO, "");
    }

    public static void setDistro(Context ctx, String distro) {
        setDistro(ctx, getActiveEnv(ctx), distro);
    }

    public static void setDistro(Context ctx, String envName, String distro) {
        prefs(ctx).edit()
                .putString(KEY_DISTRO, distro)
                .putString(KEY_DISTRO + "_" + envName, distro)
                .apply();
    }

    public static String getDistroOf(Context ctx, String envName) {
        return prefs(ctx).getString(KEY_DISTRO + "_" + envName, "");
    }

    public static boolean isInstalled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_INSTALLED, false);
    }

    public static void setInstalled(Context ctx, boolean v) {
        setInstalled(ctx, getActiveEnv(ctx), v);
    }

    public static void setInstalled(Context ctx, String envName, boolean v) {
        prefs(ctx).edit()
                .putBoolean(KEY_INSTALLED, v)
                .putBoolean(KEY_INSTALLED + "_" + envName, v)
                .apply();
    }

    public static boolean isInstalledOf(Context ctx, String envName) {
        return prefs(ctx).getBoolean(KEY_INSTALLED + "_" + envName, false);
    }

    public static String getSourceName(Context ctx) {
        return prefs(ctx).getString(KEY_SOURCE_NAME, "");
    }

    public static void setSourceName(Context ctx, String name) {
        setSourceName(ctx, getActiveEnv(ctx), name);
    }

    public static void setSourceName(Context ctx, String envName, String name) {
        prefs(ctx).edit()
                .putString(KEY_SOURCE_NAME, name)
                .putString(KEY_SOURCE_NAME + "_" + envName, name)
                .apply();
    }

    // ------------------------------------------------------------ 兼容旧接口

    /** 兼容旧调用：等价于当前激活环境目录。 */
    public static File envDir(Context ctx) {
        return activeEnvDir(ctx);
    }

    /** 兼容旧调用（位置参数已废弃，恒为 tmp 方案）。 */
    public static String getLocation(Context ctx) {
        return "tmp";
    }

    /** 兼容旧调用（位置参数已废弃）。 */
    public static void setLocation(Context ctx, String loc) {
        // no-op
    }

    /** 位置展示文本。 */
    public static String locationLabel(String loc) {
        return "data/local/tmp（终端与 MCP 共享）";
    }

    /** 是否需要 Shizuku：现在恒为 true（写入 /data/local/tmp 必须走 Shizuku）。 */
    public static boolean needsShizuku(String loc) {
        return true;
    }

    // ------------------------------------------------------------ 引导页标记

    public static boolean isOnboardDone(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ONBOARD_DONE, false);
    }

    public static void markOnboardDone(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARD_DONE, true).apply();
    }

    public static void clearOnboardDone(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARD_DONE, false).apply();
    }

    /** 是否应该显示引导页：引导未完成 且 环境未安装。 */
    public static boolean shouldShowOnboarding(Context ctx) {
        return !isOnboardDone(ctx) && !isInstalled(ctx);
    }

    /** 排序后的环境列表（便于界面展示）。 */
    public static List<String> sortedEnvs(Context ctx) {
        List<String> list = listEnvs(ctx);
        Collections.sort(list);
        return list;
    }
}
