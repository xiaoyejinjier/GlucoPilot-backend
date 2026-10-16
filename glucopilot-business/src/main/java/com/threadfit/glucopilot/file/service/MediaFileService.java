package com.threadfit.glucopilot.file.service;

import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.file.dto.FileDtos.RegisterRequest;
import com.threadfit.glucopilot.file.dto.FileDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.vo.FileVos.MediaFileRegisterResult;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;

import java.util.Collection;
import java.util.List;

public interface MediaFileService {

    /** 生成直传凭证。 */
    UploadCredential createUploadCredential(UploadCredentialRequest request, Long userId);

    /** 登记上传完成的文件，返回 { id, fileUrl }。 */
    MediaFileRegisterResult register(RegisterRequest request, Long userId);

    /** 校验并返回 C 端用户可用的头像（供 health 2.7 头像校验）；不存在返回 null。 */
    MediaFile findAvailableAvatar(Long userId, String fileUrl);

    /** 校验并返回 C 端用户可用的指定类型媒体（供 meal 照片归属校验）；不存在返回 null。 */
    MediaFile findOwnedMedia(Long mediaId, Long userId, MediaBizType bizType);

    /**
     * 批量取"当前用户拥有的、业务类型匹配的、可用的"媒体（供会话历史一次性取回图片地址）。
     * <p>
     * 存在的意义只有一个：避免在列表/历史这种"一页多条"的场景里逐条查（N+1）。
     * 归属与可用性条件与 {@link #findOwnedMedia} 完全一致——两条路径的可见性判据必须同源，
     * 否则会出现"单条查得到、批量查不到"（或反之）这种最难查的不一致。
     */
    List<MediaFile> listOwnedByIds(Collection<Long> mediaIds, Long userId, MediaBizType bizType);
}
