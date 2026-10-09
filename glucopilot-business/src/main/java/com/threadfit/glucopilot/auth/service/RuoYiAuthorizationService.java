package com.threadfit.glucopilot.auth.service;

import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.dto.RuoYiSystemDtos;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuoYiAuthorizationService {
  public static final String ALL_PERMISSION = "*:*:*";
  private final ISysUserService users;
  private final ISysMenuService menus;
  private final SysPermissionService permissions;
  private final OpsIdentityService identities;

  public Set<String> roleCodesOf(Long opsId) {
    return permissions.getRolePermission(user(opsId));
  }

  public Set<String> permissionCodesOf(Long opsId) {
    return permissions.getMenuPermission(user(opsId));
  }

  public boolean hasPermission(Long opsId, String permission) {
    if (opsId == null) return false;
    var codes = permissionCodesOf(opsId);
    return codes.contains(ALL_PERMISSION) || codes.contains(permission);
  }

  public List<RuoYiSystemDtos.MenuItem> menusOf(Long opsId) {
    var user = user(opsId);
    var granted = permissions.getMenuPermission(user);
    return menus.selectMenuList(user.getUserId()).stream()
        .filter(
            m ->
                "0".equals(m.getStatus())
                    && "0".equals(m.getVisible())
                    && "C".equals(m.getMenuType()))
        .filter(m -> granted.contains(ALL_PERMISSION) || granted.contains(m.getPerms()))
        .map(
            m ->
                RuoYiSystemDtos.MenuItem.builder()
                    .name(m.getMenuName())
                    .permission(m.getPerms())
                    .path(m.getPath())
                    .order(m.getOrderNum())
                    .build())
        .toList();
  }

  public RuoYiSystemDtos.UserInfo userInfoOf(Long opsId) {
    var user = user(opsId);
    var roles = roleCodesOf(opsId);
    return RuoYiSystemDtos.UserInfo.builder()
        .opsId(opsId)
        .username(user.getUserName())
        .nickname(user.getNickName())
        .role(user.isAdmin() || roles.contains("super_admin") ? "admin" : "editor")
        .roles(roles.stream().sorted().toList())
        .permissions(permissionCodesOf(opsId).stream().sorted().toList())
        .menus(menusOf(opsId))
        .build();
  }

  private com.ruoyi.common.core.domain.entity.SysUser user(Long opsId) {
    var user = users.selectUserById(identities.userIdOf(opsId));
    if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag()))
      throw new BizException(ErrorCode.UNAUTHORIZED);
    return user;
  }
}
