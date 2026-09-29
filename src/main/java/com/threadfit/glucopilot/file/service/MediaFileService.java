package com.threadfit.glucopilot.file.service;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.file.dto.FileDtos.RegisterRequest;
import com.threadfit.glucopilot.file.dto.FileDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.vo.FileVos.MediaFileRegisterResult;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;

public interface MediaFileService {

    /** 生成直传凭证。 */
    UploadCredential createUploadCredential(UploadCredentialRequest request, Long userId);

    /** 登记上传完成的文件，返回 { id, fileUrl }。 */
    MediaFileRegisterResult register(RegisterRequest request, Long userId);

    /** 校验并返回 C 端用户可用的头像（供 health 2.7 头像校验）；不存在返回 null。 */
    MediaFile findAvailableAvatar(Long userId, String fileUrl);

    /** 校验并返回 C 端用户可用的指定类型媒体（供 meal 照片归属校验）；不存在返回 null。 */
    MediaFile findOwnedMedia(Long mediaId, Long userId, MediaBizType bizType);
}
