package com.threadfit.glucopilot.common.constant;

/**
 * 运营权限码（与 SQL 种子 sys_permission.perm_code 对齐）。
 * 鉴权规则在 security / admin 模块，这里只集中常量，避免魔法字符串。
 */
public final class PermCodes {

    public static final String DASHBOARD_VIEW = "dashboard:view";
    public static final String CONTENT_NEWS_LIST = "content:news:list";
    public static final String CONTENT_NEWS_EDIT = "content:news:edit";
    public static final String CONTENT_BANNER_MANAGE = "content:banner:manage";
    public static final String CONTENT_MEDIA_MANAGE = "content:media:manage";
    public static final String FOOD_LIBRARY_MANAGE = "food:library:manage";
    public static final String FEEDBACK_MANAGE = "feedback:manage";
    public static final String FEEDBACK_REPLY = "feedback:reply";
    public static final String EXPORT_TASK_MANAGE = "export:task:manage";
    public static final String USER_MANAGE = "user:manage";

    public static final String DEVICE_MANAGE = "device:manage";
    public static final String HEALTH_ARCHIVE_VIEW = "health:archive:view";
    public static final String MESSAGE_PUSH_MANAGE = "message:push:manage";
    public static final String AI_CONFIG_MANAGE = "ai:config:manage";
    public static final String SYSTEM_ROLE_MANAGE = "system:role:manage";
    public static final String SYSTEM_CONFIG_MANAGE = "system:config:manage";
    public static final String SYSTEM_LOG_VIEW = "system:log:view";

    private PermCodes() {
    }
}