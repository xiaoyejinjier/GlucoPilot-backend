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
        /** 预签名 PUT URL，客户端直传对象（有效期内方可上传）。 */
        private String uploadUrl;

        /** 与直传落点对应的对象 Key，登记接口回传此值。 */
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
        /** 新登记记录主键。 */
        private String id;
        /** 正式访问 URL（由服务端基于 storageKey 解析生成）。 */
        private String fileUrl;
    }
}