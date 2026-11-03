import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Explicit local operator import; never called at startup or from user APIs. */
public class UserArticleBatchImport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TAGS=Set.of("diet","exercise","daily","type1","type2","medication");
    private static final List<String> BACKUP_TABLES=List.of("cms_news","community_article","community_article_tag","community_article_access","community_moderation_log","community_blogger");
    record Entry(String key,String title,String body,List<String> tags) {}

    static List<Entry> entries(Path file)throws Exception {
        var result=new ArrayList<Entry>();var keys=new HashSet<String>();var coverage=new HashSet<String>();
        for(var row:JSON.readTree(Files.readString(file,StandardCharsets.UTF_8))){
            String key=row.path("seedKey").asText(),title=row.path("title").asText(),body=row.path("body").asText();
            var source=java.net.URI.create(row.path("sourceUrl").asText());
            if(!key.matches("[a-z0-9-]{8,40}")||!keys.add(key)||title.isBlank()||title.length()>80||body.length()<100||body.length()>7000
                    ||!"https".equals(source.getScheme())||!Set.of("www.cdc.gov","www.niddk.nih.gov","diabetes.org","medlineplus.gov").contains(source.getHost()))throw new IllegalArgumentException("Invalid article input");
            var tags=new ArrayList<String>();for(var tag:row.path("tagCodes"))tags.add(tag.asText());
            if(tags.isEmpty()||tags.size()>3||new HashSet<>(tags).size()!=tags.size()||!TAGS.containsAll(tags))throw new IllegalArgumentException("Invalid article tags");
            coverage.addAll(tags);
            body+="\n\n资料说明：依据公开健康教育资料原创整理，供一般科普交流，不替代个体诊疗或用药指导。\n参考来源："+row.path("sourceTitle").asText()+"\n"+source+"\n资料核对日期：2026-10-28";
            result.add(new Entry(key,title,body,List.copyOf(tags)));
        }
        if(result.size()!=18||!coverage.equals(TAGS))throw new IllegalArgumentException("Expected 18 articles covering six categories");
        return result;
    }

    static Path backup(Connection c,Path workspace,long owner)throws Exception{
        Path folder=workspace.resolve(".tmp/user-article-batch-backup-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))).normalize();
        if(!folder.startsWith(workspace.resolve(".tmp")))throw new IllegalStateException("Invalid backup location");Files.createDirectories(folder);
        for(var table:BACKUP_TABLES){
            var rows=new ArrayList<Map<String,Object>>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM `"+table+"`")){
                var meta=r.getMetaData();while(r.next()){
                    var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++){var value=r.getObject(i);row.put(meta.getColumnLabel(i),value==null||value instanceof Number||value instanceof Boolean?value:value.toString());}rows.add(row);
                }
            }
            Files.writeString(folder.resolve(table+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rows),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(folder.resolve("manifest.json"),JSON.writeValueAsString(Map.of("ownerId",owner,"articles",18,"publication","AI_PENDING then administrator review","createdAt",LocalDateTime.now().toString())),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        return folder;
    }

    static long generated(PreparedStatement statement)throws SQLException{
        statement.executeUpdate();try(var keys=statement.getGeneratedKeys()){if(!keys.next())throw new IllegalStateException("Missing generated ID");return keys.getLong(1);}
    }

    public static void main(String[] args)throws Exception{
        if(args.length<6||!Set.of("audit","apply").contains(args[0]))throw new IllegalArgumentException("Use audit|apply workspace expected-owner-id phone fixture-json runtime-client-ports");
        Path workspace=Path.of(args[1]).toAbsolutePath().normalize();long owner=Long.parseLong(args[2]);
        var articles=entries(Path.of(args[4]));var env=new Properties();
        try(var reader=Files.newBufferedReader(workspace.resolve(".env"),StandardCharsets.UTF_8)){env.load(reader);}
        String schema=env.getProperty("DB_NAME"),host=env.getProperty("DB_HOST");
        if(!Set.of("localhost","127.0.0.1").contains(host)||schema==null||!schema.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Only confirmed local development database is supported");
        String url="jdbc:mysql://127.0.0.1:"+env.getProperty("DB_PORT","3306")+"/"+schema+"?allowPublicKeyRetrieval=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        try(var c=DriverManager.getConnection(url,env.getProperty("DB_USERNAME"),env.getProperty("DB_PASSWORD"))){
            var ports=Set.of(args[5].split(","));var runtimeSchemas=new HashSet<String>();
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT HOST,DB FROM information_schema.PROCESSLIST WHERE DB IS NOT NULL")){
                while(rows.next()){String client=rows.getString(1);if(ports.contains(client.substring(client.lastIndexOf(':')+1)))runtimeSchemas.add(rows.getString(2));}
            }
            if(!runtimeSchemas.equals(Set.of(schema)))throw new IllegalStateException("Running backend database mismatch; no writes performed");
            try(var s=c.prepareStatement("SELECT id FROM user_account WHERE id=? AND phone=? AND is_deleted=0 AND account_status=1")){
                s.setLong(1,owner);s.setString(2,args[3]);try(var rows=s.executeQuery()){if(!rows.next())throw new IllegalStateException("Owner identity mismatch; no writes performed");}
            }
            var tagIds=new HashMap<String,Long>();try(var s=c.createStatement();var rows=s.executeQuery("SELECT id,code FROM community_tag WHERE enabled=1")){while(rows.next())tagIds.put(rows.getString(2),rows.getLong(1));}
            if(!tagIds.keySet().containsAll(TAGS))throw new IllegalStateException("Required enabled tags are missing");
            System.out.println("AUDIT_OK ownerId="+owner+" articles="+articles.size()+" enabledCategories=6");
            if(args[0].equals("audit"))return;
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT GET_LOCK('glucopilot-user-article-batch',5)")){rows.next();if(rows.getInt(1)!=1)throw new IllegalStateException("Another import is active");}
            c.setAutoCommit(false);
            try{
                try(var s=c.prepareStatement("SELECT id FROM user_account WHERE id=? AND phone=? AND is_deleted=0 AND account_status=1 FOR UPDATE")){s.setLong(1,owner);s.setString(2,args[3]);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("Owner changed");}}
                var backup=backup(c,workspace,owner);Long blogger=null;
                try(var s=c.prepareStatement("SELECT id FROM community_blogger WHERE user_id=? AND enabled=1 ORDER BY id LIMIT 1")){s.setLong(1,owner);try(var rows=s.executeQuery()){if(rows.next())blogger=rows.getLong(1);}}
                if(blogger==null)try(var s=c.prepareStatement("INSERT INTO community_blogger(user_id,nickname,avatar_url) SELECT u.id,COALESCE(NULLIF(p.nickname,''),'社区用户'),COALESCE(p.avatar_url,'') FROM user_account u LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0 WHERE u.id=?",Statement.RETURN_GENERATED_KEYS)){s.setLong(1,owner);blogger=generated(s);}
                int inserted=0,skipped=0;var ids=new ArrayList<Long>();
                for(var article:articles){
                    String seed=article.key()+"-u"+owner;
                    try(var s=c.prepareStatement("SELECT a.news_id,a.author_user_id,n.title,n.content_body FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE a.seed_key=?")){
                        s.setString(1,seed);try(var rows=s.executeQuery()){if(rows.next()){
                            if(rows.getLong("author_user_id")!=owner||!article.title().equals(rows.getString("title"))||!article.body().equals(rows.getString("content_body")))throw new IllegalStateException("Existing batch article differs; nothing overwritten");
                            ids.add(rows.getLong("news_id"));skipped++;continue;
                        }}
                    }
                    long id;
                    try(var s=c.prepareStatement("INSERT INTO cms_news(title,summary_text,content_body,channel_code,content_type,publish_at,publish_status,blogger_id,community_managed,created_by,updated_by) VALUES(?,?,?,4,1,CURRENT_TIMESTAMP,0,?,1,?,?)",Statement.RETURN_GENERATED_KEYS)){
                        s.setString(1,article.title());s.setString(2,article.body().substring(0,Math.min(140,article.body().length())).replace('\n',' '));s.setString(3,article.body());s.setLong(4,blogger);s.setLong(5,owner);s.setLong(6,owner);id=generated(s);
                    }
                    try(var s=c.prepareStatement("INSERT INTO community_article(news_id,author_user_id,client_request_id,seed_key,state,ai_reason) VALUES(?,?,?,?,'AI_PENDING','批量原创科普投稿，等待AI初审')")){
                        s.setLong(1,id);s.setLong(2,owner);s.setString(3,seed);s.setString(4,seed);s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("INSERT INTO community_article_access(news_id,visibility) VALUES(?,'PUBLIC')")){s.setLong(1,id);s.executeUpdate();}
                    for(String tag:article.tags())try(var s=c.prepareStatement("INSERT INTO community_article_tag(news_id,tag_id) VALUES(?,?)")){s.setLong(1,id);s.setLong(2,tagIds.get(tag));s.executeUpdate();}
                    try(var s=c.prepareStatement("INSERT INTO community_moderation_log(target_type,target_id,revision,actor_type,actor_id,decision,note) VALUES('ARTICLE',?,1,'USER',?,'SUBMIT','账号持有人授权批量导入，来源附文末；等待AI初审与管理员终审')")){s.setLong(1,id);s.setLong(2,owner);s.executeUpdate();}
                    ids.add(id);inserted++;
                }
                if(ids.size()!=18)throw new IllegalStateException("Unexpected batch size");
                c.commit();System.out.println("IMPORTED ownerId="+owner+" inserted="+inserted+" skipped="+skipped+" articleIds="+ids);System.out.println("BACKUP="+backup);
            }catch(Exception e){c.rollback();throw e;}
            finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('glucopilot-user-article-batch')");}}
        }catch(SQLException e){System.err.println("DATABASE_ERROR state="+e.getSQLState()+" code="+e.getErrorCode()+"; sensitive details omitted");System.exit(2);}
    }
}
