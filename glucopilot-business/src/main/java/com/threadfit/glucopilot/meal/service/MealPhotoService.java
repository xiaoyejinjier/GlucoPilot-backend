package com.threadfit.glucopilot.meal.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MealPhotoService {
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    public record Image(byte[] bytes, String mime) { }
    public static String path(long id) { return "/api/v1/meal-records/images/" + id; }

    public Image read(long user, long id) {
        var rows = jdbc.queryForList("""
                SELECT m.storage_key,m.mime_type,m.storage_provider,m.bucket_name,m.size_bytes
                FROM media_file m JOIN user_account u ON u.id=m.user_id
                WHERE m.id=? AND m.user_id=? AND m.ops_id IS NULL AND m.biz_type=3
                  AND m.is_deleted=0 AND m.file_status=1 AND u.is_deleted=0 AND u.account_status=1
                  AND EXISTS(SELECT 1 FROM meal_record r
                    WHERE r.user_id=? AND r.media_id=m.id AND r.is_deleted=0)
                """, id, user, user);
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        String mime = (String) row.get("mime_type"), key = (String) row.get("storage_key");
        Number provider = (Number) row.get("storage_provider"), size = (Number) row.get("size_bytes");
        if (!MediaFileConstant.isMimeAllowed(MediaBizType.MEAL, mime) || key == null || key.isBlank()
                || provider == null || provider.intValue() != storage.provider().getCode()
                || !java.util.Objects.equals(row.get("bucket_name"), storage.bucket())
                || size == null || size.longValue() <= 0 || size.longValue() > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) throw missing();
        byte[] bytes = storage.getObject(key);
        if (bytes == null || bytes.length == 0 || bytes.length > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) throw missing();
        return new Image(bytes, mime);
    }
    private static BizException missing() { return new BizException(ErrorCode.NOT_FOUND, "正餐照片不存在或不可查看"); }
}
