import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Local-only compatibility for the unpublished feedback/access V36/V37 branch. Never repairs checksums. */
public class ReconcileFeedbackMigrations extends ReconcileCommunityMigrations {
    static Map<String,Snapshot> allSnapshots(Connection c) throws Exception {
        var result=new TreeMap<String,Snapshot>();
        for(String table:tables(c))if(!table.equals("flyway_schema_history"))result.put(table,fingerprint(c,table,null));
        return result;
    }
    static Flyway current(String schema,boolean outOfOrder){return Flyway.configure().dataSource(url(schema),user,password)
            .locations("filesystem:"+migrations.toString().replace('\\','/')).outOfOrder(outOfOrder)
            .ignoreMigrationPatterns("*:pending").cleanDisabled(true).baselineOnMigrate(false).load();}
    static void verifyNormal(String schema){
        var normal=current(schema,false);normal.validate();
        if(normal.info().pending().length!=0)throw new IllegalStateException("Pending migrations remain");
        System.out.println("NORMAL_VALIDATION=passed OUT_OF_ORDER=false PENDING=0");
    }
    static void checkOld(Connection c,String schema) throws Exception {
        var rows=history(c);
        if(rows.size()!=37||!rows.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1,37).boxed().toList())))throw new IllegalStateException("Expected exactly V1..V37 unpublished local history");
        for(int old:List.of(36,37)){
            var row=rows.get(old);
            String expected=old==36?"V36__community_report_feedback.sql":"V37__community_visibility_views_preferences.sql";
            String target=old==36?"V37__community_report_feedback.sql":"V38__community_visibility_views_preferences.sql";
            var resource=new org.flywaydb.core.internal.resource.filesystem.FileSystemResource(new org.flywaydb.core.api.Location("filesystem:"+migrations.toString().replace('\\','/')),migrations.resolve(target).toString(),StandardCharsets.UTF_8,false);
            int checksum=org.flywaydb.core.internal.resolver.ChecksumCalculator.calculate(resource);
            String description=target.substring(target.indexOf("__")+2,target.length()-4).replace('_',' ');
            if(!Files.isRegularFile(migrations.resolve(target))||!row.script().equals(expected)||row.checksum()!=checksum||!row.description().equals(description))throw new IllegalStateException("Local history does not match renamed script V"+old);
        }
        var validation=current(schema,true).validateWithResult();var invalid=new TreeSet<String>();
        for(var item:validation.invalidMigrations){
            if(!Set.of("CHECKSUM_MISMATCH","DESCRIPTION_MISMATCH").contains(item.errorDetails.errorCode.toString()))throw new IllegalStateException("Unexpected validation error "+item.errorDetails.errorCode);
            invalid.add(item.version);
        }
        if(validation.validationSuccessful||!invalid.equals(Set.of("36","37")))throw new IllegalStateException("Unexpected migration conflict state");
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='community_comment_like'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Comment-like table already exists without matching history");}
        System.out.println("EXACT_LOCAL_RENAME_MATCH=V36_to_V37,V37_to_V38 CHECKSUMS=unchanged");
    }
    static void convert(Connection c,String schema,Map<String,Snapshot> before) throws Exception {
        checkOld(c,schema);c.setAutoCommit(false);
        try{
            try(var s=c.createStatement();var r=s.executeQuery("SELECT installed_rank FROM flyway_schema_history FOR UPDATE")){while(r.next()){} }
            var rows=history(c);
            for(int old:List.of(37,36)){
                var row=rows.get(old);String script=old==37?"V38__community_visibility_views_preferences.sql":"V37__community_report_feedback.sql";
                try(var s=c.prepareStatement("UPDATE flyway_schema_history SET version=?,script=? WHERE version=? AND script=? AND checksum=? AND success=1")){
                    s.setString(1,String.valueOf(old+1));s.setString(2,script);s.setString(3,row.version());s.setString(4,row.script());s.setInt(5,row.checksum());
                    if(s.executeUpdate()!=1)throw new IllegalStateException("History changed concurrently");
                }
            }
            c.commit();
        }catch(Exception error){c.rollback();throw error;}finally{c.setAutoCommit(true);}
        var flyway=current(schema,true);flyway.validate();
        var pending=Arrays.stream(flyway.info().pending()).map(i->i.getVersion().toString()).toList();
        if(!pending.equals(List.of("36")))throw new IllegalStateException("Only published V36 may remain pending");
        var migrated=flyway.migrate();if(!migrated.success||migrated.migrationsExecuted!=1)throw new IllegalStateException("Published V36 not applied");
        verifyNormal(schema);verifyData(c,before);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM community_comment_like")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Unexpected comment-like data");}
    }
    static void rehearsal(Connection source,Map<String,Snapshot> before) throws Exception {
        String schema="gp_feedback_rehearsal_"+UUID.randomUUID().toString().replace("-","");boolean created=false;
        try{
            try(var s=source.createStatement()){s.execute("CREATE DATABASE "+name(schema)+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;}
            try(var clone=open(schema)){
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=0");}
                for(String table:tables(source))try(var s=clone.createStatement()){
                    String fields=String.join(",",writableColumns(source,table).stream().map(ReconcileCommunityMigrations::name).toList());
                    s.execute(ddl(source,table));s.execute("INSERT INTO "+name(schema)+"."+name(table)+" ("+fields+") SELECT "+fields+" FROM "+name(db)+"."+name(table));
                }
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=1");}
                verifyData(clone,before);convert(clone,schema,before);
            }
            System.out.println("FEEDBACK_REHEARSAL=passed");
        }finally{
            if(created&&schema.matches("gp_feedback_rehearsal_[0-9a-f]{32}")&&!schema.equals(db))try(var s=source.createStatement()){s.execute("DROP DATABASE "+name(schema));System.out.println("FEEDBACK_REHEARSAL_DATABASE=removed");}
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3||!Set.of("audit","apply").contains(args[0]))throw new IllegalArgumentException("Use audit|apply workspace expectedLocalDatabase");
        workspace=Path.of(args[1]).toAbsolutePath().normalize();migrations=workspace.resolve("backend/glucopilot-business/src/main/resources/db/migration");
        for(Path file:List.of(workspace.resolve("backend/.env"),workspace.resolve(".env")))if(Files.isRegularFile(file))try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){CONFIG.load(r);}
        db=config("DB_NAME","");String host=config("DB_HOST",""),port=config("DB_PORT","3306");
        if(!Set.of("localhost","127.0.0.1").contains(host)||!"3306".equals(port)||!"sugar_health_clean".equals(db)||!db.equals(args[2]))throw new IllegalStateException("Only the verified local development database is allowed");
        user=config("DB_USERNAME","");password=config("DB_PASSWORD","");serverUrl="jdbc:mysql://"+host+":"+port+"/";
        try(var c=open(db)){
            if(!db.equals(c.getCatalog()))throw new IllegalStateException("Unexpected database identity");
            var rows=history(c);
            if(rows.size()==38&&rows.containsKey(38)&&rows.get(36).script().equals("V36__community_comment_like.sql")){verifyNormal(db);System.out.println("STATUS=ALREADY_RECONCILED");return;}
            checkOld(c,db);if(args[0].equals("audit"))return;
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('glucopilot.feedback.history-reconcile',10)")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Another migration reconciliation is active");}
            try{
                var before=allSnapshots(c);backup(c);rehearsal(c,before);verifyData(c,before);convert(c,db,before);System.out.println("STATUS=RECONCILED SCHEMA=38");
            }finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('glucopilot.feedback.history-reconcile')");}}
        }catch(SQLException error){System.err.println("DATABASE_ERROR state="+error.getSQLState()+" code="+error.getErrorCode()+"; credentials/data omitted");System.exit(2);}
    }
}
