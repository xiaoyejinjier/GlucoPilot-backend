package com.threadfit.glucopilot.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.model.BaseEntity;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.content.entity.CmsBanner;
import com.threadfit.glucopilot.content.entity.CmsNews;
import com.threadfit.glucopilot.content.entity.FoodCategory;
import com.threadfit.glucopilot.content.entity.FoodNutrition;
import com.threadfit.glucopilot.content.mapper.CmsBannerMapper;
import com.threadfit.glucopilot.content.mapper.CmsNewsMapper;
import com.threadfit.glucopilot.content.mapper.FoodCategoryMapper;
import com.threadfit.glucopilot.content.mapper.FoodNutritionMapper;
import com.threadfit.glucopilot.content.dto.AdminCmsDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminCmsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] CHANNELS = {"", "企业动态", "行业快讯", "设备新知", "控糖科普"};

    private final CmsBannerMapper bannerMapper;
    private final CmsNewsMapper newsMapper;
    private final FoodCategoryMapper foodCategoryMapper;
    private final FoodNutritionMapper foodNutritionMapper;

    public List<AdminCmsDtos.NewsItem> listNews() {
        return newsMapper.selectList(new LambdaQueryWrapper<CmsNews>()
                        .orderByDesc(CmsNews::getId))
                .stream().map(this::toNews).toList();
    }

    @Transactional
    public AdminCmsDtos.NewsItem saveNews(AdminCmsDtos.NewsSaveRequest req) {
        CmsNews row = req.getId() == null ? new CmsNews() : requireNews(req.getId());
        row.setTitle(req.getTitle().trim());
        row.setSummaryText(req.getSummary());
        row.setChannelCode(channelCode(req.getChannel()));
        row.setContentType("video".equalsIgnoreCase(req.getType()) ? 2 : 1);
        row.setCoverUrl(req.getCoverUrl());
        row.setVideoUrl(req.getVideoUrl());
        row.setContentBody(req.getContent());
        int status = statusCode(req.getStatus());
        row.setPublishStatus(status);
        LocalDateTime publishAt = parseDateTime(req.getPublishAt());
        if (publishAt == null) {
            publishAt = LocalDateTime.now();
        }
        row.setPublishAt(publishAt);
        if (row.getLikeCount() == null) {
            row.setLikeCount(0);
        }
        if (row.getCollectCount() == null) {
            row.setCollectCount(0);
        }
        if (req.getId() == null) {
            newsMapper.insert(row);
        } else {
            newsMapper.updateById(row);
        }
        return toNews(requireNews(row.getId()));
    }

    @Transactional
    public void removeNews(Long id) {
        softDelete(newsMapper, requireNews(id).getId(), CmsNews::getId, CmsNews::getIsDeleted, CmsNews::getDeletedAt, CmsNews::getDeletedBy);
    }

    @Transactional
    public void setNewsStatus(Long id, String status) {
        CmsNews row = requireNews(id);
        row.setPublishStatus(statusCode(status));
        if (row.getPublishStatus() == 1 && row.getPublishAt() == null) {
            row.setPublishAt(LocalDateTime.now());
        }
        newsMapper.updateById(row);
    }

    public List<AdminCmsDtos.BannerItem> listBanners() {
        return bannerMapper.selectList(new LambdaQueryWrapper<CmsBanner>()
                        .orderByAsc(CmsBanner::getSortNo)
                        .orderByDesc(CmsBanner::getId))
                .stream().map(this::toBanner).toList();
    }

    @Transactional
    public AdminCmsDtos.BannerItem saveBanner(AdminCmsDtos.BannerSaveRequest req) {
        CmsBanner row = req.getId() == null ? new CmsBanner() : requireBanner(req.getId());
        row.setTitle(req.getTitle().trim());
        row.setImageUrl(req.getImage());
        row.setSubtitle(req.getRemark());
        row.setLinkUrl(resolveLinkUrl(req.getLinkType(), req.getLinkValue()));
        row.setSortNo(req.getSort() == null ? 0 : req.getSort());
        row.setShelfStatus(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        if (req.getId() == null) {
            bannerMapper.insert(row);
        } else {
            bannerMapper.updateById(row);
        }
        return toBanner(requireBanner(row.getId()));
    }

    @Transactional
    public void removeBanner(Long id) {
        softDelete(bannerMapper, requireBanner(id).getId(),
                CmsBanner::getId, CmsBanner::getIsDeleted, CmsBanner::getDeletedAt, CmsBanner::getDeletedBy);
    }

    public List<AdminCmsDtos.FoodCategoryItem> listCategories() {
        return foodCategoryMapper.selectList(new LambdaQueryWrapper<FoodCategory>()
                        .orderByAsc(FoodCategory::getSortNo)
                        .orderByAsc(FoodCategory::getId))
                .stream()
                .map(c -> AdminCmsDtos.FoodCategoryItem.builder()
                        .id(c.getId())
                        .name(c.getCategoryName())
                        .sort(c.getSortNo())
                        .build())
                .toList();
    }

    @Transactional
    public AdminCmsDtos.FoodCategoryItem saveCategory(AdminCmsDtos.FoodCategorySaveRequest req) {
        FoodCategory row = req.getId() == null ? new FoodCategory() : requireCategory(req.getId());
        row.setCategoryName(req.getName().trim());
        row.setSortNo(req.getSort() == null ? 0 : req.getSort());
        if (row.getIsEnabled() == null) {
            row.setIsEnabled(1);
        }
        if (req.getId() == null) {
            foodCategoryMapper.insert(row);
        } else {
            foodCategoryMapper.updateById(row);
        }
        return AdminCmsDtos.FoodCategoryItem.builder()
                .id(row.getId())
                .name(row.getCategoryName())
                .sort(row.getSortNo())
                .build();
    }

    @Transactional
    public void removeCategory(Long id) {
        long used = foodNutritionMapper.selectCount(new LambdaQueryWrapper<FoodNutrition>()
                .eq(FoodNutrition::getCategoryId, id));
        if (used > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "该分类下仍有食物，请先移动或删除食物");
        }
        softDelete(foodCategoryMapper, requireCategory(id).getId(),
                FoodCategory::getId, FoodCategory::getIsDeleted, FoodCategory::getDeletedAt, FoodCategory::getDeletedBy);
    }

    public List<AdminCmsDtos.FoodItem> listFoods() {
        Map<Long, String> catNames = foodCategoryMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(FoodCategory::getId, FoodCategory::getCategoryName, (a, b) -> a));
        return foodNutritionMapper.selectList(new LambdaQueryWrapper<FoodNutrition>()
                        .orderByDesc(FoodNutrition::getId))
                .stream().map(f -> toFood(f, catNames)).toList();
    }

    @Transactional
    public AdminCmsDtos.FoodItem saveFood(AdminCmsDtos.FoodSaveRequest req) {
        FoodCategory cat = requireCategory(req.getCategoryId());
        FoodNutrition row = req.getId() == null ? new FoodNutrition() : requireFood(req.getId());
        row.setFoodName(req.getName().trim());
        row.setCategoryId(cat.getId());
        row.setCategoryCode(cat.getId().intValue() <= 9 ? cat.getId().intValue() : 9);
        row.setCarbsPer100g(req.getCarbs() == null ? BigDecimal.ZERO : req.getCarbs());
        row.setCaloriesPer100g(req.getCal() == null ? BigDecimal.ZERO : req.getCal());
        row.setGiValue(req.getGi());
        row.setImageUrl(req.getImage());
        row.setShelfStatus(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        if (req.getId() == null) {
            foodNutritionMapper.insert(row);
        } else {
            foodNutritionMapper.updateById(row);
        }
        return toFood(requireFood(row.getId()), Map.of(cat.getId(), cat.getCategoryName()));
    }

    @Transactional
    public void removeFood(Long id) {
        softDelete(foodNutritionMapper, requireFood(id).getId(),
                FoodNutrition::getId, FoodNutrition::getIsDeleted, FoodNutrition::getDeletedAt, FoodNutrition::getDeletedBy);
    }

    private static <T extends BaseEntity> void softDelete(
            BaseMapper<T> mapper,
            Long id,
            SFunction<T, ?> idGetter,
            SFunction<T, Integer> isDeletedGetter,
            SFunction<T, LocalDateTime> deletedAtGetter,
            SFunction<T, Long> deletedByGetter) {
        SoftDeletes.mark(mapper, idGetter, id, isDeletedGetter, deletedAtGetter, deletedByGetter);
    }

    private AdminCmsDtos.NewsItem toNews(CmsNews n) {
        int ch = n.getChannelCode() == null ? 0 : n.getChannelCode();
        String channel = ch >= 1 && ch < CHANNELS.length ? CHANNELS[ch] : "资讯";
        String status = switch (n.getPublishStatus() == null ? 0 : n.getPublishStatus()) {
            case 1 -> "published";
            case 2 -> "unpublished";
            default -> "draft";
        };
        return AdminCmsDtos.NewsItem.builder()
                .id(n.getId())
                .title(n.getTitle())
                .summary(n.getSummaryText())
                .channel(channel)
                .type(n.getContentType() != null && n.getContentType() == 2 ? "video" : "article")
                .coverUrl(n.getCoverUrl() == null ? "" : n.getCoverUrl())
                .coverText(n.getContentType() != null && n.getContentType() == 2 ? "▶" : "资讯")
                .videoUrl(n.getVideoUrl())
                .content(n.getContentBody() == null ? "" : n.getContentBody())
                .status(status)
                .publishAt(fmt(n.getPublishAt()))
                .views(0)
                .likes(n.getLikeCount() == null ? 0 : n.getLikeCount())
                .collects(n.getCollectCount() == null ? 0 : n.getCollectCount())
                .createdAt(fmt(n.getGmtCreate()))
                .updatedAt(fmt(n.getGmtModified()))
                .build();
    }

    private AdminCmsDtos.BannerItem toBanner(CmsBanner b) {
        String link = b.getLinkUrl() == null ? "" : b.getLinkUrl();
        boolean article = link.matches("^\\d+$");
        return AdminCmsDtos.BannerItem.builder()
                .id(b.getId())
                .title(b.getTitle())
                .image(b.getImageUrl() == null ? "" : b.getImageUrl())
                .linkType(article ? "article" : "url")
                .linkValue(link)
                .sort(b.getSortNo())
                .enabled(b.getShelfStatus() != null && b.getShelfStatus() == 1)
                .remark(b.getSubtitle() == null ? "" : b.getSubtitle())
                .build();
    }

    private AdminCmsDtos.FoodItem toFood(FoodNutrition f, Map<Long, String> catNames) {
        return AdminCmsDtos.FoodItem.builder()
                .id(f.getId())
                .name(f.getFoodName())
                .categoryId(f.getCategoryId())
                .categoryName(f.getCategoryId() == null ? "" : catNames.getOrDefault(f.getCategoryId(), ""))
                .carbs(f.getCarbsPer100g())
                .cal(f.getCaloriesPer100g())
                .gi(f.getGiValue())
                .image(f.getImageUrl() == null ? "" : f.getImageUrl())
                .description("")
                .enabled(f.getShelfStatus() == null || f.getShelfStatus() == 1)
                .updatedAt(fmt(f.getGmtModified()))
                .build();
    }

    private CmsNews requireNews(Long id) {
        CmsNews row = newsMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资讯不存在");
        }
        return row;
    }

    private CmsBanner requireBanner(Long id) {
        CmsBanner row = bannerMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Banner 不存在");
        }
        return row;
    }

    private FoodCategory requireCategory(Long id) {
        FoodCategory row = foodCategoryMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        return row;
    }

    private FoodNutrition requireFood(Long id) {
        FoodNutrition row = foodNutritionMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "食物不存在");
        }
        return row;
    }

    private static int channelCode(String channel) {
        for (int i = 1; i < CHANNELS.length; i++) {
            if (CHANNELS[i].equals(channel)) {
                return i;
            }
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "频道不正确");
    }

    private static int statusCode(String status) {
        if (!StringUtils.hasText(status) || "draft".equalsIgnoreCase(status)) {
            return 0;
        }
        if ("published".equalsIgnoreCase(status)) {
            return 1;
        }
        if ("unpublished".equalsIgnoreCase(status)) {
            return 2;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "状态不正确");
    }

    private static String resolveLinkUrl(String linkType, String linkValue) {
        return linkValue == null ? "" : linkValue.trim();
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim().replace('T', ' ');
        try {
            if (s.length() == 16) {
                return LocalDateTime.parse(s, FMT);
            }
            if (s.length() >= 19) {
                return LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
