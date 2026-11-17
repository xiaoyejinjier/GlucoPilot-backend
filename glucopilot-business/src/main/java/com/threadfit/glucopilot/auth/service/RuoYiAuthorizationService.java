package com.threadfit.glucopilot.auth.service;

import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.dto.RuoYiSystemDtos;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.doctor.service.DoctorRealmService;
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
  private final DoctorRealmService doctors;

  public Set<String> roleCodesOf(Long opsId) {
    return permissions.getRolePermission(user(opsId));
  }

  public Set<String> permissionCodesOf(Long opsId) {
    var user = user(opsId);
    return doctors.grants(opsId, user, permissions.getMenuPermission(user));
  }

  public boolean hasPermission(Long opsId, String permission) {
    if (opsId == null) return false;
    var codes = permissionCodesOf(opsId);
    return codes.contains(ALL_PERMISSION) || codes.contains(permission);
  }

  public List<RuoYiSystemDtos.MenuItem> menusOf(Long opsId) {
    var user = user(opsId);
    var granted = doctors.grants(opsId, user, permissions.getMenuPermission(user));
    boolean doctor = doctors.restricted(opsId, user);
    return menus.selectMenuList(user.getUserId()).stream()
        .filter(
            m ->
                "0".equals(m.getStatus())
                    && "0".equals(m.getVisible())
                    && "C".equals(m.getMenuType()))
        .filter(m -> !doctor || visibleDoctorMenu(m))
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

  /** 医生目录及上级目录隐藏/停用时，同步移除其子菜单，避免静态分组绕过菜单配置。 */
  private boolean visibleDoctorMenu(com.ruoyi.common.core.domain.entity.SysMenu menu) {
    if (menu.getPath() == null || !menu.getPath().startsWith("/doctor-management/")) return false;
    var visited = new java.util.HashSet<Long>();
    Long parent = menu.getParentId();
    while (parent != null && parent > 0) {
      if (!visited.add(parent)) return false;
      var directory = menus.selectMenuById(parent);
      if (directory == null
          || !"0".equals(directory.getStatus())
          || !"0".equals(directory.getVisible())) return false;
      parent = directory.getParentId();
    }
    return true;
  }

  public RuoYiSystemDtos.UserInfo userInfoOf(Long opsId) {
    var user = user(opsId);
    var roles = roleCodesOf(opsId);
    return RuoYiSystemDtos.UserInfo.builder()
        .opsId(opsId)
        .username(user.getUserName())
        .nickname(user.getNickName())
        .role(
            doctors.restricted(opsId, user)
                ? "doctor"
                : user.isAdmin() || roles.contains("super_admin") ? "admin" : "editor")
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
