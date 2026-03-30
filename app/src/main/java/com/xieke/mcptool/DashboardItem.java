package com.xieke.mcptool;

/**
 * 数据模型：表示仪表盘中的单条指标
 */
public class DashboardItem {
    // 按钮类型常量
    public static final int BUTTON_NONE = 0;
    public static final int BUTTON_COPY = 1;
    public static final int BUTTON_FOLDER = 2;
    
    private final String title;
    private final String value;
    private final String subtitle;
    private final int buttonType;
    private final String copyText; // 用于复制操作的文本

    public DashboardItem(String title, String value, String subtitle) {
        this(title, value, subtitle, BUTTON_NONE, null);
    }

    public DashboardItem(String title, String value, String subtitle, int buttonType) {
        this(title, value, subtitle, buttonType, null);
    }
    
    // 兼容旧代码：用于复制按钮
    public DashboardItem(String title, String value, String subtitle, boolean showCopyButton) {
        this.title = title;
        this.value = value;
        this.subtitle = subtitle;
        this.buttonType = showCopyButton ? BUTTON_COPY : BUTTON_NONE;
        this.copyText = null;
    }

    // 完整构造函数
    public DashboardItem(String title, String value, String subtitle, int buttonType, String copyText) {
        this.title = title;
        this.value = value;
        this.subtitle = subtitle;
        this.buttonType = buttonType;
        this.copyText = copyText;
    }

    /** 指标名称 */
    public String getTitle() {
        return title;
    }

    /** 指标数值 */
    public String getValue() {
        return value;
    }

    /** 指标说明 */
    public String getSubtitle() {
        return subtitle;
    }

    /** 按钮类型：BUTTON_NONE, BUTTON_COPY, BUTTON_FOLDER */
    public int getButtonType() {
        return buttonType;
    }

    /** 获取复制文本，如果没有设置则返回value */
    public String getCopyText() {
        return copyText != null ? copyText : value;
    }

    /** 是否显示复制按钮（兼容旧代码） */
    public boolean isShowCopyButton() {
        return buttonType == BUTTON_COPY;
    }
}
