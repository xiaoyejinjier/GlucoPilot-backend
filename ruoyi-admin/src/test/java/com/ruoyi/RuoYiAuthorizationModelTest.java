package com.ruoyi;

import static org.junit.jupiter.api.Assertions.*;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RuoYiAuthorizationModelTest {
  private SysRole role(Long id, String key, String status) {
    var role = new SysRole();
    role.setRoleId(id);
    role.setRoleKey(key);
    role.setStatus(status);
    return role;
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void numericIdentityDoesNotGrantSuperPrivileges() {
    var user = new SysUser();
    user.setUserId(1L);
    var operator = role(1L, "operator", "0");
    user.setRoles(List.of(operator));
    assertFalse(user.isAdmin());
    assertFalse(operator.isAdmin());
    var login = new LoginUser(1L, null, user, Set.of("content:news:list"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    assertFalse(SecurityUtils.isAdmin(1L));
  }

  @Test
  void anyActiveSuperRoleGrantsPrivilegesAndDisabledRoleDoesNot() {
    var user = new SysUser();
    user.setUserId(37L);
    var superRole = role(42L, "super_admin", "0");
    user.setRoles(List.of(superRole));
    assertTrue(user.isAdmin());
    assertTrue(superRole.isAdmin());
    var login = new LoginUser(37L, null, user, Set.of("*:*:*"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    assertTrue(SecurityUtils.isAdmin(37L));
    assertFalse(SecurityUtils.isAdmin(1L));
    superRole.setStatus("1");
    assertFalse(user.isAdmin());
    assertFalse(superRole.isAdmin());
    assertFalse(SecurityUtils.isAdmin(37L));
  }
}
