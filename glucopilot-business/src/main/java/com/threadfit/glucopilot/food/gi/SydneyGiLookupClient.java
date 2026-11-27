package com.threadfit.glucopilot.food.gi;

import com.threadfit.glucopilot.ai.business.translation.service.EnglishTranslator;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

/**
 * 只检索悉尼大学公开GI数据库。模型仅翻译名称，不允许模型生成GI数值或决定来源URL。
 * 仅接受名称完整匹配、无未指定品牌、健康组且样本数不少于10人的条目；冲突不取平均、不取首条。
 */
@Component
public class SydneyGiLookupClient {
    public static final String SOURCE = "https://glycemicindex.com/gi-search/";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final EnglishTranslator translator;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private List<Entry> cached = List.of();
    private long expiresAt;

    public SydneyGiLookupClient(EnglishTranslator translator) { this.translator = translator; }

    public FoodGiResult lookup(String foodName) throws IOException, InterruptedException {
        if (foodName == null || foodName.isBlank() || foodName.length() > 64)
            return FoodGiResult.unknown("食物名称不足以匹配GI试验食品");
        List<Entry> entries = entries();
        String name = foodName;
        if (name.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
            var translated = translator.translate("FOOD_GI_NAME", Map.of("name", foodName));
            name = translated == null ? null : translated.get("name");
        }
        if (name == null || name.length() > 200 || !name.matches("[A-Za-z0-9 ,()./'®+%&;-]+"))
            return FoodGiResult.unknown("未获得可精确匹配的食物英文名称，等待人工核对");
        return match(entries, name);
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    static FoodGiResult match(List<Entry> entries, String name) {
        var matches = entries.stream().filter(entry -> normalize(entry.name()).equals(normalize(name)))
                .filter(entry -> entry.manufacturer().isBlank())
                .filter(entry -> "normal".equalsIgnoreCase(entry.subjects()) && entry.sampleSize() >= 10)
                .toList();
        var values = matches.stream().map(Entry::value).map(BigDecimal::stripTrailingZeros).distinct().toList();
        if (values.size() != 1)
            return FoodGiResult.unknown(matches.isEmpty() ? "未找到名称、做法和品牌可可靠匹配的GI研究"
                    : "同名试验GI存在差异，等待补充做法或人工核对");
        Entry entry = matches.getFirst();
        return new FoodGiResult("REFERENCE", entry.value(), entry.name(), SOURCE,
                "公开数据库文献参考；适用原试验食品，不代表当前食品实测。研究：" + entry.reference());
    }

    private synchronized List<Entry> entries() throws IOException, InterruptedException {
        if (System.currentTimeMillis() < expiresAt && !cached.isEmpty()) return cached;
        var request = HttpRequest.newBuilder(URI.create(SOURCE)).timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html").header("User-Agent", "GlucoPilot-GI-Reference/1.0").GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("GI source unavailable");
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("GI source exceeded size limit");
            List<Entry> parsed = parse(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.size() < 10) throw new IOException("GI source format changed");
            cached = List.copyOf(parsed);
            expiresAt = System.currentTimeMillis() + Duration.ofHours(6).toMillis();
            return cached;
        }
    }

    record Entry(String name, BigDecimal value, String manufacturer, String subjects, int sampleSize, String reference) {}

    /** 标准HTML解析器读取表格文本，不执行网页脚本，也不使用页面提供的任意外链。 */
    static List<Entry> parse(String html) throws IOException {
        List<Entry> entries = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row = new ArrayList<>();
            StringBuilder cell;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                if (tag == HTML.Tag.TR) row = new ArrayList<>();
                if (tag == HTML.Tag.TD) cell = new StringBuilder();
            }
            @Override public void handleText(char[] text, int position) {
                if (cell != null) cell.append(text).append(' ');
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.TD && cell != null) {
                    row.add(HtmlUtils.htmlUnescape(cell.toString()).replaceAll("\\s+", " ").trim());
                    cell = null;
                }
                if (tag != HTML.Tag.TR || row.size() < 13) return;
                try {
                    var gi = new BigDecimal(row.get(1));
                    if (gi.signum() <= 0 || gi.compareTo(new BigDecimal("200")) > 0) return;
                    entries.add(new Entry(row.get(0), gi, row.get(2), row.get(9),
                            Integer.parseInt(row.get(11)), row.get(8).substring(0, Math.min(600, row.get(8).length()))));
                } catch (NumberFormatException ignored) { /* 不完整值、范围值和非标准样本数不猜测。 */ }
            }
        }, true);
        return entries;
    }
}
