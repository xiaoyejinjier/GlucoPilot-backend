package com.threadfit.glucopilot.localization;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.ArticleAccessService;
import com.threadfit.glucopilot.community.CommunityService;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static com.threadfit.glucopilot.localization.LocalizationDtos.*;

@Service
public class LocalizationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(LocalizationService.class);
    // Corrected numeric-heading handling starts fresh jobs instead of reusing older failures.
    private static final String CACHE_VERSION="en-v3:";
    private static final Set<String> KINDS=Set.of("ARTICLE_PREVIEW","ARTICLE_BODY","PROFILE_NAME");
    private final JdbcTemplate jdbc;
    private final CommunityService community;
    private final EnglishTranslator translator;
    private final ObjectMapper json;
    private volatile boolean closing;
    private final ConcurrentMap<String,Job> activeJobs=new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"localization-lease");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{var t=new Thread(r,"english-localization");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public LocalizationService(JdbcTemplate jdbc,CommunityService community,EnglishTranslator translator,ObjectMapper json) {
        this.jdbc=jdbc;this.community=community;this.translator=translator;this.json=json;
        heartbeat.scheduleWithFixedDelay(this::renewLeases,20,20,TimeUnit.SECONDS);
    }
    @PreDestroy public void close(){
        closing=true;heartbeat.shutdownNow();
        for(var job:activeJobs.values())try{jdbc.update("UPDATE content_localization SET lease_token=NULL,lease_until=NULL WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en' AND lease_token=?",job.item().kind(),job.item().id(),job.hash(),job.lease());}catch(RuntimeException ignored){}
        workers.shutdownNow();
        try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
    }
    private void renewLeases(){for(var job:activeJobs.values())try{jdbc.update("UPDATE content_localization SET lease_until=? WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en' AND lease_token=?",LocalDateTime.now().plusSeconds(90),job.item().kind(),job.item().id(),job.hash(),job.lease());}catch(RuntimeException ignored){}}
    public List<Result> localize(long viewer,Request request) {
        if(request==null||request.items()==null||request.items().isEmpty()||request.items().size()>20)throw new BizException(ErrorCode.BAD_REQUEST,"每批最多20项");
        for(Item item:request.items())validate(item);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1",Long.class,viewer)!=1)throw new BizException(ErrorCode.FORBIDDEN);
        var result=new ArrayList<Result>();var unique=new HashSet<Item>();var jobs=new ArrayList<Job>();
        for(Item item:request.items())if(unique.add(item))result.add(item(viewer,item,Boolean.TRUE.equals(request.retry()),jobs));
        // Batch short names/previews. Long article bodies retain independent error boundaries.
        for(String kind:KINDS) {
            var matching=jobs.stream().filter(job->job.item().kind().equals(kind)).toList();
            int batchSize=kind.equals("ARTICLE_BODY")?1:8;
            for(int start=0;start<matching.size();start+=batchSize) {
                var batch=List.copyOf(matching.subList(start,Math.min(start+batchSize,matching.size())));
                batch.forEach(job->activeJobs.put(job.lease(),job));
                try{workers.execute(()->translate(batch));}catch(RejectedExecutionException ex){batch.forEach(job->{activeJobs.remove(job.lease());fail(job.item(),job.hash(),job.lease());});}
            }
        }
        return result;
    }
    private record Job(Item item,String hash,String lease,Map<String,String> source) {}
    private Result item(long viewer,Item item,boolean retry,List<Job> jobs) {
        if(item==null||!KINDS.contains(item.kind())||item.id()==null)throw new BizException(ErrorCode.BAD_REQUEST,"无效的翻译对象");
        Map<String,String> source;
        try{source=source(viewer,item);}catch(BizException ex){if(ex.getErrorCode()==ErrorCode.NOT_FOUND||ex.getErrorCode()==ErrorCode.BAD_REQUEST)return new Result(item.kind(),item.id(),"UNAVAILABLE","",Map.of(),Map.of());throw ex;}
        String hash=hash(source);
        // English names and generated account nicknames don't require a remote AI request.
        var quick=direct(item,source);
        if(quick!=null)return new Result(item.kind(),item.id(),"READY",hash,source,quick);
        var cached=cache(item,hash);
        if(cached==null) {
            try{jdbc.update("INSERT INTO content_localization(kind,resource_id,source_hash,locale,status) VALUES(?,?,?,'en','PENDING')",item.kind(),item.id(),hash);}catch(org.springframework.dao.DuplicateKeyException ignored){}
            cached=cache(item,hash);
        }
        if("READY".equals(cached.status()))return new Result(item.kind(),item.id(),"READY",hash,source,read(cached.json()));
        String lease=UUID.randomUUID().toString();LocalDateTime now=LocalDateTime.now();
        int claimed=jdbc.update("UPDATE content_localization SET status='PENDING',lease_token=?,lease_until=?,updated_at=? WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en' AND ((status='PENDING' AND (lease_until IS NULL OR lease_until<?)) OR (status='FAILED' AND ? AND (retry_after IS NULL OR retry_after<=?)))",
                lease,now.plusSeconds(90),now,item.kind(),item.id(),hash,now,retry,now);
        if(claimed==1) {
            jobs.add(new Job(item,hash,lease,source));
            return new Result(item.kind(),item.id(),"PENDING",hash,source,Map.of());
        }
        return new Result(item.kind(),item.id(),cached.status(),hash,source,Map.of());
    }
    private static void validate(Item item) {
        if(item==null||!KINDS.contains(item.kind())||item.id()==null||!item.id().matches(item.kind().equals("PROFILE_NAME")?"[ub][1-9][0-9]{0,18}":"[1-9][0-9]{0,18}"))throw new BizException(ErrorCode.BAD_REQUEST,"无效的翻译对象");
        try{Long.parseLong(item.kind().equals("PROFILE_NAME")?item.id().substring(1):item.id());}catch(NumberFormatException ex){throw new BizException(ErrorCode.BAD_REQUEST,"无效的翻译标识");}
    }
    private Map<String,String> source(long viewer,Item item) {
        if(item.kind().equals("PROFILE_NAME")) {
            if(!item.id().matches("[ub][1-9][0-9]{0,18}"))throw new BizException(ErrorCode.BAD_REQUEST,"无效的用户标识");
            return Map.of("nickname",Objects.toString(community.profile(viewer,item.id()).nickname(),""));
        }
        long id;
        try{if(!item.id().matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();id=Long.parseLong(item.id());}catch(NumberFormatException ex){throw new BizException(ErrorCode.BAD_REQUEST,"无效的文章标识");}
        community.requireReadable(viewer,id);
        // Read and authorize in the same SELECT too, so a concurrent privacy change cannot slip through.
        String fields=item.kind().equals("ARTICLE_BODY")?"n.content_body":"n.title,n.summary_text";
        var rows=jdbc.query("SELECT "+fields+" FROM cms_news n WHERE n.id=? AND "+ArticleAccessService.publishedSql("n")+" AND "+ArticleAccessService.readableSql("n",viewer),(rs,index)->{
            var map=new LinkedHashMap<String,String>();
            if(item.kind().equals("ARTICLE_BODY"))map.put("body",Objects.toString(rs.getString("content_body"),""));
            else{map.put("title",Objects.toString(rs.getString("title"),""));map.put("summary",Objects.toString(rs.getString("summary_text"),""));}
            return map;
        },id);
        if(rows.isEmpty())throw new BizException(ErrorCode.NOT_FOUND,"文章不可查看");
        var result=rows.getFirst();if(result.values().stream().mapToInt(String::length).sum()>120000)throw new BizException(ErrorCode.BAD_REQUEST,"文章过长，暂不支持英文阅读");
        return result;
    }
    private Map<String,String> direct(Item item,Map<String,String> source) {
        if(source.values().stream().noneMatch(value->ModelEnglishTranslator.HAN.matcher(value).find()))return source;
        if(item.kind().equals("PROFILE_NAME")) {
            String value=source.get("nickname");
            if(value.matches("用户[_-][A-Za-z0-9_-]+"))return Map.of("nickname",value.replaceFirst("^用户","User"));
        }
        return null;
    }
    private record Cached(String status,String json) {}
    private Cached cache(Item item,String hash) {
        var rows=jdbc.query("SELECT status,translated_json FROM content_localization WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en'",(rs,i)->new Cached(rs.getString(1),rs.getString(2)),item.kind(),item.id(),hash);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,String> read(String data) {
        var result=new LinkedHashMap<String,String>();for(var entry:json.readTree(data).properties())result.put(entry.getKey(),entry.getValue().asString(""));return result;
    }
    private String hash(Map<String,String> source) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((CACHE_VERSION+json.writeValueAsString(new TreeMap<>(source))).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private void translate(List<Job> jobs) {
        try{
            if(closing||Thread.currentThread().isInterrupted())return;
            var input=new LinkedHashMap<String,String>();
            if(jobs.size()==1)input.putAll(jobs.getFirst().source());
            else for(int i=0;i<jobs.size();i++)for(var entry:jobs.get(i).source().entrySet())input.put("j"+i+"_"+entry.getKey(),entry.getValue());
            Map<String,String> output;
            try{
                output=translator.translate(jobs.getFirst().item().kind(),input);
                if(!output.keySet().equals(input.keySet()))throw new IllegalArgumentException("Missing translated fields");
            }catch(RuntimeException ex){
                // A malformed article/name must not fail every other item sharing its short-text batch.
                // Split once only: single-job failures go through the normal FAILED/retry policy.
                if(jobs.size()==1||closing||Thread.currentThread().isInterrupted()||cancelled(ex))throw ex;
                LOG.warn("英文批量翻译失败，逐项隔离 kind={} items={} failureType={}",jobs.getFirst().item().kind(),jobs.size(),ex.getClass().getSimpleName());
                for(var job:jobs){if(closing||Thread.currentThread().isInterrupted())return;translate(List.of(job));}
                return;
            }
            for(int i=0;i<jobs.size();i++) {
                var job=jobs.get(i);var translated=new LinkedHashMap<String,String>();
                for(String field:job.source().keySet())translated.put(field,output.get(jobs.size()==1?field:"j"+i+"_"+field));
                jdbc.update("UPDATE content_localization SET status='READY',translated_json=?,lease_token=NULL,lease_until=NULL,retry_after=NULL,updated_at=? WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en' AND lease_token=?",
                        json.writeValueAsString(translated),LocalDateTime.now(),job.item().kind(),job.item().id(),job.hash(),job.lease());
            }
        }catch(Exception ex){if(!closing){
            LOG.warn("英文翻译未完成 kind={} items={} failureType={}",jobs.getFirst().item().kind(),jobs.size(),ex.getClass().getSimpleName());
            jobs.forEach(job->fail(job.item(),job.hash(),job.lease()));
        }}
        finally{jobs.forEach(job->activeJobs.remove(job.lease()));}
    }
    private static boolean cancelled(Throwable error){
        for(int depth=0;error!=null&&depth<16;depth++,error=error.getCause())if(error instanceof InterruptedException||error instanceof CancellationException)return true;
        return false;
    }
    private void fail(Item item,String hash,String lease) {
        try{jdbc.update("UPDATE content_localization SET status='FAILED',lease_token=NULL,lease_until=NULL,retry_after=?,updated_at=? WHERE kind=? AND resource_id=? AND source_hash=? AND locale='en' AND lease_token=?",LocalDateTime.now().plusSeconds(15),LocalDateTime.now(),item.kind(),item.id(),hash,lease);}catch(RuntimeException ignored){/* A later request can recover an expired lease. */}
    }
}
