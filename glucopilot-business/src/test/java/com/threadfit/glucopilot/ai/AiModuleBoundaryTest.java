package com.threadfit.glucopilot.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 依赖方向的守卫：{@code ai} 包<b>只能被别的模块依赖，不能反向依赖业务模块</b>。
 *
 * <h2>为什么需要一条测试</h2>
 *
 * 「能力收束到 ai 模块」这件事最容易在半年后失效：某天为了省一次查询，有人在 ai 里
 * {@code import community.article.CommunityArticle}，编译照过、测试照绿，等到社区模块想单独演进时才发现 拆不动了。这类回归无法靠 code review 兜住，只能靠一条会红的测试。
 *
 * <h2>允许与禁止</h2>
 *
 * <ul>
 *   <li>允许：{@code community} / {@code localization} 依赖 {@code ai}（它们是调用方）；
 *   <li>禁止：{@code ai} 依赖 {@code com.threadfit.glucopilot.community} 或 {@code
 *       .localization}（能力侧不该知道文章、评论、缓存表长什么样）。
 * </ul>
 *
 * 检查的是<b>源码 import 语句</b>而不是编译产物：全限定名裸写（{@code
 * com.threadfit.glucopilot.community.X.Y}）同样会被抓到，因为匹配的是包前缀而非 import 关键字。
 */
class AiModuleBoundaryTest {

    /** 能力侧不许出现的包前缀。 */
    private static final List<String> FORBIDDEN = List.of(
            "com.threadfit.glucopilot.community", "com.threadfit.glucopilot.localization");

    private static final Path AI_SOURCES =
            Path.of("src/main/java/com/threadfit/glucopilot/ai");

    @Test
    void ai模块不得反向依赖业务模块() throws IOException {
        assertTrue(
                Files.isDirectory(AI_SOURCES),
                "找不到 ai 源码目录（测试的工作目录应当是 glucopilot-business）：" + AI_SOURCES.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(AI_SOURCES)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> collectViolations(p, violations));
        }

        assertTrue(
                violations.isEmpty(),
                "ai 模块出现了对业务模块的依赖，能力层必须与业务实现解耦：\n"
                        + String.join("\n", violations));
    }

    private static void collectViolations(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String prefix : FORBIDDEN) {
                if (Pattern.compile("\\b" + Pattern.quote(prefix) + "\\b").matcher(line).find()) {
                    violations.add(
                            AI_SOURCES.relativize(file) + ":" + (i + 1) + " " + line.trim());
                    break;
                }
            }
        }
    }

    /**
     * 反向确认：守卫本身必须能抓到违规。
     *
     * <p>没有这条自测，守卫可能因为路径写错、正则空转而永远绿——那种测试比没有更糟。
     */
    @Test
    void 守卫能识别出违规写法() {
        String bad = "import com.threadfit.glucopilot.community.article.Article;";
        assertFalse(FORBIDDEN.stream().noneMatch(bad::contains), "守卫必须能识别社区模块依赖");

        String good = "import com.threadfit.glucopilot.ai.business.moderation.dto.ModerationVerdict;";
        assertTrue(FORBIDDEN.stream().noneMatch(good::contains), "ai 内部互相引用不算违规");
    }
}
