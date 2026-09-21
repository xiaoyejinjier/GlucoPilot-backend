package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.dto.RuoYiSystemDtos;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.entity.SysOpsRole;
import com.threadfit.glucopilot.auth.entity.SysPermission;
import com.threadfit.glucopilot.auth.entity.SysRolePermission;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.auth.mapper.SysOpsRoleMapper;
import com.threadfit.glucopilot.auth.mapper.SysPermissionMapper;
import com.threadfit.glucopilot.auth.mapper.SysRolePermissionMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将现有运营账号 RBAC 接入 RuoYi 的角色、权限、菜单模型。
 * 超级管理员沿用 RuoYi 的全权限语义，不受逐条权限记录限制。
 */
@Service
@RequiredArgsConstructor
public class RuoYiAuthorizationService {

    public static final String ALL_PERMISSION = "*:*:*";
    private static final int MENU_TYPE = 1;
    private static final int ENABLED = 1;

    private final OpsAccountMapper opsAccountMapper;
    private final OpsProfileMapper opsProfileMapper;
    private final SysOpsRoleMapper sysOpsRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final OpsRoleResolver opsRoleResolver;

    public boolean hasPermission(Long opsId, String permission) {
        if (opsId == null || !StringUtils.hasText(permission)) {
            return false;
        }
        Set<String> roleCodes = roleCodesOf(opsId);
        return roleCodes.contains(OpsRoleResolver.CODE_SUPER_ADMIN)
                || permissionCodesOf(opsId, roleCodes).contains(permission.trim());
    }

    public Set<String> roleCodesOf(Long opsId) {
        return opsRoleResolver.roleCodesOf(opsId);
    }

    public Set<String> permissionCodesOf(Long opsId) {
        Set<String> roleCodes = roleCodesOf(opsId);
        return permissionCodesOf(opsId, roleCodes);
    }

    public List<RuoYiSystemDtos.MenuItem> menusOf(Long opsId) {
        Set<String> roleCodes = roleCodesOf(opsId);
        LambdaQueryWrapper<SysPermission> query = new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getPermType, MENU_TYPE)
                .eq(SysPermission::getPermStatus, ENABLED)
                .orderByAsc(SysPermission::getSortNo)
                .orderByAsc(SysPermission::getId);
        if (!roleCodes.contains(OpsRoleResolver.CODE_SUPER_ADMIN)) {
            Set<String> codes = permissionCodesOf(opsId, roleCodes);
            if (codes.isEmpty()) {
                return List.of();
            }
            query.in(SysPermission::getPermCode, codes);
        }
        return sysPermissionMapper.selectList(query).stream()
                .map(permission -> RuoYiSystemDtos.MenuItem.builder()
                        .permission(permission.getPermCode())
                        .name(permission.getPermName())
                        .path(permission.getPathUrl())
                        .order(permission.getSortNo())
                        .build())
                .toList();
    }

    public RuoYiSystemDtos.UserInfo userInfoOf(Long opsId) {
        OpsAccount account = opsAccountMapper.selectById(opsId);
        if (account == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "运营账号不存在");
        }
        OpsProfile profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, opsId)
                .last("LIMIT 1"));
        Set<String> roles = roleCodesOf(opsId);
        List<String> permissions = permissionCodesOf(opsId, roles).stream().sorted().toList();
        if (roles.contains(OpsRoleResolver.CODE_SUPER_ADMIN)) {
            permissions = List.of(ALL_PERMISSION);
        }
        String nickname = profile != null && StringUtils.hasText(profile.getRealName())
                ? profile.getRealName()
                : account.getUsername();
        return RuoYiSystemDtos.UserInfo.builder()
                .opsId(opsId)
                .username(account.getUsername())
                .nickname(nickname)
                .role(opsRoleResolver.resolveFrontRole(opsId))
                .roles(roles.stream().sorted().toList())
                .permissions(permissions)
                .menus(menusOf(opsId))
                .build();
    }

    private Set<String> permissionCodesOf(Long opsId, Set<String> roleCodes) {
        if (roleCodes.contains(OpsRoleResolver.CODE_SUPER_ADMIN)) {
            return Set.of(ALL_PERMISSION);
        }
        List<Long> roleIds = sysOpsRoleMapper.selectList(new LambdaQueryWrapper<SysOpsRole>()
                        .eq(SysOpsRole::getOpsId, opsId))
                .stream()
                .map(SysOpsRole::getRoleId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<Long> permissionIds = sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, roleIds))
                .stream()
                .map(SysRolePermission::getPermId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (permissionIds.isEmpty()) {
            return Set.of();
        }
        return sysPermissionMapper.selectBatchIds(permissionIds).stream()
                .filter(permission -> ENABLED == (permission.getPermStatus() == null ? 0 : permission.getPermStatus()))
                .map(SysPermission::getPermCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }
}
