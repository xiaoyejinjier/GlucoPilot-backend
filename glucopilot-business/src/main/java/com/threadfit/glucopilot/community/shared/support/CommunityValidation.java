package com.threadfit.glucopilot.community.shared.support;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;

import java.util.*;

/** 社区业务共用的参数校验，不持有业务状态或执行数据库写入。 */
public final class CommunityValidation {
    private CommunityValidation() {}

    public static BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "内容或用户不存在");
    }

    public static void page(PageQuery q) {
        if (q.getPageNum() < 1 || q.getPageSize() < 1 || q.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页条数应为1～50");
    }

    public static String listKeyword(String keyword, int max) {
        String term = Objects.toString(keyword, "").trim();
        if (term.length() > max) throw new BizException(ErrorCode.BAD_REQUEST, "搜索词最多" + max + "字");
        return term;
    }

    public static String validateText(String content, int max, String key) {
        String result = content == null ? "" : content.trim();
        if (result.isEmpty()
                || result.length() > max
                || key == null
                || !key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BizException(ErrorCode.BAD_REQUEST, "正文或请求标识不合法");
        return result;
    }
}
