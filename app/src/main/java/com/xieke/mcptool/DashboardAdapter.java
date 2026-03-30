package com.xieke.mcptool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * RecyclerView 适配器：支持多种视图类型的仪表盘
 * 包括普通卡片和 URL 卡片
 */
public class DashboardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_NORMAL = 0;
    public static final int VIEW_TYPE_URL = 1;

    private List<DashboardItem> items;
    private OnItemActionListener actionListener;
    private String localUrl = "";
    private String networkUrl = "";
    private boolean isRunning = false;
    
    public interface OnItemActionListener {
        void onFolderSelectClick(int position, DashboardItem item);
    }
    
    public void setOnItemActionListener(OnItemActionListener listener) {
        this.actionListener = listener;
    }
    
    // 更新数据方法
    public void updateData(List<DashboardItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }
    
    // 获取当前列表
    public List<DashboardItem> getItems() {
        return items;
    }
    
    // 更新 URL 信息
    public void updateUrlInfo(String localUrl, String networkUrl, boolean isRunning) {
        this.localUrl = localUrl;
        this.networkUrl = networkUrl;
        this.isRunning = isRunning;
        notifyItemChanged(6); // URL 卡片在位置6
    }

    public DashboardAdapter(List<DashboardItem> items) {
        this.items = items;
    }

    @Override
    public int getItemViewType(int position) {
        // URL 卡片在位置6（工作空间后面）
        if (position == 6) {
            return VIEW_TYPE_URL;
        }
        return VIEW_TYPE_NORMAL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_URL) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_url_card, parent, false);
            return new UrlViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dashboard_card, parent, false);
            return new ViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof UrlViewHolder) {
            // 绑定 URL 卡片
            UrlViewHolder urlHolder = (UrlViewHolder) holder;
            urlHolder.localUrlValue.setText(localUrl);
            urlHolder.networkUrlValue.setText(isRunning && !networkUrl.isEmpty() ? networkUrl : "未启动");
            
            // 服务器未启动时隐藏复制按钮
            if (isRunning) {
                urlHolder.copyLocalUrlButton.setVisibility(View.VISIBLE);
                urlHolder.copyLocalUrlButton.setOnClickListener(v -> {
                    Context context = v.getContext();
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Local URL", localUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "已复制本机URL: " + localUrl, Toast.LENGTH_SHORT).show();
                });
                
                urlHolder.copyNetworkUrlButton.setVisibility(View.VISIBLE);
                urlHolder.copyNetworkUrlButton.setOnClickListener(v -> {
                    Context context = v.getContext();
                    String urlToCopy = networkUrl;
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Network URL", urlToCopy);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "已复制网络URL: " + urlToCopy, Toast.LENGTH_SHORT).show();
                });
            } else {
                urlHolder.copyLocalUrlButton.setVisibility(View.GONE);
                urlHolder.copyLocalUrlButton.setOnClickListener(null);
                urlHolder.copyNetworkUrlButton.setVisibility(View.GONE);
                urlHolder.copyNetworkUrlButton.setOnClickListener(null);
            }
        } else {
            // 绑定普通卡片
            DashboardItem item = items.get(position);
            ViewHolder normalHolder = (ViewHolder) holder;
            normalHolder.itemTitle.setText(item.getTitle());
            normalHolder.itemValue.setText(item.getValue());
            normalHolder.itemSubtitle.setText(item.getSubtitle());
            
            // 根据按钮类型处理
            int buttonType = item.getButtonType();
            if (buttonType != DashboardItem.BUTTON_NONE) {
                normalHolder.actionButton.setVisibility(View.VISIBLE);
                
                // 设置按钮图标
                if (buttonType == DashboardItem.BUTTON_FOLDER) {
                    normalHolder.actionButton.setImageResource(android.R.drawable.ic_menu_upload);
                } else {
                    normalHolder.actionButton.setImageResource(R.drawable.ic_copy);
                }
                
                normalHolder.actionButton.setOnClickListener(v -> {
                    Context context = v.getContext();
                    
                    if (buttonType == DashboardItem.BUTTON_COPY) {
                        // 复制功能 - 使用 getCopyText() 获取要复制的文本
                        String textToCopy = item.getCopyText();
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("URL", textToCopy);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, "已复制: " + textToCopy, Toast.LENGTH_SHORT).show();
                    } else if (buttonType == DashboardItem.BUTTON_FOLDER) {
                        // 文件夹选择功能
                        if (actionListener != null) {
                            actionListener.onFolderSelectClick(holder.getAdapterPosition(), item);
                        }
                    }
                });
            } else {
                normalHolder.actionButton.setVisibility(View.GONE);
                normalHolder.actionButton.setOnClickListener(null);
            }
        }
    }

    @Override
    public int getItemCount() {
        // URL 卡片在普通列表中占一个位置
        return items != null ? items.size() : 0;
    }

    // 普通卡片 ViewHolder
    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView itemTitle;
        final TextView itemValue;
        final TextView itemSubtitle;
        final ImageButton actionButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemTitle = itemView.findViewById(R.id.itemTitle);
            itemValue = itemView.findViewById(R.id.itemValue);
            itemSubtitle = itemView.findViewById(R.id.itemSubtitle);
            actionButton = itemView.findViewById(R.id.actionButton);
        }
    }
    
    // URL 卡片 ViewHolder
    static class UrlViewHolder extends RecyclerView.ViewHolder {
        final TextView localUrlValue;
        final TextView networkUrlValue;
        final ImageButton copyLocalUrlButton;
        final ImageButton copyNetworkUrlButton;

        UrlViewHolder(@NonNull View itemView) {
            super(itemView);
            localUrlValue = itemView.findViewById(R.id.localUrlValue);
            networkUrlValue = itemView.findViewById(R.id.networkUrlValue);
            copyLocalUrlButton = itemView.findViewById(R.id.copyLocalUrlButton);
            copyNetworkUrlButton = itemView.findViewById(R.id.copyNetworkUrlButton);
        }
    }
}
