import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Local, explicit operator import. No startup seeding, DDL, credential output or publication. */
public class CommunityStarterImport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> TABLES=List.of("cms_news","community_article","community_tag","community_article_tag","community_moderation_log");
    private static final Set<String> CATEGORIES=Set.of("diet","type1","type2","medication","exercise","daily");
    private static final Properties CONFIG=new Properties();
    static String value(String name,String fallback) { return System.getenv(name)!=null?System.getenv(name):CONFIG.getProperty(name,fallback); }
    record Entry(String key,String title,String body,List<String> tags) {}
    static List<Entry> entries(Path file) throws Exception {
        var result=new ArrayList<Entry>();var categories=new HashSet<String>();var keys=new HashSet<String>();
        for(var row:JSON.readTree(Files.readString(file,StandardCharsets.UTF_8))) {
            String key=row.path("seedKey").asString(),title=row.path("title").asString(),url=row.path("sourceUrl").asString();
            if(!key.matches("community-20261020-[a-z0-9-]+") || !keys.add(key) || title.isBlank() || title.length()>80
                    || !url.matches("https://(?:www\\.cdc\\.gov|www\\.niddk\\.nih\\.gov)/[A-Za-z0-9/_.-]+")) throw new IllegalArgumentException("Invalid article fixture");
            List<String> tags=new ArrayList<>();for(var tag:row.path("tagCodes")) tags.add(tag.asString());
            if(tags.isEmpty() || tags.size()>3 || !CATEGORIES.containsAll(tags)) throw new IllegalArgumentException("Invalid category fixture");
            categories.addAll(tags);
            String body=row.path("body").asString()+"\n\n资料说明：社区科普示例，依据权威资料原创整理，并非原文转载。内容用于一般健康教育，不能替代个体诊疗。\n参考来源："+row.path("sourceTitle").asString()+"\n"+url+"\n资料核对日期：2026-10-20";
            if(body.length()<100 || body.length()>8000) throw new IllegalArgumentException("Invalid article length");
            result.add(new Entry(key,title,body,List.copyOf(tags)));
        }
        if(result.size()!=14 || !categories.equals(CATEGORIES)) throw new IllegalArgumentException("Expected fourteen articles across six categories");
        return result;
    }
    static long count(Connection c,String table) throws SQLException {
        try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM `"+table+"`")) {rows.next();return rows.getLong(1);}
    }
    static Path backup(Connection c,Path workspace) throws Exception {
        Path folder=workspace.resolve(".tmp/community-starter-backup-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))).normalize();
        if(!folder.startsWith(workspace.resolve(".tmp"))) throw new IllegalStateException("Invalid backup path");
        Files.createDirectories(folder);
        for(String table:TABLES) {
            var output=new ArrayList<Map<String,Object>>();
            try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT * FROM `"+table+"`")) {
                var meta=rows.getMetaData();while(rows.next()) {
                    Map<String,Object> record=new LinkedHashMap<>();
                    for(int i=1;i<=meta.getColumnCount();i++) {Object value=rows.getObject(i);record.put(meta.getColumnLabel(i),value==null || value instanceof Number || value instanceof Boolean?value:value.toString());}
                    output.add(record);
                }
            }
            Files.writeString(folder.resolve(table+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
        return folder;
    }
    public static void main(String[] args) throws Exception {
        if(args.length<3 || !Set.of("audit","apply").contains(args[0])) throw new IllegalArgumentException("Use audit|apply workspace expected-database [runtime-client-ports]");
        Path workspace=Path.of(args[1]).toAbsolutePath().normalize();String expected=args[2];
        for(Path file:List.of(workspace.resolve("backend/.env"),workspace.resolve(".env"),workspace.resolve("backend/GlucoPilot/.env")))
            if(Files.isRegularFile(file)) try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {CONFIG.load(reader);}
        String db=value("DB_NAME","sugar_health"),host=value("DB_HOST","127.0.0.1");
        if(!db.equals(expected) || !db.matches("[A-Za-z0-9_]+") || !Set.of("localhost","127.0.0.1","::1").contains(host)) throw new IllegalStateException("Only the confirmed local development database is supported");
        var entries=entries(workspace.resolve("backend/data/community/starter-articles.json"));
        String url="jdbc:mysql://"+host+":"+value("DB_PORT","3306")+"/"+db+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL="+value("DB_SSL_ENABLED","false");
        try(var c=DriverManager.getConnection(url,value("DB_USERNAME","root"),value("DB_PASSWORD",""))) {
            try(var s=c.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE version='31' AND success=1");var r=s.executeQuery()) {r.next();if(r.getLong(1)!=1) throw new IllegalStateException("V31 is not applied");}
            if(args.length>3 && !args[3].isBlank()) {
                Set<String> schemas=new HashSet<>(),ports=Set.of(args[3].split(","));
                try(var s=c.createStatement();var r=s.executeQuery("SELECT HOST,DB FROM information_schema.PROCESSLIST WHERE DB IS NOT NULL")) {
                    while(r.next()) {String client=r.getString(1);if(ports.contains(client.substring(client.lastIndexOf(':')+1))) schemas.add(r.getString(2));}
                }
                if(!schemas.equals(Set.of(db))) throw new IllegalStateException("Running backend database mismatch; no writes performed");
            } else if(args[0].equals("apply")) throw new IllegalStateException("Runtime database confirmation required for writes");
            for(String table:TABLES) System.out.println("TABLE_COUNT "+table+"="+count(c,table));
            System.out.println("FIXTURE_ARTICLES="+entries.size());
            if(args[0].equals("audit")) return;
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('glucopilot-community-starter-import',5)")) {r.next();if(r.getInt(1)!=1) throw new IllegalStateException("Another import is active");}
            c.setAutoCommit(false);
            try {
                long before=count(c,"cms_news");Path backup=backup(c,workspace);long publisher;
                try(var s=c.prepareStatement("SELECT id FROM community_blogger WHERE user_id IS NULL AND nickname='社区编辑部' AND enabled=1 ORDER BY id LIMIT 1");var r=s.executeQuery()) {
                    if(!r.next()) throw new IllegalStateException("Explicit editorial publisher not found");publisher=r.getLong(1);
                }
                int inserted=0,skipped=0;
                for(Entry article:entries) {
                    try(var s=c.prepareStatement("SELECT news_id FROM community_article WHERE seed_key=?")) {s.setString(1,article.key());try(var r=s.executeQuery()) {if(r.next()) {skipped++;continue;}}}
                    long id;
                    try(var s=c.prepareStatement("INSERT INTO cms_news(title,summary_text,content_body,channel_code,content_type,publish_at,publish_status,blogger_id,community_managed) VALUES(?,?,?,4,1,CURRENT_TIMESTAMP,0,?,1)",Statement.RETURN_GENERATED_KEYS)) {
                        s.setString(1,article.title());s.setString(2,article.body().substring(0,Math.min(120,article.body().length())).replace('\n',' '));s.setString(3,article.body());s.setLong(4,publisher);s.executeUpdate();
                        try(var r=s.getGeneratedKeys()) {if(!r.next()) throw new IllegalStateException("No generated article id");id=r.getLong(1);}
                    }
                    try(var s=c.prepareStatement("INSERT INTO community_article(news_id,seed_key,state,ai_verdict) VALUES(?,?,'AI_PENDING','PENDING')")) {s.setLong(1,id);s.setString(2,article.key());s.executeUpdate();}
                    for(String tag:article.tags()) try(var s=c.prepareStatement("INSERT INTO community_article_tag(news_id,tag_id) SELECT ?,id FROM community_tag WHERE code=? AND enabled=1")) {
                        s.setLong(1,id);s.setString(2,tag);if(s.executeUpdate()!=1) throw new IllegalStateException("Missing category");
                    }
                    try(var s=c.prepareStatement("INSERT INTO community_moderation_log(target_type,target_id,revision,actor_type,decision,note) VALUES('ARTICLE',?,1,'SYSTEM','SEED','原创科普资料整理，等待AI初审和管理员终审，未公开')")) {s.setLong(1,id);s.executeUpdate();}
                    inserted++;
                }
                if(count(c,"cms_news")!=before+inserted) throw new IllegalStateException("Unexpected article count");
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*),SUM(n.publish_status=0),SUM(a.state='AI_PENDING') FROM community_article a JOIN cms_news n ON n.id=a.news_id WHERE a.seed_key LIKE 'community-20261020-%'")) {
                    r.next();if(r.getInt(1)!=14) throw new IllegalStateException("Seed count mismatch");
                    System.out.println("VERIFIED_SEED_TOTAL="+r.getInt(1)+" UNPUBLISHED="+r.getInt(2)+" AI_PENDING="+r.getInt(3));
                }
                c.commit();System.out.println("INSERTED="+inserted+" SKIPPED_EXISTING="+skipped);System.out.println("BACKUP="+backup);
            } catch(Exception ex) { c.rollback();throw ex; }
            finally { try(var s=c.createStatement()) {s.execute("SELECT RELEASE_LOCK('glucopilot-community-starter-import')");} }
        } catch(SQLException ex) {System.err.println("DATABASE_ERROR state="+ex.getSQLState()+" code="+ex.getErrorCode()+"; sensitive details omitted");System.exit(2);}
    }
}
