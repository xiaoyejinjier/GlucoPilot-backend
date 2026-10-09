package com.threadfit.glucopilot.glucose.period;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseNode;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import com.threadfit.glucopilot.glucose.mapper.GlucoseNodeMapper;
import com.threadfit.glucopilot.glucose.trend.GlucoseTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 九时段维度：节点字典与分时段（早/午/晚/其他）统计。
 * 节点名只从 dict_glucose_node 取，避免应用内硬编码中文。
 */
@Service
@RequiredArgsConstructor
public class GlucosePeriodService {

    private static final int ENABLED = 1;

    /** 分时段归属：时段编码、展示名与匹配的节点编码片段。 */
    private record Slot(String code, String name, String nodeFragment) {
    }

    private static final List<Slot> SLOTS = List.of(
            new Slot("breakfast", "早餐段", "breakfast"),
            new Slot("lunch", "午餐段", "lunch"),
            new Slot("dinner", "晚餐段", "dinner"),
            new Slot("other", "其他", ""));

    private final GlucoseNodeMapper glucoseNodeMapper;

    /** 启用中的九时段节点，按 sort_no 升序；分页接口与日看板共用。 */
    public List<GlucoseNode> listEnabledNodes() {
        return glucoseNodeMapper.selectList(new LambdaQueryWrapper<GlucoseNode>()
                .eq(GlucoseNode::getIsEnabled, ENABLED)
                .orderByAsc(GlucoseNode::getSortNo)
                .orderByAsc(GlucoseNode::getId));
    }

    public List<GlucoseDtos.NodeItem> listNodeItems() {
        return listEnabledNodes().stream()
                .map(n -> GlucoseDtos.NodeItem.builder()
                        .nodeCode(n.getNodeCode())
                        .nodeName(n.getNodeName())
                        .shortName(n.getShortName())
                        .isMeal(isMeal(n))
                        .sortNo(n.getSortNo())
                        .build())
                .toList();
    }

    /** nodeCode -> 节点，用于补全中文名与 isMeal。 */
    public Map<String, GlucoseNode> nodeMap() {
        return listEnabledNodes().stream()
                .collect(Collectors.toMap(GlucoseNode::getNodeCode, Function.identity(), (a, b) -> a));
    }

    public static boolean isMeal(GlucoseNode node) {
        return node != null && node.getIsMeal() != null && node.getIsMeal() == 1;
    }

    /**
     * 分时段统计。pct 以记录最多的时段为 100%，与端上进度条口径一致。
     * 均值口径与趋势模块共用 GlucoseTrendService，避免两处各算一套。
     */
    public List<GlucoseDtos.SlotStat> slotStats(List<GlucoseRecord> records) {
        Map<String, List<GlucoseRecord>> grouped = new LinkedHashMap<>();
        SLOTS.forEach(slot -> grouped.put(slot.code(), new ArrayList<>()));
        for (GlucoseRecord record : records) {
            grouped.get(codeOf(record.getNodeCode())).add(record);
        }

        int maxCount = grouped.values().stream().mapToInt(List::size).max().orElse(0);
        return SLOTS.stream()
                .map(slot -> {
                    List<GlucoseRecord> rows = grouped.get(slot.code());
                    return GlucoseDtos.SlotStat.builder()
                            .nodeCode(slot.code())
                            .name(slot.name())
                            .count(rows.size())
                            .avg(GlucoseTrendService.average(rows))
                            .pct(maxCount == 0 ? 0 : rows.size() * 100 / maxCount)
                            .build();
                })
                .toList();
    }

    private static String codeOf(String nodeCode) {
        String code = nodeCode == null ? "" : nodeCode;
        for (Slot slot : SLOTS) {
            if (!slot.nodeFragment().isEmpty() && code.contains(slot.nodeFragment())) {
                return slot.code();
            }
        }
        return "other";
    }
}
