package com.xieke.mcptool;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.snackbar.Snackbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具主界面 Activity。
 * 显示仪表盘指标、支持下拉刷新，并在底部输出日志。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_OPEN_DOCUMENT_TREE = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "mcp_server_channel";
    private static final int NOTIFICATION_ID = 1;

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerDashboard;
    private TextView logOutput;
    private ImageButton clearLogButton;
    private ScrollView logScrollView;
    private DashboardAdapter dashboardAdapter;

    private String workspacePath = "/data/mcp/workspace";
    private String workspaceUriString = "";
    private String networkStatus = "未连接";
    private Button startServerButton;
    private Button stopServerButton;
    
    // MCP服务器相关
    private McpServer mcpServer;
    private String localUrl = "http://127.0.0.1:8090";
    private String networkUrl = "";
    private int serverPort = 8090;
    private long serverStartTime = 0;
    private Handler statsHandler;
    private Runnable statsRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swipeRefresh = findViewById(R.id.swipeRefresh);
        recyclerDashboard = findViewById(R.id.recyclerDashboard);
        logOutput = findViewById(R.id.logOutput);
        clearLogButton = findViewById(R.id.clearLogButton);
        logScrollView = findViewById(R.id.logScrollView);
        startServerButton = findViewById(R.id.startServerButton);
        stopServerButton = findViewById(R.id.stopServerButton);

        SharedPreferences prefs = getSharedPreferences("mcp_preferences", MODE_PRIVATE);
        workspaceUriString = prefs.getString("workspace_uri", "");
        workspacePath = prefs.getString("workspace_path", "/data/mcp/workspace");
        serverPort = prefs.getInt("server_port", 8090);

        // 如果有保存的URI，尝试获取可读路径
        if (!workspaceUriString.isEmpty()) {
            Uri uri = Uri.parse(workspaceUriString);
            String readablePath = getReadablePathFromUri(uri);
            if (readablePath != null && !readablePath.isEmpty()) {
                workspacePath = readablePath;
            }
        }

        // 初始化MCP服务器
        initMcpServer();
        
        // 设置服务器启动/停止按钮
        if (startServerButton != null) {
            startServerButton.setOnClickListener(v -> startMcpServer());
        }
        
        if (stopServerButton != null) {
            stopServerButton.setOnClickListener(v -> stopMcpServer());
            stopServerButton.setEnabled(false);
        }

        if (clearLogButton == null) {
            appendLog("警告：清理按钮未找到 (横屏模式)");
        } else {
            appendLog("清理按钮已初始化");
        }

        // 使用网格布局显示卡片式仪表盘，混合布局
        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
        // 设置特定位置卡片占用的列数：URL卡片占一整行
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // URL 卡片在位置6（工作空间后面），占一整行
                if (position == 6) {
                    return 2;
                }
                // 其他卡片各占1列
                return 1;
            }
        });
        recyclerDashboard.setLayoutManager(gridLayoutManager);

        dashboardAdapter = new DashboardAdapter(fetchDashboardItems());
        
        // 设置工作空间选择的监听器
        dashboardAdapter.setOnItemActionListener(new DashboardAdapter.OnItemActionListener() {
            @Override
            public void onFolderSelectClick(int position, DashboardItem item) {
                if ("工作空间".equals(item.getTitle())) {
                    openWorkspaceDirectory();
                }
            }
        });
        
        recyclerDashboard.setAdapter(dashboardAdapter);

        swipeRefresh.setOnRefreshListener(() -> {
            refreshDashboard();
            swipeRefresh.setRefreshing(false);
            appendLog("刷新完成：更新日志数据");
        });

        appendLog("应用启动：Mcp 工具已加载");
        
        // 创建通知通道（Android 8.0+需要）
        createNotificationChannel();
        
        // 启动统计更新 - 只更新运行时长
        startStatsUpdater();

        // 设置清理日志按钮的点击事件
        if (clearLogButton != null) {
            clearLogButton.setOnClickListener(v -> {
                logOutput.setText("日志输出区域\n（运行日志将在这里显示）");
                appendLog("日志已清理");
                Toast.makeText(this, "日志已清理", Toast.LENGTH_SHORT).show();
            });
        }

        // 检查存储权限
        checkStoragePermission();
    }

    private static final int REQUEST_STORAGE_PERMISSION = 1002;

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 MANAGE_EXTERNAL_STORAGE
            if (!android.os.Environment.isExternalStorageManager()) {
                appendLog("需要存储权限，正在请求...");
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                } catch (Exception e) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
                }
            } else {
                appendLog("存储权限已授予");
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 使用传统权限
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                appendLog("需要存储权限，正在请求...");
                requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE_PERMISSION);
            } else {
                appendLog("存储权限已授予");
            }
        } else {
            appendLog("存储权限已授予（旧版Android）");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    appendLog("存储权限已授予");
                } else {
                    appendLog("存储权限被拒绝");
                    Toast.makeText(this, "需要存储权限才能访问工作区", Toast.LENGTH_LONG).show();
                }
            } else {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    appendLog("存储权限已授予");
                } else {
                    appendLog("存储权限被拒绝");
                    Toast.makeText(this, "需要存储权限才能访问工作区", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 处理存储权限请求结果
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    appendLog("存储权限已授予");
                } else {
                    appendLog("存储权限被拒绝");
                    Toast.makeText(this, "需要存储权限才能访问工作区", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        
        // 处理文档树选择结果
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                workspaceUriString = uri.toString();
                workspacePath = getReadablePathFromUri(uri);
                
                appendLog("工作空间路径已设置：" + workspacePath);

                // 尝试获取持久化权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }

                // 保存到SharedPreferences
                SharedPreferences prefs = getSharedPreferences("mcp_preferences", MODE_PRIVATE);
                prefs.edit()
                        .putString("workspace_uri", workspaceUriString)
                        .putString("workspace_path", workspacePath)
                        .apply();

                // 更新MCP服务器的工作空间
                if (mcpServer != null) {
                    mcpServer.setWorkspacePath(workspacePath);
                }
                
                refreshDashboard();

                Snackbar.make(findViewById(android.R.id.content), "工作空间已保存", Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void initMcpServer() {
        mcpServer = new McpServer();
        mcpServer.setCallback(new McpServer.ServerCallback() {
            @Override
            public void onServerStarted(String localUrl, String networkUrl) {
                runOnUiThread(() -> {
                    MainActivity.this.localUrl = localUrl;
                    MainActivity.this.networkUrl = networkUrl != null ? networkUrl : "";
                    networkStatus = networkUrl != null ? "已启动" : "仅本地";
                    
                    if (startServerButton != null) {
                        startServerButton.setEnabled(false);
                    }
                    if (stopServerButton != null) {
                        stopServerButton.setEnabled(true);
                    }
                    
                    appendLog("MCP服务器已启动");
                    appendLog("本机URL: " + localUrl);
                    if (networkUrl != null && !networkUrl.isEmpty()) {
                        appendLog("网络URL: " + networkUrl);
                    }
                    
                    // 显示前台通知
                    showServerNotification(true);
                    
                    // 服务器启动时只更新状态，不完整刷新
                    updateServerStatusItems();
                });
            }
            
            @Override
            public void onServerStopped() {
                runOnUiThread(() -> {
                    networkStatus = "已停止";
                    
                    if (startServerButton != null) {
                        startServerButton.setEnabled(true);
                    }
                    if (stopServerButton != null) {
                        stopServerButton.setEnabled(false);
                    }
                    
                    appendLog("MCP服务器已停止");
                    // 移除前台通知
                    showServerNotification(false);
                    // 服务器停止时只更新状态
                    updateServerStatusItems();
                });
            }
            
            @Override
            public void onRequestReceived(String method, String params) {
                runOnUiThread(() -> {
                    appendLog("收到MCP请求: " + method);
                    // 请求到来时只更新请求数
                    updateRequestCountItem();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    appendLog("服务器错误: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                    
                    if (startServerButton != null) {
                        startServerButton.setEnabled(true);
                    }
                    if (stopServerButton != null) {
                        stopServerButton.setEnabled(false);
                    }
                });
            }
            
            @Override
            public void onLog(String message) {
                // 日志已在McpServer中处理
            }
        });
    }
    
    private void startMcpServer() {
        if (mcpServer != null && !mcpServer.isRunning()) {
            mcpServer.setWorkspacePath(workspacePath);
            mcpServer.start(this, serverPort);
            serverStartTime = System.currentTimeMillis();
        }
    }
    
    private void stopMcpServer() {
        if (mcpServer != null && mcpServer.isRunning()) {
            mcpServer.stop();
            serverStartTime = 0;
        }
    }
    
    private void startStatsUpdater() {
        statsHandler = new Handler(Looper.getMainLooper());
        statsRunnable = new Runnable() {
            @Override
            public void run() {
                if (mcpServer != null && mcpServer.isRunning()) {
                    // 只更新运行时长那一项
                    updateRuntimeOnly();
                }
                statsHandler.postDelayed(this, 1000); // 每秒更新一次
            }
        };
        statsHandler.post(statsRunnable);
    }
    
    // 只更新运行时长 - 直接更新TextView避免闪烁
    private void updateRuntimeOnly() {
        if (recyclerDashboard == null || dashboardAdapter == null) return;
        
        // 遍历列表找到运行时长
        for (int i = 0; i < dashboardAdapter.getItemCount(); i++) {
            DashboardItem item = dashboardAdapter.getItems().get(i);
            if ("运行时长".equals(item.getTitle())) {
                // 直接获取对应的ViewHolder
                RecyclerView.ViewHolder vh = recyclerDashboard.findViewHolderForAdapterPosition(i);
                if (vh != null && vh instanceof DashboardAdapter.ViewHolder) {
                    String newRuntime = calculateRuntime();
                    if (!item.getValue().equals(newRuntime)) {
                        // 直接更新TextView
                        ((DashboardAdapter.ViewHolder) vh).itemValue.setText(newRuntime);
                    }
                }
                break;
            }
        }
    }
    
    // 更新服务器状态相关项
    private void updateServerStatusItems() {
        if (dashboardAdapter == null) return;
        
        List<DashboardItem> items = dashboardAdapter.getItems();
        if (items == null) return;
        
        boolean isRunning = mcpServer != null && mcpServer.isRunning();
        
        for (int i = 0; i < items.size(); i++) {
            DashboardItem item = items.get(i);
            String title = item.getTitle();
            
            if ("运行状态".equals(title)) {
                String newValue = isRunning ? "已启动" : "未启动";
                if (!item.getValue().equals(newValue)) {
                    items.set(i, new DashboardItem("运行状态", newValue, isRunning ? "正常" : "点击启动"));
                    dashboardAdapter.notifyItemChanged(i);
                }
            } else if ("访问地址".equals(title)) {
                // 更新 URL 卡片信息
                dashboardAdapter.updateUrlInfo(localUrl, networkUrl, isRunning);
            }
        }
    }
    
    // 更新请求数量
    private void updateRequestCountItem() {
        if (dashboardAdapter == null) return;
        
        List<DashboardItem> items = dashboardAdapter.getItems();
        if (items == null) return;
        
        boolean isRunning = mcpServer != null && mcpServer.isRunning();
        if (!isRunning) return;
        
        for (int i = 0; i < items.size(); i++) {
            DashboardItem item = items.get(i);
            if ("请求数量".equals(item.getTitle())) {
                int newCount = mcpServer.getRequestCount();
                if (!item.getValue().equals(String.valueOf(newCount))) {
                    items.set(i, new DashboardItem("请求数量", String.valueOf(newCount), "累计请求"));
                    dashboardAdapter.notifyItemChanged(i);
                }
                break;
            }
        }
    }
    
    private String calculateRuntime() {
        boolean isRunning = mcpServer != null && mcpServer.isRunning();
        if (!isRunning || serverStartTime == 0) {
            return "未启动";
        }
        
        long elapsed = System.currentTimeMillis() - serverStartTime;
        long seconds = elapsed / 1000;
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + "分" + secs + "秒";
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            return hours + "时" + minutes + "分" + secs + "秒";
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
        }
        if (mcpServer != null) {
            mcpServer.stop();
        }
    }

    private void appendLog(String text) {
        if (logOutput == null) return;
        String existing = logOutput.getText().toString();
        String newText = existing + "\n" + text;
        logOutput.setText(newText);

        // 自动滚动到底部显示最新日志
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void refreshDashboard() {
        if (recyclerDashboard == null || dashboardAdapter == null) return;
        
        // 保存滚动位置
        int scrollPosition = 0;
        RecyclerView.LayoutManager layoutManager = recyclerDashboard.getLayoutManager();
        if (layoutManager != null && layoutManager instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager lm = (androidx.recyclerview.widget.LinearLayoutManager) layoutManager;
            scrollPosition = lm.findFirstVisibleItemPosition();
        }
        
        // 获取新的数据并更新adapter
        dashboardAdapter.updateData(fetchDashboardItems());
        
        // 恢复滚动位置
        if (layoutManager != null && layoutManager instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            ((androidx.recyclerview.widget.LinearLayoutManager) layoutManager).scrollToPosition(scrollPosition);
        }
    }

    private void openWorkspaceDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!workspaceUriString.isEmpty()) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(workspaceUriString));
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE);
    }

    private String getReadablePathFromUri(Uri uri) {
        if (uri == null) {
            return "";
        }
        try {
            // 尝试获取实际文件路径
            String path = getFilePathFromUri(uri);
            if (path != null && !path.isEmpty()) {
                return path;
            }
            
            // 如果获取不到，尝试从path字段解析
            String uriPath = uri.getPath();
            if (uriPath != null && !uriPath.isEmpty()) {
                // 处理路径格式，将 /tree/primary:xxx 转换为 /storage/emulated/0/xxx
                String readablePath = uriPath.replace("/tree/", "");
                // 如果包含 : ，说明是SAF格式
                if (readablePath.contains(":")) {
                    String[] parts = readablePath.split(":");
                    if (parts.length >= 2) {
                        String storageType = parts[0];
                        String relativePath = parts[1];
                        
                        // 映射存储类型到实际路径
                        if ("primary".equals(storageType)) {
                            readablePath = "/storage/emulated/0/" + relativePath;
                        } else if ("sdcard".equals(storageType) || "extSdCard".equals(storageType)) {
                            readablePath = "/storage/" + storageType + "/" + relativePath;
                        } else {
                            readablePath = "/storage/" + storageType + "/" + relativePath;
                        }
                    }
                }
                // 解码URL编码的字符
                readablePath = java.net.URLDecoder.decode(readablePath, "UTF-8");
                return readablePath;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return uri.toString();
    }
    
    private String getFilePathFromUri(Uri uri) {
        if (uri == null) return null;
        
        // 检查是否是外部存储文档
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            try {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] parts = docId.split(":");
                String type = parts[0];
                String relativePath = parts.length > 1 ? parts[1] : "";
                
                if ("primary".equalsIgnoreCase(type)) {
                    return android.os.Environment.getExternalStorageDirectory().getPath() + "/" + relativePath;
                } else {
                    // 其他存储设备
                    return "/storage/" + type + "/" + relativePath;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private List<DashboardItem> fetchDashboardItems() {
        List<DashboardItem> items = new ArrayList<>();

        // 服务器运行状态
        boolean isRunning = mcpServer != null && mcpServer.isRunning();
        items.add(new DashboardItem("运行状态", isRunning ? "已启动" : "未启动", isRunning ? "正常" : "点击启动"));
        
        // 端口
        items.add(new DashboardItem("端口", String.valueOf(serverPort), "TCP 监听"));
        
        // 连接信息
        int connections = isRunning ? mcpServer.getConnectionCount() : 0;
        items.add(new DashboardItem("连接信息", connections + " 连接", "当前连接数"));
        
        // 请求数量
        int requests = isRunning ? mcpServer.getRequestCount() : 0;
        items.add(new DashboardItem("请求数量", String.valueOf(requests), "累计请求"));
        
        // 运行时长（从秒开始计时）
        items.add(new DashboardItem("运行时长", calculateRuntime(), "自启动"));
        
        // 工作空间 - 带文件夹选择按钮
        items.add(new DashboardItem("工作空间", workspacePath, "点击选择文件夹", DashboardItem.BUTTON_FOLDER));
        
        // URL 卡片 - 占位符，实际内容由适配器管理，放在工作空间下面
        items.add(new DashboardItem("访问地址", localUrl, networkUrl));

        return items;
    }
    
    // 创建通知通道（Android 8.0+需要）
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "MCP服务器",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示MCP服务器运行状态");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    // 显示/隐藏服务器通知
    private void showServerNotification(boolean show) {
        if (show) {
            // 创建点击通知打开应用的功能
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // 创建通知
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("MCP服务器运行中")
                    .setContentText("端口: " + serverPort)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true);
            
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            try {
                notificationManager.notify(NOTIFICATION_ID, builder.build());
            } catch (SecurityException e) {
                // 没有通知权限
            }
        } else {
            // 移除通知
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }
}
