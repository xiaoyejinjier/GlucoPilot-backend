package com.threadfit.glucopilot.auth.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsRoleResolver {
  public static final String CODE_SUPER_ADMIN = "super_admin",
      CODE_OPERATOR = "operator",
      FRONT_ADMIN = "admin",
      FRONT_EDITOR = "editor";
  private final ISysRoleService roles;
  private final ISysUserService users;
  private final OpsIdentityService identities;
  private final OpsSessionRegistry sessions;

  public String resolveFrontRole(Long opsId) {
    return hasSuperAdmin(opsId) ? FRONT_ADMIN : FRONT_EDITOR;
  }

  public boolean hasSuperAdmin(Long opsId) {
    var u = users.selectUserById(identities.userIdOf(opsId));
    return u != null && u.isAdmin();
  }

  public Set<String> roleCodesOf(Long opsId) {
    return roles.selectRolePermissionByUserId(identities.userIdOf(opsId));
  }

  public Long requireRoleIdForFront(String role) {
    String code = FRONT_ADMIN.equals(role) ? CODE_SUPER_ADMIN : CODE_OPERATOR;
    return roles.selectRoleAll().stream()
        .filter(r -> code.equals(r.getRoleKey()))
        .findFirst()
        .orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR, "角色未初始化"))
        .getRoleId();
  }

  public void bindFrontRole(Long opsId, String role) {
    Long userId = identities.userIdOf(opsId);
    users.insertUserAuth(userId, new Long[] {requireRoleIdForFront(role)});
    sessions.revokeUser(userId);
  }

  public long countActiveSuperAdmins() {
    return users.selectUserList(new SysUser()).stream()
        .map(u -> users.selectUserById(u.getUserId()))
        .filter(u -> u.isAdmin() && "0".equals(u.getStatus()))
        .count();
  }
}
