package com.threadfit.glucopilot.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** B 端 CMS / 营养库 DTO（字段对齐 admin 前端展示模型） */
public final class AdminCmsDtos {

    private AdminCmsDtos() {
    }

    @Data
    @Builder
    public static class NewsTag {
        private String code;
        private String name;
    }

    @Data
    @Builder
    public static class NewsItem {
        private Long id;
        private String title;
        private String summary;
        /** 兼容回传；管理端改以 tags 分类 */
        private String channel;
        private String type;
        private String coverUrl;
        private String coverText;
        private String videoUrl;
        private String content;
        private String status;
        private String publishAt;
        private Integer views;
        private Integer likes;
        private Integer collects;
        private String createdAt;
        private String updatedAt;
        private List<NewsTag> tags;
    }

    @Data
    public static class NewsSaveRequest {
        private Long id;
        @NotBlank
        private String title;
        private String summary;
        /** 已废弃：忽略客户端传入，服务端固定 channel_code=4 */
        private String channel;
        /** 已启用标签 code；与 customTags 合计去重后 1～3 个 */
        @Size(max = 3)
        private List<String> tagCodes;
        /** 自定义标签名，服务端创建或复用已有标签 */
        @Size(max = 3)
        private List<String> customTags;
        @NotBlank
        private String type;
        private String coverUrl;
        private String coverText;
        private String videoUrl;
        private String content;
        /** draft | published | unpublished */
        private String status;
        private String publishAt;
    }

    @Data
    @Builder
    public static class BannerItem {
        private Long id;
        private String title;
        private String image;
        private String linkType;
        private String linkValue;
        private Integer sort;
        private Boolean enabled;
        private String remark;
    }

    @Data
    public static class BannerSaveRequest {
        private Long id;
        @NotBlank
        private String title;
        private String image;
        private String linkType;
        private String linkValue;
        private Integer sort;
        private Boolean enabled;
        private String remark;
    }

    @Data
    @Builder
    public static class ListWrap<T> {
        private List<T> records;
    }
}
