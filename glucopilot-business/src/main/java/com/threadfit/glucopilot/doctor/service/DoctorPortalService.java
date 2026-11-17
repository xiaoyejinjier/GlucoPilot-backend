package com.threadfit.glucopilot.doctor.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.threadfit.glucopilot.auth.service.OpsSessionRegistry;
import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.consultation.service.ConsultationService;
import com.threadfit.glucopilot.doctor.dto.DoctorPortalDtos.*;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.service.RuoYiUserService;
import com.threadfit.glucopilot.wallet.service.WalletOrderService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

import java.util.*;

@Service
@RequiredArgsConstructor
public class DoctorPortalService {
    private final DoctorPortalMapper mapper;
    private final ConsultationMapper profiles;
    private final ConsultationService consultations;
    private final WalletOrderService wallets;
    private final RuoYiUserService users;
    private final ISysRoleService roles;
    private final ISysMenuService menus;
    private final OpsSessionRegistry sessions;

    public long doctorId(long ops) {
        Long id = mapper.boundDoctor(ops);
        if (id == null) throw new BizException(ErrorCode.FORBIDDEN, "医生账号未绑定、已停用或资质未通过审核");
        return id;
    }

    public PageResult<Account> accounts(PageQuery page) {
        checkPage(page);
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                mapper.accountCount(),
                mapper.accounts(page.getPageSize(), page.offset()));
    }

    @Transactional
    public void create(long actor, long doctor, CreateAccount form) {
        var profile = profiles.lockDoctor(doctor);
        if (profile == null
                || !"APPROVED".equals(profile.getStatus())
                || profiles.doctor(doctor) == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请先通过医生资质审核");
        if (mapper.opsForDoctor(doctor) != null)
            throw new BizException(ErrorCode.CONFLICT, "该医生已开通后台账号");
        var request = new RuoYiAdminDtos.UserRequest();
        request.setUsername(form.username());
        request.setPassword(form.password());
        request.setNickname(profile.getRealName());
        request.setStatus(1);
        request.setRoleCodes(List.of("doctor"));
        var account = users.create(request);
        mapper.bind(doctor, account.getId(), actor);
    }

    @Transactional
    public void update(long doctor, UpdateAccount form) {
        if (profiles.lockDoctor(doctor) == null) throw new BizException(ErrorCode.NOT_FOUND);
        Long ops = mapper.opsForDoctor(doctor);
        if (ops == null) throw new BizException(ErrorCode.NOT_FOUND, "医生尚未开通后台账号");
        var old = users.get(ops);
        var request = new RuoYiAdminDtos.UserRequest();
        request.setUsername(old.getUsername());
        request.setNickname(old.getNickname());
        request.setMobile(old.getMobile());
        request.setEmail(old.getEmail());
        request.setDeptId(old.getDeptId());
        request.setPostIds(old.getPostIds());
        request.setRoleCodes(old.getRoleCodes());
        request.setStatus(form.status());
        request.setPassword(form.password());
        users.update(ops, request);
    }

    public Workbench workbench(long ops, PageQuery page) {
        long doctor = doctorId(ops);
        return new Workbench(
                consultations.doctor(doctor), consultations.list(doctor, false, false, null, page));
    }

    public Income income(long ops, PageQuery page) {
        checkPage(page);
        long doctor = doctorId(ops);
        return new Income(
                wallets.summary(doctor),
                PageResult.of(
                        page.getPageNum(),
                        page.getPageSize(),
                        mapper.incomeCount(doctor),
                        mapper.income(doctor, page.getPageSize(), page.offset())));
    }

    public Permissions permissions() {
        return new Permissions(
                menus.selectMenuPermsByRoleId(doctorRole().getRoleId()).stream()
                        .filter(DoctorRealmService.PERMISSIONS::contains)
                        .sorted()
                        .toList());
    }

    @Transactional
    public Permissions permissions(Permissions form) {
        if (!DoctorRealmService.PERMISSIONS.containsAll(form.permissions()))
            throw new BizException(ErrorCode.BAD_REQUEST, "只能配置医生功能权限");
        var role = doctorRole();
        var allowed =
                menus.selectMenuList(SecurityUtils.getUserId()).stream()
                        .filter(m -> form.permissions().contains(m.getPerms()))
                        .map(m -> m.getMenuId())
                        .toArray(Long[]::new);
        role.setMenuIds(allowed);
        role.setUpdateBy(SecurityUtils.getUsername());
        roles.updateRole(role);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sessions.online().stream()
                                .filter(
                                        l ->
                                                l.getUser() != null
                                                        && l.getUser().getRoles() != null
                                                        && l.getUser().getRoles().stream()
                                                                .anyMatch(
                                                                        r ->
                                                                                "doctor"
                                                                                        .equals(
                                                                                                r
                                                                                                        .getRoleKey())))
                                .forEach(l -> sessions.revokeToken(l.getToken()));
                    }
                });
        return permissions();
    }

    private SysRole doctorRole() {
        return roles.selectRoleAll().stream()
                .filter(r -> "doctor".equals(r.getRoleKey()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "医生角色不存在，请检查数据库迁移"));
    }

    private void checkPage(PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
    }
}
