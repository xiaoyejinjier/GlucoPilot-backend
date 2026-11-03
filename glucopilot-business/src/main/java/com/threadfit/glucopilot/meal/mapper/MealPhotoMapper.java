package com.threadfit.glucopilot.meal.mapper;

import com.threadfit.glucopilot.file.entity.MediaFile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 正餐照片只返回当前有效用户已关联到未删除正餐记录的媒体元数据。 */
@Mapper
public interface MealPhotoMapper {
    @Select(
            """
            SELECT m.storage_key,m.mime_type,m.storage_provider,m.bucket_name,m.size_bytes
            FROM media_file m JOIN user_account u ON u.id=m.user_id
            WHERE m.id=#{id} AND m.user_id=#{user} AND m.ops_id IS NULL AND m.biz_type=3
              AND m.is_deleted=0 AND m.file_status=1 AND u.is_deleted=0 AND u.account_status=1
              AND EXISTS(SELECT 1 FROM meal_record r
                WHERE r.user_id=#{user} AND r.media_id=m.id AND r.is_deleted=0)
            """)
    MediaFile selectOwnedPhoto(@Param("user") long user, @Param("id") long id);
}
