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
        shellExec.put("description", "通过 Shizuku 以 shell/root 权限执行命令");
        JSONObject schema1 = new JSONObject();
        schema1.put("type", "object");
        JSONObject props1 = new JSONObject();
        props1.put("cmd", typeObj("string"));
        props1.put("cwd", typeObj("string"));
        props1.put("timeoutSec", typeObj("number"));
        props1.put("useRoot", typeObj("boolean"));
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
            ShellExecutor.Result r = ShellExecutor.exec(cmd, cwd, timeout, useRoot);
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
        }
        return errContent("unknown tool: " + name);
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
