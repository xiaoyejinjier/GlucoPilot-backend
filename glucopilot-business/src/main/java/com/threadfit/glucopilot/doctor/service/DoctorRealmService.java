package com.threadfit.glucopilot.doctor.service;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/** 医生后台边界：即使错误地加了其他角色，也不能获得原管理员业务的访问权限。 */
@Service
@RequiredArgsConstructor
public class DoctorRealmService {
    public static final Set<String> PERMISSIONS =
            Set.of("doctor:workbench:view", "doctor:consultation:manage", "doctor:income:view");
    private final DoctorPortalMapper mapper;

    public boolean restricted(long ops, SysUser user) {
        return (mapper.bindingExists(ops)
                || (user.getRoles() != null
                        && user.getRoles().stream()
                                .anyMatch(
                                        r ->
                                                "doctor".equals(r.getRoleKey())
                                                        && "0".equals(r.getStatus()))));
    }

    public Set<String> grants(long ops, SysUser user, Set<String> granted) {
        return restricted(ops, user)
                ? granted.stream().filter(PERMISSIONS::contains).collect(Collectors.toSet())
                : granted;
    }

    public boolean allowed(String method, String path) {
        return path.startsWith("/admin/v1/doctor/")
                || ("GET".equals(method)
                        && Set.of("/admin/v1/system/user-info", "/admin/v1/system/menus")
                                .contains(path))
                || ("DELETE".equals(method) && "/admin/v1/auth/sessions/current".equals(path))
                || ("POST".equals(method) && "/admin/v1/auth/sessions".equals(path));
    }
}
