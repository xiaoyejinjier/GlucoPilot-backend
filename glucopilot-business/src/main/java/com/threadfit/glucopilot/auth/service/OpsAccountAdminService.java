package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.auth.dto.OpsAccountAdminDtos;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.service.RuoYiUserService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpsAccountAdminService {
  private final RuoYiUserService users;
  private final OpsRoleResolver roles;

  private void requireSuper() {
    if (!roles.hasSuperAdmin(RequestContext.requireOpsId()))
      throw new BizException(ErrorCode.FORBIDDEN);
  }

  public List<OpsAccountAdminDtos.AccountItem> list() {
    requireSuper();
    return users.list().stream().map(this::item).toList();
  }

  public OpsAccountAdminDtos.AccountItem create(OpsAccountAdminDtos.CreateRequest r) {
    requireSuper();
    var v = new RuoYiAdminDtos.UserRequest();
    v.setUsername(r.getUsername());
    v.setPassword(r.getPassword());
    v.setNickname(r.getNickname());
    v.setStatus(r.getStatus());
    v.setRoleCodes(roleCodes(r.getRole()));
    return item(users.create(v));
  }

  public OpsAccountAdminDtos.AccountItem update(Long id, OpsAccountAdminDtos.UpdateRequest r) {
    requireSuper();
    var old = users.get(id);
    var v = new RuoYiAdminDtos.UserRequest();
    v.setUsername(old.getUsername());
    v.setNickname(r.getNickname() == null ? old.getNickname() : r.getNickname());
    v.setPassword(r.getPassword());
    v.setStatus(r.getStatus() == null ? old.getStatus() : r.getStatus());
    v.setMobile(old.getMobile());
    v.setEmail(old.getEmail());
    v.setDeptId(old.getDeptId());
    v.setRoleCodes(r.getRole() == null ? old.getRoleCodes() : roleCodes(r.getRole()));
    v.setPostIds(old.getPostIds());
    return item(users.update(id, v));
  }

  private List<String> roleCodes(String role) {
    if (!List.of("admin", "editor").contains(role))
      throw new BizException(ErrorCode.BAD_REQUEST, "角色无效");
    return List.of("admin".equals(role) ? "super_admin" : "operator");
  }

  private static String date(LocalDateTime d) {
    return d == null ? "" : d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  private OpsAccountAdminDtos.AccountItem item(RuoYiAdminDtos.UserItem v) {
    return OpsAccountAdminDtos.AccountItem.builder()
        .id(v.getId())
        .username(v.getUsername())
        .password("")
        .nickname(v.getNickname())
        .role(v.getRoleCodes().contains("super_admin") ? "admin" : "editor")
        .status(v.getStatus())
        .createdAt(date(v.getCreatedAt()))
        .lastLoginAt(date(v.getLastLoginAt()))
        .build();
  }
}
