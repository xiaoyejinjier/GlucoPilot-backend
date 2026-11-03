package com.threadfit.glucopilot.file.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.mapper.AvatarMediaMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AvatarMediaService {
    private final AvatarMediaMapper mapper;
    private final ObjectStorageService storage;

    public record Image(byte[] bytes, String mime) {}

    public static String path(long id) {
        return "/api/v1/media-files/avatars/" + id;
    }

    public static Long referenceId(String url) {
        if (url == null) {
            return null;
        }
        try {
            var uri = java.net.URI.create(url);
            if (uri.isAbsolute()
                    && !java.util.Set.of("http", "https")
                            .contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))) {
                return null;
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            if (uri.getPath() == null) {
                return null;
            }
            var match =
                    java.util.regex.Pattern.compile(
                                    "^/api/v1/media-files/avatars/([1-9]\\d{0,18})$")
                            .matcher(uri.getPath());
            return match.matches() ? Long.valueOf(match.group(1)) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Image read(long id) {
        if (id < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "头像编号不合法");
        }
        var row = mapper.selectReadableAvatar(id);
        if (row == null) {
            throw missing();
        }
        String mime = row.getMimeType();
        if (!MediaFileConstant.isMimeAllowed(MediaBizType.AVATAR, mime)
                || row.getStorageProvider() == null
                || row.getStorageProvider() != storage.provider().getCode()
                || !java.util.Objects.equals(row.getBucketName(), storage.bucket())) {
            throw missing();
        }
        String key = row.getStorageKey();
        if (!storage.objectExists(key)) {
            throw missing();
        }
        return new Image(storage.getObject(key), mime);
    }

    private static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "头像不存在或不可查看");
    }
}
