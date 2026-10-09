package com.threadfit.glucopilot.glucose.trend;

import com.threadfit.glucopilot.glucose.dto.GlucoseDtos;
import com.threadfit.glucopilot.glucose.entity.GlucoseRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 按天聚合的血糖趋势序列：区间内每一天都出点，无记录的天 count=0、avg=null，
 * 便于端上直接绘制折线（配合 spanGaps 断线即可）。
 */
@Service
public class GlucoseTrendService {

    private static final int ONE_DECIMAL = 1;

    public List<GlucoseDtos.TrendPoint> dailySeries(List<GlucoseRecord> records, LocalDate from, LocalDate to) {
        Map<LocalDate, List<GlucoseRecord>> byDate = records.stream()
                .filter(r -> r.getRecordDate() != null)
                .collect(Collectors.groupingBy(GlucoseRecord::getRecordDate));

        List<GlucoseDtos.TrendPoint> points = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<GlucoseRecord> rows = byDate.getOrDefault(date, List.of());
            points.add(GlucoseDtos.TrendPoint.builder()
                    .date(date)
                    .count(rows.size())
                    .avg(average(rows))
                    .build());
        }
        return points;
    }

    public static BigDecimal average(List<GlucoseRecord> rows) {
        List<BigDecimal> values = rows.stream()
                .map(GlucoseRecord::getGlucoseValue)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), ONE_DECIMAL, RoundingMode.HALF_UP);
    }
}
