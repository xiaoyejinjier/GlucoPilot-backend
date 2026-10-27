import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Only for the unpublished local food V42, renamed V44 when integrating the published device V42. */
public class ReconcileFoodMigrations extends ReconcileCommunityMigrations {
    static Flyway current(String schema,boolean outOfOrder) {
        return Flyway.configure().dataSource(url(schema),user,password)
                .locations("filesystem:"+migrations.toString().replace('\\','/'))
                .outOfOrder(outOfOrder).ignoreMigrationPatterns("*:pending")
                .cleanDisabled(true).baselineOnMigrate(false).load();
    }
    static void checkOld(Connection c,String schema) throws Exception {
        var rows=history(c);
        if(rows.size()!=42||!rows.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1,42).boxed().toList())))
            throw new IllegalStateException("Expected exactly the unpublished local V1..V42 history");
        var food=resolved(current(schema,true)).get(44);var old=rows.get(42);
        if(food==null||!old.script().equals("V42__food_active_name_uniqueness.sql")
                ||!Objects.equals(old.checksum(),food.getChecksum())||!old.description().equals(food.getDescription()))
            throw new IllegalStateException("Food V42 does not exactly match unchanged V44 SQL");
        var validation=current(schema,true).validateWithResult();
        if(validation.validationSuccessful||validation.invalidMigrations.size()!=1
                ||!validation.invalidMigrations.getFirst().version.equals("42")
                ||!validation.invalidMigrations.getFirst().errorDetails.errorCode.toString().equals("CHECKSUM_MISMATCH"))
            throw new IllegalStateException("Unexpected validation state; do not repair or skip checksums");
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='food_nutrition' AND index_name='uk_food_active_name'")) {
            r.next();if(r.getInt(1)!=3)throw new IllegalStateException("Applied food uniqueness index not found");
        }
        System.out.println("EXACT_LOCAL_RENAME_MATCH=V42_food_to_V44 CHECKSUM_UNCHANGED=true");
    }
    static void verifyCurrent(String schema) {
        var normal=current(schema,false);normal.validate();
        if(normal.info().pending().length!=0)throw new IllegalStateException("Pending migrations remain");
        System.out.println("NORMAL_VALIDATION=passed OUT_OF_ORDER=false PENDING=0");
    }
    static void convert(Connection c,String schema,Map<String,Snapshot> before) throws Exception {
        checkOld(c,schema);c.setAutoCommit(false);
        try {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT installed_rank FROM flyway_schema_history FOR UPDATE")){while(r.next()){} }
            var old=history(c).get(42);
            try(var s=c.prepareStatement("UPDATE flyway_schema_history SET version='44',script='V44__food_active_name_uniqueness.sql' WHERE version='42' AND script='V42__food_active_name_uniqueness.sql' AND checksum=? AND success=1")) {
                s.setInt(1,old.checksum());if(s.executeUpdate()!=1)throw new IllegalStateException("History changed concurrently");
            }
            c.commit();
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        var flyway=current(schema,true);flyway.validate();
        var pending=Arrays.stream(flyway.info().pending()).map(i->i.getVersion().toString()).toList();
        if(!pending.equals(List.of("42","43")))throw new IllegalStateException("Expected only published device V42 and moderation V43");
        var result=flyway.migrate();
        if(!result.success||result.migrationsExecuted!=2)throw new IllegalStateException("Published migrations did not complete");
        verifyCurrent(schema);verifyData(c,before);
    }
    static void rehearsal(Connection source,Map<String,Snapshot> before) throws Exception {
        String schema="gp_foodsync_rehearsal_"+UUID.randomUUID().toString().replace("-","");boolean created=false;
        try {
            try(var s=source.createStatement()){s.execute("CREATE DATABASE "+name(schema)+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;}
            try(var clone=open(schema)) {
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=0");}
                for(String table:tables(source))try(var s=clone.createStatement()) {
                    String fields=String.join(",",writableColumns(source,table).stream().map(ReconcileCommunityMigrations::name).toList());
                    s.execute(ddl(source,table));s.execute("INSERT INTO "+name(schema)+"."+name(table)+" ("+fields+") SELECT "+fields+" FROM "+name(db)+"."+name(table));
                }
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=1");}
                verifyData(clone,before);convert(clone,schema,before);
            }
            System.out.println("FOOD_REHEARSAL=passed");
        }finally {
            if(created&&schema.matches("gp_foodsync_rehearsal_[0-9a-f]{32}")&&!schema.equals(db))
                try(var s=source.createStatement()){s.execute("DROP DATABASE "+name(schema));System.out.println("FOOD_REHEARSAL_DATABASE=removed");}
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3||!Set.of("audit","apply").contains(args[0]))throw new IllegalArgumentException("Use audit|apply workspace expectedLocalDatabase");
        workspace=Path.of(args[1]).toAbsolutePath().normalize();migrations=workspace.resolve("backend/glucopilot-business/src/main/resources/db/migration");
        try(var r=Files.newBufferedReader(workspace.resolve(".env"),StandardCharsets.UTF_8)){CONFIG.load(r);}
        db=config("DB_NAME","");String host=config("DB_HOST",""),port=config("DB_PORT","3306");
        if(!Set.of("localhost","127.0.0.1").contains(host)||!port.equals("3306")||!db.equals("sugar_health_clean")||!db.equals(args[2]))
            throw new IllegalStateException("Only the verified local development database is allowed");
        user=config("DB_USERNAME","");password=config("DB_PASSWORD","");serverUrl="jdbc:mysql://"+host+":"+port+"/";
        try(var c=open(db)) {
            if(!db.equals(c.getCatalog()))throw new IllegalStateException("Database identity mismatch");
            var rows=history(c);
            if(rows.size()==44&&rows.containsKey(44)&&rows.get(42).script().equals("V42__device_sync_log.sql")) {
                verifyCurrent(db);System.out.println("STATUS=ALREADY_RECONCILED");return;
            }
            checkOld(c,db);if(args[0].equals("audit"))return;
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('glucopilot.foodsync.history-reconcile',10)")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Another reconciliation is active");}
            try {
                var before=snapshots(c);backup(c);rehearsal(c,before);verifyData(c,before);convert(c,db,before);
                System.out.println("STATUS=RECONCILED SCHEMA=44");
            }finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('glucopilot.foodsync.history-reconcile')");}}
        }catch(SQLException e){System.err.println("DATABASE_ERROR state="+e.getSQLState()+" code="+e.getErrorCode()+"; credentials/data omitted");System.exit(2);}
    }
}
