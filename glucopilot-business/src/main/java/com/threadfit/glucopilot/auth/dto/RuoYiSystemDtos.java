package com.threadfit.glucopilot.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 管理端权限上下文，字段与 RuoYi 的角色/权限/菜单模型对齐。 */
public final class RuoYiSystemDtos {

    private RuoYiSystemDtos() {
    }

    @Data
    @Builder
    public static class UserInfo {
        private Long opsId;
        private String username;
        private String nickname;
        /** 管理端兼容角色：admin | editor。 */
        private String role;
        /** 后端权威角色编码，如 super_admin、operator。 */
        private List<String> roles;
        /** RuoYi 风格权限标识，如 content:news:edit。 */
        private List<String> permissions;
        private List<MenuItem> menus;
    }

    @Data
    @Builder
    public static class MenuItem {
        private String permission;
        private String name;
        private String path;
        private Integer order;
    }
}
