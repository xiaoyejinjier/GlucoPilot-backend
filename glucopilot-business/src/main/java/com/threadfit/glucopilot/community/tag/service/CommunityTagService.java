package com.threadfit.glucopilot.community.tag.service;

import static com.threadfit.glucopilot.community.shared.support.ProjectionValues.*;
import static com.threadfit.glucopilot.community.tag.dto.CommunityTagDtos.*;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.community.tag.mapper.CommunityTagMapper;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;

@Service
public class CommunityTagService {
    private final CommunityTagMapper mapper;

    public CommunityTagService(CommunityTagMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<Row> list(String keyword, String type, Integer enabled, PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50)
            throw bad("每页1～50条");
        String name = Objects.toString(keyword, "").trim();
        if (name.length() > 32
                || (type != null && !type.isBlank() && !Set.of("OFFICIAL", "USER").contains(type))
                || (enabled != null && enabled != 0 && enabled != 1)) throw bad("筛选条件不合法");
        long total = mapper.countTags(name, type, enabled);
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                total,
                mapper.selectTags(name, type, enabled, page.getPageSize(), page.offset()).stream()
                        .map(
                                rs ->
                                        new Row(
                                                text(rs, "id"),
                                                text(rs, "code"),
                                                text(rs, "name"),
                                                text(rs, "code").startsWith("custom_")
                                                        ? "USER"
                                                        : "OFFICIAL",
                                                intValue(rs, "sort_no"),
                                                intValue(rs, "enabled"),
                                                longValue(rs, "article_count")))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    }

    private Row get(String code) {
        return mapper.selectTag(code).stream()
                .map(
                        rs ->
                                new Row(
                                        text(rs, "id"),
                                        text(rs, "code"),
                                        text(rs, "name"),
                                        text(rs, "code").startsWith("custom_")
                                                ? "USER"
                                                : "OFFICIAL",
                                        intValue(rs, "sort_no"),
                                        intValue(rs, "enabled"),
                                        longValue(rs, "article_count")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                .stream()
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "标签不存在"));
    }

    private String name(Change request) {
        if (request == null
                || request.sortNo() < 0
                || request.sortNo() > 9999
                || (request.enabled() != 0 && request.enabled() != 1)) throw bad("标签设置不合法");
        String name =
                Normalizer.normalize(Objects.toString(request.name(), ""), Normalizer.Form.NFKC)
                        .trim()
                        .replaceAll("\\s+", " ");
        if (!name.matches("[\\p{L}\\p{N} _-]{1,16}")) throw bad("标签限1～16字，可用中文、字母和数字");
        return name;
    }

    private void lockTags() {
        mapper.lockTags();
    }

    @Transactional
    public Row create(Change request) {
        String name = name(request);
        lockTags();
        var existing = mapper.selectByName(name);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!row.get("code").toString().startsWith("custom_")
                    && Objects.equals(row.get("name"), name)
                    && ((Number) row.get("sort_no")).intValue() == request.sortNo()
                    && ((Number) row.get("enabled")).intValue() == request.enabled())
                return get(row.get("code").toString());
            throw new BizException(ErrorCode.CONFLICT, "同名标签已存在，请编辑或恢复原标签");
        }
        String code = "topic_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        mapper.insertTag(code, name, request.sortNo(), request.enabled());
        return get(code);
    }

    @Transactional
    public Row update(String code, Change request) {
        String name = name(request);
        lockTags();
        var prior = get(code);
        if (prior.type().equals("USER") && !name.equals(prior.name()))
            throw bad("用户标签不能改名，可排序、删除或恢复");
        if (code.equals("other") && (!name.equals("其他") || request.enabled() != 1))
            throw bad("其他分类必须保留，不能改名或删除");
        if (mapper.countOtherName(name, code) > 0)
            throw new BizException(ErrorCode.CONFLICT, "同名标签已存在");
        mapper.updateTag(name, request.sortNo(), request.enabled(), prior.code());
        return get(code);
    }

    @Transactional
    public void remove(String code) {
        lockTags();
        get(code);
        if (code.equals("other")) throw bad("其他分类必须保留，不能删除");
        mapper.disableTag(code);
    }

    private static BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
