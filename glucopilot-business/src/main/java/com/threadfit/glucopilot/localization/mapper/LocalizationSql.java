package com.threadfit.glucopilot.localization.mapper;

import com.threadfit.glucopilot.community.access.mapper.ArticleAccessSql;

import java.util.Map;

/** 只在固定字段集合之间选择，文章发布、阅读权限和原文读取仍在同一SQL内执行。 */
public final class LocalizationSql {
    private LocalizationSql() {}

    public static String source(Map<String, Object> args) {
        long viewer = ((Number) args.get("viewer")).longValue();
        String fields =
                Boolean.TRUE.equals(args.get("body")) ? "n.content_body" : "n.title,n.summary_text";
        // id保证空正文仍返回一个有效对象，避免MyBatis把全NULL投影当作不存在。
        return "SELECT n.id,"
                + fields
                + " FROM cms_news n WHERE n.id=#{id} AND "
                + ArticleAccessSql.publishedSql("n")
                + " AND "
                + ArticleAccessSql.readableSql("n", viewer);
    }
}
