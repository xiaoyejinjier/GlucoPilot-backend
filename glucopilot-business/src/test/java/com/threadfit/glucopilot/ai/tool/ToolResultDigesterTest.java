package com.threadfit.glucopilot.ai.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果摘要（B3 后半）。
 * <p>
 * 目标是"让模型记得上一轮查到了什么"，而不是"备份一份数据"。因此这里钉三条：
 * ① 标量字段（avg/count/recordedDays 这类关键字段）必须留下；
 * ② 长数组只留前几项 + 总条数（避免半截 JSON 误导模型）；
 * ③ 无论如何不超过上限，且<b>不保存完整返回</b>。
 */
class ToolResultDigesterTest {

    private final ToolResultDigester digester = new ToolResultDigester();

    @Test
    void 保留标量字段并丢掉长数组() {
        String json = "{\"avg\":6.82,\"count\":12,\"recordedDays\":5,"
                + "\"highest\":9.1,\"lowest\":4.3,"
                + "\"records\":[{\"date\":\"2026-10-01\",\"value\":6.1},{\"date\":\"2026-10-02\",\"value\":6.9},"
                + "{\"date\":\"2026-10-03\",\"value\":7.2},{\"date\":\"2026-10-04\",\"value\":5.5}],"
                + "\"note\":null}";

        String digest = digester.digest(json, 512);

        assertTrue(digest.contains("avg:6.82"), digest);
        assertTrue(digest.contains("count:12"), digest);
        assertTrue(digest.contains("recordedDays:5"), digest);
        assertTrue(digest.contains("共4项"), "长数组要标注总条数，否则模型不知道被截断过：" + digest);
        assertFalse(digest.contains("2026-10-04"), "超出保留项数的明细不要：" + digest);
        assertFalse(digest.contains("note"), "空值字段应被丢掉：" + digest);
    }

    @Test
    void 超过上限时截断并留省略号() {
        StringBuilder json = new StringBuilder("{\"text\":\"");
        for (int i = 0; i < 2000; i++) {
            json.append('字');
        }
        json.append("\"}");

        String digest = digester.digest(json.toString(), 100);

        assertTrue(digest.length() <= 100, "无论如何不得超过上限，实际=" + digest.length());
        assertTrue(digest.contains("…"), "截断要留可见标记：" + digest);
        // 结构压缩已经把它压到 100 以内了（超长字符串本身先被压到 80 字），不必等到硬截断
        assertTrue(digest.startsWith("{text:"), digest);
    }

    @Test
    void 非JSON返回退化为文本压缩() {
        String digest = digester.digest("没查到   记录\n请换个时间范围", 512);

        assertEquals("没查到 记录 请换个时间范围", digest);
    }

    @Test
    void 空返回不生成摘要() {
        assertNull(digester.digest(null, 512));
        assertNull(digester.digest("   ", 512));
    }

    @Test
    void 深嵌套结构只保留浅层() {
        String json = "{\"a\":{\"b\":{\"c\":{\"d\":1,\"e\":2}},\"f\":3},\"g\":4}";

        String digest = digester.digest(json, 512);

        assertTrue(digest.contains("a:"));
        assertTrue(digest.contains("g:4"));
        // 第 3 层往下折叠成"字段数"，避免摘要被结构撑满
        assertFalse(digest.contains("\"d\""));
    }

    @Test
    void 上限被夹到最小值防止传0导致异常() {
        // 上限传 0/负数时夹到 32 字符：内容短就原样返回（不必凑长度），内容长就会被截断
        assertEquals("{a:1}", digester.digest("{\"a\":1}", 0));

        String longJson = "{\"text\":\"" + "字".repeat(100) + "\"}";
        assertTrue(digester.digest(longJson, -100).length() <= 32);
    }
}
