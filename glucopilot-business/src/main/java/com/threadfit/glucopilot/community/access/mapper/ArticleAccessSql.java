package com.threadfit.glucopilot.community.access.mapper;

/** Shared publication/audience predicates for legacy content queries. */
public final class ArticleAccessSql {
    private ArticleAccessSql() {}

    // Aliases are source-code constants. Viewer is a numeric identity obtained from RequestContext.
    public static String audienceSql(String n, Long viewer) {
        long user = viewer == null ? 0 : viewer;
        String author =
                "(SELECT va.author_user_id FROM community_article va WHERE va.news_id="
                        + n
                        + ".id)";
        return " (NOT EXISTS(SELECT 1 FROM community_article_access ac WHERE ac.news_id="
                + n
                + ".id AND ac.visibility<>'PUBLIC') OR "
                + author
                + "="
                + user
                + " OR EXISTS(SELECT 1 FROM community_article_access ac JOIN"
                + " community_article_reader ar ON ar.news_id=ac.news_id WHERE ac.news_id="
                + n
                + ".id AND ac.visibility='FRIENDS' AND ar.user_id="
                + user
                + " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="
                + user
                + " AND f.target_key=CONCAT('u',"
                + author
                + ") AND f.following=1)"
                + " AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id="
                + author
                + " AND f.target_key=CONCAT('u',"
                + user
                + ") AND f.following=1))) ";
    }

    public static String readableSql(String n, Long viewer) {
        long user = viewer == null ? 0 : viewer;
        return audienceSql(n, viewer)
                + " AND NOT EXISTS(SELECT 1 FROM community_article_hidden ah WHERE ah.news_id="
                + n
                + ".id AND ah.user_id="
                + user
                + ") ";
    }

    public static String publishedSql(String n) {
        return n
                + ".is_deleted=0 AND "
                + n
                + ".publish_status=1 AND "
                + n
                + ".publish_at<=CURRENT_TIMESTAMP AND ("
                + n
                + ".community_managed=0 OR EXISTS(SELECT 1 FROM community_article pa LEFT JOIN"
                + " user_account au ON au.id=pa.author_user_id AND au.is_deleted=0 AND"
                + " au.account_status=1 WHERE pa.news_id="
                + n
                + ".id AND pa.state='PUBLISHED' AND (au.id IS NOT NULL OR (pa.author_user_id IS"
                + " NULL AND EXISTS(SELECT 1 FROM community_blogger eb WHERE eb.id="
                + n
                + ".blogger_id AND eb.enabled=1 AND eb.user_id IS NULL)))))";
    }
}
