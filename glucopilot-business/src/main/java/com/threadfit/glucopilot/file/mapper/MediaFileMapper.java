package com.threadfit.glucopilot.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.file.entity.MediaFile;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MediaFileMapper extends BaseMapper<MediaFile> {

    /**
     * 账号注销：物理删除 C 端用户的业务媒体（bizType 1..6）。
     * <p>
     * 7/8（社区配图/视频）刻意保留：文章与评论要匿名继续展示，删了对象图就裂了。
     * 这也意味着注销清理不能简单按 user_id 全删。
     */
    @Delete("DELETE FROM media_file WHERE user_id = #{userId} AND biz_type BETWEEN 1 AND 6")
    int purgeUserBusinessFiles(@Param("userId") Long userId);

    /**
     * 删除对象前的共用检查：除该用户自己外，还有没有别的行引用同一个 storageKey。
     * <p>
     * <b>刻意不过滤 is_deleted</b>：逻辑删除的行仍是有效引用方（回收站里的素材还要能看），
     * 按"无引用"删对象会让那些行指向一个 404。
     */
    @Select("SELECT COUNT(*) FROM media_file WHERE storage_key = #{storageKey} "
            + "AND (user_id IS NULL OR user_id <> #{userId})")
    int countOtherReferences(@Param("storageKey") String storageKey, @Param("userId") Long userId);
}