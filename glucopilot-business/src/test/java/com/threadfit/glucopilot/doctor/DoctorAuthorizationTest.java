package com.threadfit.glucopilot.doctor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.*;
import com.threadfit.glucopilot.auth.service.*;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;
import com.threadfit.glucopilot.doctor.service.DoctorRealmService;

import org.junit.jupiter.api.*;

import java.util.*;

class DoctorAuthorizationTest {
    ISysUserService users = mock(ISysUserService.class);
    ISysMenuService menus = mock(ISysMenuService.class);
    SysPermissionService permissions = mock(SysPermissionService.class);
    OpsIdentityService ids = mock(OpsIdentityService.class);
    DoctorPortalMapper mapper = mock(DoctorPortalMapper.class);
    RuoYiAuthorizationService service =
            new RuoYiAuthorizationService(
                    users, menus, permissions, ids, new DoctorRealmService(mapper));
    SysUser user;
    SysMenu directory, workbench, income;

    @BeforeEach
    void setup() {
        user = new SysUser();
        user.setUserId(70L);
        user.setStatus("0");
        user.setDelFlag("0");
        user.setUserName("doctor");
        var role = new SysRole();
        role.setRoleKey("doctor");
        role.setStatus("0");
        user.setRoles(List.of(role));
        when(ids.userIdOf(7L)).thenReturn(70L);
        when(users.selectUserById(70L)).thenReturn(user);
        when(permissions.getRolePermission(user)).thenReturn(Set.of("doctor"));
        when(permissions.getMenuPermission(user))
                .thenReturn(Set.of("doctor:workbench:view", "doctor:income:view", "user:manage"));
        directory = menu("/doctor-management", "", 0L);
        directory.setMenuType("M");
        when(menus.selectMenuById(100L)).thenReturn(directory);
        workbench = menu("/doctor-management/workbench", "doctor:workbench:view", 100L);
        income = menu("/doctor-management/income", "doctor:income:view", 100L);
        when(menus.selectMenuList(70L))
                .thenReturn(List.of(workbench, income, menu("/users", "user:manage", 0L)));
    }

    SysMenu menu(String path, String perm, Long parent) {
        var m = new SysMenu();
        m.setPath(path);
        m.setPerms(perm);
        m.setParentId(parent);
        m.setStatus("0");
        m.setVisible("0");
        m.setMenuType("C");
        return m;
    }

    @Test
    void loginContextPreservesDoctorRoleAndFiltersLegacyMenusAndPermissions() {
        var context = service.userInfoOf(7L);
        assertEquals("doctor", context.getRole());
        assertEquals(
                List.of("doctor:income:view", "doctor:workbench:view"), context.getPermissions());
        assertEquals(2, context.getMenus().size());
        assertFalse(service.hasPermission(7L, "user:manage"));
    }

    @Test
    void hidingOrDisablingMenuAndParentChangesActualReturnedMenuPaths() {
        workbench.setVisible("1");
        assertEquals(1, service.menusOf(7L).size());
        directory.setVisible("1");
        assertTrue(service.menusOf(7L).isEmpty());
        directory.setVisible("0");
        directory.setStatus("1");
        assertTrue(service.menusOf(7L).isEmpty());
        directory.setStatus("0");
        assertEquals(1, service.menusOf(7L).size());
        when(permissions.getMenuPermission(user)).thenReturn(Set.of());
        assertTrue(service.menusOf(7L).isEmpty());
    }
}
