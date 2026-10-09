package com.threadfit.glucopilot.ruoyi.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** DTOs for the compatibility RuoYi administration APIs under /admin/v1/ruoyi. */
public final class RuoYiAdminDtos {

    private RuoYiAdminDtos() {}

    @Data
    public static class UserRequest {
        @NotBlank @Size(max = 64) private String username;
        @Size(max = 128) private String password;
        @Size(max = 64) private String nickname;
        @Size(max = 20) private String mobile;
        @Size(max = 128) private String email;
        private Long deptId;
        @NotNull @Min(0) @Max(1) private Integer status;
        private List<String> roleCodes = List.of();
        private List<Long> postIds = List.of();
    }

    @Data
    public static class UserItem {
        private Long id;
        private String username;
        private String nickname;
        private String mobile;
        private String email;
        private Long deptId;
        private String deptName;
        private Integer status;
        private List<String> roleCodes;
        private List<Long> postIds;
        private LocalDateTime lastLoginAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class RoleRequest {
        @NotBlank @Size(max = 64) private String roleCode;
        @NotBlank @Size(max = 64) private String roleName;
        @Min(0) private Integer sortNo = 0;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
        private List<String> permissionCodes = List.of();
    }

    @Data
    public static class RoleItem {
        private Long id;
        private String roleCode;
        private String roleName;
        private Integer sortNo;
        private Integer status;
        private String remark;
        private List<String> permissionCodes;
    }

    @Data
    public static class MenuRequest {
        @NotBlank @Size(max = 64) private String menuName;
        private Long parentId = 0L;
        @Min(0) private Integer sortNo = 0;
        @Size(max = 256) private String pathUrl;
        @Size(max = 256) private String componentName;
        @Size(max = 256) private String queryString;
        @NotBlank private String menuType = "C";
        @Min(0) @Max(1) private Integer isFrame = 1;
        @Min(0) @Max(1) private Integer isCache = 0;
        @Min(0) @Max(1) private Integer visible = 1;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 128) private String permCode;
        @Size(max = 64) private String iconName;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class DeptRequest {
        @NotBlank @Size(max = 64) private String deptName;
        private Long parentId = 0L;
        @Min(0) private Integer sortNo = 0;
        @Size(max = 64) private String leader;
        @Size(max = 20) private String phone;
        @Size(max = 128) private String email;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class PostRequest {
        @NotBlank @Size(max = 64) private String postCode;
        @NotBlank @Size(max = 64) private String postName;
        @Min(0) private Integer postSort = 0;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class DictTypeRequest {
        @NotBlank @Size(max = 100) private String dictName;
        @NotBlank @Size(max = 100) private String dictType;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class DictDataRequest {
        @NotBlank @Size(max = 100) private String dictLabel;
        @NotBlank @Size(max = 100) private String dictValue;
        @NotBlank @Size(max = 100) private String dictType;
        @Min(0) private Integer dictSort = 0;
        @Size(max = 100) private String cssClass;
        @Size(max = 100) private String listClass;
        @Min(0) @Max(1) private Integer isDefault = 0;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class NoticeRequest {
        @NotBlank @Size(max = 100) private String title;
        @NotBlank private String type = "1";
        @NotBlank private String content;
        @NotNull @Min(0) @Max(1) private Integer status;
    }

    @Data
    public static class ConfigRequest {
        @NotBlank @Size(max = 64) private String configKey;
        @Size(max = 2000) private String configValue;
        @NotNull @Min(1) @Max(4) private Integer valueType;
        @NotBlank @Size(max = 32) private String configGroup;
        @Size(max = 255) private String remark;
    }

    @Data
    public static class JobRequest {
        @NotBlank @Size(max = 64) private String jobName;
        @Size(max = 64) private String jobGroup = "DEFAULT";
        @NotBlank @Size(max = 128) private String invokeTarget;
        @NotBlank @Size(max = 64) private String cronExpression;
        @Size(max = 20) private String misfirePolicy = "DO_NOTHING";
        @Min(0) @Max(1) private Integer concurrentAllowed = 0;
        @NotNull @Min(0) @Max(1) private Integer status;
        @Size(max = 256) private String remark;
    }

    @Data
    public static class OnlineSessionItem {
        private String id;
        private Long opsId;
        private String username;
        private String nickname;
        private String clientIp;
        private String userAgent;
        private LocalDateTime expireAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class ServerStatus {
        private String application;
        private String javaVersion;
        private String osName;
        private int processors;
        private long heapUsedBytes;
        private long heapMaxBytes;
        private long uptimeMillis;
        private long activeSessions;
    }

    @Data
    public static class CacheStatus {
        private boolean redisReachable;
        private String detail;
        private String policy;
    }

    @Data
    public static class GeneratorTableItem {
        private String tableName;
        private String tableComment;
        private boolean imported;
        private Long genTableId;
    }

    @Data
    public static class GeneratorColumnItem {
        private String columnName;
        private String columnComment;
        private String columnType;
        private String javaType;
        private String javaField;
        private boolean primaryKey;
        private int sortNo;
    }

    @Data
    public static class GeneratorPreview {
        private Long tableId;
        private String tableName;
        private String entitySource;
        private String controllerSource;
        private List<GeneratorColumnItem> columns;
    }
}
