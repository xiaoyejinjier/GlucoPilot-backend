package com.threadfit.glucopilot.community.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.community.tag.entity.CommunityTag;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** Community persistence. All values are bound parameters; row locks share Spring transactions. */
@Mapper
public interface CommunityTagMapper extends BaseMapper<CommunityTag> {

    @Select(
            "<script>SELECT COUNT(*) FROM community_tag t WHERE 1=1 <if test=\"name != null and"
                    + " name != &quot;&quot;\"> AND LOCATE(LOWER(#{name}),LOWER(t.name))>0</if><if"
                    + " test=\"type == &quot;USER&quot;\"> AND LEFT(t.code,7)='custom_'</if><if"
                    + " test=\"type == &quot;OFFICIAL&quot;\"> AND"
                    + " LEFT(t.code,7)&lt;&gt;'custom_'</if><if test=\"enabled != null\"> AND"
                    + " t.enabled=#{enabled}</if></script> ")
    Long countTags(
            @Param("name") String name,
            @Param("type") String type,
            @Param("enabled") Integer enabled);

    @Select(
            "<script>SELECT t.*,(SELECT COUNT(*) FROM community_article_tag a JOIN cms_news n ON"
                + " n.id=a.news_id WHERE a.tag_id=t.id AND n.is_deleted=0) article_count FROM"
                + " community_tag t  WHERE 1=1 <if test=\"name != null and name != &quot;&quot;\">"
                + " AND LOCATE(LOWER(#{name}),LOWER(t.name))>0</if><if test=\"type =="
                + " &quot;USER&quot;\"> AND LEFT(t.code,7)='custom_'</if><if test=\"type =="
                + " &quot;OFFICIAL&quot;\"> AND LEFT(t.code,7)&lt;&gt;'custom_'</if><if"
                + " test=\"enabled != null\"> AND t.enabled=#{enabled}</if> ORDER BY t.sort_no,t.id"
                + " LIMIT #{limit} OFFSET #{offset}</script> ")
    List<Map<String, Object>> selectTags(
            @Param("name") String name,
            @Param("type") String type,
            @Param("enabled") Integer enabled,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select(
            "SELECT t.*,(SELECT COUNT(*) FROM community_article_tag a JOIN cms_news n ON"
                    + " n.id=a.news_id WHERE a.tag_id=t.id AND n.is_deleted=0) article_count FROM"
                    + " community_tag t  WHERE t.code=#{code} ")
    List<Map<String, Object>> selectTag(@Param("code") String code);

    @Select("SELECT id FROM community_tag ORDER BY id FOR UPDATE ")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> lockTags();

    @Select(
            "SELECT code,name,sort_no,enabled FROM community_tag WHERE LOWER(name)=LOWER(#{name})"
                    + " ORDER BY id LIMIT 1 ")
    List<Map<String, Object>> selectByName(@Param("name") String name);

    @Insert(
            "INSERT INTO community_tag(code,name,sort_no,enabled)"
                    + " VALUES(#{code},#{name},#{sortNo},#{enabled}) ")
    int insertTag(
            @Param("code") String code,
            @Param("name") String name,
            @Param("sortNo") int sortNo,
            @Param("enabled") int enabled);

    @Select(
            "SELECT COUNT(*) FROM community_tag WHERE LOWER(name)=LOWER(#{name}) AND code<>#{code}"
                    + " ")
    Long countOtherName(@Param("name") String name, @Param("code") String code);

    @Update(
            "UPDATE community_tag SET name=#{name},sort_no=#{sortNo},enabled=#{enabled} WHERE"
                    + " code=#{code} ")
    int updateTag(
            @Param("name") String name,
            @Param("sortNo") int sortNo,
            @Param("enabled") int enabled,
            @Param("code") String code);

    @Update("UPDATE community_tag SET enabled=0 WHERE code=#{code} ")
    int disableTag(@Param("code") String code);
}
