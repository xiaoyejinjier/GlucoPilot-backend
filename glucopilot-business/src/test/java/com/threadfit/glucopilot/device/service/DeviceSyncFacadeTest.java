package com.threadfit.glucopilot.device.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.adapter.MockDeviceAdapter;
import com.threadfit.glucopilot.device.dto.DeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceSyncLog;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.device.port.GlucoseIngestPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSyncFacadeTest {

    @Mock
    private GlucoseIngestPort glucoseIngestPort;
    @Mock
    private UserDeviceMapper userDeviceMapper;
    @Mock
    private DeviceSyncLogMapper deviceSyncLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceSyncFacade facade;

    @BeforeEach
    void setUp() {
        facade = new DeviceSyncFacade(
                List.of(new MockDeviceAdapter()), glucoseIngestPort, userDeviceMapper, deviceSyncLogMapper);
    }

    @Test
    void syncRoutesMockAndIngests() {
        when(glucoseIngestPort.upsertByNode(any())).thenReturn(new GlucoseIngestPort.IngestResult(88L, true));

        DeviceDtos.SyncRequest request = new DeviceDtos.SyncRequest();
        request.setProvider("mock");
        request.setPayload(mockPayload());

        DeviceDtos.SyncResponse response = facade.sync(3L, request);

        assertEquals("MOCK", response.getProvider());
        assertEquals(1, response.getItems().size());
        assertEquals("88", response.getItems().getFirst().getRecordId());
        ArgumentCaptor<GlucoseIngestPort.IngestCommand> captor =
                ArgumentCaptor.forClass(GlucoseIngestPort.IngestCommand.class);
        verify(glucoseIngestPort).upsertByNode(captor.capture());
        assertEquals("device:MOCK", captor.getValue().remark());
        verify(userDeviceMapper, never()).updateById(any(UserDevice.class));
        verify(deviceSyncLogMapper, never()).insert(any(DeviceSyncLog.class));
    }

    @Test
    void syncUpdatesLastSyncWhenDeviceSnBound() {
        UserDevice bound = new UserDevice();
        bound.setId(7L);
        bound.setDeviceSn("SN-7");
        bound.setUserId(3L);
        bound.setDeviceStatus(1);
        bound.setProvider("MOCK");
        when(userDeviceMapper.selectOne(any())).thenReturn(bound);
        when(glucoseIngestPort.upsertByNode(any())).thenReturn(new GlucoseIngestPort.IngestResult(1L, true));

        DeviceDtos.SyncRequest request = new DeviceDtos.SyncRequest();
        request.setProvider("MOCK");
        request.setDeviceSn("SN-7");
        request.setPayload(mockPayload());

        facade.sync(3L, request);

        ArgumentCaptor<UserDevice> captor = ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceMapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastSyncAt());
        ArgumentCaptor<DeviceSyncLog> logCaptor = ArgumentCaptor.forClass(DeviceSyncLog.class);
        verify(deviceSyncLogMapper).insert(logCaptor.capture());
        assertEquals(7L, logCaptor.getValue().getDeviceId());
        assertEquals(3L, logCaptor.getValue().getUserId());
        assertEquals("MOCK", logCaptor.getValue().getProvider());
        assertEquals(1, logCaptor.getValue().getSampleCount());
        assertEquals(1, logCaptor.getValue().getCreatedCount());
    }

    @Test
    void unknownProviderRejected() {
        DeviceDtos.SyncRequest request = new DeviceDtos.SyncRequest();
        request.setProvider("UNKNOWN_VENDOR");
        request.setPayload(objectMapper.createObjectNode());

        BizException error = assertThrows(BizException.class, () -> facade.sync(3L, request));
        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
    }

    private ObjectNode mockPayload() {
        ObjectNode root = objectMapper.createObjectNode();
        var readings = root.putArray("readings");
        ObjectNode row = readings.addObject();
        row.put("recordDate", "2026-10-14");
        row.put("nodeCode", "before_breakfast");
        row.put("value", 5.6);
        row.put("unit", "MMOL_L");
        row.put("externalRecordId", "ext-1");
        return root;
    }
}
