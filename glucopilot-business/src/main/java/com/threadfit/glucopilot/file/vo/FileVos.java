package com.threadfit.glucopilot.file.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public final class FileVos {

    private FileVos() {
    }

    /** 凭证接口出参：{ uploadUrl, storageKey, expireIn }。 */
    @Data
    @Builder
    public static class UploadCredential {
        private String uploadUrl;
        private String storageKey;
        /** 预签名 URL 有效期（秒）。 */
        private Long expireIn;
    }

    /** 登记接口出参：{ id, fileUrl }。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaFileRegisterResult {
        private String id;
        private String fileUrl;
    }
}