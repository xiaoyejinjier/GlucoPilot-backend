package com.threadfit.glucopilot.community.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量 cms_news 标题拼音分批回填；启动时跑一批，之后定时补漏。
 */
@Component
public class TitlePinyinBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TitlePinyinBackfillRunner.class);
    private static final int BATCH = 200;

    private final CmsNewsMapper newsMapper;

    public TitlePinyinBackfillRunner(CmsNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillOnce();
    }

    @Scheduled(fixedDelayString = "${community.title-pinyin.backfill-interval-ms:300000}")
    public void scheduledBackfill() {
        backfillOnce();
    }

    void backfillOnce() {
        List<CmsNews> rows = newsMapper.selectList(new LambdaQueryWrapper<CmsNews>()
                .isNull(CmsNews::getTitlePinyin)
                .eq(CmsNews::getIsDeleted, 0)
                .orderByAsc(CmsNews::getId)
                .last("LIMIT " + BATCH));
        if (rows.isEmpty()) {
            return;
        }
        int updated = 0;
        for (CmsNews row : rows) {
            String title = row.getTitle() == null ? "" : row.getTitle();
            row.setTitlePinyin(TitlePinyin.full(title));
            row.setTitlePinyinInitials(TitlePinyin.initials(title));
            updated += newsMapper.updateById(row);
        }
        log.info("title pinyin backfill updated {}/{} rows", updated, rows.size());
    }
}
