package com.threadfit.glucopilot.file.service;

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
public class AvatarMediaService {
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    public record Image(byte[] bytes, String mime) {}

    public static String path(long id) { return "/api/v1/media-files/avatars/" + id; }

    public static Long referenceId(String url) {
        if (url == null) return null;
        try {
            var uri = java.net.URI.create(url);
            if (uri.isAbsolute() && !java.util.Set.of("http","https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))) return null;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            if (uri.getPath() == null) return null;
            var match = java.util.regex.Pattern.compile("^/api/v1/media-files/avatars/([1-9]\\d{0,18})$").matcher(uri.getPath());
            return match.matches() ? Long.valueOf(match.group(1)) : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    public Image read(long id) {
        if (id < 1) throw new BizException(ErrorCode.BAD_REQUEST, "头像编号不合法");
        var records = jdbc.queryForList("SELECT m.storage_key,m.mime_type,m.storage_provider,m.bucket_name FROM media_file m "
                + "JOIN user_account u ON u.id=m.user_id AND u.is_deleted=0 AND u.account_status=1 "
                + "WHERE m.id=? AND m.biz_type=1 AND m.file_status=1 AND m.is_deleted=0", id);
        if (records.isEmpty()) throw missing();
        var row = records.getFirst();
        String mime = (String) row.get("mime_type");
        if (!MediaFileConstant.isMimeAllowed(MediaBizType.AVATAR, mime)
                || ((Number) row.get("storage_provider")).intValue() != storage.provider().getCode()
                || !java.util.Objects.equals(row.get("bucket_name"), storage.bucket())) throw missing();
        String key = (String) row.get("storage_key");
        if (!storage.objectExists(key)) throw missing();
        return new Image(storage.getObject(key), mime);
    }
    private static BizException missing() { return new BizException(ErrorCode.NOT_FOUND, "头像不存在或不可查看"); }
}
