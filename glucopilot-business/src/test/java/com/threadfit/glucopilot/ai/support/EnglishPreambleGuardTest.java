package com.threadfit.glucopilot.ai.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link EnglishPreambleGuard} 的判定边界。
 * <p>
 * 这个清理器改的是<b>用户可见的正文</b>，误删比漏删更糟（漏删只是留一句英文，误删是丢内容），
 * 所以下面既有"必须删"的用例，也有成组的"必须原样保留"用例——
 * 后者才是重点：{@code HbA1c}、{@code 7.7 mmol/L} 这类以英文词/数字开头的正常回答一个都不能伤。
 */
class EnglishPreambleGuardTest {

    private final EnglishPreambleGuard guard = new EnglishPreambleGuard();

    // ---------- 必须删 ----------

    @Test
    void 开头的英文整句应被删除_实测样例1() {
        assertEquals("本月（10 月）血糖情况分析如下",
                guard.apply("I'll check your monthly glucose stats.本月（10 月）血糖情况分析如下"));
    }

    @Test
    void 开头的英文整句应被删除_实测样例2() {
        assertEquals("这个月（10月）的血糖情况如下",
                guard.apply("I'll check your records for this month.这个月（10月）的血糖情况如下"));
    }

    @Test
    void 英文句子后带空格也应删除() {
        assertEquals("你的记录如下", guard.apply("Let me check that for you. 你的记录如下"));
    }

    @Test
    void 英文句末用中文句号也应删除() {
        assertEquals("这个月的血糖如下", guard.apply("I'll check your monthly records。这个月的血糖如下"));
    }

    @Test
    void 问号与感叹号结尾的英文句也应删除() {
        assertEquals("需要先查一下记录", guard.apply("Should I check your records?需要先查一下记录"));
        assertEquals("我来查一下", guard.apply("Let me pull that up!我来查一下"));
    }

    @Test
    void 多句英文铺垫都会被删掉() {
        assertEquals("这个月的记录我帮您梳理了一下",
                guard.apply("Let me check your profile. And your monthly records too.这个月的记录我帮您梳理了一下"));
    }

    // ---------- 必须原样保留 ----------

    @Test
    void 以英文缩写开头的正常回答不应被删() {
        String reply = "HbA1c 是糖化血红蛋白，反映近 3 个月的平均血糖。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 以数值加单位开头的正常回答不应被删() {
        String reply = "7.7 mmol/L 属于偏高，建议留意餐后血糖。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 以数值加单位加中文句号开头也不应被删() {
        // 前缀 "7.7 mmol/L" 只有两个词，且句号是小数点：两种情况都要挡住
        String reply = "7.7 mmol/L。这个数值偏高，建议复查。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 全中文回答不应被改动() {
        String reply = "我来帮您查一下最近的血糖记录。您的平均血糖是 7.7 mmol/L。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 本来就没有中文的纯英文回答不应被改动() {
        // 用户要的就是英文（或模型另有问题），不归这个清理器管
        String reply = "Your average glucose was 7.7 mmol/L.";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 英文前缀过长时不应被当作铺垫删除() {
        // 前缀超过上限，说明整段都是英文（不是一句铺垫），砍掉会丢内容
        String english = "I will carefully review every single glucose record you have logged over the past month "
                + "and then summarise the trends in detail, comparing morning, noon and evening values, "
                + "and finally explain what they might mean for your daily routine and diet";
        String reply = english + "。这段其实很长，已经是正文的一部分了。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 开头的单个英文词不应被删() {
        String reply = "BMI 是身体质量指数，可以用来粗略评估体重情况。";
        assertSame(reply, guard.apply(reply));
    }

    @Test
    void 空值与空串应原样返回() {
        assertNull(guard.apply(null));
        assertEquals("", guard.apply(""));
    }

    @Test
    void 英文前缀紧贴中文且以字母结尾时不应被删() {
        // 没有句末标点 → 不是完整句子，可能只是"专有名词 + 中文"的正常拼接
        String reply = "GI 值低意味着升糖慢。";
        assertSame(reply, guard.apply(reply));
    }
}
