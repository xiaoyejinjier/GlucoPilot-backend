package com.threadfit.glucopilot.localization.mapper;

import com.threadfit.glucopilot.localization.LocalizationDtos.Item;
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
