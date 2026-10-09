package com.threadfit.glucopilot.common.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移文件的静态体检（不连数据库、不启动 Spring）。
 * <p>
 * <b>为什么值得单独一个测试</b>：迁移版本号重复是「编译期完全看不出来、启动时才炸」的典型，
 * 而且报错发生在 Flyway 初始化阶段，现场是 {@code Unable to start embedded Tomcat} 之类，
 * 很容易被误判成 Web 层问题。本项目已经真实发生过两次：
 * <ol>
 *   <li>{@code V4__widen_user_agent.sql} 与 {@code V4__ruoyi_full_system.sql} 撞车
 *       （Flyway 报 Found more than one migration with version 4）；</li>
 *   <li>两条分叉分支合并后，{@code V7__ruoyi_full_system.sql} 与
 *       {@code V7__alter_media_file_storage_provider.sql} 再次撞车——
 *       两个提交各自都没错，是合并把两个 V7 凑到了同一个 HEAD 上。</li>
 * </ol>
 * 这类冲突的根因是「多人各自挑下一个版本号」，靠人眼 review diff 很难发现，
 * 但用一个测试拦住几乎零成本。
 * <p>
 * <b>注意本测试只检查「版本号唯一」</b>，不检查连续性：合并、回退、重编号都会合法地留下空档，
 * 断言连续会变成噪声。真正要守的不变量只有「同一版本号只能有一个文件」。
 */
class MigrationFilesTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** {@code V<版本>__<描述>.sql}；版本允许 {@code 1}、{@code 1.1}、{@code 1_1} 三种写法。 */
    private static final Pattern MIGRATION = Pattern.compile("^V([0-9][0-9._]*)__.+$");

    @Test
    void 迁移版本号不得重复() throws IOException {
        Map<String, List<String>> byVersion = new HashMap<>();
        int total = 0;

        try (Stream<Path> stream = Files.list(MIGRATION_DIR)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".sql")) {
                    continue;
                }
                Matcher matcher = MIGRATION.matcher(name);
                if (!matcher.matches()) {
                    // 不合 Flyway 命名规范的文件（如 README）跳过，由人工约定保证；这里不误伤
                    continue;
                }
                total++;
                // Flyway 把 1_1 与 1.1 都解析成版本 1.1，因此归一化后再比
                String version = matcher.group(1).replace('_', '.');
                byVersion.computeIfAbsent(version, key -> new ArrayList<>()).add(name);
            }
        }

        assertFalse(total == 0, "没找到任何迁移文件，工作目录是否不是 backend/？");
        assertTrue(total == byVersion.values().stream().mapToInt(List::size).sum(), "统计逻辑异常");

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V" + entry.getKey() + " → " + entry.getValue())
                .sorted()
                .toList();

        assertTrue(duplicates.isEmpty(),
                "迁移版本号重复，Flyway 会报 『Found more than one migration with version N』、应用直接起不来。"
                        + "处理方式：保留已经应用到数据库的那一个，把未应用的那个改成下一个可用版本号"
                        + "（可查 flyway_schema_history 确认哪个真的应用过）。重复项：" + duplicates);
    }

    /**
     * 迁移文件必须放在 {@code classpath:db/migration}，与 {@code spring.flyway.locations} 一致。
     * 放错目录不会报错，只是「迁移静默不生效」——比报错更难查。
     */
    @Test
    void 迁移目录应与Flyway配置一致() {
        assertTrue(Files.isDirectory(MIGRATION_DIR),
                "迁移目录不存在：" + MIGRATION_DIR.toAbsolutePath());
    }
}
