package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.entity.SysOpsRole;
import com.threadfit.glucopilot.auth.entity.SysRole;
import com.threadfit.glucopilot.auth.mapper.SysOpsRoleMapper;
import com.threadfit.glucopilot.auth.mapper.SysRoleMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B 端角色：库表 sys_ops_role / sys_role ↔ 前端 admin|editor。
 * super_admin → admin；其余（含 operator/editor）→ editor。
 */
@Component
@RequiredArgsConstructor
public class OpsRoleResolver {

    public static final String CODE_SUPER_ADMIN = "super_admin";
    public static final String CODE_OPERATOR = "operator";

    public static final String FRONT_ADMIN = "admin";
    public static final String FRONT_EDITOR = "editor";

    private final SysOpsRoleMapper sysOpsRoleMapper;
    private final SysRoleMapper sysRoleMapper;

    /** 登录/鉴权：是否含超级管理员。 */
    public String resolveFrontRole(Long opsId) {
        return hasSuperAdmin(opsId) ? FRONT_ADMIN : FRONT_EDITOR;
    }

    public boolean hasSuperAdmin(Long opsId) {
        return roleCodesOf(opsId).contains(CODE_SUPER_ADMIN);
    }

    public Set<String> roleCodesOf(Long opsId) {
        List<SysOpsRole> binds = sysOpsRoleMapper.selectList(new LambdaQueryWrapper<SysOpsRole>()
                .eq(SysOpsRole::getOpsId, opsId));
        if (binds.isEmpty()) {
            return Set.of();
        }
        List<Long> roleIds = binds.stream().map(SysOpsRole::getRoleId).filter(Objects::nonNull).toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return sysRoleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getRoleCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    /** 前端 role → DB role_id。 */
    public Long requireRoleIdForFront(String frontRole) {
        String code = FRONT_ADMIN.equalsIgnoreCase(frontRole) ? CODE_SUPER_ADMIN : CODE_OPERATOR;
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, code)
                .last("LIMIT 1"));
        if (role == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "系统角色未初始化: " + code);
        }
        return role.getId();
    }

    /** 绑定或替换账号的唯一业务角色（就地更新 role_id，避免软删唯一键冲突）。 */
    public void bindFrontRole(Long opsId, String frontRole) {
        Long roleId = requireRoleIdForFront(frontRole);
        SysOpsRole existing = sysOpsRoleMapper.selectOne(new LambdaQueryWrapper<SysOpsRole>()
                .eq(SysOpsRole::getOpsId, opsId)
                .last("LIMIT 1"));
        if (existing == null) {
            SysOpsRole row = new SysOpsRole();
            row.setOpsId(opsId);
            row.setRoleId(roleId);
            sysOpsRoleMapper.insert(row);
            return;
        }
        if (!Objects.equals(existing.getRoleId(), roleId)) {
            existing.setRoleId(roleId);
            sysOpsRoleMapper.updateById(existing);
        }
    }

    public long countActiveSuperAdmins() {
        SysRole superRole = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, CODE_SUPER_ADMIN)
                .last("LIMIT 1"));
        if (superRole == null) {
            return 0;
        }
        return sysOpsRoleMapper.selectCount(new LambdaQueryWrapper<SysOpsRole>()
                .eq(SysOpsRole::getRoleId, superRole.getId()));
    }
}
