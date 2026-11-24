package com.threadfit.glucopilot.localization.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.threadfit.glucopilot.localization.dto.LocalizationDtos.Item;
import com.threadfit.glucopilot.localization.entity.LocalizationAuditRow;
import com.threadfit.glucopilot.localization.entity.LocalizationCacheRow;
import com.threadfit.glucopilot.localization.entity.LocalizationSourceRow;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 翻译缓存和租约的条件更新。线程池和重试策略继续由Service负责。 */
@Mapper
public interface LocalizationMapper {
    String IDENTITY =
            "kind=#{item.kind} AND resource_id=#{item.id}"
                    + " AND source_hash=#{hash} AND locale='en'";
    String LEASE_IDENTITY = IDENTITY + " AND lease_token=#{lease}";

    @Select(
            "SELECT COUNT(*) FROM user_account WHERE id=#{viewer} AND is_deleted=0 AND"
                    + " account_status=1")
    long countActiveUser(@Param("viewer") long viewer);

    @SelectProvider(type = LocalizationSql.class, method = "source")
    LocalizationSourceRow selectSource(
            @Param("viewer") long viewer, @Param("id") long id, @Param("body") boolean body);

    @Select("SELECT status,translated_json FROM content_localization WHERE " + IDENTITY)
    LocalizationCacheRow selectCache(@Param("item") Item item, @Param("hash") String hash);

    /**
     * 审计分页（管理端「AI 审计 → 翻译」）：只读，按最近更新倒序。
     *
     * <p>排序用 {@code updated_at DESC}：翻译表是「同一资源随原文变化不断插入新 hash 行」的追加型缓存，
     * 运营想看的是「最近谁被翻了、翻成什么」，而不是最早的一条。
     * 问诊译文包含健康隐私及付费内容，通用AI审计权限不授予问诊阅读权，因此仅返回原三类公开内容。
     *
     * @param kind   ARTICLE_PREVIEW / ARTICLE_BODY / PROFILE_NAME，可空
     * @param status PENDING / READY / FAILED，可空
     */
    @Select("<script>"
            + "SELECT kind,resource_id,source_hash,locale,status,prompt_ver,translated_json,updated_at"
            + " FROM content_localization WHERE kind IN ('ARTICLE_PREVIEW','ARTICLE_BODY','PROFILE_NAME')"
            + "<if test='kind != null'> AND kind = #{kind}</if>"
            + "<if test='status != null'> AND status = #{status}</if>"
            + " ORDER BY updated_at DESC, kind ASC, resource_id ASC"
            + "</script>")
    IPage<LocalizationAuditRow> selectAuditPage(IPage<LocalizationAuditRow> page,
                                                @Param("kind") String kind,
                                                @Param("status") String status);

    @Insert(
            """
            INSERT INTO content_localization(kind,resource_id,source_hash,locale,status,prompt_ver)
            VALUES(#{item.kind},#{item.id},#{hash},'en','PENDING',#{promptVer})
            """)
    int insertPending(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("promptVer") String promptVer);

    @Update(
            "UPDATE content_localization SET status='PENDING',lease_token=#{lease},"
                    + "lease_until=#{until},updated_at=#{now} WHERE "
                    + IDENTITY
                    + " AND ((status='PENDING' AND (lease_until IS NULL OR lease_until<#{now})) OR"
                    + " (status='FAILED' AND #{retry} AND (retry_after IS NULL OR"
                    + " retry_after<=#{now})))")
    int claim(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("lease") String lease,
            @Param("until") LocalDateTime until,
            @Param("now") LocalDateTime now,
            @Param("retry") boolean retry);

    @Update("UPDATE content_localization SET lease_until=#{until} WHERE " + LEASE_IDENTITY)
    int renew(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("lease") String lease,
            @Param("until") LocalDateTime until);

    @Update(
            "UPDATE content_localization SET lease_token=NULL,lease_until=NULL WHERE "
                    + LEASE_IDENTITY)
    int release(@Param("item") Item item, @Param("hash") String hash, @Param("lease") String lease);

    @Update(
            "UPDATE content_localization SET lease_token=NULL,lease_until=#{until} WHERE "
                    + LEASE_IDENTITY)
    int defer(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("lease") String lease,
            @Param("until") LocalDateTime until);

    /**
     * 清掉「租约已过期、但 token 还挂着」的行（定时巡检用）。
     *
     * <p>清理本身不改变可认领性（{@code claim} 的条件已经允许 {@code lease_until < now} 的行被接手），
     * 它的价值是：① 让运维能从 {@code lease_token} 是否为空看出「这张表有没有堆积」；
     * ② 进程崩溃后残留的 token 不会再被 {@code renew} 心跳续上。
     *
     * @return 被清理的行数
     */
    @Update(
            "UPDATE content_localization SET lease_token=NULL,lease_until=NULL"
                    + " WHERE status='PENDING' AND lease_token IS NOT NULL"
                    + " AND (lease_until IS NULL OR lease_until<#{now})")
    int clearExpiredLeases(@Param("now") LocalDateTime now);

    @Update(
            "UPDATE content_localization SET status='READY',translated_json=#{translatedJson},"
                    + "lease_token=NULL,lease_until=NULL,retry_after=NULL,updated_at=#{now} WHERE "
                    + LEASE_IDENTITY)
    int complete(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("lease") String lease,
            @Param("translatedJson") String translatedJson,
            @Param("now") LocalDateTime now);

    @Update(
            "UPDATE content_localization SET status='FAILED',lease_token=NULL,lease_until=NULL,"
                    + "retry_after=#{retryAfter},updated_at=#{now} WHERE "
                    + LEASE_IDENTITY)
    int fail(
            @Param("item") Item item,
            @Param("hash") String hash,
            @Param("lease") String lease,
            @Param("retryAfter") LocalDateTime retryAfter,
            @Param("now") LocalDateTime now);
}
