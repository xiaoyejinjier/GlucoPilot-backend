package com.threadfit.glucopilot.device.adapter;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock 设备适配器：单位换算只在此处；业务写入走 GlucoseIngestPort。
 */
@Component
public class MockDeviceAdapter {

    private static final BigDecimal MGDL_TO_MMOL = new BigDecimal("0.0555");

    private final GlucoseIngestPort glucoseIngestPort;

    public MockDeviceAdapter(GlucoseIngestPort glucoseIngestPort) {
        this.glucoseIngestPort = glucoseIngestPort;
    }

    public DeviceDtos.SyncResponse sync(Long userId, DeviceDtos.SyncRequest request) {
        if (!"MOCK".equalsIgnoreCase(request.getProvider())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "当前仅支持 MOCK 设备同步");
        }
        List<DeviceDtos.SyncResultItem> items = new ArrayList<>();
        for (DeviceDtos.Reading reading : request.getReadings()) {
            BigDecimal mmol = toMmolL(reading.getValue(), reading.getUnit());
            GlucoseIngestPort.IngestResult result = glucoseIngestPort.upsertByNode(
                    new GlucoseIngestPort.IngestCommand(
                            userId,
                            reading.getRecordDate(),
                            reading.getNodeCode(),
                            mmol,
                            CodeEnum.GlucoseSourceType.DEVICE.getCode(),
                            "device:MOCK",
                            LocalDateTime.now(),
                            reading.getExternalRecordId()
                    ));
            items.add(DeviceDtos.SyncResultItem.builder()
                    .nodeCode(reading.getNodeCode())
                    .recordId(String.valueOf(result.recordId()))
                    .created(result.created())
                    .build());
        }
        return DeviceDtos.SyncResponse.builder()
                .provider("MOCK")
                .items(items)
                .build();
    }

    static BigDecimal toMmolL(BigDecimal value, String unit) {
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "value 不能为空");
        }
        if (!StringUtils.hasText(unit) || "MMOL_L".equalsIgnoreCase(unit) || "mmol/L".equalsIgnoreCase(unit)) {
            return value;
        }
        if ("MG_DL".equalsIgnoreCase(unit) || "mg/dL".equalsIgnoreCase(unit)) {
            return value.multiply(MGDL_TO_MMOL).setScale(1, RoundingMode.HALF_UP);
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的单位: " + unit);
    }
}
