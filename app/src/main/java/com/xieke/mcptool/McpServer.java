package com.xieke.mcptool;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP服务器类 - 实现 Streamable HTTP 协议的 MCP 服务器
 * 符合 JSON-RPC 2.0 规范
 */
public class McpServer {
    private static final String TAG = "McpServer";
    private static final int DEFAULT_PORT = 8090;
    private static final String PROTOCOL_VERSION = "2025-06-18";
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    
    private int port = DEFAULT_PORT;
    private String workspacePath = "/data/mcp/workspace";
    private Context context;
    private ServerCallback callback;
    private final Gson gson = new Gson();
    
    public interface ServerCallback {
        void onServerStarted(String localUrl, String networkUrl);
        void onServerStopped();
        void onRequestReceived(String method, String params);
        void onError(String error);
        void onLog(String message);
    }
    
    public void setCallback(ServerCallback callback) {
        this.callback = callback;
    }
    
    public void setWorkspacePath(String path) {
        this.workspacePath = path;
    }
    
    public int getPort() {
        return port;
    }
    
    public int getRequestCount() {
        return requestCount.get();
    }
    
    public int getConnectionCount() {
        return connectionCount.get();
    }
    
    public boolean isRunning() {
        return isRunning.get();
    }
    
    public void start(Context context, int port) {
        if (isRunning.get()) {
            Log.w(TAG, "Server is already running");
            return;
        }
        
        this.context = context;
        this.port = port;
        executorService = Executors.newCachedThreadPool();
        
        try {
            serverSocket = new ServerSocket(port);
            isRunning.set(true);
            
            String localUrl = "http://127.0.0.1:" + port;
            String networkUrl = getNetworkIpAddress();
            if (networkUrl != null) {
                networkUrl = "http://" + networkUrl + ":" + port;
            }
            
            Log.i(TAG, "MCP Server started on port " + port);
            log("MCP服务器已启动 - 端口: " + port);
            
            if (callback != null) {
                callback.onServerStarted(localUrl, networkUrl);
            }
            
            executorService.execute(this::acceptConnections);
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
            log("服务器启动失败: " + e.getMessage());
            if (callback != null) {
                callback.onError("端口 " + port + " 可能已被占用");
            }
        }
    }
    
    public void stop() {
        if (!isRunning.get()) {
            return;
        }
        
        isRunning.set(false);
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (executorService != null) {
                executorService.shutdown();
            }
            Log.i(TAG, "MCP Server stopped");
            log("MCP服务器已停止");
            if (callback != null) {
                callback.onServerStopped();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }
    
    private void acceptConnections() {
        while (isRunning.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setKeepAlive(true);
                connectionCount.incrementAndGet();
                executorService.execute(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e);
                }
                break;
            }
        }
    }
    
    private void handleClient(Socket clientSocket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                clientSocket.close();
                return;
            }
            
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            
            log("收到请求: " + method + " " + path);
            
            // 处理 OPTIONS 预检请求
            if ("OPTIONS".equals(method)) {
                sendCorsResponse(clientSocket);
                return;
            }
            
            // 读取请求头
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith("content-length:")) {
                    try {
                        String[] clParts = line.split(":");
                        if (clParts.length >= 2) {
                            contentLength = Integer.parseInt(clParts[1].trim());
                        }
                    } catch (Exception e) {
                        log("解析Content-Length失败: " + e.getMessage());
                    }
                }
            }
            
            log("Content-Length: " + contentLength);
            
            // 读取请求体
            String requestBody = null;
            if (contentLength > 0 && "POST".equals(method)) {
                StringBuilder bodyBuilder = new StringBuilder();
                int bytesRead = 0;
                char[] buffer = new char[1024];
                while (bytesRead < contentLength) {
                    int read = reader.read(buffer, 0, Math.min(buffer.length, contentLength - bytesRead));
                    if (read == -1) break;
                    bodyBuilder.append(buffer, 0, read);
                    bytesRead += read;
                }
                requestBody = bodyBuilder.toString();
                log("请求体: " + requestBody);
            }
            
            // 处理请求
            String responseBody = handleRequest(method, path, requestBody);
            log("响应: " + responseBody);
            
            // 发送响应
            OutputStream outputStream = clientSocket.getOutputStream();
            String httpResponse = buildHttpResponse(responseBody);
            outputStream.write(httpResponse.getBytes());
            outputStream.flush();
            
            requestCount.incrementAndGet();
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
            log("处理请求错误: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }
    
    private void sendCorsResponse(Socket clientSocket) throws IOException {
        OutputStream outputStream = clientSocket.getOutputStream();
        String response = "HTTP/1.1 200 OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        outputStream.write(response.getBytes());
        outputStream.flush();
    }
    
    private String handleRequest(String httpMethod, String path, String body) {
        // 如果是 GET 请求，返回 200 OK（用于健康检查或 SSE）
        if ("GET".equals(httpMethod)) {
            return "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"ok\"}}";
        }
        
        // POST 请求需要处理 JSON-RPC
        if (body == null || body.isEmpty()) {
            return createErrorResponse(null, -32600, "Invalid Request: empty body");
        }
        
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            return processJsonRpcRequest(request);
        } catch (JsonSyntaxException e) {
            log("JSON解析错误: " + e.getMessage());
            return createErrorResponse(null, -32700, "Parse error: " + e.getMessage());
        } catch (Exception e) {
            log("处理请求错误: " + e.getMessage());
            return createErrorResponse(null, -32603, "Internal error: " + e.getMessage());
        }
    }
    
    private String processJsonRpcRequest(JsonObject request) {
        // 检查 jsonrpc 版本
        if (!request.has("jsonrpc") || !"2.0".equals(request.get("jsonrpc").getAsString())) {
            return createErrorResponse(getRequestId(request), -32600, "Invalid Request: jsonrpc must be '2.0'");
        }
        
        // 获取 method
        if (!request.has("method")) {
            return createErrorResponse(getRequestId(request), -32600, "Invalid Request: missing method");
        }
        String methodName = request.get("method").getAsString();
        
        // 获取 params（可能是 JsonObject 或 JsonArray）
        JsonElement paramsElement = null;
        if (request.has("params")) {
            paramsElement = request.get("params");
        }
        
        // 获取 id（可选，用于响应）
        JsonElement requestId = getRequestId(request);
        
        if (callback != null) {
            String paramsStr = paramsElement != null ? paramsElement.toString() : "";
            callback.onRequestReceived(methodName, paramsStr);
        }
        
        // 处理 MCP 方法 - 注意 notifications 不需要返回 id
        switch (methodName) {
            case "initialize":
                return handleInitialize(requestId, paramsElement);
                
            case "notifications/initialized":
                // 客户端初始化完成通知，不需要返回结果
                log("客户端初始化完成");
                return "{\"jsonrpc\":\"2.0\"}";
                
            case "notifications/cancelled":
                // 请求被取消的通知，不需要返回结果
                log("收到请求取消通知");
                return "{\"jsonrpc\":\"2.0\"}";
                
            case "tools/list":
                return handleToolsList(requestId);
                
            case "tools/call":
                return handleToolsCall(requestId, paramsElement);
                
            case "resources/list":
                return handleResourcesList(requestId);
                
            case "ping":
                return handlePing(requestId);
                
            default:
                // 返回错误响应
                JsonObject response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                if (requestId != null) {
                    response.add("id", requestId);
                }
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "Method not found: " + methodName);
                response.add("error", error);
                return response.toString();
        }
    }
    
    private JsonElement getRequestId(JsonObject request) {
        if (!request.has("id")) {
            return null;
        }
        JsonElement id = request.get("id");
        // 如果 id 是 null，不包含在响应中
        if (id == null || id.isJsonNull()) {
            return null;
        }
        return id;
    }
    
    private String handleInitialize(JsonElement requestId, JsonElement params) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        
        // 声明服务器能力
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", true);
        capabilities.add("tools", tools);
        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", true);
        resources.addProperty("listChanged", true);
        capabilities.add("resources", resources);
        result.add("capabilities", capabilities);
        
        // 服务器信息
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "mcptool");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);
        
        log("处理 initialize 请求，协议版本: " + PROTOCOL_VERSION);
        
        // 构建响应
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (requestId != null) {
            response.add("id", requestId);
        }
        response.add("result", result);
        return response.toString();
    }
    
    private String handleToolsList(JsonElement requestId) {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        
        // ls 工具 - 列出工作区文件
        JsonObject lsTool = new JsonObject();
        lsTool.addProperty("name", "ls");
        lsTool.addProperty("description", "列出工作区目录中的文件和文件夹");
        JsonObject lsSchema = new JsonObject();
        lsSchema.addProperty("type", "object");
        lsSchema.add("properties", new JsonObject());
        lsSchema.add("required", new JsonArray());
        lsTool.add("inputSchema", lsSchema);
        tools.add(lsTool);
        
        result.add("tools", tools);
        log("处理 tools/list 请求，返回 " + tools.size() + " 个工具");
        
        // 构建响应
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (requestId != null) {
            response.add("id", requestId);
        }
        response.add("result", result);
        return response.toString();
    }
    
    private String handleToolsCall(JsonElement requestId, JsonElement params) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        
        // 解析工具名称和参数
        String toolName = "";
        JsonObject arguments = null;
        
        if (params != null && params.isJsonObject()) {
            JsonObject paramsObj = params.getAsJsonObject();
            if (paramsObj.has("name")) {
                toolName = paramsObj.get("name").getAsString();
            }
            if (paramsObj.has("arguments") && paramsObj.get("arguments").isJsonObject()) {
                arguments = paramsObj.get("arguments").getAsJsonObject();
            }
        }
        
        if (toolName.isEmpty()) {
            contentItem.addProperty("text", "错误: 缺少工具名称");
            content.add(contentItem);
            result.add("content", content);
            result.addProperty("isError", true);
        } else {
            log("调用工具: " + toolName);
            
            switch (toolName) {
                case "ls":
                    contentItem.addProperty("text", handleLs());
                    break;
                default:
                    contentItem.addProperty("text", "未知工具: " + toolName);
                    break;
            }
            content.add(contentItem);
            result.add("content", content);
            result.addProperty("isError", false);
        }
        
        // 构建响应
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (requestId != null) {
            response.add("id", requestId);
        }
        response.add("result", result);
        return response.toString();
    }
    
    private String handleLs() {
        try {
            java.io.File dir = new java.io.File(workspacePath);
            if (!dir.exists() || !dir.isDirectory()) {
                return "工作区目录不存在: " + workspacePath;
            }
            
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return "工作区为空目录";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("工作区文件列表:\n");
            for (java.io.File file : files) {
                String type = file.isDirectory() ? "[DIR] " : "[FILE] ";
                String size = file.isFile() ? " (" + file.length() + " bytes)" : "";
                sb.append(type).append(file.getName()).append(size).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取文件列表失败: " + e.getMessage();
        }
    }
    
    private String handleResourcesList(JsonElement requestId) {
        JsonObject result = new JsonObject();
        JsonArray resources = new JsonArray();
        JsonObject resource = new JsonObject();
        resource.addProperty("uri", "file://" + workspacePath);
        resource.addProperty("name", "workspace");
        resource.addProperty("description", "工作空间目录");
        resource.addProperty("mimeType", "text/plain");
        resources.add(resource);
        result.add("resources", resources);
        
        // 构建响应
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (requestId != null) {
            response.add("id", requestId);
        }
        response.add("result", result);
        return response.toString();
    }
    
    private String handlePing(JsonElement requestId) {
        // 构建响应
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (requestId != null) {
            response.add("id", requestId);
        }
        response.add("result", new JsonObject());
        return response.toString();
    }
    
    private String createErrorResponse(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response.toString();
    }
    
    private String buildHttpResponse(String body) {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: application/json; charset=utf-8\r\n");
        response.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");
        response.append(body);
        return response.toString();
    }
    
    private String getNetworkIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return null;
    }
    
    private void log(String message) {
        Log.d(TAG, message);
        if (callback != null) {
            callback.onLog(message);
        }
    }
}