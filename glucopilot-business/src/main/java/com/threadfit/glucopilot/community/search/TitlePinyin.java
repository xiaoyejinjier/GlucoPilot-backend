package com.threadfit.glucopilot.community.search;

import com.github.promeg.pinyinhelper.Pinyin;

/**
 * 社区标题拼音：全拼无调小写连写、声母小写连写。多音字取库默认读音。
 */
public final class TitlePinyin {

    private TitlePinyin() {}

    public static String full(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(title.length() * 4);
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Pinyin.isChinese(c)) {
                String py = Pinyin.toPinyin(c);
                if (py != null && !py.isEmpty()) {
                    out.append(py.toLowerCase());
                }
            } else if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return truncate(out.toString(), 512);
    }

    public static String initials(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Pinyin.isChinese(c)) {
                String py = Pinyin.toPinyin(c);
                if (py != null && !py.isEmpty()) {
                    out.append(Character.toLowerCase(py.charAt(0)));
                }
            } else if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return truncate(out.toString(), 128);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
