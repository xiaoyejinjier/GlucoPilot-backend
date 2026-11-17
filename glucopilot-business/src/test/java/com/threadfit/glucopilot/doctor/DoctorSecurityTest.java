package com.threadfit.glucopilot.doctor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.*;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.security.OpsSessionAuthFilter;
import com.threadfit.glucopilot.auth.service.OpsIdentityService;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;
import com.threadfit.glucopilot.doctor.service.DoctorRealmService;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

class DoctorSecurityTest {
    DoctorPortalMapper mapper = mock(DoctorPortalMapper.class);
    DoctorRealmService realm = new DoctorRealmService(mapper);
    SysUser user;

    @BeforeEach
    void setup() {
        user = new SysUser();
        user.setUserId(12L);
        user.setStatus("0");
        user.setDelFlag("0");
        var role = new SysRole();
        role.setRoleKey("doctor");
        role.setStatus("0");
        user.setRoles(List.of(role));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void doctorCannotReceiveLegacyOrWildcardPermissions() {
        assertTrue(realm.restricted(7, user));
        assertEquals(
                Set.of("doctor:income:view"),
                realm.grants(7, user, Set.of("*:*:*", "user:manage", "doctor:income:view")));
        assertFalse(realm.allowed("GET", "/admin/v1/users"));
        assertFalse(realm.allowed("PUT", "/admin/v1/doctor-accounts/1"));
        assertFalse(realm.allowed("PUT", "/admin/v1/doctor-role-permissions"));
        assertTrue(realm.allowed("GET", "/admin/v1/doctor/income"));
        assertTrue(realm.allowed("GET", "/admin/v1/system/user-info"));
        assertTrue(realm.allowed("DELETE", "/admin/v1/auth/sessions/current"));
    }

    @Test
    void fixedBindingStillRestrictsAnAccountAfterRoleChangeAndOtherAdminsRemainUnchanged() {
        user.setRoles(List.of());
        when(mapper.bindingExists(7)).thenReturn(true);
        assertTrue(realm.restricted(7, user));
        var admin = new SysRole();
        admin.setRoleKey("super_admin");
        admin.setStatus("0");
        user.setRoles(List.of(admin));
        assertTrue(realm.restricted(7, user));
        when(mapper.bindingExists(7)).thenReturn(false);
        assertFalse(realm.restricted(7, user));
    }

    OpsSessionAuthFilter filter(LoginUser login) {
        var tokens = mock(TokenService.class);
        when(tokens.getLoginUser(any(jakarta.servlet.http.HttpServletRequest.class)))
                .thenReturn(login);
        var users = mock(ISysUserService.class);
        when(users.selectUserById(12L)).thenReturn(user);
        var perms = mock(SysPermissionService.class);
        when(perms.getMenuPermission(user)).thenReturn(Set.of("user:manage", "doctor:income:view"));
        var ids = mock(OpsIdentityService.class);
        when(ids.opsIdOf(12L)).thenReturn(7L);
        return new OpsSessionAuthFilter(
                tokens, users, perms, ids, realm, new JsonUtils(new ObjectMapper()));
    }

    @Test
    void realOpsFilterDeniesLegacyEndpointsBeforeCallingController() throws Exception {
        var login = new LoginUser();
        login.setUserId(12L);
        login.setUser(user);
        var request = new MockHttpServletRequest("GET", "/admin/v1/users");
        var response = new MockHttpServletResponse();
        filter(login)
                .doFilter(request, response, (req, res) -> fail("Legacy controller must not run"));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40300"));
    }

    @Test
    void realOpsFilterKeepsDoctorAuthorityScopedAndClearsSecurityContext() throws Exception {
        var login = new LoginUser();
        login.setUserId(12L);
        login.setUser(user);
        var request = new MockHttpServletRequest("GET", "/admin/v1/doctor/income");
        var response = new MockHttpServletResponse();
        boolean[] called = {false};
        filter(login)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            called[0] = true;
                            assertEquals(Set.of("doctor:income:view"), login.getPermissions());
                            assertEquals(7L, RequestContext.getOpsId());
                        });
        assertTrue(called[0]);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void regularOperatorPermissionsRemainUnchanged() throws Exception {
        user.setRoles(List.of());
        var login = new LoginUser();
        login.setUserId(12L);
        login.setUser(user);
        boolean[] called = {false};
        filter(login)
                .doFilter(
                        new MockHttpServletRequest("GET", "/admin/v1/users"),
                        new MockHttpServletResponse(),
                        (req, res) -> called[0] = true);
        assertTrue(called[0]);
        assertTrue(login.getPermissions().contains("user:manage"));
    }
}
