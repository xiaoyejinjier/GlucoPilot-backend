package com.threadfit.glucopilot.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.enums.CodeEnum.StorageProvider;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.idempotency.IdempotencyStore;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.dto.FileDtos.RegisterRequest;
import com.threadfit.glucopilot.file.dto.FileDtos.UploadCredentialRequest;
import com.threadfit.glucopilot.file.entity.MediaFile;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import com.threadfit.glucopilot.file.vo.FileVos.MediaFileRegisterResult;
import com.threadfit.glucopilot.file.vo.FileVos.UploadCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MediaFileServiceImpl implements MediaFileService {

    private static final int FILE_STATUS_AVAILABLE = 1;

    private final MediaFileMapper mediaFileMapper;
    private final ObjectStorageService objectStorageService;
    private final IdempotencyStore idempotencyStore;
    private final JsonUtils jsonUtils;

    /**
     * 第一步：签发凭证。仅做参数校验并据此生成对象存储直传凭证，文件本体不经后端传输。
     * 校验顺序不可颠倒（bizType → size → MIME），且须与登记接口 {@link #register} 完全一致，
     * 否则两侧口径不一致会让客户端钻空子（例如凭证不校验 MIME、登记时才校验）。
     */
    @Override
    public UploadCredential createUploadCredential(UploadCredentialRequest request, Long userId) {
        // bizType 必须落在 MediaBizType 枚举内，避免用非法值拼接存储对象 key / 调对象存储
        MediaBizType bizType = CodeEnum.of(MediaBizType.class, request.getBizType());
        // 校验顺序：size(≤20MB) → MIME 白名单，不可颠倒
        validateSizeAndMime(bizType, request.getMimeType(), request.getSizeBytes());
        // 由 MIME 推导存储扩展名（未知 MIME 回退 bin），用于拼接 storageKey
        String ext = MediaFileConstant.extOf(request.getMimeType());
        return objectStorageService.createUploadCredential(bizType, ext, request.getSizeBytes(), userId);
    }

    /**
     * 第二步：登记。客户端直传完成后调用，按序完成四步守门后落库：
     * (1) 参数校验；(2) storageKey 前缀防伪造；(3) HEAD 确认真实上传；(4) 内容去重 + 接口级幂等；
     * 最后通过 {@link #insertMediaFile} 落库并由对象存储生成正式 fileUrl。
     * spring 事务保证 insert 与去重/幂等读写在一致性边界内。
     */
    @Override
    @Transactional
    public MediaFileRegisterResult register(RegisterRequest request, Long userId) {
        // 与凭证接口同序、同规则的参数校验（bizType → size → MIME）
        MediaBizType bizType = CodeEnum.of(MediaBizType.class, request.getBizType());
        validateSizeAndMime(bizType, request.getMimeType(), request.getSizeBytes());

        // 1. 防伪造：storageKey 由客户端回传，必须确认其第 3 段等于当前登录用户，
        //    否则客户端可用他人 key 代登记、造成越权（403/40001）。
        requireOwnStorageKey(request.getStorageKey(), bizType, userId);

        // 2. 真实性校验：HEAD 确认对象已真实上传。若不校验，客户端可「未直传先登记」，
        //    拿一份空凭证换回正式的 fileUrl，破坏两步契约。
        if (!objectStorageService.objectExists(request.getStorageKey())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "对象尚未上传");
        }

        // 3. 内容级去重（sha256Hex + userId + bizType）：同一用户传同一内容直接复用，减少占存
        Optional<MediaFile> duplicated = findDuplicated(userId, bizType, request.getSha256Hex());
        if (duplicated.isPresent()) {
            return toResult(duplicated.get());
        }

        // 4. 接口级幂等：透传 X-Idempotency-Key 时先 Redis 占锁再落库，
        //    避免「先查后写」竞态下并发重复 insert。
        String idempotencyKey = RequestContext.getIdempotencyKey();
        if (idempotencyKey != null) {
            String key = IdempotencyStore.key("media:register", userId, idempotencyKey);
            IdempotencyStore.Claim claim = idempotencyStore.claim(key);
            if (claim instanceof IdempotencyStore.Claim.Replay replay) {
                return jsonUtils.fromJson(replay.json(), MediaFileRegisterResult.class);
            }
            if (claim instanceof IdempotencyStore.Claim.InFlight) {
                throw new BizException(ErrorCode.CONFLICT, "相同请求处理中，请稍后重试");
            }
            try {
                MediaFile saved = insertMediaFile(bizType, request, userId);
                MediaFileRegisterResult result = toResult(saved);
                idempotencyStore.complete(key, result);
                return result;
            } catch (RuntimeException ex) {
                idempotencyStore.release(key);
                throw ex;
            }
        }

        return toResult(insertMediaFile(bizType, request, userId));
    }

    @Override
    public MediaFile findAvailableAvatar(Long userId, String fileUrl) {
        Long reference = AvatarMediaService.referenceId(fileUrl);
        var query = new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, MediaBizType.AVATAR.getCode())
                .eq(MediaFile::getFileStatus, FILE_STATUS_AVAILABLE)
                .last("LIMIT 1");
        if (reference == null) query.eq(MediaFile::getFileUrl, fileUrl);
        else query.eq(MediaFile::getId, reference);
        return mediaFileMapper.selectOne(query);
    }

    @Override
    public MediaFile findOwnedMedia(Long mediaId, Long userId, MediaBizType bizType) {
        return mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaId)
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, bizType.getCode())
                .eq(MediaFile::getFileStatus, FILE_STATUS_AVAILABLE)
                .last("LIMIT 1"));
    }

    @Override
    public List<MediaFile> listOwnedByIds(Collection<Long> mediaIds, Long userId, MediaBizType bizType) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            // 空集合直接返回，不发一条 `IN ()` 的废查询
            return List.of();
        }
        return mediaFileMapper.selectList(new LambdaQueryWrapper<MediaFile>()
                .in(MediaFile::getId, mediaIds)
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, bizType.getCode())
                .eq(MediaFile::getFileStatus, FILE_STATUS_AVAILABLE));
    }

    /**
     * 账号注销：删除 C 端业务媒体（bizType 1..6）的对象与行；7/8 社区配图/视频保留。
     * <p>
     * 顺序：<b>先删对象，再删行</b>。反过来的话行没了就再也拿不到 storageKey，
     * 一旦对象删除失败就成了"永久孤儿"，重试也救不回来。
     * <p>
     * 共用一个 storageKey 的其他行仍存在时不删对象——同一张图可能被多行业务引用
     * （例如同一次上传登记出来的头像与相册），按用户全删会让别人的引用变成 404。
     */
    @Override
    @Transactional
    public void purgeAccountData(Long userId) {
        List<MediaFile> owned = mediaFileMapper.selectList(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .between(MediaFile::getBizType,
                        CodeEnum.MediaBizType.AVATAR.getCode(), CodeEnum.MediaBizType.CHAT_VOICE.getCode()));
        for (MediaFile file : owned) {
            String storageKey = file.getStorageKey();
            if (storageKey == null || storageKey.isBlank()) {
                continue;
            }
            if (mediaFileMapper.countOtherReferences(storageKey, userId) == 0) {
                objectStorageService.deleteObject(storageKey);
            }
        }
        mediaFileMapper.purgeUserBusinessFiles(userId);
    }

    /**
     * 落库入口：写入一条 media_file，fileUrl 也由此处统一生成（file_url 由服务端 resolve，非客户端传入）。
     */
    private MediaFile insertMediaFile(MediaBizType bizType, RegisterRequest request, Long userId) {
        StorageProvider provider = objectStorageService.provider();
        MediaFile row = new MediaFile();
        row.setUserId(userId);
        row.setBizType(bizType.getCode());
        row.setStorageProvider(provider.getCode());
        row.setBucketName(objectStorageService.bucket());
        row.setStorageKey(request.getStorageKey());
        row.setMimeType(request.getMimeType());
        row.setSizeBytes(request.getSizeBytes());
        row.setSha256Hex(request.getSha256Hex());
        row.setFileStatus(FILE_STATUS_AVAILABLE);
        // fileUrl 由服务端基于 storageKey 解析生成，绝不信任客户端传入的地址
        row.setFileUrl(objectStorageService.resolvePublicUrl(request.getStorageKey()));
        mediaFileMapper.insert(row);
        return row;
    }

    /** 内容级去重：sha256Hex 非空时按 userId + bizType + sha256Hex 查已有记录。 */
    private Optional<MediaFile> findDuplicated(Long userId, MediaBizType bizType, String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) {
            return Optional.empty();
        }
        MediaFile row = mediaFileMapper.selectOne(new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .eq(MediaFile::getBizType, bizType.getCode())
                .eq(MediaFile::getSha256Hex, sha256Hex)
                .last("LIMIT 1"));
        return Optional.ofNullable(row);
    }

    /** storageKey = {bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}，前缀必须含 /{userId}/ 且 bizType 段一致。 */
    private void requireOwnStorageKey(String storageKey, MediaBizType bizType, Long userId) {
        String[] parts = storageKey.split("/");
        // 段数须 >=5：{bizType}/{yyyy}/{MM}/{userId}/{uuid}.{ext}
        if (parts.length < 5) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 非法");
        }
        // 第 0 段必须等于登记声称的 bizType，防止跨业务类型借用 key
        if (!String.valueOf(bizType.getCode()).equals(parts[0])) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 业务类型不匹配");
        }
        // 第 3 段必须等于当前登录用户 id —— 此处是「防伪/防越权」的核心判点
        if (!String.valueOf(userId).equals(parts[3])) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "storageKey 不属于当前用户");
        }
    }

    /** 入参体积 / MIME 白名单校验（凭证与登记入口共用，顺序固定：大小在前、MIME 在后）。 */
    private void validateSizeAndMime(MediaBizType bizType, String mimeType, long sizeBytes) {
        if (bizType == MediaBizType.ARTICLE_IMAGE && sizeBytes > 5L * 1024 * 1024) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文章图片不能超过5MB");
        }
        // 限制：0 < size ≤20MB，超限直接 40000（BAD_REQUEST）
        if (sizeBytes <= 0 || sizeBytes > MediaFileConstant.DEFAULT_MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件大小超出限制");
        }
        // MIME 必须在按 bizType 划分的白名单内，实现即 MediaFileConstant#isMimeAllowed
        if (!MediaFileConstant.isMimeAllowed(bizType, mimeType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 MIME 类型");
        }
    }

    private MediaFileRegisterResult toResult(MediaFile row) {
        return MediaFileRegisterResult.builder()
                .id(String.valueOf(row.getId()))
                .fileUrl(row.getBizType().equals(MediaBizType.AVATAR.getCode()) ? AvatarMediaService.path(row.getId()) : row.getFileUrl())
                .build();
    }
}
