package com.threadfit.glucopilot.ruoyi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.entity.SysOpsRole;
import com.threadfit.glucopilot.auth.entity.SysRole;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.auth.mapper.SysOpsRoleMapper;
import com.threadfit.glucopilot.auth.mapper.SysRoleMapper;
import com.threadfit.glucopilot.auth.service.OpsRoleResolver;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.SysDept;
import com.threadfit.glucopilot.ruoyi.entity.SysOpsPost;
import com.threadfit.glucopilot.ruoyi.entity.SysPost;
import com.threadfit.glucopilot.ruoyi.mapper.SysDeptMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysOpsPostMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysPostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** RuoYi user management mapped to the existing GlucoPilot operations-account identity. */
@Service
@RequiredArgsConstructor
public class RuoYiUserService {

    private final OpsAccountMapper opsAccountMapper;
    private final OpsProfileMapper opsProfileMapper;
    private final SysOpsRoleMapper sysOpsRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysPostMapper sysPostMapper;
    private final SysOpsPostMapper sysOpsPostMapper;
    private final OpsRoleResolver opsRoleResolver;
    private final PasswordEncoder passwordEncoder;

    public List<RuoYiAdminDtos.UserItem> list() {
        List<OpsAccount> accounts = opsAccountMapper.selectList(new LambdaQueryWrapper<OpsAccount>()
                .orderByAsc(OpsAccount::getId));
        if (accounts.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = accounts.stream().map(OpsAccount::getId).collect(Collectors.toSet());
        Map<Long, OpsProfile> profiles = opsProfileMapper.selectList(new LambdaQueryWrapper<OpsProfile>()
                        .in(OpsProfile::getOpsId, ids))
                .stream().collect(Collectors.toMap(OpsProfile::getOpsId, Function.identity(), (a, b) -> a));
        Map<Long, String> deptNames = sysDeptMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a));
        Map<Long, SysRole> roles = sysRoleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, Function.identity()));
        Map<Long, List<String>> roleCodes = sysOpsRoleMapper.selectList(new LambdaQueryWrapper<SysOpsRole>()
                        .in(SysOpsRole::getOpsId, ids))
                .stream().collect(Collectors.groupingBy(SysOpsRole::getOpsId,
                        Collectors.mapping(link -> {
                            SysRole role = roles.get(link.getRoleId());
                            return role == null ? null : role.getRoleCode();
                        }, Collectors.filtering(java.util.Objects::nonNull, Collectors.toList()))));
        Map<Long, List<Long>> postIds = sysOpsPostMapper.selectList(new LambdaQueryWrapper<SysOpsPost>()
                        .in(SysOpsPost::getOpsId, ids))
                .stream().collect(Collectors.groupingBy(SysOpsPost::getOpsId,
                        Collectors.mapping(SysOpsPost::getPostId, Collectors.toList())));
        return accounts.stream().map(account -> toItem(account, profiles.get(account.getId()), deptNames,
                roleCodes.getOrDefault(account.getId(), List.of()), postIds.getOrDefault(account.getId(), List.of())))
                .toList();
    }

    @Transactional
    public RuoYiAdminDtos.UserItem create(RuoYiAdminDtos.UserRequest request) {
        String username = request.getUsername().trim();
        if (!StringUtils.hasText(request.getPassword()) || request.getPassword().length() < 8) {
            throw new BizException(ErrorCode.BAD_REQUEST, "初始密码至少需要 8 位");
        }
        if (opsAccountMapper.selectOne(new LambdaQueryWrapper<OpsAccount>()
                .eq(OpsAccount::getUsername, username).last("LIMIT 1")) != null) {
            throw new BizException(ErrorCode.CONFLICT, "登录名已存在");
        }
        OpsAccount account = new OpsAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        account.setAccountStatus(request.getStatus());
        opsAccountMapper.insert(account);
        OpsProfile profile = new OpsProfile();
        profile.setOpsId(account.getId());
        profile.setDeptId(validateDept(request.getDeptId()));
        profile.setRealName(valueOr(request.getNickname(), username));
        profile.setMobile(trimOrNull(request.getMobile()));
        profile.setEmail(trimOrNull(request.getEmail()));
        opsProfileMapper.insert(profile);
        replaceRoles(account.getId(), request.getRoleCodes());
        replacePosts(account.getId(), request.getPostIds());
        return loadOne(account.getId());
    }

    @Transactional
    public RuoYiAdminDtos.UserItem update(Long id, RuoYiAdminDtos.UserRequest request) {
        OpsAccount account = requiredAccount(id);
        if (!account.getUsername().equals(request.getUsername().trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "登录名创建后不可修改");
        }
        boolean losingLastSuper = opsRoleResolver.hasSuperAdmin(id)
                && opsRoleResolver.countActiveSuperAdmins() <= 1
                && (request.getStatus() == 0 || !safeList(request.getRoleCodes()).contains(OpsRoleResolver.CODE_SUPER_ADMIN));
        if (losingLastSuper) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用或移除最后一个超级管理员");
        }
        account.setAccountStatus(request.getStatus());
        if (StringUtils.hasText(request.getPassword())) {
            if (request.getPassword().length() < 8) {
                throw new BizException(ErrorCode.BAD_REQUEST, "新密码至少需要 8 位");
            }
            account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        opsAccountMapper.updateById(account);
        OpsProfile profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, id).last("LIMIT 1"));
        if (profile == null) {
            profile = new OpsProfile();
            profile.setOpsId(id);
            opsProfileMapper.insert(profile);
        }
        profile.setDeptId(validateDept(request.getDeptId()));
        profile.setRealName(valueOr(request.getNickname(), account.getUsername()));
        profile.setMobile(trimOrNull(request.getMobile()));
        profile.setEmail(trimOrNull(request.getEmail()));
        opsProfileMapper.updateById(profile);
        replaceRoles(id, request.getRoleCodes());
        replacePosts(id, request.getPostIds());
        return loadOne(id);
    }

    @Transactional
    public void disable(Long id) {
        Long caller = RequestContext.getOpsId();
        if (caller != null && caller.equals(id)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用当前登录账号");
        }
        if (opsRoleResolver.hasSuperAdmin(id) && opsRoleResolver.countActiveSuperAdmins() <= 1) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用最后一个超级管理员");
        }
        OpsAccount account = requiredAccount(id);
        account.setAccountStatus(0);
        opsAccountMapper.updateById(account);
    }

    private RuoYiAdminDtos.UserItem loadOne(Long id) {
        OpsAccount account = requiredAccount(id);
        OpsProfile profile = opsProfileMapper.selectOne(new LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, id).last("LIMIT 1"));
        Map<Long, String> depts = sysDeptMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName));
        return toItem(account, profile, depts, roleCodes(id), postIds(id));
    }

    private RuoYiAdminDtos.UserItem toItem(OpsAccount account, OpsProfile profile, Map<Long, String> deptNames,
                                              List<String> roleCodes, List<Long> postIds) {
        RuoYiAdminDtos.UserItem item = new RuoYiAdminDtos.UserItem();
        item.setId(account.getId());
        item.setUsername(account.getUsername());
        item.setNickname(profile == null ? account.getUsername() : valueOr(profile.getRealName(), account.getUsername()));
        item.setMobile(profile == null ? null : profile.getMobile());
        item.setEmail(profile == null ? null : profile.getEmail());
        item.setDeptId(profile == null ? null : profile.getDeptId());
        item.setDeptName(profile == null || profile.getDeptId() == null ? null : deptNames.get(profile.getDeptId()));
        item.setStatus(account.getAccountStatus());
        item.setRoleCodes(roleCodes.stream().sorted().toList());
        item.setPostIds(postIds.stream().sorted().toList());
        item.setLastLoginAt(account.getLastLoginAt());
        item.setCreatedAt(account.getGmtCreate());
        return item;
    }

    private void replaceRoles(Long opsId, List<String> requestedCodes) {
        List<String> codes = safeList(requestedCodes).stream().map(String::trim)
                .filter(StringUtils::hasText).distinct().toList();
        if (codes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少选择一个角色");
        }
        List<SysRole> roles = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getRoleCode, codes).eq(SysRole::getRoleStatus, 1));
        if (roles.size() != codes.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在不存在或已停用的角色");
        }
        retire(sysOpsRoleMapper, sysOpsRoleMapper.selectList(new LambdaQueryWrapper<SysOpsRole>()
                .eq(SysOpsRole::getOpsId, opsId)));
        for (SysRole role : roles) {
            SysOpsRole link = new SysOpsRole();
            link.setOpsId(opsId);
            link.setRoleId(role.getId());
            sysOpsRoleMapper.insert(link);
        }
    }

    private void replacePosts(Long opsId, List<Long> requestedIds) {
        List<Long> ids = safeList(requestedIds).stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            long count = sysPostMapper.selectCount(new LambdaQueryWrapper<SysPost>()
                    .in(SysPost::getId, ids).eq(SysPost::getPostStatus, 1));
            if (count != ids.size()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "存在不存在或已停用的岗位");
            }
        }
        retire(sysOpsPostMapper, sysOpsPostMapper.selectList(new LambdaQueryWrapper<SysOpsPost>()
                .eq(SysOpsPost::getOpsId, opsId)));
        for (Long postId : ids) {
            SysOpsPost link = new SysOpsPost();
            link.setOpsId(opsId);
            link.setPostId(postId);
            sysOpsPostMapper.insert(link);
        }
    }

    private List<String> roleCodes(Long opsId) {
        Map<Long, String> codes = sysRoleMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleCode));
        return sysOpsRoleMapper.selectList(new LambdaQueryWrapper<SysOpsRole>().eq(SysOpsRole::getOpsId, opsId))
                .stream().map(link -> codes.get(link.getRoleId())).filter(java.util.Objects::nonNull).toList();
    }

    private List<Long> postIds(Long opsId) {
        return sysOpsPostMapper.selectList(new LambdaQueryWrapper<SysOpsPost>().eq(SysOpsPost::getOpsId, opsId))
                .stream().map(SysOpsPost::getPostId).toList();
    }

    private Long validateDept(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        if (dept == null || dept.getDeptStatus() == null || dept.getDeptStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不存在或已停用");
        }
        return deptId;
    }

    private OpsAccount requiredAccount(Long id) {
        OpsAccount account = opsAccountMapper.selectById(id);
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运营用户不存在");
        }
        return account;
    }

    private static <T extends com.threadfit.glucopilot.common.model.BaseEntity> void retire(BaseMapper<T> mapper, Collection<T> links) {
        LocalDateTime now = LocalDateTime.now();
        long actor = RequestContext.operatorIdOrZero();
        for (T link : links) {
            link.setIsDeleted(1);
            link.setDeletedAt(now);
            link.setDeletedBy(actor);
            mapper.updateById(link);
        }
    }

    private static <T> List<T> safeList(List<T> input) { return input == null ? List.of() : input; }
    private static String valueOr(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private static String trimOrNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
}
