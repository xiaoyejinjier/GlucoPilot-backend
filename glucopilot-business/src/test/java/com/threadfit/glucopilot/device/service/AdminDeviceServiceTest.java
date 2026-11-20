package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.cloud.DeviceCloudCredentialService;
import com.threadfit.glucopilot.device.dto.AdminDeviceDtos;
import com.threadfit.glucopilot.device.entity.DeviceCloudCredential;
import com.threadfit.glucopilot.device.entity.DeviceSyncLog;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.DeviceSyncLogMapper;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDeviceServiceTest {

    @Mock
    private UserDeviceMapper userDeviceMapper;
    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private DeviceSyncLogMapper deviceSyncLogMapper;
    @Mock
    private DeviceCloudCredentialService deviceCloudCredentialService;

    @InjectMocks
    private AdminDeviceService service;

    @Test
    void pageReturnsUnboundDeviceWithSyncHealth() {
        UserDevice device = device(1L, "SN-001", "手表", null, 1);
        when(userDeviceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<UserDevice> page = invocation.getArgument(0);
            page.setRecords(List.of(device));
            page.setTotal(1);
            return page;
        });
        when(deviceCloudCredentialService.linkedDeviceIds(anyList())).thenReturn(Set.of());

        PageResult<AdminDeviceDtos.DeviceItem> result = service.page(null, null, null, null, null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        AdminDeviceDtos.DeviceItem item = result.getRecords().getFirst();
        assertEquals("1", item.getId());
        assertEquals("SN-001", item.getDeviceSn());
        assertNull(item.getUserId());
        assertEquals("未绑定", item.getUserMasked());
        assertFalse(Boolean.TRUE.equals(item.getCloudLinked()));
        assertEquals("NEVER", item.getSyncHealth());
    }

    @Test
    void pageCloudLinkedTrueWithEmptySetReturnsEmpty() {
        when(deviceCloudCredentialService.allLinkedDeviceIds()).thenReturn(Set.of());

        PageResult<AdminDeviceDtos.DeviceItem> result =
                service.page(null, null, null, null, null, true, new PageQuery());

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void pageFiltersByDeviceCategory() {
        UserDevice cgm = device(3L, "SN-CGM", "连续监测", null, 1);
        cgm.setDeviceCategory("CGM");
        cgm.setDeviceModel("GS3");
        when(userDeviceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<UserDevice> page = invocation.getArgument(0);
            page.setRecords(List.of(cgm));
            page.setTotal(1);
            return page;
        });
        when(deviceCloudCredentialService.linkedDeviceIds(anyList())).thenReturn(Set.of());

        PageResult<AdminDeviceDtos.DeviceItem> result =
                service.page(null, null, null, "cgm", null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        assertEquals("CGM", result.getRecords().getFirst().getDeviceCategory());
        assertEquals("GS3", result.getRecords().getFirst().getDeviceModel());
        verify(userDeviceMapper).selectPage(any(), any());
    }

    @Test
    void createRejectsDuplicateSn() {
        when(userDeviceMapper.selectOne(any())).thenReturn(device(9L, "SN-DUP", "旧", null, 1));

        AdminDeviceDtos.CreateRequest request = new AdminDeviceDtos.CreateRequest();
        request.setDeviceSn("SN-DUP");
        request.setDeviceName("新设备");

        BizException error = assertThrows(BizException.class, () -> service.create(request));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test
    void createInsertsWithDefaultProvider() {
        when(userDeviceMapper.selectOne(any())).thenReturn(null);
        when(userDeviceMapper.insert(any(UserDevice.class))).thenAnswer(invocation -> {
            UserDevice row = invocation.getArgument(0);
            row.setId(11L);
            return 1;
        });
        UserDevice saved = device(11L, "SN-NEW", "CGM", null, 1);
        saved.setProvider("MOCK");
        when(userDeviceMapper.selectById(11L)).thenReturn(saved);
        when(deviceCloudCredentialService.findActiveByDeviceId(11L)).thenReturn(null);

        AdminDeviceDtos.CreateRequest request = new AdminDeviceDtos.CreateRequest();
        request.setDeviceSn(" SN-NEW ");
        request.setDeviceName(" CGM ");

        AdminDeviceDtos.DeviceItem item = service.create(request);

        ArgumentCaptor<UserDevice> captor = ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceMapper).insert(captor.capture());
        assertEquals("SN-NEW", captor.getValue().getDeviceSn());
        assertEquals("MOCK", captor.getValue().getProvider());
        assertEquals(1, captor.getValue().getDeviceStatus());
        assertNull(captor.getValue().getUserId());
        assertEquals("11", item.getId());
        assertEquals("NEVER", item.getSyncHealth());
    }

    @Test
    void createPersistsCategoryAndModel() {
        when(userDeviceMapper.selectOne(any())).thenReturn(null);
        when(userDeviceMapper.insert(any(UserDevice.class))).thenAnswer(invocation -> {
            UserDevice row = invocation.getArgument(0);
            row.setId(12L);
            return 1;
        });
        UserDevice saved = device(12L, "SN-CGM", "连续监测", null, 1);
        saved.setProvider("HUAWEI");
        saved.setDeviceCategory("CGM");
        saved.setDeviceModel("GS3");
        when(userDeviceMapper.selectById(12L)).thenReturn(saved);
        when(deviceCloudCredentialService.findActiveByDeviceId(12L)).thenReturn(null);

        AdminDeviceDtos.CreateRequest request = new AdminDeviceDtos.CreateRequest();
        request.setDeviceSn("SN-CGM");
        request.setDeviceName("连续监测");
        request.setProvider("HUAWEI");
        request.setDeviceCategory("cgm");
        request.setDeviceModel(" GS3 ");

        AdminDeviceDtos.DeviceItem item = service.create(request);
        ArgumentCaptor<UserDevice> captor = ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceMapper).insert(captor.capture());
        assertEquals("CGM", captor.getValue().getDeviceCategory());
        assertEquals("GS3", captor.getValue().getDeviceModel());
        assertEquals("CGM", item.getDeviceCategory());
        assertEquals("GS3", item.getDeviceModel());
    }

    @Test
    void updateStatusChangesDevice() {
        when(userDeviceMapper.selectById(5L)).thenReturn(device(5L, "SN-5", "手表", null, 1));

        AdminDeviceDtos.UpdatedResult result = service.updateStatus(5L, 0);

        assertTrue(result.getUpdated());
        ArgumentCaptor<UserDevice> captor = ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getDeviceStatus());
    }

    @Test
    void missingDeviceUsesNotFound() {
        when(userDeviceMapper.selectById(99L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.updateStatus(99L, 0));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void listSyncLogsMapsRows() {
        when(userDeviceMapper.selectById(3L)).thenReturn(device(3L, "SN-3", "表", 2L, 1));
        DeviceSyncLog log = new DeviceSyncLog();
        log.setId(7L);
        log.setProvider("HUAWEI");
        log.setSampleCount(2);
        log.setCreatedCount(1);
        log.setRemark("sync");
        log.setGmtCreate(LocalDateTime.of(2026, 11, 20, 10, 0));
        when(deviceSyncLogMapper.selectList(any())).thenReturn(List.of(log));

        List<AdminDeviceDtos.SyncLogItem> items = service.listSyncLogs(3L, 20);
        assertEquals(1, items.size());
        assertEquals("7", items.getFirst().getId());
        assertEquals(2, items.getFirst().getSampleCount());
    }

    @Test
    void cloudAuthMasksSubjectAndNeverExposesToken() {
        when(userDeviceMapper.selectById(4L)).thenReturn(device(4L, "SN-4", "表", 2L, 1));
        DeviceCloudCredential cred = new DeviceCloudCredential();
        cred.setVendorSubject("huawei-openid-abcdef");
        cred.setScopes("openid bloodglucose.read");
        cred.setTokenExpiresAt(LocalDateTime.of(2026, 12, 1, 0, 0));
        cred.setAccessTokenEnc("cipher-should-not-leak");
        when(deviceCloudCredentialService.findActiveByDeviceId(4L)).thenReturn(cred);

        AdminDeviceDtos.CloudAuthStatus status = service.cloudAuth(4L);
        assertTrue(status.getLinked());
        assertEquals("hua****ef", status.getVendorSubjectMasked());
        assertEquals("openid bloodglucose.read", status.getScopes());
    }

    @Test
    void revokeCloudAuthDelegates() {
        when(userDeviceMapper.selectById(4L)).thenReturn(device(4L, "SN-4", "表", 2L, 1));
        service.revokeCloudAuth(4L);
        verify(deviceCloudCredentialService).revokeByDeviceId(4L);
    }

    @Test
    void syncHealthAndMaskHelpers() {
        assertEquals("NEVER", AdminDeviceService.syncHealthOf(null));
        assertEquals("STALE", AdminDeviceService.syncHealthOf(LocalDateTime.now().minusHours(49)));
        assertEquals("OK", AdminDeviceService.syncHealthOf(LocalDateTime.now().minusHours(1)));
        assertEquals("****", AdminDeviceService.maskSubject("ab"));
        assertEquals("hua****ef", AdminDeviceService.maskSubject("huawei-openid-abcdef"));
    }

    private static UserDevice device(Long id, String sn, String name, Long userId, int status) {
        UserDevice d = new UserDevice();
        d.setId(id);
        d.setDeviceSn(sn);
        d.setDeviceName(name);
        d.setProvider("MOCK");
        d.setUserId(userId);
        d.setDeviceStatus(status);
        return d;
    }
}
