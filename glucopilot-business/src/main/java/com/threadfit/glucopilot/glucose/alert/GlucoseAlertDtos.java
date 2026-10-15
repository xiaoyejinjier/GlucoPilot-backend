package com.threadfit.glucopilot.glucose.alert;

import com.threadfit.glucopilot.common.api.PageResult;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class GlucoseAlertDtos {
    private GlucoseAlertDtos() { }

    public record Event(String id, String userId, String username, String nickname, String phoneMasked,
                        LocalDate recordDate, String nodeCode, String nodeName, BigDecimal glucoseValue,
                        int sourceType, String remark, LocalDateTime recordedAt, String alertType,
                        String status, String note, long version, String handledBy, LocalDateTime handledAt,
                        String userReply, LocalDateTime repliedAt) { }

    public record Result(PageResult<Event> page, Map<String, Long> overview,
                         BigDecimal lowThreshold, BigDecimal highThreshold) { }

    public record History(String id, BigDecimal glucoseValue, LocalDateTime recordedAt,
                          String fromStatus, String toStatus, String note, String operatorId,
                          LocalDateTime operatedAt, String requestId, String userReply) { }

    /** 用户端专用白名单，不复用包含内部note的管理端DTO。 */
    public record UserReply(String id, String recordId, LocalDate recordDate, String nodeCode,
                            String nodeName, BigDecimal glucoseValue, LocalDateTime recordedAt,
                            String userReply, LocalDateTime repliedAt) { }

    public record Detail(Event event, List<History> history) { }

    public record Handling(
            @NotNull @DecimalMin(value="0", inclusive=false) @DecimalMax(value="40", inclusive=false) BigDecimal glucoseValue,
            @NotNull LocalDateTime recordedAt,
            @NotNull @Min(0) Long expectedVersion,
            @NotNull @Pattern(regexp="PROCESSING|RESOLVED|CLOSED") String status,
            @NotBlank @Size(max=1000) String note,
            @Size(max=1000) String userReply) {
        public Handling(BigDecimal value, LocalDateTime at, Long version, String status, String note) {
            this(value,at,version,status,note,null);
        }
    }
}
