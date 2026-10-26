package com.threadfit.glucopilot.glucose.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.constant.GlucoseNodes;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.enums.CodeEnum.MediaBizType;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.mybatis.SoftDeletes;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseRecordMapper;
import com.threadfit.glucopilot.glucose.period.GlucosePeriodService;
import com.threadfit.glucopilot.file.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 血糖记录：按 (recordDate, nodeCode) 覆盖写入、查询、修改、逻辑删除与日看板。
 * 设备同步（device 模块）也走本服务的 upsertByNode，保证唯一键语义只有一处实现。
 */
@Service
@RequiredArgsConstructor
public class GlucoseRecordService {

    private static final BigDecimal VALUE_LOW = BigDecimal.ZERO;
    private static final BigDecimal VALUE_HIGH = new BigDecimal("40");
    private static final int REMARK_MAX = 256;

    private final GlucoseRecordMapper glucoseRecordMapper;
    private final GlucosePeriodService glucosePeriodService;
    private final MediaFileService mediaFileService;

    /** C 端覆盖写入：sourceType 缺省按手动。 */
    @Transactional
    public GlucoseDtos.UpsertResult upsertByNode(Long userId, GlucoseDtos.UpsertRequest request) {
        Integer sourceType = request.getSourceType() == null
                ? CodeEnum.GlucoseSourceType.MANUAL.getCode()
                : request.getSourceType();
        return upsertByNode(userId, request.getRecordDate(), request.getNodeCode(), request.getGlucoseValue(),
                sourceType, request.getRemark(), request.getMediaId(), null, null);
    }

    /**
     * 覆盖写入并返回主键与是否为新建。设备侧 externalKey 非空时追加到备注，便于对账。
     */
    @Transactional
    public GlucoseDtos.UpsertResult upsertByNode(
            Long userId,
            LocalDate recordDate,
            String nodeCode,
            BigDecimal glucoseValue,
            Integer sourceType,
            String remark,
            Long mediaId,
            LocalDateTime recordedAt,
            String externalKey) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (recordDate == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "recordDate 不能为空");
        }
        if (!GlucoseNodes.isGlucoseNode(nodeCode)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "nodeCode 必须是非餐点血糖节点");
        }
        assertValueInRange(glucoseValue);
        int resolvedSource = sourceType == null ? CodeEnum.GlucoseSourceType.MANUAL.getCode() : sourceType;
        CodeEnum.of(CodeEnum.GlucoseSourceType.class, resolvedSource);
        if (resolvedSource == CodeEnum.GlucoseSourceType.OCR.getCode() && mediaId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OCR 血糖记录必须关联原始图片");
        }
        if (mediaId != null && mediaFileService.findOwnedMedia(mediaId, userId, MediaBizType.GLUCOSE) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "血糖图片不存在或不可用");
        }

        String merged = remark;
        if (StringUtils.hasText(externalKey)) {
            merged = (merged == null ? "" : merged + " ") + "[ext:" + externalKey + "]";
        }

        return write(userId, recordDate, nodeCode, glucoseValue, resolvedSource, mediaId, merged, recordedAt);
    }

    /** 内部写入：按 (userId, recordDate, nodeCode) 唯一键判断新增或覆盖。 */
    private GlucoseDtos.UpsertResult write(
            Long userId,
            LocalDate recordDate,
            String nodeCode,
            BigDecimal glucoseValue,
            int sourceType,
            Long mediaId,
            String remark,
            LocalDateTime recordedAt) {
        GlucoseRecord existing = glucoseRecordMapper.selectOne(new LambdaQueryWrapper<GlucoseRecord>()
                .eq(GlucoseRecord::getUserId, userId)
                .eq(GlucoseRecord::getRecordDate, recordDate)
                .eq(GlucoseRecord::getNodeCode, nodeCode)
                .last("LIMIT 1"));

        String trimmed = trimRemark(remark);
        LocalDateTime at = recordedAt == null ? LocalDateTime.now() : recordedAt;

        if (existing == null) {
            GlucoseRecord row = new GlucoseRecord();
            row.setUserId(userId);
            row.setRecordDate(recordDate);
            row.setNodeCode(nodeCode);
            row.setGlucoseValue(glucoseValue);
            row.setSourceType(sourceType);
            row.setMediaId(mediaId);
            row.setRemark(trimmed);
            row.setRecordedAt(at);
            glucoseRecordMapper.insert(row);
            return GlucoseDtos.UpsertResult.builder()
                    .id(String.valueOf(row.getId()))
                    .created(true)
                    .build();
        }

        existing.setGlucoseValue(glucoseValue);
        existing.setSourceType(sourceType);
        existing.setMediaId(mediaId);
        existing.setRemark(trimmed);
        existing.setRecordedAt(at);
        glucoseRecordMapper.updateById(existing);
        return GlucoseDtos.UpsertResult.builder()
                .id(String.valueOf(existing.getId()))
                .created(false)
                .build();
    }

    public PageResult<GlucoseDtos.RecordItem> page(
            Long userId, LocalDate startDate, LocalDate endDate, String nodeCode, PageQuery query) {
        Page<GlucoseRecord> page = glucoseRecordMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<GlucoseRecord>()
                        .eq(GlucoseRecord::getUserId, userId)
                        .ge(startDate != null, GlucoseRecord::getRecordDate, startDate)
                        .le(endDate != null, GlucoseRecord::getRecordDate, endDate)
                        .eq(StringUtils.hasText(nodeCode), GlucoseRecord::getNodeCode, nodeCode)
                        .orderByDesc(GlucoseRecord::getRecordDate)
                        .orderByDesc(GlucoseRecord::getId));

        Map<String, GlucoseNode> nodes = glucosePeriodService.nodeMap();
        Page<GlucoseDtos.RecordItem> mapped = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        mapped.setRecords(page.getRecords().stream().map(r -> toItem(r, nodes)).toList());
        return PageResult.of(mapped);
    }

    public GlucoseDtos.RecordItem getItem(Long userId, Long recordId) {
        return toItem(requireOwned(userId, recordId), glucosePeriodService.nodeMap());
    }

    /**
     * 部分修改。remark 传空串表示清空、传 null/省略表示不改（Jackson 无法区分省略与显式 null）。
     */
    @Transactional
    public GlucoseDtos.RecordItem patch(Long userId, Long recordId, GlucoseDtos.PatchRequest request) {
        GlucoseRecord row = requireOwned(userId, recordId);
        if (request.getGlucoseValue() == null && request.getRemark() == null && request.getRecordedAt() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少提交一个待修改字段");
        }
        if (request.getGlucoseValue() != null) {
            assertValueInRange(request.getGlucoseValue());
            row.setGlucoseValue(request.getGlucoseValue());
        }
        if (request.getRemark() != null) {
            row.setRemark(trimRemark(request.getRemark()));
        }
        if (request.getRecordedAt() != null) {
            row.setRecordedAt(request.getRecordedAt());
        }
        glucoseRecordMapper.updateById(row);
        return toItem(row, glucosePeriodService.nodeMap());
    }

    @Transactional
    public void remove(Long userId, Long recordId) {
        GlucoseRecord row = requireOwned(userId, recordId);
        SoftDeletes.mark(glucoseRecordMapper, GlucoseRecord::getId, row.getId(),
                GlucoseRecord::getIsDeleted, GlucoseRecord::getDeletedAt, GlucoseRecord::getDeletedBy);
    }

    /**
     * 账号注销：物理删除该用户全部血糖记录。
     * <p>
     * 独立事务、幂等：worker 重试时重复调用没有副作用。刻意不复用 {@link #remove}——
     * 单条删除是逻辑删除（用户可感知的"删除按钮"），注销要求真的从库里消失。
     */
    @Transactional
    public void purgeAccountData(Long userId) {
        glucoseRecordMapper.purgeByUserId(userId);
    }

    /** 区间记录（含首尾），升序，供统计、趋势与分时段复用。 */
    public List<GlucoseRecord> listRange(Long userId, LocalDate from, LocalDate to) {
        return glucoseRecordMapper.selectList(new LambdaQueryWrapper<GlucoseRecord>()
                .eq(GlucoseRecord::getUserId, userId)
                .ge(GlucoseRecord::getRecordDate, from)
                .le(GlucoseRecord::getRecordDate, to)
                .orderByAsc(GlucoseRecord::getRecordDate)
                .orderByAsc(GlucoseRecord::getId));
    }

    /** 区间内有记录的业务日期（去重、倒序），用于打卡天数与连续打卡计算。 */
    public List<LocalDate> recordedDatesDesc(Long userId, LocalDate from, LocalDate to) {
        return glucoseRecordMapper.selectObjs(new QueryWrapper<GlucoseRecord>()
                        .select("DISTINCT record_date")
                        .eq("user_id", userId)
                        .ge("record_date", from)
                        .le("record_date", to)
                        .orderByDesc("record_date"))
                .stream()
                .map(GlucoseRecordService::toLocalDate)
                .toList();
    }

    /** selectObjs 不走类型处理器，MySQL DATE 可能返回 java.sql.Date，这里统一成 LocalDate。 */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /**
     * 血糖日看板：9 个节点全部返回，未记录节点 hasRecord=false。
     * 餐点记录由 /api/v1/meal-records 独立查询，因此本接口的餐点节点不混入饮食数据。
     * <p>
     * <b>口径提醒（2026-10-14 现场核对后补）</b>：{@code recordedCount} 只数<b>血糖</b>记录，
     * 而 {@code totalNodes} 是全部启用节点（当前 6 血糖 + 3 餐点 = 9）。也就是说
     * {@code recordedCount/totalNodes} <b>不是同一个口径</b>，直接显示成「已记 X/9」会让用户
     * 以为还差 3 个时段。为此额外返回 {@code glucoseTotalNodes} 与 {@code mealTotalNodes}：
     * 端上要显示进度就用 {@code recordedCount/glucoseTotalNodes}（6 段血糖），餐点另有入口。
     * <p>
     * 刻意不改 {@code totalNodes} 的含义——它已被既有调用方当"节点总数"使用，改语义会静默影响别处。
     */
    public GlucoseDtos.DayBoard dayBoard(Long userId, LocalDate recordDate) {
        List<GlucoseNode> nodes = glucosePeriodService.listEnabledNodes();
        Map<String, GlucoseRecord> glucoseByNode = glucoseRecordMapper.selectList(
                        new LambdaQueryWrapper<GlucoseRecord>()
                                .eq(GlucoseRecord::getUserId, userId)
                                .eq(GlucoseRecord::getRecordDate, recordDate))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        GlucoseRecord::getNodeCode, r -> r, (a, b) -> a));

        List<GlucoseDtos.NodeSlot> slots = new ArrayList<>();
        List<GlucoseDtos.ChartPoint> chartPoints = new ArrayList<>();
        int recorded = 0;
        int glucoseNodes = 0;
        int mealNodes = 0;
        for (GlucoseNode node : nodes) {
            boolean isMeal = GlucosePeriodService.isMeal(node);
            if (isMeal) {
                mealNodes++;
            } else {
                glucoseNodes++;
            }
            GlucoseRecord row = glucoseByNode.get(node.getNodeCode());
            boolean hasRecord = !isMeal && row != null;
            if (hasRecord) {
                recorded++;
                chartPoints.add(GlucoseDtos.ChartPoint.builder()
                        .nodeCode(node.getNodeCode())
                        .value(row.getGlucoseValue())
                        .build());
            }
            slots.add(GlucoseDtos.NodeSlot.builder()
                    .nodeCode(node.getNodeCode())
                    .nodeName(node.getNodeName())
                    .isMeal(isMeal)
                    .hasRecord(hasRecord)
                    .recordKind(hasRecord ? "glucose" : null)
                    .glucose(hasRecord ? GlucoseDtos.GlucoseBrief.builder()
                            .glucoseValue(row.getGlucoseValue())
                            .sourceType(row.getSourceType())
                            .remark(row.getRemark())
                            .build() : null)
                    .build());
        }

        return GlucoseDtos.DayBoard.builder()
                .recordDate(recordDate)
                .recordedCount(recorded)
                .totalNodes(nodes.size())
                .glucoseTotalNodes(glucoseNodes)
                .mealTotalNodes(mealNodes)
                .nodes(slots)
                .chartPoints(chartPoints)
                .build();
    }

    /** 记录必须存在且属于当前用户，否则一律 40400，避免越权探测。 */
    public GlucoseRecord requireOwned(Long userId, Long recordId) {
        GlucoseRecord row = glucoseRecordMapper.selectById(recordId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "血糖记录不存在");
        }
        return row;
    }

    private static void assertValueInRange(BigDecimal value) {
        if (value == null || value.compareTo(VALUE_LOW) <= 0 || value.compareTo(VALUE_HIGH) >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "血糖值须在 (0, 40) mmol/L 之间");
        }
    }

    private static String trimRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String trimmed = remark.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= REMARK_MAX ? trimmed : trimmed.substring(0, REMARK_MAX);
    }

    private static GlucoseDtos.RecordItem toItem(GlucoseRecord r, Map<String, GlucoseNode> nodes) {
        GlucoseNode node = nodes.get(r.getNodeCode());
        return GlucoseDtos.RecordItem.builder()
                .id(String.valueOf(r.getId()))
                .recordDate(r.getRecordDate())
                .nodeCode(r.getNodeCode())
                .nodeName(node == null ? r.getNodeCode() : node.getNodeName())
                .glucoseValue(r.getGlucoseValue())
                .sourceType(r.getSourceType())
                .mediaId(r.getMediaId() == null ? null : String.valueOf(r.getMediaId()))
                .remark(r.getRemark())
                .recordedAt(r.getRecordedAt())
                .build();
    }
}
