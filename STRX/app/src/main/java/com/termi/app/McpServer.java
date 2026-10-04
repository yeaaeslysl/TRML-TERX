package com.termi.app;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

public final class McpServer {

    private static volatile Consumer<String> sCommandEchoListener;
    public static void setCommandEchoListener(Consumer<String> l) { sCommandEchoListener = l; }

    private static volatile Consumer<String> sOutputEchoListener;
    public static void setOutputEchoListener(Consumer<String> l) { sOutputEchoListener = l; }

    /**
     * 终端 tab（窗口）控制桥。
     *
     * <p>MCP 服务跑在独立线程，而 tab 列表属于 UI 状态，必须在主线程操作。
     * 故由 MainActivity 注册一个桥接实现，MCP 通过它请求主线程执行。
     * 接口方法均在<b>主线程</b>被调用。
     */
    public interface TabBridge {
        /** 列出所有 tab：返回每项 "环境名|序号|是否当前"。 */
        java.util.List<String> listTabs();
        /** 新建 tab（指定环境名，可空=Android）。返回新 tab 的序号，失败返回 -1。 */
        int openTab(String envName);
        /** 关闭指定序号的 tab。返回是否成功。 */
        boolean closeTab(int seq);
        /** 把指定序号的 tab 置顶（切换）。返回是否成功。 */
        boolean selectTab(int seq);
    }

    private static volatile TabBridge sTabBridge;
    public static void setTabBridge(TabBridge b) { sTabBridge = b; }

    /**
     * 终端诊断桥：由 MainActivity 注册，供 MCP 读取当前终端缓冲状态（排障用）。
     */
    public interface TermBridge {
        /** 返回终端状态摘要（行列、光标、视口偏移、已写行数、屏幕文本快照）。 */
        String dumpState();
    }

    private static volatile TermBridge sTermBridge;
    public static void setTermBridge(TermBridge b) { sTermBridge = b; }

    /** 把任务投递到主线程并等待结果（供 MCP 线程调用）。 */
    private static <T> T onMain(java.util.concurrent.Callable<T> task, T fallback) {
        final java.util.concurrent.FutureTask<T> ft = new java.util.concurrent.FutureTask<>(task);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(ft);
        try {
            return ft.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            LogStore.getInstance().error("McpServer", "onMain 失败: " + t);
            return fallback;
        }
    }

    private final String host;
    private final int port;
    @Nullable private final String token;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public McpServer(String host, int port, @Nullable String token) {
        this.host = host;
        this.port = port;
        this.token = (token == null || token.isEmpty()) ? null : token;
    }

    public synchronized void start() {
        if (running) return;
        try {
            serverSocket = new ServerSocket(port);
        } catch (Exception e) {
            LogStore.getInstance().append("ERROR", "McpServer",
                    "bind failed on " + host + ":" + port + " -> " + e);
            throw new RuntimeException("bind failed: " + e.getMessage(), e);
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LogStore.getInstance().append("INFO", "McpServer",
                "server started on " + host + ":" + port + " token=" + (token != null));
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverSocket = null;
        acceptThread = null;
        LogStore.getInstance().append("INFO", "McpServer", "server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = serverSocket.accept();
                new Thread(() -> handle(sock), "mcp-conn").start();
            } catch (Exception e) {
                if (running) running = false;
            }
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String header = readHttpHeader(in);
            if (header == null || header.isEmpty()) {
                writeResponse(out, 400, "Bad Request");
                sock.close();
                return;
            }

            String[] lines = header.split("\r\n");
            String requestLine = lines[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeResponse(out, 400, "Bad Request");
                sock.close();
                return;
            }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            for (String l : lines) {
                if (l.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(l.substring(l.indexOf(':') + 1).trim());
                    } catch (Exception ignored) {}
                }
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n <= 0) break;
                read += n;
            }

            if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
                writeResponse(out, 200, "ok");
            } else if ("GET".equalsIgnoreCase(method) && "/mcp".equals(path)) {
                writeResponse(out, 200, "MCP Streamable HTTP ready. POST JSON-RPC here.");
            } else if ("POST".equalsIgnoreCase(method) && "/mcp".equals(path)) {
                String reqBody = new String(body, StandardCharsets.UTF_8);
                LogStore.getInstance().append("DEBUG", "McpServer", "POST /mcp body=" + reqBody);
                String resp = dispatch(reqBody);
                LogStore.getInstance().append("DEBUG", "McpServer", "POST /mcp resp=" + resp);
                if (resp == null) {
                    // 通知类请求：返回 202 空响应，不写 JSON 体
                    writeResponse(out, 202, "");
                } else {
                    writeJsonResponse(out, 200, resp);
                }
            } else {
                writeResponse(out, 404, "Not Found");
            }
            sock.close();
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private String readHttpHeader(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
            if (buf.size() > 65536) break;
        }
        return buf.toString("UTF-8");
    }

    private void writeResponse(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void writeJsonResponse(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String dispatch(String reqBody) {
        try {
            JSONObject req = new JSONObject(reqBody);
            String method = req.optString("method", "");
            boolean isNotification = !req.has("id");
            Object id = req.opt("id");

            if (isNotification) {
                // JSON-RPC 通知：不返回任何响应体
                return null;
            }

            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);

            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("protocolVersion", "2024-11-05");
                JSONObject info = new JSONObject();
                info.put("name", "shizuku-mcp");
                info.put("version", "1.0.0");
                result.put("serverInfo", info);
                JSONObject caps = new JSONObject();
                caps.put("tools", new JSONObject());
                result.put("capabilities", caps);
                resp.put("result", result);
            } else if ("notifications/initialized".equals(method)) {
                resp.put("result", JSONObject.NULL);
            } else if ("tools/list".equals(method)) {
                resp.put("result", buildToolsList());
            } else if ("tools/call".equals(method)) {
                JSONObject params = req.optJSONObject("params");
                resp.put("result", handleToolCall(params));
            } else {
                JSONObject err = new JSONObject();
                err.put("code", -32601);
                err.put("message", "method not found");
                resp.put("error", err);
            }
            return resp.toString();
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"parse error\"}}";
        }
    }

    private JSONObject buildToolsList() throws Exception {
        JSONObject result = new JSONObject();
        JSONArray tools = new JSONArray();

        JSONObject shellExec = new JSONObject();
        shellExec.put("name", "shell_exec");
        shellExec.put("description",
                "通过 Shizuku 以 shell/root 权限执行命令。"
                + "可选参数 env：指定后命令在对应的 Linux 环境内执行"
                + "（如 env=\"alpine\"），省略则在 Android 原生 shell 中执行。");
        JSONObject schema1 = new JSONObject();
        schema1.put("type", "object");
        JSONObject props1 = new JSONObject();
        props1.put("cmd", typeObj("string"));
        props1.put("cwd", typeObj("string"));
        props1.put("timeoutSec", typeObj("number"));
        props1.put("useRoot", typeObj("boolean"));
        JSONObject envProp = typeObj("string");
        envProp.put("description",
                "Linux 环境名（如 alpine）。指定后在该环境的 rootfs 内执行命令，"
                + "可使用 apt/apk 安装的工具；省略则在 Android shell 执行。");
        props1.put("env", envProp);
        schema1.put("properties", props1);
        JSONArray req1 = new JSONArray();
        req1.put("cmd");
        schema1.put("required", req1);
        shellExec.put("inputSchema", schema1);
        tools.put(shellExec);

        JSONObject sysInfo = new JSONObject();
        sysInfo.put("name", "sys_info");
        sysInfo.put("description", "系统信息：uname / 内存 / 存储");
        JSONObject schema2 = new JSONObject();
        schema2.put("type", "object");
        schema2.put("properties", new JSONObject());
        sysInfo.put("inputSchema", schema2);
        tools.put(sysInfo);

        JSONObject pkgList = new JSONObject();
        pkgList.put("name", "pkg_list");
        pkgList.put("description", "列出已安装包名，支持过滤前缀");
        JSONObject schema3 = new JSONObject();
        schema3.put("type", "object");
        JSONObject props3 = new JSONObject();
        props3.put("filter", typeObj("string"));
        schema3.put("properties", props3);
        pkgList.put("inputSchema", schema3);
        tools.put(pkgList);

        // === view_image ===
        JSONObject viewImage = new JSONObject();
        viewImage.put("name", "view_image");
        viewImage.put("description", "Read an image file and return Base64-encoded PNG. Supports jpg/png/webp. Max 2MB.");
        JSONObject schema4 = new JSONObject();
        schema4.put("type", "object");
        JSONObject props4 = new JSONObject();
        props4.put("path", typeObj("string"));
        schema4.put("properties", props4);
        schema4.put("required", new JSONArray().put("path"));
        viewImage.put("inputSchema", schema4);
        tools.put(viewImage);

        // === read_file ===
        JSONObject readFile = new JSONObject();
        readFile.put("name", "read_file");
        readFile.put("description", "Read text content of a file as UTF-8. Max 1MB.");
        JSONObject schema5 = new JSONObject();
        schema5.put("type", "object");
        JSONObject props5 = new JSONObject();
        props5.put("path", typeObj("string"));
        schema5.put("properties", props5);
        schema5.put("required", new JSONArray().put("path"));
        readFile.put("inputSchema", schema5);
        tools.put(readFile);

        // === write_file ===
        JSONObject writeFile = new JSONObject();
        writeFile.put("name", "write_file");
        writeFile.put("description", "Write text content to a file. Creates parent dirs if needed. Overwrites existing.");
        JSONObject schema6 = new JSONObject();
        schema6.put("type", "object");
        JSONObject props6 = new JSONObject();
        props6.put("path", typeObj("string"));
        props6.put("content", typeObj("string"));
        schema6.put("properties", props6);
        schema6.put("required", new JSONArray().put("path").put("content"));
        writeFile.put("inputSchema", schema6);
        tools.put(writeFile);

        // === env_list：列出所有 Linux 环境 ===
        JSONObject envList = new JSONObject();
        envList.put("name", "env_list");
        envList.put("description",
                "列出所有已安装的 Linux 环境（名称、发行版、占用大小），"
                + "并标出当前激活环境。STRX 独有能力。");
        JSONObject schemaEnvList = new JSONObject();
        schemaEnvList.put("type", "object");
        schemaEnvList.put("properties", new JSONObject());
        envList.put("inputSchema", schemaEnvList);
        tools.put(envList);

        // === env_packages：列出某环境的已装软件包 ===
        JSONObject envPkgs = new JSONObject();
        envPkgs.put("name", "env_packages");
        envPkgs.put("description",
                "列出指定 Linux 环境内已安装的软件包及其大小（按大小降序）。"
                + "省略 env 则用当前激活环境。");
        JSONObject schemaEnvPkgs = new JSONObject();
        schemaEnvPkgs.put("type", "object");
        JSONObject propsEnvPkgs = new JSONObject();
        propsEnvPkgs.put("env", typeObj("string"));
        schemaEnvPkgs.put("properties", propsEnvPkgs);
        envPkgs.put("inputSchema", schemaEnvPkgs);
        tools.put(envPkgs);

        // === env_switch：切换当前激活环境 ===
        JSONObject envSwitch = new JSONObject();
        envSwitch.put("name", "env_switch");
        envSwitch.put("description",
                "切换当前激活的 Linux 环境（终端与 MCP 的 env 参数默认使用它）。");
        JSONObject schemaEnvSwitch = new JSONObject();
        schemaEnvSwitch.put("type", "object");
        JSONObject propsEnvSwitch = new JSONObject();
        propsEnvSwitch.put("env", typeObj("string"));
        schemaEnvSwitch.put("properties", propsEnvSwitch);
        schemaEnvSwitch.put("required", new JSONArray().put("env"));
        envSwitch.put("inputSchema", schemaEnvSwitch);
        tools.put(envSwitch);

        // === file_search：在 Linux 环境内搜索文件 ===
        JSONObject fileSearch = new JSONObject();
        fileSearch.put("name", "file_search");
        fileSearch.put("description",
                "在指定 Linux 环境内按文件名/内容搜索文件（基于 find/grep）。"
                + "省略 env 则用当前激活环境。");
        JSONObject schemaFileSearch = new JSONObject();
        schemaFileSearch.put("type", "object");
        JSONObject propsFileSearch = new JSONObject();
        propsFileSearch.put("pattern", typeObj("string"));
        propsFileSearch.put("path", typeObj("string"));
        propsFileSearch.put("by_content", typeObj("boolean"));
        propsFileSearch.put("env", typeObj("string"));
        schemaFileSearch.put("properties", propsFileSearch);
        schemaFileSearch.put("required", new JSONArray().put("pattern"));
        fileSearch.put("inputSchema", schemaFileSearch);
        tools.put(fileSearch);

        // === tab_list：列出终端 tab ===
        JSONObject tabList = new JSONObject();
        tabList.put("name", "tab_list");
        tabList.put("description",
                "列出终端的所有 tab（窗口）：序号、环境、是否为当前。"
                + "每个 tab 是独立会话，互不干扰。");
        JSONObject schemaTabList = new JSONObject();
        schemaTabList.put("type", "object");
        schemaTabList.put("properties", new JSONObject());
        tabList.put("inputSchema", schemaTabList);
        tools.put(tabList);

        // === tab_open：新建 tab ===
        JSONObject tabOpen = new JSONObject();
        tabOpen.put("name", "tab_open");
        tabOpen.put("description",
                "新建一个终端 tab。可指定 env（Linux 环境名），省略则建 Android shell tab。"
                + "返回新 tab 的序号。");
        JSONObject schemaTabOpen = new JSONObject();
        schemaTabOpen.put("type", "object");
        JSONObject propsTabOpen = new JSONObject();
        propsTabOpen.put("env", typeObj("string"));
        schemaTabOpen.put("properties", propsTabOpen);
        tabOpen.put("inputSchema", schemaTabOpen);
        tools.put(tabOpen);

        // === tab_close：关闭 tab ===
        JSONObject tabClose = new JSONObject();
        tabClose.put("name", "tab_close");
        tabClose.put("description",
                "关闭指定序号的终端 tab（seq 由 tab_list 获得）。"
                + "只关闭窗口，不会删除或影响对应的 Linux 环境及其已安装软件。");
        JSONObject schemaTabClose = new JSONObject();
        schemaTabClose.put("type", "object");
        JSONObject propsTabClose = new JSONObject();
        propsTabClose.put("seq", typeObj("number"));
        schemaTabClose.put("properties", propsTabClose);
        schemaTabClose.put("required", new JSONArray().put("seq"));
        tabClose.put("inputSchema", schemaTabClose);
        tools.put(tabClose);

        // === tab_select：切换到指定 tab ===
        JSONObject tabSelect = new JSONObject();
        tabSelect.put("name", "tab_select");
        tabSelect.put("description",
                "切换到指定序号的终端 tab（把它置顶为当前窗口）。");
        JSONObject schemaTabSelect = new JSONObject();
        schemaTabSelect.put("type", "object");
        JSONObject propsTabSelect = new JSONObject();
        propsTabSelect.put("seq", typeObj("number"));
        schemaTabSelect.put("properties", propsTabSelect);
        schemaTabSelect.put("required", new JSONArray().put("seq"));
        tabSelect.put("inputSchema", schemaTabSelect);
        tools.put(tabSelect);

        // === log_control：日志控制（查/开关 Devil Log + 读日志尾部）===
        JSONObject logControl = new JSONObject();
        logControl.put("name", "log_control");
        logControl.put("description",
                "日志控制：查询或开关 Devil Log（详细调试日志），并读取日志文件尾部。"
                + "action=status 查看当前开关与日志路径；"
                + "action=on/off 开关 Devil Log；"
                + "action=tail 读取最近日志末尾若干行（lines 指定行数，默认 100）。"
                + "日志路径由 App 自行推导，无需预先知道。");
        JSONObject schemaLogControl = new JSONObject();
        schemaLogControl.put("type", "object");
        JSONObject propsLogControl = new JSONObject();
        propsLogControl.put("action", typeObj("string"));
        propsLogControl.put("lines", typeObj("number"));
        schemaLogControl.put("properties", propsLogControl);
        schemaLogControl.put("required", new JSONArray().put("action"));
        logControl.put("inputSchema", schemaLogControl);
        tools.put(logControl);

        // === term_debug：终端状态诊断（排障用）===
        // 仅在 Devil Log 开启时暴露：它是排查工具而非日常功能，
        // 默认隐藏可避免误导普通用户，也减少工具列表噪音。
        if (LogStore.isDevilLog()) {
            JSONObject termDebug = new JSONObject();
            termDebug.put("name", "term_debug");
            termDebug.put("description",
                    "读取当前终端缓冲的状态摘要（行列数、光标、视口偏移、已写入行数、"
                    + "屏幕可见文本 + 历史区）。用于排查「输出丢失/清行」类问题。"
                    + "仅在 Devil Log 开启时可用。");
            JSONObject schemaTermDebug = new JSONObject();
            schemaTermDebug.put("type", "object");
            schemaTermDebug.put("properties", new JSONObject());
            termDebug.put("inputSchema", schemaTermDebug);
            tools.put(termDebug);
        }

        result.put("tools", tools);
        return result;
    }

    private JSONObject typeObj(String t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", t);
        return o;
    }

    private JSONObject handleToolCall(JSONObject params) throws Exception {
        if (params == null) return errContent("no params");
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();

        if ("shell_exec".equals(name)) {
            String cmd = args.optString("cmd", "");
            if (cmd.isEmpty()) return errContent("cmd required");
            // 将 MCP 调用的命令同步到终端显示
            if (sCommandEchoListener != null) sCommandEchoListener.accept(cmd);
            String cwd = args.optString("cwd", null);
            long timeout = args.optLong("timeoutSec", 30L);
            boolean useRoot = args.optBoolean("useRoot", false);
            String envName = args.optString("env", "");

            ShellExecutor.Result r;
            if (!envName.isEmpty()) {
                // 指定了 Linux 环境：包一层 proot，使命令在该 rootfs 内执行。
                // 注意：proot 与 rootfs 都在 /data/local/tmp，shell 身份可读；
                // 而 shell_exec 走的正是 Shizuku（shell 身份），故可直接访问。
                android.content.Context appCtx = McpService.appContext();
                String prefix = (appCtx == null) ? null
                        : RuntimeManager.buildProotPrefix(appCtx, envName);
                if (prefix == null) {
                    return errContent("environment not usable: " + envName
                            + "（请确认已安装该环境且 Shizuku 已授权）");
                }
                r = ShellExecutor.execInEnv(cmd, cwd, timeout, useRoot, prefix);
            } else {
                r = ShellExecutor.exec(cmd, cwd, timeout, useRoot);
            }

            // 将命令输出同步到终端显示
            if (sOutputEchoListener != null) {
                StringBuilder sb = new StringBuilder();
                if (r.stdout != null && !r.stdout.isEmpty()) sb.append(r.stdout);
                if (r.stderr != null && !r.stderr.isEmpty()) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                    sb.append(r.stderr);
                }
                sOutputEchoListener.accept(sb.toString());
            }
            return contentJson("exitCode: " + r.exitCode + "\n--- stdout ---\n" + r.stdout + "\n--- stderr ---\n" + r.stderr);
        } else if ("sys_info".equals(name)) {
            ShellExecutor.Result r = ShellExecutor.exec(
                    "uname -a; echo '---'; cat /proc/meminfo | head -3; echo '---'; df -h /data | tail -1",
                    null, 10L, false);
            return contentJson(r.stdout.isEmpty() ? r.stderr : r.stdout);
        } else if ("pkg_list".equals(name)) {
            String filter = args.optString("filter", "");
            ShellExecutor.Result r = ShellExecutor.exec(
                    "pm list packages | grep -i '" + filter + "'", null, 15L, false);
            return contentJson(r.stdout.isEmpty() ? r.stderr : r.stdout);
        } else if ("view_image".equals(name)) {
            String path = args.optString("path", "");
            if (path.isEmpty()) return errContent("path required");
            // 通过 Shizuku shell 检查文件大小并读取 base64
            ShellExecutor.Result sizeCheck = ShellExecutor.exec(
                    "stat -c %s '" + path.replace("'", "'\\''") + "' 2>/dev/null || echo -1",
                    null, 5L, false);
            long fileSize;
            try { fileSize = Long.parseLong(sizeCheck.stdout.trim()); } catch (Exception e) { fileSize = -1; }
            if (fileSize < 0) return errContent("File not found: " + path);
            if (fileSize > 2 * 1024 * 1024) return errContent("File too large (" + fileSize + " bytes). Max 2MB.");
            // 用 shell base64 编码读取，避免 app 进程权限问题
            // 注意：图片 base64 后体积约为原文件 1.37 倍，必须用大输出通道，否则会被 64KB 截断
            ShellExecutor.Result b64Result = ShellExecutor.exec(
                    "base64 '" + path.replace("'", "'\\''") + "'",
                    null, 15L, false, ShellExecutor.MAX_OUTPUT_LARGE);
            if (b64Result.exitCode != 0) return errContent("Failed to read image: " + b64Result.stderr);
            String b64 = b64Result.stdout.replaceAll("\\s+", "");
            // base64 长度必须是 4 的倍数；不满足说明被截断，明确报错而非静默返回坏数据
            if (b64.isEmpty() || b64.length() % 4 != 0) {
                return errContent("Image data incomplete (base64 length=" + b64.length()
                        + "). File may exceed the read limit.");
            }
            JSONObject imgItem = new JSONObject();
            imgItem.put("type", "image");
            imgItem.put("data", b64);
            imgItem.put("mimeType", "image/png");
            JSONArray arr = new JSONArray();
            arr.put(imgItem);
            JSONObject result = new JSONObject();
            result.put("content", arr);
            return result;

        } else if ("read_file".equals(name)) {
            String path = args.optString("path", "");
            if (path.isEmpty()) return errContent("path required");
            // 通过 Shizuku shell 读取文件内容
            ShellExecutor.Result r = ShellExecutor.exec(
                    "cat '" + path.replace("'", "'\\''") + "'",
                    null, 10L, false);
            if (r.exitCode != 0) return errContent("Read failed: " + r.stderr);
            if (r.stdout.length() > 1024 * 1024) return errContent("File too large (" + r.stdout.length() + " chars). Max 1MB.");
            return contentJson(r.stdout);

        } else if ("write_file".equals(name)) {
            String path = args.optString("path", "");
            String content = args.optString("content", "");
            if (path.isEmpty()) return errContent("path required");
            // 通过 Shizuku shell 写入文件（自动创建父目录）
            String escapedPath = path.replace("'", "'\\''");
            String escapedContent = content.replace("'", "'\\''");
            ShellExecutor.Result mkdirR = ShellExecutor.exec(
                    "mkdir -p \"$(dirname '" + escapedPath + "')\"",
                    null, 5L, false);
            ShellExecutor.Result writeR = ShellExecutor.exec(
                    "printf '%s' '" + escapedContent + "' > '" + escapedPath + "'",
                    null, 10L, false);
            if (writeR.exitCode != 0) return errContent("Write failed: " + writeR.stderr);
            return contentJson("Written " + content.length() + " chars to " + path);
        } else if ("env_list".equals(name)) {
            android.content.Context ctx = McpService.appContext();
            if (ctx == null) return errContent("app context unavailable");
            java.util.List<String> envs = EnvStore.listEnvs(ctx);
            if (envs.isEmpty()) return contentJson("（没有已安装的 Linux 环境）");
            String active = EnvStore.getActiveEnv(ctx);
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(envs.size()).append(" 个环境：\n");
            for (String e : envs) {
                long bytes = RuntimeManager.envSizeOf(ctx, e);
                String distro = EnvStore.getDistroOf(ctx, e);
                sb.append(e.equals(active) ? " * " : "   ")
                  .append(e)
                  .append("   [").append(distro == null || distro.isEmpty() ? "未知" : distro).append("]")
                  .append("   ").append(fmtSize(bytes))
                  .append(e.equals(active) ? "   ← 当前" : "")
                  .append('\n');
            }
            return contentJson(sb.toString());
        } else if ("env_packages".equals(name)) {
            android.content.Context ctx = McpService.appContext();
            if (ctx == null) return errContent("app context unavailable");
            String env = args.optString("env", "");
            if (env.isEmpty()) env = EnvStore.getActiveEnv(ctx);
            RuntimeManager.PkgListResult pr = RuntimeManager.listPackages(ctx, env);
            if (!pr.ok || pr.packages.isEmpty()) {
                return errContent("无法获取 " + env + " 的软件包列表"
                        + (pr.error.isEmpty() ? "" : "：" + pr.error));
            }
            StringBuilder sb = new StringBuilder();
            sb.append(env).append("：共 ").append(pr.packages.size()).append(" 个包");
            if (pr.totalKb() > 0) sb.append("，合计 ").append(pr.totalText());
            sb.append("（").append(pr.manager).append("）\n\n");
            for (RuntimeManager.PkgInfo p : pr.packages) {
                sb.append(p.name);
                if (p.sizeKb > 0) sb.append("   ").append(p.sizeText());
                sb.append('\n');
            }
            return contentJson(sb.toString());
        } else if ("env_switch".equals(name)) {
            android.content.Context ctx = McpService.appContext();
            if (ctx == null) return errContent("app context unavailable");
            String env = args.optString("env", "");
            if (env.isEmpty()) return errContent("env required");
            java.util.List<String> envs = EnvStore.listEnvs(ctx);
            if (!envs.contains(env)) {
                return errContent("环境不存在：" + env + "（可用：" + envs + "）");
            }
            EnvStore.setActiveEnv(ctx, env);
            LogStore.getInstance().info("McpServer", "env_switch → " + env);
            return contentJson("已切换当前环境：" + env);
        } else if ("file_search".equals(name)) {
            android.content.Context ctx = McpService.appContext();
            if (ctx == null) return errContent("app context unavailable");
            String pattern = args.optString("pattern", "");
            if (pattern.isEmpty()) return errContent("pattern required");
            String env = args.optString("env", "");
            if (env.isEmpty()) env = EnvStore.getActiveEnv(ctx);
            String searchPath = args.optString("path", "/");
            boolean byContent = args.optBoolean("by_content", false);

            String prefix = RuntimeManager.buildProotPrefix(ctx, env);
            if (prefix == null) {
                return errContent("环境不可用：" + env);
            }

            // 转义：单引号包裹，' → '\''
            String escPattern = pattern.replace("'", "'\\''");
            String escPath = searchPath.replace("'", "'\\''");
            String cmd;
            if (byContent) {
                // 按内容搜索：grep -rIl 只列文件名，避免输出过多
                cmd = "grep -rIl --exclude-dir=/proc --exclude-dir=/sys "
                        + "-e '" + escPattern + "' '" + escPath + "' 2>/dev/null | head -100";
            } else {
                // 按文件名搜索
                cmd = "find '" + escPath + "' -name '*" + escPattern + "*' "
                        + "2>/dev/null | head -100";
            }

            ShellExecutor.Result r = ShellExecutor.execInEnv(cmd, null, 60L, false, prefix);
            if (r == null) return errContent("搜索失败");
            String out = (r.stdout == null || r.stdout.isEmpty())
                    ? "（未找到匹配项）" : r.stdout;
            if (r.stderr != null && !r.stderr.isEmpty()) {
                out += "\n--- stderr ---\n" + r.stderr;
            }
            return contentJson(out);
        } else if ("tab_list".equals(name)) {
            final TabBridge b = sTabBridge;
            if (b == null) return errContent("tab bridge unavailable（App 未在前台？）");
            java.util.List<String> tabs = onMain(b::listTabs, null);
            if (tabs == null) return errContent("获取 tab 列表失败");
            if (tabs.isEmpty()) return contentJson("（没有 tab）");
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(tabs.size()).append(" 个 tab：\n");
            for (String t : tabs) sb.append("  ").append(t).append('\n');
            return contentJson(sb.toString());
        } else if ("tab_open".equals(name)) {
            final TabBridge b = sTabBridge;
            if (b == null) return errContent("tab bridge unavailable");
            final String env = args.optString("env", "");
            Integer seq = onMain(() -> b.openTab(env), null);
            if (seq == null || seq < 0) return errContent("新建 tab 失败");
            return contentJson("已新建 tab，序号=" + seq
                    + "（环境：" + (env.isEmpty() ? "Android" : env) + "）");
        } else if ("tab_close".equals(name)) {
            final TabBridge b = sTabBridge;
            if (b == null) return errContent("tab bridge unavailable");
            final int seq = args.optInt("seq", -1);
            if (seq < 0) return errContent("seq required");
            Boolean ok = onMain(() -> b.closeTab(seq), Boolean.FALSE);
            return ok != null && ok
                    ? contentJson("已关闭 tab，序号=" + seq + "（环境本身未受影响）")
                    : errContent("关闭失败：找不到序号 " + seq + " 的 tab");
        } else if ("tab_select".equals(name)) {
            final TabBridge b = sTabBridge;
            if (b == null) return errContent("tab bridge unavailable");
            final int seq = args.optInt("seq", -1);
            if (seq < 0) return errContent("seq required");
            Boolean ok = onMain(() -> b.selectTab(seq), Boolean.FALSE);
            return ok != null && ok
                    ? contentJson("已切换到 tab，序号=" + seq)
                    : errContent("切换失败：找不到序号 " + seq + " 的 tab");
        } else if ("log_control".equals(name)) {
            android.content.Context ctx = McpService.appContext();
            String action = args.optString("action", "status");
            LogStore ls = LogStore.getInstance();
            String dir = ls.logDirPath();
            String latest = ls.latestLogFilePath();

            if ("status".equals(action)) {
                StringBuilder sb = new StringBuilder();
                sb.append("Devil Log：" ).append(LogStore.isDevilLog() ? "开启" : "关闭").append('\n');
                sb.append("日志目录：").append(dir == null ? "(不可用)" : dir).append('\n');
                sb.append("最新日志：").append(latest == null ? "(暂无)" : latest).append('\n');
                return contentJson(sb.toString());
            }
            if ("on".equals(action)) {
                LogStore.setDevilLog(ctx, true);
                return contentJson("Devil Log 已开启（将记录 DEBUG 级细节）");
            }
            if ("off".equals(action)) {
                LogStore.setDevilLog(ctx, false);
                return contentJson("Devil Log 已关闭（仅记录 INFO 及以上）");
            }
            if ("tail".equals(action)) {
                if (latest == null) return errContent("暂无日志文件");
                int lines = args.optInt("lines", 100);
                if (lines <= 0) lines = 100;
                if (lines > 2000) lines = 2000;
                // 用 shell tail 读取（App 身份可读自己的外部目录）
                ShellExecutor.Result r = ShellExecutor.exec(
                        "tail -n " + lines + " '" + latest.replace("'", "'\\''") + "'",
                        null, 10L, false);
                if (r.exitCode != 0) {
                    return errContent("读取失败：" + r.stderr);
                }
                return contentJson("文件：" + latest + "\n\n" + r.stdout);
            }
            return errContent("未知 action：" + action + "（可用 status/on/off/tail）");
        } else if ("term_debug".equals(name)) {
            // 该工具仅在 Devil Log 开启时提供（排障用途）
            if (!LogStore.isDevilLog()) {
                return errContent("term_debug 仅在 Devil Log 开启时可用"
                        + "（可用 log_control action=on 开启）");
            }
            final TermBridge tb = sTermBridge;
            if (tb == null) return errContent("term bridge unavailable");
            String dump = onMain(tb::dumpState, null);
            return dump == null ? errContent("读取终端状态失败") : contentJson(dump);
        }
        return errContent("unknown tool: " + name);
    }

    /** 字节数 → 可读文本。 */
    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.2f GB",
                bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private JSONObject contentJson(String text) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", text);
        arr.put(item);
        result.put("content", arr);
        return result;
    }

    private JSONObject errContent(String msg) throws Exception {
        JSONObject result = new JSONObject();
        result.put("isError", true);
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", msg);
        arr.put(item);
        result.put("content", arr);
        return result;
    }
}
