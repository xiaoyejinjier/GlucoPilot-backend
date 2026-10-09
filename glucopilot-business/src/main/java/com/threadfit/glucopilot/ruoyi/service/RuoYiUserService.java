package com.threadfit.glucopilot.ruoyi.service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.ISysPostService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.auth.service.OpsIdentityService;
import com.threadfit.glucopilot.auth.service.OpsSessionRegistry;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RuoYiUserService {
  private final ISysUserService users;
  private final ISysRoleService roles;
  private final ISysPostService posts;
  private final OpsIdentityService identities;
  private final OpsSessionRegistry sessions;
  private final OpsAccountMapper businessAccounts;
  private final OpsProfileMapper businessProfiles;
  private final PasswordEncoder encoder;

  public List<RuoYiAdminDtos.UserItem> list() {
    return users.selectUserList(new SysUser()).stream()
        .map(u -> item(users.selectUserById(u.getUserId())))
        .toList();
  }

  public RuoYiAdminDtos.UserItem get(Long opsId) {
    return item(required(opsId));
  }

  private SysUser required(Long opsId) {
    var u = users.selectUserById(identities.userIdOf(opsId));
    if (u == null) throw new BizException(ErrorCode.NOT_FOUND);
    return u;
  }

  private static LocalDateTime local(Date d) {
    return d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneId.of("Asia/Shanghai"));
  }

  private RuoYiAdminDtos.UserItem item(SysUser u) {
    var i = new RuoYiAdminDtos.UserItem();
    i.setId(identities.opsIdOf(u.getUserId()));
    i.setUsername(u.getUserName());
    i.setNickname(u.getNickName());
    i.setMobile(u.getPhonenumber());
    i.setEmail(u.getEmail());
    i.setDeptId(u.getDeptId());
    i.setDeptName(u.getDept() == null ? null : u.getDept().getDeptName());
    i.setStatus("0".equals(u.getStatus()) ? 1 : 0);
    i.setRoleCodes(roles.selectRolePermissionByUserId(u.getUserId()).stream().sorted().toList());
    i.setPostIds(posts.selectPostListByUserId(u.getUserId()));
    i.setCreatedAt(local(u.getCreateTime()));
    i.setLastLoginAt(local(u.getLoginDate()));
    return i;
  }

  private void apply(SysUser u, RuoYiAdminDtos.UserRequest r) {
    if (r.getStatus() == null || !List.of(0, 1).contains(r.getStatus()))
      throw new BizException(ErrorCode.BAD_REQUEST, "账号状态无效");
    if (!StringUtils.hasText(r.getUsername()) || r.getUsername().trim().length() > 64)
      throw new BizException(ErrorCode.BAD_REQUEST, "账号名须为 1 到 64 个字符");
    u.setUserName(r.getUsername().trim());
    u.setNickName(StringUtils.hasText(r.getNickname()) ? r.getNickname().trim() : u.getUserName());
    u.setEmail(r.getEmail());
    u.setPhonenumber(r.getMobile());
    u.setDeptId(r.getDeptId());
    u.setStatus(r.getStatus() == 1 ? "0" : "1");
    u.setDelFlag("0");
    u.setSex("2");
    u.setCreateBy(SecurityUtils.getUsername());
    u.setUpdateBy(SecurityUtils.getUsername());
    if (StringUtils.hasText(r.getPassword())) {
      if (r.getPassword().length() < 8
          || r.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
        throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 8 位且不超过 72 字节");
      u.setPassword(encoder.encode(r.getPassword()));
    }
    var all = roles.selectRoleAll();
    var requested = r.getRoleCodes() == null ? List.<String>of() : r.getRoleCodes();
    for (String code : requested)
      if (all.stream().noneMatch(v -> code.equals(v.getRoleKey()) && "0".equals(v.getStatus())))
        throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在或停用");
    u.setRoleIds(
        all.stream()
            .filter(v -> requested.contains(v.getRoleKey()))
            .map(SysRole::getRoleId)
            .toArray(Long[]::new));
    var postIds = r.getPostIds() == null ? List.<Long>of() : r.getPostIds();
    for (Long id : postIds)
      if (posts.selectPostById(id) == null) throw new BizException(ErrorCode.BAD_REQUEST, "岗位不存在");
    u.setPostIds(postIds.toArray(Long[]::new));
    if (!users.checkUserNameUnique(u) || !users.checkPhoneUnique(u) || !users.checkEmailUnique(u))
      throw new BizException(ErrorCode.CONFLICT, "账号、手机或邮箱已存在");
  }

  @Transactional
  public RuoYiAdminDtos.UserItem create(RuoYiAdminDtos.UserRequest r) {
    if (!StringUtils.hasText(r.getPassword()))
      throw new BizException(ErrorCode.BAD_REQUEST, "新账号必须设置密码");
    var u = new SysUser();
    apply(u, r);
    var a = new OpsAccount();
    a.setUsername(u.getUserName());
    a.setPasswordHash("!ruoyi-managed");
    a.setAccountStatus(r.getStatus());
    businessAccounts.insert(a);
    var p = new OpsProfile();
    p.setOpsId(a.getId());
    p.setRealName(u.getNickName());
    p.setMobile(u.getPhonenumber());
    p.setEmail(u.getEmail());
    p.setDeptId(u.getDeptId());
    businessProfiles.insert(p);
    u.setUserId(a.getId());
    users.insertUser(u);
    identities.bind(a.getId(), u.getUserId());
    return get(a.getId());
  }

  @Transactional
  public RuoYiAdminDtos.UserItem update(Long id, RuoYiAdminDtos.UserRequest r) {
    var u = required(id);
    boolean losesSuper =
        u.isAdmin()
            && (r.getStatus() != 1
                || r.getRoleCodes() == null
                || !r.getRoleCodes().contains("super_admin"));
    if (losesSuper && activeSuperCount() <= 1)
      throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用或降级最后一个管理员");
    apply(u, r);
    users.updateUser(u);
    var account = businessAccounts.selectById(id);
    if (account != null) {
      account.setUsername(u.getUserName());
      account.setAccountStatus(r.getStatus());
      businessAccounts.updateById(account);
    }
    var profile =
        businessProfiles.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpsProfile>()
                .eq(OpsProfile::getOpsId, id));
    if (profile != null) {
      profile.setRealName(u.getNickName());
      profile.setMobile(u.getPhonenumber());
      profile.setEmail(u.getEmail());
      profile.setDeptId(u.getDeptId());
      businessProfiles.updateById(profile);
    }
    sessions.revokeUser(u.getUserId());
    return get(id);
  }

  @Transactional
  public void disable(Long id) {
    var u = required(id);
    if (u.isAdmin() && activeSuperCount() <= 1)
      throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用最后一个管理员");
    if (u.getUserId().equals(SecurityUtils.getUserId()))
      throw new BizException(ErrorCode.BIZ_REJECTED, "不能停用当前账号");
    u.setStatus("1");
    users.updateUserStatus(u);
    sessions.revokeUser(u.getUserId());
    var account = businessAccounts.selectById(id);
    if (account != null) {
      account.setAccountStatus(0);
      businessAccounts.updateById(account);
    }
  }

  @Transactional
  public void delete(Long id) {
    var u = required(id);
    if (u.isAdmin() && activeSuperCount() <= 1)
      throw new BizException(ErrorCode.BIZ_REJECTED, "不能删除最后一个管理员");
    if (u.getUserId().equals(SecurityUtils.getUserId()))
      throw new BizException(ErrorCode.BIZ_REJECTED, "不能删除当前账号");
    users.deleteUserById(u.getUserId());
    sessions.revokeUser(u.getUserId());
  }

  public long activeSuperCount() {
    return users.selectUserList(new SysUser()).stream()
        .map(v -> users.selectUserById(v.getUserId()))
        .filter(u -> u.isAdmin() && "0".equals(u.getStatus()))
        .count();
  }
}
