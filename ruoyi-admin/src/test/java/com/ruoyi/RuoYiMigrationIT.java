package com.ruoyi;

import static org.junit.jupiter.api.Assertions.*;

import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import redis.embedded.RedisServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Uses only a newly-created disposable database; never runs against the deployment DB. */
@EnabledIfEnvironmentVariable(named = "MIGRATION_MYSQL_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuoYiMigrationIT {
  private final String database = "gp_ruoyi_it_" + UUID.randomUUID().toString().replace("-", "");
  private final String password = "Migration-test-" + UUID.randomUUID();
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private String baseUrl, jdbcUrl, originalHash, userToken;
  private Connection server;
  private RedisServer redis;
  private ConfigurableApplicationContext app;
  private String[] appArgs;

  @BeforeAll
  void migrateAndStart() throws Exception {
    String serverUrl = System.getenv("MIGRATION_MYSQL_URL");
    String username = System.getenv().getOrDefault("MIGRATION_MYSQL_USERNAME", "root");
    String dbPassword = System.getenv().getOrDefault("MIGRATION_MYSQL_PASSWORD", "");
    server = DriverManager.getConnection(serverUrl, username, dbPassword);
    server
        .createStatement()
        .execute(
            "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    jdbcUrl =
        serverUrl.replaceFirst(
            "/([^/?]*)?(\\?.*)?$",
            "/"
                + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    Flyway.configure().dataSource(jdbcUrl, username, dbPassword).target("9").load().migrate();
    originalHash = new BCryptPasswordEncoder().encode(password);
    userToken = UUID.randomUUID().toString();
    try (var db = DriverManager.getConnection(jdbcUrl, username, dbPassword)) {
      try (var stmt =
          db.prepareStatement(
              "UPDATE ops_account SET password_hash=? WHERE username IN ('admin','editor01')")) {
        stmt.setString(1, originalHash);
        stmt.executeUpdate();
      }
      db.createStatement()
          .execute(
              "INSERT INTO user_account(openid,username)"
                  + " VALUES('migration-fixture','migration-user')");
      db.createStatement()
          .execute(
              "INSERT INTO user_profile(user_id,nickname) SELECT id,'migration-user' FROM"
                  + " user_account WHERE openid='migration-fixture'");
      db.createStatement()
          .execute(
              "INSERT INTO user_session(user_id,access_token,expire_at) SELECT id,'"
                  + userToken
                  + "',DATE_ADD(NOW(),INTERVAL 1 DAY) FROM user_account WHERE"
                  + " openid='migration-fixture'");
    }
    int redisPort;
    try (var socket = new ServerSocket(0)) {
      redisPort = socket.getLocalPort();
    }
    redis =
        RedisServer.newRedisServer()
            .port(redisPort)
            .setting("bind 127.0.0.1")
            .setting("maxmemory 64M")
            .build();
    redis.start();
    appArgs =
        new String[] {
          "--spring.profiles.active=migration-it",
          "--server.port=0",
          "--spring.datasource.url=" + jdbcUrl,
          "--spring.datasource.username=" + username,
          "--spring.datasource.password=" + dbPassword,
          "--spring.data.redis.host=127.0.0.1",
          "--spring.data.redis.port=" + redisPort,
          "--spring.data.redis.password=",
          "--token.secret=" + UUID.randomUUID() + UUID.randomUUID(),
          "--spring.ai.deepseek.api-key=integration-test-placeholder",
          "--glucopilot.storage.provider=minio",
          "--glucopilot.storage.endpoint=http://127.0.0.1:9000",
          "--glucopilot.storage.access-key=integration-test",
          "--glucopilot.storage.secret-key=integration-test",
          "--glucopilot.storage.bucket=integration-test"
        };
    app = SpringApplication.run(RuoYiApplication.class, appArgs);
    baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
  }

  private HttpResponse<String> request(String method, String path, String token, String body)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("X-Request-Id", "ruoyi-migration-test");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body != null) builder.header("Content-Type", "application/json");
    return http.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    var node = json.readTree(response.body());
    assertEquals(0, node.path("code").asInt(), response.body());
    assertEquals("ruoyi-migration-test", node.path("requestId").asString());
    return node.path("data");
  }

  private String login(String name) throws Exception {
    return ok(request(
            "POST",
            "/admin/v1/auth/sessions",
            null,
            json.writeValueAsString(java.util.Map.of("username", name, "password", password))))
        .path("accessToken")
        .asString();
  }

  @Test
  void preservesIdentitiesPasswordsAndBusinessData() throws Exception {
    var users = app.getBean(ISysUserService.class);
    var admin = users.selectUserByUserName("admin");
    assertEquals(originalHash, admin.getPassword());
    assertTrue(admin.isAdmin());
    assertFalse(users.selectUserByUserName("editor01").isAdmin());
    try (var db =
        DriverManager.getConnection(
            jdbcUrl,
            System.getenv().getOrDefault("MIGRATION_MYSQL_USERNAME", "root"),
            System.getenv().getOrDefault("MIGRATION_MYSQL_PASSWORD", ""))) {
      var rs =
          db.createStatement()
              .executeQuery(
                  "SELECT COUNT(*) FROM gp_ops_identity_map i JOIN ops_account a ON"
                      + " a.id=i.legacy_ops_id WHERE i.ruoyi_user_id=a.id AND a.username IN"
                      + " ('admin','editor01')");
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      rs =
          db.createStatement()
              .executeQuery("SELECT COUNT(*) FROM user_account WHERE openid='migration-fixture'");
      rs.next();
      assertEquals(1, rs.getInt(1));
      rs = db.createStatement().executeQuery("SELECT COUNT(*) FROM gp_app_config");
      rs.next();
      assertTrue(rs.getInt(1) > 0);
    }
  }

  @Test
  void sessionsPermissionsAndEndpointsRemainCompatible() throws Exception {
    String admin = login("admin"), editor = login("editor01");
    assertEquals(3, admin.split("\\.").length);
    ok(request("GET", "/admin/v1/ruoyi/users", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/roles", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/menus", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/depts", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/posts", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/dict-types", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/notices", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/configs", admin, null));
    ok(request("GET", "/admin/v1/ruoyi/jobs", admin, null));
    assertEquals(403, request("GET", "/admin/v1/ruoyi/users", editor, null).statusCode());
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", userToken, null).statusCode());
    assertEquals(401, request("GET", "/api/v1/users/me", admin, null).statusCode());
    ok(request("GET", "/api/v1/users/me", userToken, null));
    ok(request("GET", "/api/v1/app-config", null, null));
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", "invalid", null).statusCode());
    var login = app.getBean(TokenService.class).getLoginUserByToken(editor);
    assertNotNull(login);
    ok(request("DELETE", "/admin/v1/ruoyi/online/" + login.getToken(), admin, null));
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", editor, null).statusCode());
    ok(request("DELETE", "/admin/v1/auth/sessions/current", admin, null));
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", admin, null).statusCode());
  }

  @Test
  void generatorUsesOfficialTemplates() throws Exception {
    String token = login("admin");
    var preview =
        ok(request("POST", "/admin/v1/ruoyi/generator/tables/food_nutrition/sync", token, null));
    assertTrue(
        preview.toString().contains("com.threadfit.glucopilot.generated"), preview.toString());
  }

  @Test
  void mutationsUseOfficialPersistenceAndQuartz() throws Exception {
    String admin = login("admin");
    var dept =
        ok(
            request(
                "POST",
                "/admin/v1/ruoyi/depts",
                admin,
                "{\"deptName\":\"Migration root\",\"parentId\":0,\"status\":1}"));
    var post =
        ok(
            request(
                "POST",
                "/admin/v1/ruoyi/posts",
                admin,
                "{\"postCode\":\"migration-post\",\"postName\":\"Migration post\",\"status\":1}"));
    var body = new java.util.HashMap<String, Object>();
    body.put("username", "migration-operator");
    body.put("password", password);
    body.put("nickname", "Migration operator");
    body.put("status", 1);
    body.put("deptId", dept.path("id").asLong());
    body.put("postIds", java.util.List.of(post.path("id").asLong()));
    body.put("roleCodes", java.util.List.of("operator"));
    var user = ok(request("POST", "/admin/v1/ruoyi/users", admin, json.writeValueAsString(body)));
    assertEquals("Migration root", user.path("deptName").asString());
    String operator = login("migration-operator");
    assertEquals(403, request("GET", "/admin/v1/ruoyi/users", operator, null).statusCode());
    body.put("username", "migration-operator-renamed");
    body.remove("password");
    ok(
        request(
            "PUT",
            "/admin/v1/ruoyi/users/" + user.path("id").asString(),
            admin,
            json.writeValueAsString(body)));
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", operator, null).statusCode());
    operator = login("migration-operator-renamed");
    ok(request("DELETE", "/admin/v1/ruoyi/users/" + user.path("id").asString(), admin, null));
    assertEquals(401, request("GET", "/admin/v1/ruoyi/users", operator, null).statusCode());
    var job =
        ok(
            request(
                "POST",
                "/admin/v1/ruoyi/jobs",
                admin,
                "{\"jobName\":\"Migration"
                    + " health\",\"invokeTarget\":\"system.health-check\",\"cronExpression\":\"0 0"
                    + " 0 * * ?\",\"status\":0}"));
    ok(request("POST", "/admin/v1/ruoyi/jobs/" + job.path("id").asString() + "/run", admin, null));
    var logs = app.getBean(com.ruoyi.quartz.service.ISysJobLogService.class);
    long end = System.currentTimeMillis() + 5000;
    while (logs.selectJobLogList(new com.ruoyi.quartz.domain.SysJobLog()).isEmpty()
        && System.currentTimeMillis() < end) Thread.sleep(50);
    assertTrue(
        logs.selectJobLogList(new com.ruoyi.quartz.domain.SysJobLog()).stream()
            .anyMatch(l -> "Migration health".equals(l.getJobName()) && "0".equals(l.getStatus())));
    ok(request("DELETE", "/admin/v1/ruoyi/jobs/" + job.path("id").asString(), admin, null));
  }

  @Test
  void executableJarBootsAndLoadsBothMapperFamilies() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var command = new java.util.ArrayList<String>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.add("-Xmx512m");
    command.add("-jar");
    command.add(java.nio.file.Path.of("target/ruoyi-admin.jar").toAbsolutePath().toString());
    for (String arg : appArgs)
      command.add(arg.equals("--server.port=0") ? "--server.port=" + port : arg);
    var log = java.nio.file.Path.of("target/jar-smoke.log");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      String url = "http://127.0.0.1:" + port;
      boolean ready = false;
      long end = System.currentTimeMillis() + 60000;
      while (process.isAlive() && System.currentTimeMillis() < end) {
        try {
          var response =
              http.send(
                  HttpRequest.newBuilder(URI.create(url + "/actuator/health"))
                      .timeout(java.time.Duration.ofSeconds(2))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 200) {
            ready = true;
            break;
          }
        } catch (java.io.IOException ex) {
        }
        Thread.sleep(200);
      }
      assertTrue(ready, () -> "Executable JAR did not start; inspect " + log.toAbsolutePath());
      var login =
          http.send(
              HttpRequest.newBuilder(URI.create(url + "/admin/v1/auth/sessions"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          json.writeValueAsString(
                              java.util.Map.of("username", "admin", "password", password))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, login.statusCode(), login.body());
      String token = json.readTree(login.body()).path("data").path("accessToken").asString();
      var users =
          http.send(
              HttpRequest.newBuilder(URI.create(url + "/admin/v1/ruoyi/users"))
                  .header("Authorization", "Bearer " + token)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, users.statusCode(), users.body());
      var config =
          http.send(
              HttpRequest.newBuilder(URI.create(url + "/api/v1/app-config")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, config.statusCode(), config.body());
      assertEquals(0, json.readTree(config.body()).path("code").asInt());
    } finally {
      process.destroy();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @AfterAll
  void cleanup() throws Exception {
    if (app != null) app.close();
    if (redis != null) redis.stop();
    if (server != null) {
      server.createStatement().execute("DROP DATABASE " + database);
      server.close();
    }
  }
}
