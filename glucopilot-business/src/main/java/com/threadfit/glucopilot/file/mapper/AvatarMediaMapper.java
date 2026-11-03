package com.threadfit.glucopilot.file.mapper;

import com.threadfit.glucopilot.file.entity.MediaFile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 有效用户的公开头像元数据；路径字段映射为MediaFile的驼峰属性。 */
@Mapper
public interface AvatarMediaMapper {
    @Select(
            """
            SELECT m.storage_key,m.mime_type,m.storage_provider,m.bucket_name
            FROM media_file m
            JOIN user_account u ON u.id=m.user_id AND u.is_deleted=0 AND u.account_status=1
            WHERE m.id=#{id} AND m.biz_type=1 AND m.file_status=1 AND m.is_deleted=0
            """)
    MediaFile selectReadableAvatar(@Param("id") long id);
}
