package com.threadfit.glucopilot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.auth.dto.OpsAccountAdminDtos;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpsAccountAdminService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsAccountMapper opsAccountMapper;
    private final OpsProfileMapper opsProfileMapper;
    private final OpsRoleResolver opsRoleResolver;
    private final PasswordEncoder passwordEncoder;

    public List<OpsAccountAdminDtos.AccountItem> list() {
        requireSuperAdminCaller();
        List<OpsAccount> accounts = opsAccountMapper.selectList(new LambdaQueryWrapper<OpsAccount>()
                .orderByAsc(OpsAccount::getId));
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = accounts.stream().map(OpsAccount::getId).toList();
        Map<Long, OpsProfile> profiles = opsProfileMapper.selectList(new LambdaQueryWrapper<OpsProfile>()
                        .in(OpsProfile::getOpsId, ids))
                .stream()
                .collect(Collectors.toMap(OpsProfile::getOpsId, Function.identity(), (a, b) -> a));

        List<OpsAccountAdminDtos.AccountItem> result = new ArrayList<>(accounts.size());
        for (OpsAccount a : accounts) {
            OpsProfile p = profiles.get(a.getId());
            result.add(toItem(a, p));
        }
        return result;
    }

    @Transactional
    public OpsAccountAdminDtos.AccountItem create(OpsAccountAdminDtos.CreateRequest req) {
        requireSuperAdminCaller();
        String username = req.getUsername().trim();
        if (!StringUtils.hasText(username)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "账号不能为空");
        }
        validateFrontRole(req.getRole());
        validateStatus(req.getStatus());

        OpsAccount exists = opsAccountMapper.selectOne(new LambdaQueryWrapper<OpsAccount>()
                .eq(OpsAccount::getUsername, username)
                .last("LIMIT 1"));
        if (exists != null) {
            throw new BizException(ErrorCode.CONFLICT, "账号已存在");
        }

        OpsAccount account = new OpsAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        account.setAccountStatus(req.getStatus());
        opsAccountMapper.insert(account);

        OpsProfile profile = new OpsProfile();
        profile.setOpsId(account.getId());
        profile.setRealName(StringUtils.hasText(req.getNickname()) ? req.getNickname().trim() : username);
        opsProfileMapper.insert(profile);

        opsRoleResolver.bindFrontRole(account.getId(), req.getRole());
        return toItem(account, profile);
    }

    @Transactional
    public OpsAccountAdminDtos.AccountItem update(Long id, OpsAccountAdminDtos.UpdateRequest req) {
        requireSuperAdminCaller();
        OpsAccount account = opsAccountMapper.selectById(id);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }

        boolean wasSuper = opsRoleResolver.hasSuperAdmin(id);
        String nextRole = StringUtils.hasText(req.getRole()) ? req.getRole().trim() : null;
        if (nextRole != null) {
            validateFrontRole(nextRole);
        }
        Integer nextStatus = req.getStatus();
        if (nextStatus != null) {
            validateStatus(nextStatus);
        }

        boolean demotingOrDisablingLastSuper =
                wasSuper
                        && opsRoleResolver.countActiveSuperAdmins() <= 1
                        && (
                        (nextRole != null && !OpsRoleResolver.FRONT_ADMIN.equalsIgnoreCase(nextRole))
                                || (nextStatus != null
                                && nextStatus == CodeEnum.OpsAccountStatus.DISABLED.getCode())
                );
        if (demotingOrDisablingLastSuper) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用或降级最后一个管理员");
        }

        if (StringUtils.hasText(req.getPassword())) {
            account.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        if (nextStatus != null) {
            account.setAccountStatus(nextStatus);
        }
        opsAccountMapper.updateById(account);

        OpsProfile profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, id)
                .last("LIMIT 1"));
        if (req.getNickname() != null) {
            String nick = req.getNickname().trim();
            if (profile == null) {
                profile = new OpsProfile();
                profile.setOpsId(id);
                profile.setRealName(StringUtils.hasText(nick) ? nick : account.getUsername());
                opsProfileMapper.insert(profile);
            } else {
                profile.setRealName(StringUtils.hasText(nick) ? nick : account.getUsername());
                opsProfileMapper.updateById(profile);
            }
        }

        if (nextRole != null) {
            opsRoleResolver.bindFrontRole(id, nextRole);
        }

        if (profile == null) {
            profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                    .eq(OpsProfile::getOpsId, id)
                    .last("LIMIT 1"));
        }
        return toItem(opsAccountMapper.selectById(id), profile);
    }

    private void requireSuperAdminCaller() {
        Long opsId = RequestContext.getOpsId();
        if (opsId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!opsRoleResolver.hasSuperAdmin(opsId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可维护账号与角色");
        }
    }

    private static void validateFrontRole(String role) {
        if (!OpsRoleResolver.FRONT_ADMIN.equalsIgnoreCase(role)
                && !OpsRoleResolver.FRONT_EDITOR.equalsIgnoreCase(role)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色仅支持 admin 或 editor");
        }
    }

    private static void validateStatus(Integer status) {
        if (status == null
                || (status != CodeEnum.OpsAccountStatus.DISABLED.getCode()
                && status != CodeEnum.OpsAccountStatus.NORMAL.getCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态仅支持 0 或 1");
        }
    }

    private OpsAccountAdminDtos.AccountItem toItem(OpsAccount a, OpsProfile p) {
        String nickname = p != null && StringUtils.hasText(p.getRealName())
                ? p.getRealName()
                : a.getUsername();
        return OpsAccountAdminDtos.AccountItem.builder()
                .id(a.getId())
                .username(a.getUsername())
                .password("")
                .nickname(nickname)
                .role(opsRoleResolver.resolveFrontRole(a.getId()))
                .status(a.getAccountStatus() == null
                        ? CodeEnum.OpsAccountStatus.DISABLED.getCode()
                        : a.getAccountStatus())
                .createdAt(a.getGmtCreate() == null ? "" : DT.format(a.getGmtCreate()))
                .lastLoginAt(a.getLastLoginAt() == null ? "" : DT.format(a.getLastLoginAt()))
                .build();
    }
}
