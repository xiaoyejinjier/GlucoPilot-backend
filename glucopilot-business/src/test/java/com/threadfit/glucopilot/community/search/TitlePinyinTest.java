package com.threadfit.glucopilot.community.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitlePinyinTest {

    @Test
    void sugarMapsToTang() {
        assertEquals("tang", TitlePinyin.full("糖"));
        assertEquals("t", TitlePinyin.initials("糖"));
    }

    @Test
    void dietControlSugar() {
        assertEquals("yinshikongtang", TitlePinyin.full("饮食控糖"));
        assertEquals("yskt", TitlePinyin.initials("饮食控糖"));
        assertTrue(TitlePinyin.full("饮食控糖").contains("tang"));
        assertTrue(TitlePinyin.initials("饮食控糖").contains("kt"));
    }

    @Test
    void blankSafe() {
        assertEquals("", TitlePinyin.full(null));
        assertEquals("", TitlePinyin.initials("   "));
    }
}
