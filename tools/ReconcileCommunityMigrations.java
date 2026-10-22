import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** One-time local operator tool. Never repair or skip checksum validation. Credentials stay in memory. */
public class ReconcileCommunityMigrations {
    static final Properties CONFIG = new Properties();
    static final List<String> NAMES = List.of("community_interactions", "food_recognition_source_photo",
            "community_publication_moderation", "community_comment_replies", "community_article_images",
            "drafts_notifications_exports", "community_article_video");
    static Path workspace, migrations;
    static String db, user, password, serverUrl;
    record History(String version, String script, int checksum, String description, boolean success) {}
    record Snapshot(List<String> columns, long rows, String hash) {}
    static String config(String name, String fallback) {
        return System.getenv(name) != null ? System.getenv(name) : CONFIG.getProperty(name, fallback);
    }
    static String name(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unsafe SQL identifier");
        return "`" + value + "`";
    }
    static String url(String schema) { return serverUrl + schema + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"; }
    static Connection open(String schema) throws SQLException { return DriverManager.getConnection(url(schema), user, password); }
    static Flyway flyway(String schema, boolean outOfOrder) {
        var configuration = Flyway.configure().dataSource(url(schema), user, password)
                .locations("filesystem:" + migrations.toString().replace('\\', '/'))
                .outOfOrder(outOfOrder).cleanDisabled(true).baselineOnMigrate(false);
        // Preflight validates all applied checksums while the seven explicitly checked scripts are pending.
        if (outOfOrder) configuration.ignoreMigrationPatterns("*:pending");
        return configuration.load();
    }
    static Map<Integer, MigrationInfo> resolved(Flyway f) {
        Map<Integer, MigrationInfo> result = new TreeMap<>();
        for (MigrationInfo item : f.info().all()) {
            if (item.getVersion() != null && item.getVersion().getVersion().matches("\\d+"))
                result.put(Integer.parseInt(item.getVersion().getVersion()), item);
        }
        return result;
    }
    static Map<Integer, History> history(Connection c) throws SQLException {
        Map<Integer, History> rows = new TreeMap<>();
        try (var stmt = c.createStatement(); var rs = stmt.executeQuery("SELECT version,script,checksum,description,success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null || !v.matches("\\d+")) throw new IllegalStateException("Unexpected non-versioned history");
                History row = new History(v, rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBoolean(5));
                if (!row.success() || rows.put(Integer.parseInt(v), row) != null) throw new IllegalStateException("Failed or duplicate migration in history");
            }
        }
        return rows;
    }
    static void inspectOld(Connection c, String schema) throws Exception {
        Map<Integer, History> applied = history(c);
        if (applied.size() != 28 || !applied.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 28).boxed().toList())))
            throw new IllegalStateException("Expected exactly the previously verified V1..V28 local development history");
        Flyway f = flyway(schema, true);
        Map<Integer, MigrationInfo> local = resolved(f);
        for (int i = 0; i < NAMES.size(); i++) {
            int old = 22 + i, replacement = 29 + i;
            History row = applied.get(old); MigrationInfo script = local.get(replacement);
            String oldName = "V" + old + "__" + NAMES.get(i) + ".sql";
            if (script == null || !row.script().equals(oldName) || !Objects.equals(row.checksum(), script.getChecksum())
                    || !row.description().equals(script.getDescription()))
                throw new IllegalStateException("History does not match renamed community script at V" + old);
            System.out.println("MATCHED=V" + old + "->V" + replacement + " CHECKSUM=" + row.checksum());
        }
        var validation = f.validateWithResult();
        Set<String> invalid = new HashSet<>();
        for (var item : validation.invalidMigrations) {
            String code = item.errorDetails.errorCode.toString();
            if (!Set.of("CHECKSUM_MISMATCH", "DESCRIPTION_MISMATCH").contains(code))
                throw new IllegalStateException("Unexpected validation error: " + code);
            invalid.add(item.version);
        }
        if (validation.validationSuccessful || !invalid.equals(Set.of("22","23","24","25","26","27","28")))
            throw new IllegalStateException("Unexpected validation state; no writes performed");
        System.out.println("EXPECTED_CHECKSUM_CONFLICTS=7");
    }
    static List<String> tables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            while (r.next()) { name(r.getString(1)); tables.add(r.getString(1)); }
        }
        return tables;
    }
    static String ddl(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SHOW CREATE TABLE " + name(table))) { r.next(); return r.getString(2); }
    }
    static List<String> writableColumns(Connection c, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var s = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COALESCE(GENERATION_EXPRESSION,'')='' ORDER BY ORDINAL_POSITION")) {
            s.setString(1,table);try(var r=s.executeQuery()){while(r.next())columns.add(r.getString(1));}
        }
        if(columns.isEmpty())throw new IllegalStateException("No writable columns in " + table);
        return columns;
    }
    static byte[] bytes(Object value) {
        if (value instanceof byte[] raw) return raw;
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }
    static Snapshot fingerprint(Connection c, String table, List<String> selected) throws Exception {
        List<String> columns = new ArrayList<>(); List<String> rowHashes = new ArrayList<>();
        String fields = selected == null ? "*" : String.join(",", selected.stream().map(ReconcileCommunityMigrations::name).toList());
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT " + fields + " FROM " + name(table))) {
            var meta = r.getMetaData(); for (int i=1; i<=meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
            while (r.next()) {
                var hash = MessageDigest.getInstance("SHA-256");
                for (int i=1; i<=meta.getColumnCount(); i++) {
                    Object value = r.getObject(i); hash.update((byte)(value == null ? 0 : 1));
                    if (value != null) { byte[] raw = bytes(value); hash.update(java.nio.ByteBuffer.allocate(4).putInt(raw.length).array()); hash.update(raw); }
                }
                rowHashes.add(HexFormat.of().formatHex(hash.digest()));
            }
        }
        Collections.sort(rowHashes); var hash = MessageDigest.getInstance("SHA-256");
        for (String item : rowHashes) hash.update(item.getBytes(StandardCharsets.US_ASCII));
        return new Snapshot(columns, rowHashes.size(), HexFormat.of().formatHex(hash.digest()));
    }
    static boolean protectedTable(String table) {
        return !table.equals("flyway_schema_history") && !table.startsWith("sys_") && !table.startsWith("ai_");
    }
    static Map<String, Snapshot> snapshots(Connection c) throws Exception {
        Map<String, Snapshot> result = new TreeMap<>();
        for (String table : tables(c)) if (protectedTable(table)) result.put(table, fingerprint(c, table, null));
        return result;
    }
    static void verifyData(Connection c, Map<String, Snapshot> before) throws Exception {
        for (var entry : before.entrySet()) {
            Snapshot actual = fingerprint(c, entry.getKey(), entry.getValue().columns());
            if (!actual.equals(entry.getValue())) throw new IllegalStateException("Original business rows changed: " + entry.getKey());
        }
        System.out.println("BUSINESS_DATA_SHA256=unchanged TABLES=" + before.size());
    }
    static Path backup(Connection c) throws Exception {
        Path path = workspace.resolve(".tmp/community-history-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        Files.createDirectories(path); Map<String,String> manifest = new TreeMap<>();
        c.setAutoCommit(false);
        try {
            try (var s = c.createStatement()) { s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"); s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT"); }
            for (String table : tables(c)) {
                Path file = path.resolve(table + ".sql"); long count = 0;
                String fields=String.join(",",writableColumns(c,table).stream().map(ReconcileCommunityMigrations::name).toList());
                try (var output = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    output.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n" + ddl(c,table) + ";\n");
                    try (var s=c.createStatement(); var r=s.executeQuery("SELECT " + fields + " FROM " + name(table))) {
                        int columns=r.getMetaData().getColumnCount();
                        while(r.next()) {
                            output.write("INSERT INTO " + name(table) + " (" + fields + ") VALUES (");
                            for (int i=1; i<=columns; i++) {
                                if(i>1)output.write(","); Object value=r.getObject(i);
                                if(value==null)output.write("NULL");
                                else if(value instanceof Number)output.write(value.toString());
                                else if(value instanceof Boolean b)output.write(b?"1":"0");
                                else {String hex=HexFormat.of().formatHex(bytes(value));output.write(value instanceof byte[] ? "X'"+hex+"'" : "CONVERT(X'"+hex+"' USING utf8mb4)");}
                            }
                            output.write(");\n"); count++;
                        }
                    }
                    output.write("SET FOREIGN_KEY_CHECKS=1;\n");
                }
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                manifest.put(table, "rows="+count+" sha256="+digest);
            }
            c.commit();
        } catch(Exception ex) { c.rollback(); throw ex; } finally { c.setAutoCommit(true); }
        List<String> summary = new ArrayList<>(); summary.add("database="+db);
        manifest.forEach((table,value)->summary.add(table+" "+value));
        Files.write(path.resolve("MANIFEST.txt"),summary,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println("BACKUP="+path+" TABLES="+manifest.size());return path;
    }
    static void convertAndMigrate(Connection c, String schema, Map<String,Snapshot> before) throws Exception {
        inspectOld(c,schema); c.setAutoCommit(false);
        try {
            try (var lock=c.createStatement();var rows=lock.executeQuery("SELECT installed_rank FROM flyway_schema_history FOR UPDATE")) {while(rows.next()){} }
            Map<Integer,History> history=history(c);
            for(int i=0;i<NAMES.size();i++) {
                History row=history.get(22+i);
                try(var update=c.prepareStatement("UPDATE flyway_schema_history SET version=?,script=? WHERE version=? AND script=? AND checksum=? AND success=1")) {
                    update.setString(1,String.valueOf(29+i));update.setString(2,"V"+(29+i)+"__"+NAMES.get(i)+".sql");
                    update.setString(3,row.version());update.setString(4,row.script());update.setInt(5,row.checksum());
                    if(update.executeUpdate()!=1)throw new IllegalStateException("History changed concurrently");
                }
            }
            c.commit();
        } catch(Exception ex) {c.rollback();throw ex;} finally {c.setAutoCommit(true);}
        Flyway f=flyway(schema,true);f.validate();
        Set<String> pending=new TreeSet<>();for(var item:f.info().pending())pending.add(item.getVersion().getVersion());
        if(!pending.equals(Set.of("22","23","24","25","26","27","28")))throw new IllegalStateException("Unexpected pending migrations after conversion");
        var result=f.migrate();if(!result.success || result.migrationsExecuted!=7)throw new IllegalStateException("Mainline migration incomplete; retain backup for inspection");
        verifyNormal(schema);verifyData(c,before);
    }
    static void verifyNormal(String schema) {
        Flyway normal=flyway(schema,false);normal.validate();
        if(normal.info().pending().length!=0)throw new IllegalStateException("Pending migrations remain");
        System.out.println("NORMAL_VALIDATION=passed OUT_OF_ORDER=false PENDING=0");
    }
    static void rehearse(Connection source,Map<String,Snapshot> before) throws Exception {
        String rehearsal="gp_community_rehearsal_"+UUID.randomUUID().toString().replace("-","");boolean created=false;
        try {
            try(var s=source.createStatement()){s.execute("CREATE DATABASE "+name(rehearsal)+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;}
            try(var clone=open(rehearsal)) {
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=0");}
                for(String table:tables(source))try(var s=clone.createStatement()) {
                    String fields=String.join(",",writableColumns(source,table).stream().map(ReconcileCommunityMigrations::name).toList());
                    s.execute(ddl(source,table));s.execute("INSERT INTO "+name(rehearsal)+"."+name(table)+" ("+fields+") SELECT "+fields+" FROM "+name(db)+"."+name(table));
                }
                try(var s=clone.createStatement()){s.execute("SET FOREIGN_KEY_CHECKS=1");}
                verifyData(clone,before);convertAndMigrate(clone,rehearsal,before);
            }
            System.out.println("REHEARSAL=passed");
        } finally {
            if(created && rehearsal.startsWith("gp_community_rehearsal_") && !rehearsal.equals(db))
                try(var s=source.createStatement()){s.execute("DROP DATABASE "+name(rehearsal));System.out.println("REHEARSAL_DATABASE=removed");}
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !Set.of("audit","apply").contains(args[0]))throw new IllegalArgumentException("Use audit|apply workspace expectedLocalDatabase");
        workspace=Path.of(args[1]).toAbsolutePath().normalize();migrations=workspace.resolve("backend/glucopilot-business/src/main/resources/db/migration");
        for(Path file:List.of(workspace.resolve("backend/.env"),workspace.resolve(".env"),workspace.resolve("backend/GlucoPilot/.env")))
            if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){CONFIG.load(reader);}
        db=config("DB_NAME","");String host=config("DB_HOST",""),port=config("DB_PORT","3306");
        if(!Set.of("localhost","127.0.0.1").contains(host) || !port.equals("3306") || !db.equals(args[2]) || !db.equals("sugar_health_clean"))
            throw new IllegalStateException("Only the log-confirmed local sugar_health_clean development database is allowed");
        user=config("DB_USERNAME","");password=config("DB_PASSWORD","");serverUrl="jdbc:mysql://"+host+":"+port+"/";
        try(var c=open(db)) {
            if(!db.equals(c.getCatalog()))throw new IllegalStateException("Database identity mismatch");
            System.out.println("LOCAL_DATABASE_CONFIRMED="+db);
            var applied=history(c);
            if(applied.size()==35 && applied.containsKey(35)){verifyNormal(db);System.out.println("STATUS=ALREADY_RECONCILED");return;}
            inspectOld(c,db);
            Map<String,Snapshot> before=snapshots(c);System.out.println("BUSINESS_SNAPSHOT_TABLES="+before.size());
            if(args[0].equals("audit"))return;
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('glucopilot.community.history-reconcile',10)")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Another reconciliation is active");}
            try {backup(c);rehearse(c,before);convertAndMigrate(c,db,before);System.out.println("STATUS=RECONCILED");}
            finally {try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('glucopilot.community.history-reconcile')");}}
        } catch(SQLException ex) {System.err.println("DATABASE_ERROR state="+ex.getSQLState()+" code="+ex.getErrorCode()+"; credentials and data omitted");System.exit(2);}
    }
}
