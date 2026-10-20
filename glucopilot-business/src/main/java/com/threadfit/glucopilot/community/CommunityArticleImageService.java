package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.file.constant.MediaFileConstant;
import com.threadfit.glucopilot.file.mapper.MediaFileMapper;
import com.threadfit.glucopilot.file.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
public class CommunityArticleImageService {
    private final JdbcTemplate jdbc;
    private final MediaFileMapper media;
    private final ObjectStorageService storage;
    public CommunityArticleImageService(JdbcTemplate jdbc,MediaFileMapper media,ObjectStorageService storage) {
        this.jdbc=jdbc;this.media=media;this.storage=storage;
    }
    public Image read(Long viewer, Long articleId, long mediaId) {
        return read(viewer,articleId,mediaId,false);
    }
    public Image read(Long viewer,Long articleId,long mediaId,boolean video) {
        long max=video?20971520:5242880;int type=video?8:7;
        if(viewer!=null && jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1",Long.class,viewer)!=1) throw missing();
        var photo=media.selectById(mediaId);
        if(photo==null || !Integer.valueOf(0).equals(photo.getIsDeleted()) || photo.getOpsId()!=null
                || photo.getUserId()==null || !Integer.valueOf(type).equals(photo.getBizType())
                || !Integer.valueOf(1).equals(photo.getFileStatus())
                || !Objects.equals(photo.getStorageProvider(),storage.provider().getCode())
                || !Objects.equals(photo.getBucketName(),storage.bucket())
                || !MediaFileConstant.isMimeAllowed(video?MediaBizType.ARTICLE_VIDEO:MediaBizType.ARTICLE_IMAGE,photo.getMimeType())
                || photo.getSizeBytes()==null || photo.getSizeBytes()<1 || photo.getSizeBytes()>max
                || photo.getStorageKey()==null || photo.getStorageKey().isBlank()) throw missing();
        if(articleId!=null) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article_image i JOIN cms_news n ON n.id=i.news_id WHERE i.news_id=? AND i.media_id=? AND n.is_deleted=0",Long.class,articleId,mediaId)!=1) throw missing();
        } else if(!Objects.equals(viewer,photo.getUserId()) && jdbc.queryForObject("SELECT COUNT(*) FROM community_article_image i JOIN cms_news n ON n.id=i.news_id JOIN community_article a ON a.news_id=n.id WHERE i.media_id=? AND n.is_deleted=0 AND n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND a.state='PUBLISHED'",Long.class,mediaId)==0) throw missing();
        byte[] bytes=storage.getObject(photo.getStorageKey());
        if(bytes==null || bytes.length==0 || bytes.length>max || bytes.length!=photo.getSizeBytes()) throw missing();
        return new Image(bytes,photo.getMimeType());
    }
    private static BizException missing() { return new BizException(ErrorCode.NOT_FOUND,"文章配图不存在或不可查看"); }
    public record Image(byte[] bytes,String mimeType) {}
}
