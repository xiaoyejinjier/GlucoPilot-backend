package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.util.*;

@Service
public class CommunityTagService {
    private final JdbcTemplate jdbc;
    public CommunityTagService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Change(@NotBlank @Size(max=32) String name,@Min(0) @Max(9999) int sortNo,@Min(0) @Max(1) int enabled) {}
    public record Row(String id,String code,String name,String type,int sortNo,int enabled,long articleCount) {}
    private static final String SELECT="SELECT t.*,(SELECT COUNT(*) FROM community_article_tag a JOIN cms_news n ON n.id=a.news_id WHERE a.tag_id=t.id AND n.is_deleted=0) article_count FROM community_tag t ";
    public PageResult<Row> list(String keyword,String type,Integer enabled,PageQuery page) {
        if(page.getPageNum()<1 || page.getPageSize()<1 || page.getPageSize()>50) throw bad("每页1～50条");
        String name=Objects.toString(keyword,"").trim();
        if(name.length()>32 || (type!=null&&!type.isBlank()&&!Set.of("OFFICIAL","USER").contains(type)) || (enabled!=null&&enabled!=0&&enabled!=1))throw bad("筛选条件不合法");
        var args=new ArrayList<Object>();String filter=" WHERE 1=1";
        if(!name.isEmpty()){filter+=" AND LOCATE(LOWER(?),LOWER(t.name))>0";args.add(name);}
        if(type!=null&&!type.isBlank())filter+=" AND LEFT(t.code,7)"+(type.equals("USER")?"=":"<>")+"'custom_'";
        if(enabled!=null){filter+=" AND t.enabled=?";args.add(enabled);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM community_tag t"+filter,Long.class,args.toArray());
        args.add(page.getPageSize());args.add(page.offset());
        return PageResult.of(page.getPageNum(),page.getPageSize(),total,jdbc.query(SELECT+filter+" ORDER BY t.sort_no,t.id LIMIT ? OFFSET ?",(rs,i)->new Row(rs.getString("id"),rs.getString("code"),rs.getString("name"),rs.getString("code").startsWith("custom_")?"USER":"OFFICIAL",rs.getInt("sort_no"),rs.getInt("enabled"),rs.getLong("article_count")),args.toArray()));
    }
    private Row get(String code){return jdbc.query(SELECT+" WHERE t.code=?",(rs,i)->new Row(rs.getString("id"),rs.getString("code"),rs.getString("name"),rs.getString("code").startsWith("custom_")?"USER":"OFFICIAL",rs.getInt("sort_no"),rs.getInt("enabled"),rs.getLong("article_count")),code).stream().findFirst().orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"标签不存在"));}
    private String name(Change request){
        if(request==null||request.sortNo()<0||request.sortNo()>9999||(request.enabled()!=0&&request.enabled()!=1))throw bad("标签设置不合法");
        String name=Normalizer.normalize(Objects.toString(request.name(),""),Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ");
        if(!name.matches("[\\p{L}\\p{N} _-]{1,16}"))throw bad("标签限1～16字，可用中文、字母和数字");return name;
    }
    private void lockTags(){jdbc.queryForList("SELECT id FROM community_tag ORDER BY id FOR UPDATE",Long.class);}
    @Transactional public Row create(Change request){
        String name=name(request);lockTags();
        var existing=jdbc.queryForList("SELECT code,name,sort_no,enabled FROM community_tag WHERE LOWER(name)=LOWER(?) ORDER BY id LIMIT 1",name);
        if(!existing.isEmpty()){
            var row=existing.getFirst();
            if(!row.get("code").toString().startsWith("custom_")&&Objects.equals(row.get("name"),name)&&((Number)row.get("sort_no")).intValue()==request.sortNo()&&((Number)row.get("enabled")).intValue()==request.enabled())return get(row.get("code").toString());
            throw new BizException(ErrorCode.CONFLICT,"同名标签已存在，请编辑或恢复原标签");
        }
        String code="topic_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        jdbc.update("INSERT INTO community_tag(code,name,sort_no,enabled) VALUES(?,?,?,?)",code,name,request.sortNo(),request.enabled());return get(code);
    }
    @Transactional public Row update(String code,Change request){
        String name=name(request);lockTags();var prior=get(code);
        if(prior.type().equals("USER")&&!name.equals(prior.name()))throw bad("用户标签不能改名，可排序、删除或恢复");
        if(code.equals("other")&&(!name.equals("其他")||request.enabled()!=1))throw bad("其他分类必须保留，不能改名或删除");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM community_tag WHERE LOWER(name)=LOWER(?) AND code<>?",Long.class,name,code)>0)throw new BizException(ErrorCode.CONFLICT,"同名标签已存在");
        jdbc.update("UPDATE community_tag SET name=?,sort_no=?,enabled=? WHERE code=?",name,request.sortNo(),request.enabled(),prior.code());return get(code);
    }
    @Transactional public void remove(String code){
        lockTags();get(code);if(code.equals("other"))throw bad("其他分类必须保留，不能删除");
        jdbc.update("UPDATE community_tag SET enabled=0 WHERE code=?",code);
    }
    private static BizException bad(String message){return new BizException(ErrorCode.BAD_REQUEST,message);}
}
