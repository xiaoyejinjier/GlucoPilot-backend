package com.threadfit.glucopilot.device.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.api.PageQuery;
import com.threadfit.glucopilot.common.api.PageResult;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.device.dto.AdminDeviceDtos;
import com.threadfit.glucopilot.device.entity.UserDevice;
import com.threadfit.glucopilot.device.mapper.UserDeviceMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private AdminDeviceService service;

    @Test
    void pageReturnsUnboundDevice() {
        UserDevice device = device(1L, "SN-001", "手表", null, 1);
        when(userDeviceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<UserDevice> page = invocation.getArgument(0);
            page.setRecords(List.of(device));
            page.setTotal(1);
            return page;
        });

        PageResult<AdminDeviceDtos.DeviceItem> result = service.page(null, null, new PageQuery());

        assertEquals(1, result.getTotal());
        AdminDeviceDtos.DeviceItem item = result.getRecords().getFirst();
        assertEquals("1", item.getId());
        assertEquals("SN-001", item.getDeviceSn());
        assertNull(item.getUserId());
        assertEquals("未绑定", item.getUserMasked());
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
