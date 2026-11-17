package com.threadfit.glucopilot.doctor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.service.*;
import com.threadfit.glucopilot.auth.service.OpsSessionRegistry;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.entity.DoctorProfile;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.doctor.dto.DoctorPortalDtos.*;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;
import com.threadfit.glucopilot.doctor.service.DoctorPortalService;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.service.RuoYiUserService;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

class DoctorPortalServiceTest {
    DoctorPortalMapper mapper = mock(DoctorPortalMapper.class);
    ConsultationMapper profiles = mock(ConsultationMapper.class);
    RuoYiUserService users = mock(RuoYiUserService.class);
    ISysRoleService roles = mock(ISysRoleService.class);
    ISysMenuService menus = mock(ISysMenuService.class);
    OpsSessionRegistry sessions = mock(OpsSessionRegistry.class);
    DoctorPortalService service =
            new DoctorPortalService(
                    mapper,
                    profiles,
                    mock(ConsultationService.class),
                    mock(WalletOrderService.class),
                    users,
                    roles,
                    menus,
                    sessions);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void accountProvisioningNeedsApprovalAndNeverRebindsAnExistingDoctor() {
        assertThrows(
                BizException.class,
                () -> service.create(1, 20, new CreateAccount("doctor_test", "password_123")));
        var profile = new DoctorProfile();
        profile.setStatus("APPROVED");
        profile.setRealName("测试医生");
        when(profiles.lockDoctor(20)).thenReturn(profile);
        when(profiles.doctor(20)).thenReturn(profile);
        var account = new RuoYiAdminDtos.UserItem();
        account.setId(7L);
        when(users.create(any())).thenReturn(account);
        when(mapper.opsForDoctor(20)).thenReturn(null);
        service.create(1, 20, new CreateAccount("doctor_test", "password_123"));
        var form = ArgumentCaptor.forClass(RuoYiAdminDtos.UserRequest.class);
        verify(users).create(form.capture());
        assertEquals(List.of("doctor"), form.getValue().getRoleCodes());
        assertEquals("测试医生", form.getValue().getNickname());
        verify(mapper).bind(20, 7, 1);
        when(mapper.opsForDoctor(20)).thenReturn(7L);
        assertThrows(
                BizException.class,
                () -> service.create(1, 20, new CreateAccount("other_name", "password_123")));
        verify(users, times(1)).create(any());
    }

    @Test
    void missingOrInactiveBindingCannotReadAsAnotherDoctor() {
        when(mapper.boundDoctor(7)).thenReturn(null);
        assertThrows(BizException.class, () -> service.doctorId(7));
        when(mapper.boundDoctor(7)).thenReturn(20L);
        assertEquals(20L, service.doctorId(7));
    }

    @Test
    void permissionConfigurationUsesRealRoleMenuIdsAndRevokesOnlyDoctorsAfterCommit() {
        var root = new SysUser();
        root.setUserId(1L);
        root.setUserName("admin");
        var principal = new LoginUser();
        principal.setUserId(1L);
        principal.setUser(root);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var role = new SysRole();
        role.setRoleId(9L);
        role.setRoleKey("doctor"); // upstream role projection does not include delFlag
        when(roles.selectRoleAll()).thenReturn(List.of(role));
        var menu = new SysMenu();
        menu.setMenuId(2001L);
        menu.setPerms("doctor:income:view");
        when(menus.selectMenuList(1L)).thenReturn(List.of(menu));
        when(menus.selectMenuPermsByRoleId(9L)).thenReturn(Set.of("doctor:income:view"));
        var doctorRole = new SysRole();
        doctorRole.setRoleKey("doctor");
        var doctorUser = new SysUser();
        doctorUser.setRoles(List.of(doctorRole));
        var doctor = new LoginUser();
        doctor.setToken("doctor-session");
        doctor.setUser(doctorUser);
        principal.setToken("admin-session");
        when(sessions.online()).thenReturn(List.of(doctor, principal));
        TransactionSynchronizationManager.initSynchronization();
        var result = service.permissions(new Permissions(List.of("doctor:income:view")));
        assertEquals(List.of("doctor:income:view"), result.permissions());
        assertArrayEquals(new Long[] {2001L}, role.getMenuIds());
        verify(roles).updateRole(role);
        verify(sessions, never()).online();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(sessions).revokeToken("doctor-session");
        verify(sessions, never()).revokeToken("admin-session");
    }

    @Test
    void arbitraryPermissionGrantIsRejectedBeforeRoleWrites() {
        assertThrows(
                BizException.class,
                () -> service.permissions(new Permissions(List.of("user:manage"))));
        verifyNoInteractions(roles);
    }
}
