package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class CommunityDraftService {
    private final JdbcTemplate jdbc;
    private final JsonUtils json;
    private final CommunityPublicationService publication;
    public CommunityDraftService(JdbcTemplate jdbc,JsonUtils json,CommunityPublicationService publication) {
        this.jdbc=jdbc;this.json=json;this.publication=publication;
    }
    public record Payload(String title,String body,List<String> tagCodes,List<String> customTags,List<String> imageMediaIds,
                          String clientRequestId,Integer revision,Long sourceArticleId,Integer sourceRevision,List<String> videoMediaIds,String visibility,List<String> viewerIds) {
        public Payload(String title,String body,List<String> codes,List<String> custom,List<String> images,String key,Integer revision,Long source,Integer sourceRevision,List<String> videos) {this(title,body,codes,custom,images,key,revision,source,sourceRevision,videos,null,List.of());}
        public Payload(String title,String body,List<String> codes,List<String> custom,List<String> images,String key,Integer revision,Long source,Integer sourceRevision) {this(title,body,codes,custom,images,key,revision,source,sourceRevision,List.of());}
    }
    public record Publish(int revision,String clientRequestId) {}
    public record Draft(String id,String title,String body,List<String> tagCodes,List<String> customTags,List<PublicationDtos.ArticleImage> images,
                        int revision,String sourceArticleId,Integer sourceRevision,LocalDateTime updatedAt,List<PublicationDtos.ArticleImage> videos,String visibility,List<String> viewerIds) {}
    private BizException missing(){return new BizException(ErrorCode.NOT_FOUND,"草稿不存在");}
    private BizException conflict(){return new BizException(ErrorCode.CONFLICT,"草稿已变化，请刷新后重试");}
    private void user(long user,boolean lock){if(jdbc.queryForList("SELECT id FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1"+(lock?" FOR UPDATE":""),Long.class,user).isEmpty())throw missing();}
    private List<String> strings(Object raw){return List.of(json.fromJson(raw.toString(),String[].class));}
    private LocalDateTime draftTime(Object value) {
        // Connector/J returns DATETIME as LocalDateTime; H2 and older drivers use Timestamp.
        if(value instanceof LocalDateTime time)return time;
        if(value instanceof java.sql.Timestamp time)return time.toLocalDateTime();
        throw new IllegalStateException("Unsupported draft timestamp type");
    }
    private Draft map(Map<String,Object> r){return new Draft(r.get("id").toString(),r.get("title").toString(),r.get("body").toString(),strings(r.get("tag_codes")),strings(r.get("custom_tags")),strings(r.get("image_ids")).stream().map(PublicationDtos.ArticleImage::new).toList(),((Number)r.get("revision")).intValue(),r.get("source_article_id")==null?null:r.get("source_article_id").toString(),r.get("source_revision")==null?null:((Number)r.get("source_revision")).intValue(),draftTime(r.get("updated_at")),strings(r.getOrDefault("video_ids","[]")).stream().map(PublicationDtos.ArticleImage::new).toList(),Objects.toString(r.get("visibility"),"PUBLIC"),r.get("viewer_ids")==null?List.of():strings(r.get("viewer_ids")));}
    private Map<String,Object> row(long user,long id,boolean lock,boolean closed){var rows=jdbc.queryForList("SELECT * FROM community_article_draft WHERE id=? AND user_id=?"+(closed?"":" AND is_deleted=0")+(lock?" FOR UPDATE":""),id,user);if(rows.isEmpty())throw missing();return rows.getFirst();}
    public Draft get(long user,long id){user(user,false);return map(row(user,id,false,false));}
    public PageResult<Draft> list(long user,PageQuery q){user(user,false);if(q.getPageNum()<1||q.getPageSize()<1||q.getPageSize()>50)throw new BizException(ErrorCode.BAD_REQUEST,"每页1～50条");long total=jdbc.queryForObject("SELECT COUNT(*) FROM community_article_draft WHERE user_id=? AND is_deleted=0",Long.class,user);return PageResult.of(q.getPageNum(),q.getPageSize(),total,jdbc.queryForList("SELECT * FROM community_article_draft WHERE user_id=? AND is_deleted=0 ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",user,q.getPageSize(),q.offset()).stream().map(this::map).toList());}
    private Payload normalize(long user,Payload p){
        if(p==null || p.clientRequestId()==null || !p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))throw new BizException(ErrorCode.BAD_REQUEST,"请求标识不合法");
        String title=Objects.toString(p.title(),"").trim(),body=Objects.toString(p.body(),"");
        var codes=p.tagCodes()==null?List.<String>of():p.tagCodes();var custom=p.customTags()==null?List.<String>of():p.customTags();
        if(title.length()>80||body.length()>8000||codes.size()+custom.size()>3||codes.stream().anyMatch(x->x==null||!x.matches("[A-Za-z0-9_-]{1,32}"))||custom.stream().anyMatch(x->x==null||!x.matches("[\\p{L}\\p{N} _-]{1,16}")))throw new BizException(ErrorCode.BAD_REQUEST,"草稿标题、正文或标签过长/不合法");
        var images=publication.images(user,new PublicationDtos.Submit(title,body,codes,custom,p.clientRequestId(),p.sourceRevision(),p.imageMediaIds()));
        var videos=publication.videos(user,new PublicationDtos.Submit(title,body,codes,custom,p.clientRequestId(),p.sourceRevision(),p.imageMediaIds(),p.videoMediaIds()));
        if(p.sourceArticleId()!=null){var source=publication.get(user,p.sourceArticleId(),false);if(!source.mine()||p.sourceRevision()==null||p.sourceRevision()<1)throw missing();}
        var sourceScope=p.visibility()==null&&p.sourceArticleId()!=null?publication.get(user,p.sourceArticleId(),false):null;
        var scope=new ArticleAccessService(jdbc).normalize(user,sourceScope==null?p.visibility():sourceScope.visibility(),sourceScope==null?p.viewerIds():sourceScope.viewerIds());
        return new Payload(title,body,List.copyOf(codes),List.copyOf(custom),images,p.clientRequestId(),p.revision(),p.sourceArticleId(),p.sourceRevision(),videos,scope.visibility(),scope.viewerIds());
    }
    private boolean same(Draft d,Payload p){return d.visibility().equals(p.visibility())&&d.viewerIds().equals(p.viewerIds())&&d.videos().stream().map(PublicationDtos.ArticleImage::mediaId).toList().equals(p.videoMediaIds())&&d.title().equals(p.title())&&d.body().equals(p.body())&&d.tagCodes().equals(p.tagCodes())&&d.customTags().equals(p.customTags())&&d.images().stream().map(PublicationDtos.ArticleImage::mediaId).toList().equals(p.imageMediaIds())&&Objects.equals(d.sourceArticleId(),p.sourceArticleId()==null?null:p.sourceArticleId().toString())&&Objects.equals(d.sourceRevision(),p.sourceRevision());}
    @Transactional public Draft create(long user,Payload raw){user(user,true);var p=normalize(user,raw);var prior=jdbc.queryForList("SELECT * FROM community_article_draft WHERE user_id=? AND client_request_id=?",user,p.clientRequestId());if(!prior.isEmpty()){var saved=map(prior.getFirst());if(((Number)prior.getFirst().get("is_deleted")).intValue()!=0||!same(saved,p))throw conflict();return saved;}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article_draft WHERE user_id=? AND is_deleted=0",Long.class,user)>=100)throw new BizException(ErrorCode.TOO_MANY_REQUESTS,"草稿箱已满，请清理后再保存");
        jdbc.update("INSERT INTO community_article_draft(user_id,title,body,tag_codes,custom_tags,image_ids,source_article_id,source_revision,client_request_id,last_request_id,video_ids,visibility,viewer_ids) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",user,p.title(),p.body(),json.toJson(p.tagCodes()),json.toJson(p.customTags()),json.toJson(p.imageMediaIds()),p.sourceArticleId(),p.sourceRevision(),p.clientRequestId(),p.clientRequestId(),json.toJson(p.videoMediaIds()),p.visibility(),json.toJson(p.viewerIds()));return map(jdbc.queryForMap("SELECT * FROM community_article_draft WHERE user_id=? AND client_request_id=?",user,p.clientRequestId()));}
    @Transactional public Draft edit(long user,long id,Payload raw){user(user,true);var p=normalize(user,raw);var r=row(user,id,true,false);var current=map(r);if(p.revision()==null)throw conflict();if(p.revision()!=current.revision()){if(p.revision()==current.revision()-1&&p.clientRequestId().equals(r.get("last_request_id"))&&same(current,p))return current;throw conflict();}
        if(!Objects.equals(current.sourceArticleId(),p.sourceArticleId()==null?null:p.sourceArticleId().toString())||!Objects.equals(current.sourceRevision(),p.sourceRevision()))throw conflict();
        jdbc.update("UPDATE community_article_draft SET title=?,body=?,tag_codes=?,custom_tags=?,image_ids=?,video_ids=?,visibility=?,viewer_ids=?,revision=revision+1,last_request_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",p.title(),p.body(),json.toJson(p.tagCodes()),json.toJson(p.customTags()),json.toJson(p.imageMediaIds()),json.toJson(p.videoMediaIds()),p.visibility(),json.toJson(p.viewerIds()),p.clientRequestId(),id);return get(user,id);}
    @Transactional public void delete(long user,long id,int revision){user(user,true);var r=row(user,id,true,true);if(((Number)r.get("is_deleted")).intValue()==1)return;if(((Number)r.get("revision")).intValue()!=revision)throw conflict();jdbc.update("UPDATE community_article_draft SET is_deleted=1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);}
    @Transactional public PublicationDtos.Article publish(long user,long id,Publish p){user(user,true);var r=row(user,id,true,true);var d=map(r);if(p==null||p.clientRequestId()==null||!p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}")||p.revision()!=d.revision())throw conflict();if(r.get("published_news_id")!=null){if(!p.clientRequestId().equals(r.get("publish_request_id")))throw conflict();return publication.get(user,((Number)r.get("published_news_id")).longValue(),false);}if(((Number)r.get("is_deleted")).intValue()!=0)throw missing();
        var request=new PublicationDtos.Submit(d.title(),d.body(),d.tagCodes(),d.customTags(),p.clientRequestId(),d.sourceRevision(),d.images().stream().map(PublicationDtos.ArticleImage::mediaId).toList(),d.videos().stream().map(PublicationDtos.ArticleImage::mediaId).toList(),d.visibility(),d.viewerIds());
        var saved=d.sourceArticleId()==null?publication.submit(user,request):publication.edit(user,Long.parseLong(d.sourceArticleId()),request);
        jdbc.update("UPDATE community_article_draft SET published_news_id=?,publish_request_id=?,is_deleted=1,updated_at=CURRENT_TIMESTAMP WHERE id=?",Long.parseLong(saved.id()),p.clientRequestId(),id);return saved;}
}
