package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import static com.threadfit.glucopilot.community.PublicationDtos.*;

@Service
public class CommunityPublicationService {
    private final JdbcTemplate jdbc;
    private final CmsNewsMapper news;
    private final CommunityService community;
    private final ArticleAccessService access;
    public CommunityPublicationService(JdbcTemplate jdbc,CmsNewsMapper news,CommunityService community) {
        this.jdbc=jdbc;this.news=news;this.community=community;this.access=new ArticleAccessService(jdbc);
    }
    private static final Set<String> STATES = Set.of("AI_PENDING","AI_REVIEWING","PENDING_ADMIN","AI_REJECTED","AI_ERROR","PUBLISHED","REJECTED","HIDDEN");
    private static final String ARTICLE_STATE = "CASE WHEN n.community_managed=1 THEN COALESCE(a.state,'AI_PENDING') ELSE CASE n.publish_status WHEN 1 THEN 'PUBLISHED' WHEN 2 THEN 'HIDDEN' ELSE 'DRAFT' END END";
    private static final String SELECT = "SELECT n.*,a.author_user_id," + ARTICLE_STATE + " article_state,a.revision,a.ai_verdict,a.ai_reason,a.ai_model,a.review_note,COALESCE(a.created_at,n.gmt_create) article_created_at FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id ";

    private BizException missing() { return new BizException(ErrorCode.NOT_FOUND,"文章或举报不存在"); }
    private BizException conflict() { return new BizException(ErrorCode.CONFLICT,"内容状态已变化，请刷新后重试"); }
    private void user(long id, boolean lock) {
        var ids=jdbc.queryForList("SELECT id FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1"+(lock?" FOR UPDATE":""),Long.class,id);
        if(ids.isEmpty()) throw missing();
    }
    private void page(PageQuery q) {
        if(q.getPageNum()<1 || q.getPageSize()<1 || q.getPageSize()>50) throw new BizException(ErrorCode.BAD_REQUEST,"每页1～50条");
    }
    public List<Tag> tags() { return tags(null); }
    public List<Tag> tags(Long viewer) { return jdbc.query("SELECT t.code,t.name FROM community_tag t WHERE t.enabled=1 AND (LEFT(t.code,7)<>'custom_' OR EXISTS(SELECT 1 FROM community_article_tag atg JOIN cms_news n ON n.id=atg.news_id LEFT JOIN community_article a ON a.news_id=n.id WHERE atg.tag_id=t.id AND n.is_deleted=0 AND n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR a.state='PUBLISHED') AND "+ArticleAccessService.readableSql("n",viewer)+")) ORDER BY t.sort_no,t.id",(rs,n)->new Tag(rs.getString(1),rs.getString(2))); }
    public List<Tag> tags(long id) { return jdbc.query("SELECT t.code,t.name FROM community_article_tag a JOIN community_tag t ON t.id=a.tag_id WHERE a.news_id=? AND t.enabled=1 ORDER BY t.sort_no,t.id",(rs,n)->new Tag(rs.getString(1),rs.getString(2)),id); }
    private List<String> codes(Submit request) {
        if(request.title()==null || request.title().trim().isEmpty() || request.title().trim().length()>80 || request.body()==null || (request.body().trim().length()<20 && (request.imageMediaIds()==null || request.imageMediaIds().isEmpty()) && (request.videoMediaIds()==null || request.videoMediaIds().isEmpty())) || request.body().length()>8000
                || request.clientRequestId()==null || !request.clientRequestId().matches("[A-Za-z0-9_-]{8,64}")) throw new BizException(ErrorCode.BAD_REQUEST,"标题、正文或请求标识不合法");
        List<String> predefined=request.tagCodes()==null?List.of():request.tagCodes();
        List<String> custom=request.customTags()==null?List.of():request.customTags();
        if(predefined.size()>3 || custom.size()>3 || predefined.size()+custom.size()<1) throw new BizException(ErrorCode.BAD_REQUEST,"请选择或填写1～3个标签");
        Set<String> allowed=new HashSet<>(jdbc.queryForList("SELECT code FROM community_tag WHERE enabled=1 ORDER BY id FOR UPDATE",String.class));
        if(predefined.stream().anyMatch(code->code==null || !allowed.contains(code))) throw new BizException(ErrorCode.BAD_REQUEST,"标签不存在或已停用");
        Set<String> resolved=new TreeSet<>(predefined);
        for(String raw:custom) resolved.add(customTag(raw));
        if(resolved.size()>3) throw new BizException(ErrorCode.BAD_REQUEST,"最多选择3个标签");
        return List.copyOf(resolved);
    }
    void validateDraftTags(List<String> codes,List<String> custom) {
        Set<String> allowed=new HashSet<>(jdbc.queryForList("SELECT code FROM community_tag WHERE enabled=1 ORDER BY id FOR UPDATE",String.class));
        if(codes.stream().anyMatch(code->!allowed.contains(code)))throw new BizException(ErrorCode.BAD_REQUEST,"标签已删除或停用，请重新选择标签");
        for(String raw:custom) {
            String name=java.text.Normalizer.normalize(Objects.toString(raw,""),java.text.Normalizer.Form.NFKC).trim();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM community_tag WHERE LOWER(name)=LOWER(?) AND enabled=0",Long.class,name)>0)throw new BizException(ErrorCode.BAD_REQUEST,"标签已删除或停用，请重新选择标签");
        }
    }
    private String customTag(String raw) {
        String name=java.text.Normalizer.normalize(Objects.toString(raw,""),java.text.Normalizer.Form.NFKC).replaceFirst("^\\s*#+","").trim().replaceAll("\\s+"," ");
        if(!name.matches("[\\p{L}\\p{N} _-]{1,16}")) throw new BizException(ErrorCode.BAD_REQUEST,"标签限1～16字，可用中文、字母和数字");
        var existing=jdbc.queryForList("SELECT code,enabled FROM community_tag WHERE name=? ORDER BY id LIMIT 1",name);
        if(!existing.isEmpty()) {
            if(((Number)existing.getFirst().get("enabled")).intValue()!=1) throw new BizException(ErrorCode.BAD_REQUEST,"该标签已停用");
            return existing.getFirst().get("code").toString();
        }
        String code="custom_"+org.springframework.util.DigestUtils.md5DigestAsHex(name.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,24);
        jdbc.update("INSERT INTO community_tag(code,name,sort_no) VALUES(?,?,100) ON DUPLICATE KEY UPDATE code=code",code,name);
        if(jdbc.queryForObject("SELECT enabled FROM community_tag WHERE code=?",Integer.class,code)!=1) throw new BizException(ErrorCode.BAD_REQUEST,"该标签已停用");
        return code;
    }
    private void saveTags(long id,List<String> codes) {
        jdbc.update("DELETE FROM community_article_tag WHERE news_id=?",id);
        for(String code:codes) jdbc.update("INSERT INTO community_article_tag(news_id,tag_id) SELECT ?,id FROM community_tag WHERE code=? AND enabled=1",id,code);
    }
    private boolean same(Article article,Submit request,List<String> codes) {
        var scope=request.visibility()==null?null:access.normalize(Long.parseLong(article.author().userId()),request.visibility(),request.viewerIds());
        return (scope==null || (article.visibility().equals(scope.visibility())&&article.viewerIds().equals(scope.viewerIds())))
                && article.title().equals(request.title().trim()) && article.body().equals(request.body().trim())
                && article.tags().stream().map(Tag::code).sorted().toList().equals(codes)
                && article.videos().stream().map(ArticleImage::mediaId).toList().equals(request.videoMediaIds()==null?List.of():request.videoMediaIds())
                && article.images().stream().map(ArticleImage::mediaId).toList().equals(request.imageMediaIds()==null?List.of():request.imageMediaIds());
    }
    List<String> images(long author,Submit request) {
        List<String> ids=request.imageMediaIds()==null?List.of():request.imageMediaIds();
        if(ids.size()>6 || new HashSet<>(ids).size()!=ids.size()) throw new BizException(ErrorCode.BAD_REQUEST,"最多6张配图，不能重复");
        for(String id:ids) {
            try { if(id==null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id)<1) throw new NumberFormatException(); }
            catch(NumberFormatException ex) { throw new BizException(ErrorCode.BAD_REQUEST,"图片标识不合法"); }
            if(jdbc.queryForObject("SELECT COUNT(*) FROM media_file WHERE id=? AND user_id=? AND ops_id IS NULL AND biz_type=7 AND file_status=1 AND is_deleted=0 AND size_bytes>0 AND size_bytes<=5242880 AND mime_type IN ('image/jpeg','image/png','image/webp','image/gif')",Long.class,Long.parseLong(id),author)!=1)
                throw new BizException(ErrorCode.BAD_REQUEST,"配图不存在、不可用或不属于当前账号，请重新上传");
        }
        return List.copyOf(ids);
    }
    List<String> videos(long author,Submit request) {
        List<String> ids=request.videoMediaIds()==null?List.of():request.videoMediaIds();
        if(ids.size()>1) throw new BizException(ErrorCode.BAD_REQUEST,"最多上传1个视频");
        for(String id:ids) {
            try { if(id==null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id)<1) throw new NumberFormatException(); }
            catch(NumberFormatException ex) { throw new BizException(ErrorCode.BAD_REQUEST,"视频标识不合法"); }
            if(jdbc.queryForObject("SELECT COUNT(*) FROM media_file WHERE id=? AND user_id=? AND ops_id IS NULL AND biz_type=8 AND file_status=1 AND is_deleted=0 AND size_bytes>0 AND size_bytes<=20971520 AND mime_type='video/mp4'",Long.class,Long.parseLong(id),author)!=1)
                throw new BizException(ErrorCode.BAD_REQUEST,"视频不可用或不属于当前账号，请重新上传");
        }
        return List.copyOf(ids);
    }
    private void saveMedia(long id,List<String> images,List<String> videos) { var ids=new ArrayList<>(images);ids.addAll(videos);saveImages(id,ids); }
    private void saveImages(long id,List<String> images) {
        jdbc.update("DELETE FROM community_article_image WHERE news_id=?",id);
        for(int i=0;i<images.size();i++) jdbc.update("INSERT INTO community_article_image(news_id,media_id,sort_no) VALUES(?,?,?)",id,Long.parseLong(images.get(i)),i);
    }
    @Transactional
    public Article submit(long author, Submit request) {
        user(author,true); List<String> codes=codes(request);List<String> images=images(author,request);List<String> videos=videos(author,request);
        var scope=access.normalize(author,request.visibility(),request.viewerIds());
        var prior=jdbc.queryForList("SELECT news_id FROM community_article WHERE author_user_id=? AND client_request_id=?",Long.class,author,request.clientRequestId());
        if(!prior.isEmpty()) { var saved=get(author,prior.getFirst(),false);if(!same(saved,request,codes)) throw conflict();return saved; }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_article WHERE author_user_id=? AND created_at>?",Long.class,author,LocalDateTime.now().minusHours(1))>=5)
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,"投稿较频繁，请稍后再试");
        var bloggers=jdbc.queryForList("SELECT id FROM community_blogger WHERE user_id=? ORDER BY id LIMIT 1",Long.class,author);
        if(bloggers.isEmpty()) jdbc.update("INSERT INTO community_blogger(user_id,nickname,bio) VALUES(?,'社区用户','')",author);
        Long blogger=bloggers.isEmpty()?jdbc.queryForObject("SELECT id FROM community_blogger WHERE user_id=? ORDER BY id LIMIT 1",Long.class,author):bloggers.getFirst();
        CmsNews row=new CmsNews(); row.setTitle(request.title().trim());row.setContentBody(request.body().trim());
        row.setSummaryText(summary(request.body()));row.setChannelCode(4);row.setContentType(videos.isEmpty()?1:2);row.setCoverUrl("");
        row.setPublishStatus(0);row.setPublishAt(LocalDateTime.now());row.setLikeCount(0);row.setCollectCount(0);
        row.setBloggerId(blogger);row.setCommunityManaged(1);news.insert(row);
        jdbc.update("INSERT INTO community_article(news_id,author_user_id,client_request_id) VALUES(?,?,?)",row.getId(),author,request.clientRequestId());
        access.saveScope(row.getId(),scope);
        saveTags(row.getId(),codes);saveMedia(row.getId(),images,videos);log("ARTICLE",row.getId(),1,"USER",author,"SUBMIT","");
        return get(author,row.getId(),false);
    }
    public static String summary(String body) { String text=body.trim().replaceAll("\\s+"," ");return text.substring(0,Math.min(140,text.length())); }
    @Transactional
    public Article edit(long author,long id,Submit request) {
        user(author,true);List<String> codes=codes(request);List<String> images=images(author,request);List<String> videos=videos(author,request);var state=lock(id);
        if(!Objects.equals(number(state.get("author_user_id")),author)) throw missing();
        var scope=request.visibility()==null?null:access.normalize(author,request.visibility(),request.viewerIds());
        int revision=((Number)state.get("revision")).intValue();
        if(request.revision()==null) throw new BizException(ErrorCode.BAD_REQUEST,"缺少文章版本");
        if(request.revision()!=revision) {
            Article current=get(author,id,false);
            if(request.revision()==revision-1 && same(current,request,codes)) return current;
            throw conflict();
        }
        jdbc.update("UPDATE cms_news SET title=?,summary_text=?,content_body=?,content_type=?,publish_status=0,gmt_modified=CURRENT_TIMESTAMP WHERE id=? AND is_deleted=0",request.title().trim(),summary(request.body()),request.body().trim(),videos.isEmpty()?1:2,id);
        jdbc.update("UPDATE community_article SET state='AI_PENDING',revision=revision+1,ai_verdict='PENDING',ai_reason='',ai_model='',ai_checked_at=NULL,review_note='',reviewed_by=NULL,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE news_id=?",id);
        if(scope!=null)access.saveScope(id,scope);
        saveTags(id,codes);saveMedia(id,images,videos);log("ARTICLE",id,revision+1,"USER",author,"RESUBMIT","");return get(author,id,false);
    }
    public PageResult<Article> feed(long viewer,String tag,boolean following,String keyword,PageQuery q) {
        user(viewer,false);page(q);List<Object> args=new ArrayList<>();
        String filter=ArticleAccessService.publishedSql("n");
        if(tag!=null && !tag.isBlank()) {filter+=" AND EXISTS(SELECT 1 FROM community_article_tag atg JOIN community_tag t ON t.id=atg.tag_id WHERE atg.news_id=n.id AND "+(tag.equals("other")?"(t.code='other' OR LEFT(t.code,7)='custom_')":"t.code=?")+" AND t.enabled=1)";if(!tag.equals("other"))args.add(tag);}
        if(following) {filter+=" AND EXISTS(SELECT 1 FROM community_follow f WHERE f.follower_id=? AND f.following=1 AND (f.target_key=CONCAT('u',a.author_user_id) OR f.target_key=CONCAT('b',n.blogger_id) OR f.target_key=CONCAT('u',(SELECT b.user_id FROM community_blogger b WHERE b.id=n.blogger_id))))";args.add(viewer);}
        if(keyword!=null && !keyword.isBlank()) {if(keyword.length()>80) throw new BizException(ErrorCode.BAD_REQUEST,"搜索词最多80字");filter+=" AND LOCATE(?,n.title)>0";args.add(keyword.trim());}
        return list(viewer,false,filter,args,q);
    }
    public List<Tag> myTags(long viewer) { return myTags(viewer,false); }
    public List<Tag> myTags(long viewer,boolean includeDisabled) {
        user(viewer,false);
        return jdbc.query("SELECT DISTINCT t.code,t.name,t.enabled FROM community_article_tag atg JOIN community_tag t ON t.id=atg.tag_id JOIN community_article a ON a.news_id=atg.news_id JOIN cms_news n ON n.id=a.news_id WHERE a.author_user_id=? AND n.is_deleted=0 AND (t.enabled=1 OR ?=1) ORDER BY t.name LIMIT 100",(rs,i)->new Tag(rs.getString(1),rs.getString(2),rs.getInt(3)),viewer,includeDisabled?1:0);
    }
    public PageResult<Article> byAuthor(long viewer,String key,PageQuery q) {
        user(viewer,false);page(q);var profile=community.profile(viewer,key);
        String filter=ArticleAccessService.publishedSql("n");
        var args=new ArrayList<Object>();
        if(profile.userId()!=null) { filter+=" AND (a.author_user_id=? OR EXISTS(SELECT 1 FROM community_blogger b WHERE b.id=n.blogger_id AND b.user_id=?))";args.add(Long.parseLong(profile.userId()));args.add(Long.parseLong(profile.userId())); }
        else {filter+=" AND n.blogger_id=?";args.add(Long.parseLong(key.substring(1)));}
        return list(viewer,false,filter,args,q);
    }
    public PageResult<Article> mine(long viewer,PageQuery q) {user(viewer,false);page(q);return list(viewer,true,"n.is_deleted=0 AND a.author_user_id=?",new ArrayList<>(List.of(viewer)),q);}
    public PageResult<Article> queue(String status,PageQuery q) {
        return adminArticles(status,null,null,q);
    }
    public PageResult<Article> adminArticles(String status,String source,String keyword,PageQuery q) {
        page(q);String filter="n.is_deleted=0";List<Object> args=new ArrayList<>();
        if(status!=null && !status.isBlank()) {
            if(!STATES.contains(status) && !"DRAFT".equals(status)) throw new BizException(ErrorCode.BAD_REQUEST,"文章状态不合法");
            filter+=" AND ("+ARTICLE_STATE+")=?";args.add(status);
        }
        if(source!=null && !source.isBlank()) {
            if(!Set.of("OFFICIAL","COMMUNITY").contains(source)) throw new BizException(ErrorCode.BAD_REQUEST,"文章来源不合法");
            filter+=" AND n.community_managed=?";args.add("COMMUNITY".equals(source)?1:0);
        }
        if(keyword!=null && !keyword.isBlank()) {
            String term=keyword.trim();if(term.length()>80) throw new BizException(ErrorCode.BAD_REQUEST,"搜索词最多80字");
            filter+=" AND (LOCATE(?,n.title)>0 OR EXISTS(SELECT 1 FROM user_profile p WHERE p.user_id=a.author_user_id AND p.is_deleted=0 AND LOCATE(?,p.nickname)>0) OR EXISTS(SELECT 1 FROM community_blogger b WHERE b.id=n.blogger_id AND a.author_user_id IS NULL AND LOCATE(?,b.nickname)>0))";
            args.add(term);args.add(term);args.add(term);
        }
        return list(null,true,filter,args,q);
    }
    private PageResult<Article> list(Long viewer,boolean privateData,String filter,List<Object> args,PageQuery q) {
        if(!privateData)filter+=" AND "+ArticleAccessService.readableSql("n",viewer);
        String from=" FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id WHERE "+filter;
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(q.getPageSize());args.add(q.offset());
        var rows=jdbc.query(SELECT+"WHERE "+filter+" ORDER BY n.id DESC LIMIT ? OFFSET ?",(rs,n)->map(rs,viewer,privateData,false),args.toArray());
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    public Article get(Long viewer,long id,boolean admin) {
        if(!admin&&viewer!=null)user(viewer,false);
        var rows=jdbc.query(SELECT+"WHERE n.id=? AND n.is_deleted=0",(rs,n)->map(rs,viewer,true,true),id);
        if(rows.isEmpty()) throw missing();Article item=rows.getFirst();
        if(!admin && !item.mine())access.requireReadable(viewer,id);
        if(admin || item.mine()) return item;
        return jdbc.queryForObject(SELECT+"WHERE n.id=? AND n.is_deleted=0",(rs,n)->map(rs,viewer,false,true),id);
    }
    private boolean isPublic(long id) {return jdbc.queryForObject("SELECT COUNT(*) FROM cms_news n LEFT JOIN community_article a ON a.news_id=n.id WHERE n.id=? AND n.is_deleted=0 AND n.publish_status=1 AND n.publish_at<=CURRENT_TIMESTAMP AND (n.community_managed=0 OR a.state='PUBLISHED')",Long.class,id)==1;}
    private Article map(ResultSet rs,Long viewer,boolean privateData,boolean body) throws SQLException {
        long id=rs.getLong("id");Long author=number(rs.getObject("author_user_id"));
        boolean mine=viewer!=null && Objects.equals(viewer,author);
        var policy=access.policy(id,mine);
        var flags=viewer==null?List.<Map<String,Object>>of():jdbc.queryForList("SELECT is_liked,is_collected FROM news_user_action WHERE news_id=? AND user_id=? AND is_deleted=0",id,viewer);
        boolean liked=!flags.isEmpty() && ((Number)flags.getFirst().get("is_liked")).intValue()==1;
        boolean collected=!flags.isEmpty() && ((Number)flags.getFirst().get("is_collected")).intValue()==1;
        return new Article(Long.toString(id),rs.getString("title"),rs.getString("summary_text"),body?rs.getString("content_body"):"",rs.getString("cover_url"),tags(id),
                articleAuthor(viewer,id,author,privateData),rs.getString("article_state"),rs.getInt("revision"),
                privateData?rs.getString("ai_verdict"):null,privateData?rs.getString("ai_reason"):null,privateData?rs.getString("ai_model"):null,privateData?rs.getString("review_note"):null,
                mine,liked,collected,rs.getLong("like_count"),rs.getLong("collect_count"),community.commentCount(id),time(rs,"article_created_at"),time(rs,"publish_at"),rs.getInt("community_managed")==1?"COMMUNITY":"OFFICIAL",community.articleImages(id),community.articleVideos(id),access.views(id),policy.visibility(),policy.revision(),policy.viewerIds());
    }
    private CommunityDtos.Profile articleAuthor(Long viewer,long id,Long author,boolean privateData) {
        if(author==null) return community.newsAuthor(viewer,id);
        if(!privateData) {
            try { return community.profile(viewer,"u"+author); } catch(BizException ex) { return null; }
        }
        var profiles=jdbc.query("SELECT nickname,avatar_url FROM user_profile WHERE user_id=? AND is_deleted=0",
                (rs,n)->new CommunityDtos.Profile("u"+author,author.toString(),Objects.toString(rs.getString("nickname"),"社区用户"),Objects.toString(rs.getString("avatar_url"),""),"",false,Objects.equals(viewer,author)),author);
        return profiles.isEmpty()?new CommunityDtos.Profile("u"+author,author.toString(),"社区用户","","",false,Objects.equals(viewer,author)):profiles.getFirst();
    }
    private Map<String,Object> lock(long id) {
        var rows=jdbc.queryForList("SELECT a.* FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE a.news_id=? AND n.is_deleted=0 FOR UPDATE",id);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }
    @Transactional
    public Article review(long admin,long id,Review request) {
        var state=lock(id);int revision=((Number)state.get("revision")).intValue();if(revision!=request.revision()) throw conflict();
        String current=(String)state.get("state"),decision=request.decision(),note=Objects.toString(request.note(),"").trim();
        if(note.length()>1000 || (!"APPROVE".equals(decision) && note.isBlank())) throw new BizException(ErrorCode.BAD_REQUEST,"请填写审核意见");
        String next;
        if("APPROVE".equals(decision) && "PENDING_ADMIN".equals(current) && "PASS".equals(state.get("ai_verdict"))) next="PUBLISHED";
        else if("REJECT".equals(decision) && Set.of("PENDING_ADMIN","AI_REJECTED","AI_ERROR").contains(current)) next="REJECTED";
        else if("HIDE".equals(decision) && "PUBLISHED".equals(current)) next="HIDDEN";
        else throw new BizException(ErrorCode.CONFLICT,"仅初审通过的当前版本可终审发布，请刷新状态");
        jdbc.update("UPDATE community_article SET state=?,review_note=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE news_id=?",next,note,admin,id);
        jdbc.update("UPDATE cms_news SET publish_status=?,publish_at=CURRENT_TIMESTAMP,gmt_modified=CURRENT_TIMESTAMP WHERE id=?",next.equals("PUBLISHED")?1:2,id);
        log("ARTICLE",id,revision,"ADMIN",admin,decision,note);return get(null,id,true);
    }
    @Transactional
    public void retry(long actor,long id,boolean admin) {
        var row=lock(id);if(!admin && !Objects.equals(number(row.get("author_user_id")),actor)) throw missing();
        if(!"AI_ERROR".equals(row.get("state"))) throw conflict();
        jdbc.update("UPDATE community_article SET state='AI_PENDING',ai_verdict='PENDING',updated_at=CURRENT_TIMESTAMP WHERE news_id=?",id);
        log("ARTICLE",id,((Number)row.get("revision")).intValue(),admin?"ADMIN":"USER",actor,"RETRY_AI","");
    }
    @Transactional
    public Map<String,Object> report(long viewer,ReportRequest request) {
        user(viewer,true);String type=request.targetType();long id=request.targetId();
        if(!Set.of("ARTICLE","COMMENT","USER","MESSAGE").contains(type) || !Set.of("MISINFORMATION","SPAM","ABUSE","PRIVACY","OTHER").contains(request.reason())
                || request.clientRequestId()==null || !request.clientRequestId().matches("[A-Za-z0-9_-]{8,64}") || Objects.toString(request.details(),"").length()>500) throw new BizException(ErrorCode.BAD_REQUEST,"举报参数不合法");
        var existing=jdbc.queryForList("SELECT * FROM community_report WHERE reporter_id=? AND client_request_id=?",viewer,request.clientRequestId());
        if(!existing.isEmpty()) {
            var old=existing.getFirst();if(!type.equals(old.get("target_type")) || ((Number)old.get("target_id")).longValue()!=id || !request.reason().equals(old.get("reason")) || !Objects.toString(request.details(),"").trim().equals(old.get("details"))) throw conflict();
            return Map.of("id",old.get("id").toString(),"state",old.get("state"));
        }
        if("ARTICLE".equals(type)) access.requireReportable(viewer,id);
        if("COMMENT".equals(type) && jdbc.queryForObject("SELECT COUNT(*) FROM community_comment c JOIN cms_news n ON n.id=c.news_id WHERE c.id=? AND c.is_deleted=0 AND n.is_deleted=0 AND n.publish_status=1",Long.class,id)!=1) throw missing();
        if("USER".equals(type)) {user(id,false);if(id==viewer) throw new BizException(ErrorCode.BAD_REQUEST,"不能举报自己");}
        if("MESSAGE".equals(type) && jdbc.queryForObject("SELECT COUNT(*) FROM community_message WHERE id=? AND (sender_id=? OR recipient_id=?)",Long.class,id,viewer,viewer)!=1) throw missing();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_report WHERE reporter_id=? AND state='OPEN'",Long.class,viewer)>=20) throw new BizException(ErrorCode.TOO_MANY_REQUESTS,"待处理举报较多，请稍后再提交");
        if("COMMENT".equals(type)) {Long newsId=jdbc.queryForObject("SELECT news_id FROM community_comment WHERE id=?",Long.class,id);access.requireReportable(viewer,newsId);}
        String title="ARTICLE".equals(type)?oneText("SELECT title FROM cms_news WHERE id=?",id):"";
        jdbc.update("INSERT INTO community_report(reporter_id,target_type,target_id,reason,details,client_request_id,target_title) VALUES(?,?,?,?,?,?,?)",viewer,type,id,request.reason(),Objects.toString(request.details(),"").trim(),request.clientRequestId(),title);
        if("ARTICLE".equals(type)) access.hideAfterReport(viewer,id);
        var saved=jdbc.queryForMap("SELECT id,state FROM community_report WHERE reporter_id=? AND client_request_id=?",viewer,request.clientRequestId());return Map.of("id",saved.get("id").toString(),"state",saved.get("state"));
    }
    public PageResult<Map<String,Object>> reports(String state,PageQuery q) {
        page(q);if(state!=null && !state.isBlank() && !Set.of("OPEN","DISMISSED","RESOLVED").contains(state)) throw new BizException(ErrorCode.BAD_REQUEST,"举报状态不合法");
        String filter=state==null || state.isBlank()?"1=1":"state=?";List<Object> args=new ArrayList<>();if(filter.equals("state=?")) args.add(state);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM community_report WHERE "+filter,Long.class,args.toArray());args.add(q.getPageSize());args.add(q.offset());
        var rows=jdbc.queryForList("SELECT * FROM community_report WHERE "+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray());
        for(var row:rows) {
            String type=(String)row.get("target_type");long target=((Number)row.get("target_id")).longValue();
            String context=switch(type) {
                case "ARTICLE" -> oneText("SELECT CONCAT(title,'\n',content_body) FROM cms_news WHERE id=?",target);
                case "COMMENT" -> oneText("SELECT content FROM community_comment WHERE id=?",target);
                case "MESSAGE" -> oneText("SELECT content FROM community_message WHERE id=?",target);
                case "USER" -> "社区号 u"+target;
                default -> "";
            };
            row.put("target_content",context);row.put("id",row.get("id").toString());row.put("target_id",Long.toString(target));row.put("reporter_id",row.get("reporter_id").toString());
        }
        return PageResult.of(q.getPageNum(),q.getPageSize(),total,rows);
    }
    private String oneText(String sql,long id) {var rows=jdbc.queryForList(sql,String.class,id);return rows.isEmpty()?"内容已不存在":Objects.toString(rows.getFirst(),"");}
    @Transactional
    public void resolveReport(long admin,long id,Resolution request) {
        var rows=jdbc.queryForList("SELECT * FROM community_report WHERE id=? FOR UPDATE",id);if(rows.isEmpty()) throw missing();var row=rows.getFirst();
        if(!"OPEN".equals(row.get("state"))) throw conflict();
        String action=request.action(),note=Objects.toString(request.note(),"").trim();
        if(note.isBlank() || note.length()>1000 || !Set.of("DISMISS","HIDE_ARTICLE","HIDE_COMMENT","RESOLVE").contains(action)) throw new BizException(ErrorCode.BAD_REQUEST,"请选择处理方式并填写说明");
        long target=((Number)row.get("target_id")).longValue();
        if(action.equals("HIDE_ARTICLE")) {
            if(!"ARTICLE".equals(row.get("target_type"))) throw new BizException(ErrorCode.BAD_REQUEST,"举报目标不是文章");
            // 与点赞/收藏共用文章行锁，避免陈旧实体把下架状态写回。
            news.selectForAction(target);
            jdbc.update("UPDATE cms_news SET publish_status=2,gmt_modified=CURRENT_TIMESTAMP WHERE id=? AND is_deleted=0",target);
            jdbc.update("UPDATE community_article SET state='HIDDEN',updated_at=CURRENT_TIMESTAMP WHERE news_id=?",target);
        }
        if(action.equals("HIDE_COMMENT")) {
            if(!"COMMENT".equals(row.get("target_type"))) throw new BizException(ErrorCode.BAD_REQUEST,"举报目标不是评论");
            jdbc.update("UPDATE community_comment SET is_deleted=1 WHERE id=?",target);
        }
        jdbc.update("UPDATE community_report SET state=?,resolution=?,resolution_note=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP WHERE id=?",action.equals("DISMISS")?"DISMISSED":"RESOLVED",action,note,admin,id);
        log("REPORT",id,null,"ADMIN",admin,action,note);
    }
    public void log(String type,long id,Integer revision,String actor,Long actorId,String decision,String note) {
        jdbc.update("INSERT INTO community_moderation_log(target_type,target_id,revision,actor_type,actor_id,decision,note) VALUES(?,?,?,?,?,?,?)",type,id,revision,actor,actorId,decision,note);
    }
    private static Long number(Object value) {return value==null?null:((Number)value).longValue();}
    private static LocalDateTime time(ResultSet rs,String field) throws SQLException {var timestamp=rs.getTimestamp(field);return timestamp==null?null:timestamp.toLocalDateTime();}
}
