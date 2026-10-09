package com.threadfit.glucopilot.ruoyi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.service.*;
import com.threadfit.glucopilot.auth.service.OpsIdentityService;
import com.threadfit.glucopilot.auth.service.OpsSessionRegistry;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.*;
import com.threadfit.glucopilot.ruoyi.mapper.SysConfigMapper;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Original HTTP DTOs adapted to the official system services. */
@Service
@RequiredArgsConstructor
public class RuoYiSystemService {
  private final ISysRoleService roleService;
  private final ISysMenuService menuService;
  private final ISysDeptService deptService;
  private final ISysPostService postService;
  private final ISysDictTypeService dictTypeService;
  private final ISysDictDataService dictDataService;
  private final ISysNoticeService noticeService;
  private final ISysLogininforService loginLogs;
  private final ISysOperLogService operationLogs;
  private final SysConfigMapper appConfigs;
  private final OpsSessionRegistry sessions;
  private final OpsIdentityService identities;
  private final RedisConnectionFactory redisConnectionFactory;

  private static String flag(Integer value) {
    return Integer.valueOf(1).equals(value) ? "0" : "1";
  }

  private static int enabled(String value) {
    return "0".equals(value) ? 1 : 0;
  }

  private static LocalDateTime local(Date value) {
    return value == null
        ? null
        : LocalDateTime.ofInstant(value.toInstant(), ZoneId.of("Asia/Shanghai"));
  }

  private static <T> T required(T value) {
    if (value == null) throw new BizException(ErrorCode.NOT_FOUND, "系统资源不存在");
    return value;
  }

  private static void ensure(boolean condition, String message) {
    if (!condition) throw new BizException(ErrorCode.BIZ_REJECTED, message);
  }

  public List<RuoYiAdminDtos.RoleItem> listRoles() {
    return roleService.selectRoleAll().stream().map(this::roleView).toList();
  }

  private RuoYiAdminDtos.RoleItem roleView(com.ruoyi.common.core.domain.entity.SysRole v) {
    var item = new RuoYiAdminDtos.RoleItem();
    item.setId(v.getRoleId());
    item.setRoleCode(v.getRoleKey());
    item.setRoleName(v.getRoleName());
    item.setSortNo(v.getRoleSort());
    item.setStatus(enabled(v.getStatus()));
    item.setRemark(v.getRemark());
    item.setPermissionCodes(
        menuService.selectMenuPermsByRoleId(v.getRoleId()).stream().sorted().toList());
    return item;
  }

  private com.ruoyi.common.core.domain.entity.SysRole role(RuoYiAdminDtos.RoleRequest r, Long id) {
    var v = new com.ruoyi.common.core.domain.entity.SysRole();
    v.setRoleId(id);
    v.setRoleKey(r.getRoleCode().trim());
    v.setRoleName(r.getRoleName().trim());
    v.setRoleSort(r.getSortNo());
    v.setStatus(flag(r.getStatus()));
    v.setRemark(r.getRemark());
    v.setDataScope("1");
    var all = menuService.selectMenuList(SecurityUtils.getUserId());
    var codes = r.getPermissionCodes() == null ? List.<String>of() : r.getPermissionCodes();
    for (var code : codes)
      ensure(all.stream().anyMatch(m -> code.equals(m.getPerms())), "未知权限: " + code);
    v.setMenuIds(
        all.stream()
            .filter(m -> codes.contains(m.getPerms()))
            .map(com.ruoyi.common.core.domain.entity.SysMenu::getMenuId)
            .toArray(Long[]::new));
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    ensure(roleService.checkRoleNameUnique(v) && roleService.checkRoleKeyUnique(v), "角色名称或编码已存在");
    return v;
  }

  @Transactional
  public RuoYiAdminDtos.RoleItem createRole(RuoYiAdminDtos.RoleRequest r) {
    var v = role(r, null);
    roleService.insertRole(v);
    return roleView(required(roleService.selectRoleById(v.getRoleId())));
  }

  @Transactional
  public RuoYiAdminDtos.RoleItem updateRole(Long id, RuoYiAdminDtos.RoleRequest r) {
    var old = required(roleService.selectRoleById(id));
    ensure(
        !"super_admin".equals(old.getRoleKey())
            || ("super_admin".equals(r.getRoleCode().trim()) && r.getStatus() == 1),
        "超级管理员角色不能更名或停用");
    var v = role(r, id);
    roleService.updateRole(v);
    sessions.revokeAll();
    return roleView(required(roleService.selectRoleById(id)));
  }

  @Transactional
  public void deleteRole(Long id) {
    var old = required(roleService.selectRoleById(id));
    ensure(!"super_admin".equals(old.getRoleKey()), "超级管理员角色不能删除");
    ensure(roleService.countUserRoleByRoleId(id) == 0, "角色仍被用户使用");
    roleService.deleteRoleById(id);
    sessions.revokeAll();
  }

  private SysMenu view(com.ruoyi.common.core.domain.entity.SysMenu v) {
    var item = new SysMenu();
    item.setId(v.getMenuId());
    item.setMenuName(v.getMenuName());
    item.setParentId(v.getParentId());
    item.setSortNo(v.getOrderNum());
    item.setPathUrl(v.getPath());
    item.setComponentName(v.getComponent());
    item.setQueryString(v.getQuery());
    item.setIsFrame(Integer.valueOf(v.getIsFrame()));
    item.setIsCache(enabled(v.getIsCache()));
    item.setVisible(enabled(v.getVisible()));
    item.setMenuType(v.getMenuType());
    item.setMenuStatus(enabled(v.getStatus()));
    item.setPermCode(v.getPerms());
    item.setIconName(v.getIcon());
    item.setRemark(v.getRemark());
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.common.core.domain.entity.SysMenu input(RuoYiAdminDtos.MenuRequest r, Long id) {
    var v = new com.ruoyi.common.core.domain.entity.SysMenu();
    v.setMenuId(id);
    v.setMenuName(r.getMenuName());
    v.setParentId(r.getParentId());
    v.setOrderNum(r.getSortNo());
    v.setPath(r.getPathUrl());
    v.setComponent(r.getComponentName());
    v.setQuery(r.getQueryString());
    v.setIsFrame(String.valueOf(r.getIsFrame()));
    v.setIsCache(flag(r.getIsCache()));
    v.setVisible(flag(r.getVisible()));
    v.setMenuType(r.getMenuType());
    v.setStatus(flag(r.getStatus()));
    v.setPerms(r.getPermCode());
    v.setIcon(r.getIconName());
    v.setRemark(r.getRemark());
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysMenu> listMenus() {
    return menuService.selectMenuList(SecurityUtils.getUserId()).stream().map(this::view).toList();
  }

  @Transactional
  public SysMenu createMenu(RuoYiAdminDtos.MenuRequest r) {
    var v = input(r, null);
    ensure(List.of("M", "C", "F").contains(v.getMenuType()), "菜单类型无效");
    ensure(!java.util.Objects.equals(v.getMenuId(), v.getParentId()), "父菜单不能是自身");
    ensure(
        menuService.checkMenuNameUnique(v) && menuService.checkRouteConfigUnique(v), "菜单名称或路由已存在");
    menuService.insertMenu(v);
    return view(required(menuService.selectMenuById(v.getMenuId())));
  }

  @Transactional
  public SysMenu updateMenu(Long id, RuoYiAdminDtos.MenuRequest r) {
    required(menuService.selectMenuById(id));
    var v = input(r, id);
    ensure(List.of("M", "C", "F").contains(v.getMenuType()), "菜单类型无效");
    ensure(!java.util.Objects.equals(v.getMenuId(), v.getParentId()), "父菜单不能是自身");
    ensure(
        menuService.checkMenuNameUnique(v) && menuService.checkRouteConfigUnique(v), "菜单名称或路由已存在");
    menuService.updateMenu(v);
    sessions.revokeAll();
    return view(required(menuService.selectMenuById(id)));
  }

  @Transactional
  public void deleteMenu(Long id) {
    required(menuService.selectMenuById(id));
    ensure(!menuService.hasChildByMenuId(id), "请先删除子菜单");
    ensure(!menuService.checkMenuExistRole(id), "菜单已分配给角色");
    menuService.deleteMenuById(id);
    sessions.revokeAll();
  }

  private SysDept view(com.ruoyi.common.core.domain.entity.SysDept v) {
    var item = new SysDept();
    item.setId(v.getDeptId());
    item.setDeptName(v.getDeptName());
    item.setParentId(v.getParentId());
    item.setAncestors(v.getAncestors());
    item.setSortNo(v.getOrderNum());
    item.setLeader(v.getLeader());
    item.setPhone(v.getPhone());
    item.setEmail(v.getEmail());
    item.setDeptStatus(enabled(v.getStatus()));
    item.setRemark(v.getRemark());
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.common.core.domain.entity.SysDept input(RuoYiAdminDtos.DeptRequest r, Long id) {
    var v = new com.ruoyi.common.core.domain.entity.SysDept();
    v.setDeptId(id);
    v.setDeptName(r.getDeptName());
    v.setParentId(r.getParentId());
    v.setOrderNum(r.getSortNo());
    v.setLeader(r.getLeader());
    v.setPhone(r.getPhone());
    v.setEmail(r.getEmail());
    v.setStatus(flag(r.getStatus()));
    v.setRemark(r.getRemark());
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysDept> listDepts() {
    return deptService.selectDeptList(new com.ruoyi.common.core.domain.entity.SysDept()).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public SysDept createDept(RuoYiAdminDtos.DeptRequest r) {
    var v = input(r, null);
    ensure(!java.util.Objects.equals(v.getDeptId(), v.getParentId()), "父部门不能是自身");
    ensure(deptService.checkDeptNameUnique(v), "部门名称已存在");
    deptService.insertDept(v);
    return view(required(deptService.selectDeptById(v.getDeptId())));
  }

  @Transactional
  public SysDept updateDept(Long id, RuoYiAdminDtos.DeptRequest r) {
    required(deptService.selectDeptById(id));
    var v = input(r, id);
    ensure(!java.util.Objects.equals(v.getDeptId(), v.getParentId()), "父部门不能是自身");
    ensure(deptService.checkDeptNameUnique(v), "部门名称已存在");
    deptService.updateDept(v);
    return view(required(deptService.selectDeptById(id)));
  }

  @Transactional
  public void deleteDept(Long id) {
    required(deptService.selectDeptById(id));
    ensure(!deptService.hasChildByDeptId(id) && !deptService.checkDeptExistUser(id), "部门仍有子部门或用户");
    deptService.deleteDeptById(id);
  }

  private SysPost view(com.ruoyi.system.domain.SysPost v) {
    var item = new SysPost();
    item.setId(v.getPostId());
    item.setPostCode(v.getPostCode());
    item.setPostName(v.getPostName());
    item.setPostSort(v.getPostSort());
    item.setPostStatus(enabled(v.getStatus()));
    item.setRemark(v.getRemark());
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.system.domain.SysPost input(RuoYiAdminDtos.PostRequest r, Long id) {
    var v = new com.ruoyi.system.domain.SysPost();
    v.setPostId(id);
    v.setPostCode(r.getPostCode());
    v.setPostName(r.getPostName());
    v.setPostSort(r.getPostSort());
    v.setStatus(flag(r.getStatus()));
    v.setRemark(r.getRemark());
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysPost> listPosts() {
    return postService.selectPostList(new com.ruoyi.system.domain.SysPost()).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public SysPost createPost(RuoYiAdminDtos.PostRequest r) {
    var v = input(r, null);
    ensure(postService.checkPostNameUnique(v) && postService.checkPostCodeUnique(v), "岗位名称或编码已存在");
    postService.insertPost(v);
    return view(required(postService.selectPostById(v.getPostId())));
  }

  @Transactional
  public SysPost updatePost(Long id, RuoYiAdminDtos.PostRequest r) {
    required(postService.selectPostById(id));
    var v = input(r, id);
    ensure(postService.checkPostNameUnique(v) && postService.checkPostCodeUnique(v), "岗位名称或编码已存在");
    postService.updatePost(v);
    return view(required(postService.selectPostById(id)));
  }

  @Transactional
  public void deletePost(Long id) {
    required(postService.selectPostById(id));
    ensure(postService.countUserPostById(id) == 0, "岗位仍被用户使用");
    postService.deletePostById(id);
  }

  private SysDictType view(com.ruoyi.common.core.domain.entity.SysDictType v) {
    var item = new SysDictType();
    item.setId(v.getDictId());
    item.setDictName(v.getDictName());
    item.setDictType(v.getDictType());
    item.setDictStatus(enabled(v.getStatus()));
    item.setRemark(v.getRemark());
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.common.core.domain.entity.SysDictType input(
      RuoYiAdminDtos.DictTypeRequest r, Long id) {
    var v = new com.ruoyi.common.core.domain.entity.SysDictType();
    v.setDictId(id);
    v.setDictName(r.getDictName());
    v.setDictType(r.getDictType());
    v.setStatus(flag(r.getStatus()));
    v.setRemark(r.getRemark());
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysDictType> listDictTypes() {
    return dictTypeService.selectDictTypeAll().stream().map(this::view).toList();
  }

  @Transactional
  public SysDictType createDictType(RuoYiAdminDtos.DictTypeRequest r) {
    var v = input(r, null);
    ensure(dictTypeService.checkDictTypeUnique(v), "字典类型已存在");
    dictTypeService.insertDictType(v);
    return view(required(dictTypeService.selectDictTypeById(v.getDictId())));
  }

  @Transactional
  public SysDictType updateDictType(Long id, RuoYiAdminDtos.DictTypeRequest r) {
    required(dictTypeService.selectDictTypeById(id));
    var v = input(r, id);
    ensure(dictTypeService.checkDictTypeUnique(v), "字典类型已存在");
    dictTypeService.updateDictType(v);
    return view(required(dictTypeService.selectDictTypeById(id)));
  }

  @Transactional
  public void deleteDictType(Long id) {
    required(dictTypeService.selectDictTypeById(id));
    dictTypeService.deleteDictTypeByIds(new Long[] {id});
  }

  private SysDictData view(com.ruoyi.common.core.domain.entity.SysDictData v) {
    var item = new SysDictData();
    item.setId(v.getDictCode());
    item.setDictLabel(v.getDictLabel());
    item.setDictValue(v.getDictValue());
    item.setDictType(v.getDictType());
    item.setDictSort(v.getDictSort() == null ? 0 : Math.toIntExact(v.getDictSort()));
    item.setCssClass(v.getCssClass());
    item.setListClass(v.getListClass());
    item.setIsDefault("Y".equals(v.getIsDefault()) ? 1 : 0);
    item.setDictStatus(enabled(v.getStatus()));
    item.setRemark(v.getRemark());
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.common.core.domain.entity.SysDictData input(
      RuoYiAdminDtos.DictDataRequest r, Long id) {
    var v = new com.ruoyi.common.core.domain.entity.SysDictData();
    v.setDictCode(id);
    v.setDictLabel(r.getDictLabel());
    v.setDictValue(r.getDictValue());
    v.setDictType(r.getDictType());
    v.setDictSort(r.getDictSort().longValue());
    v.setCssClass(r.getCssClass());
    v.setListClass(r.getListClass());
    v.setIsDefault(Integer.valueOf(1).equals(r.getIsDefault()) ? "Y" : "N");
    v.setStatus(flag(r.getStatus()));
    v.setRemark(r.getRemark());
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysDictData> listDictData(String type) {
    var q = new com.ruoyi.common.core.domain.entity.SysDictData();
    q.setDictType(type);
    return dictDataService.selectDictDataList(q).stream().map(this::view).toList();
  }

  @Transactional
  public SysDictData createDictData(RuoYiAdminDtos.DictDataRequest r) {
    var v = input(r, null);
    dictDataService.insertDictData(v);
    return view(required(dictDataService.selectDictDataById(v.getDictCode())));
  }

  @Transactional
  public SysDictData updateDictData(Long id, RuoYiAdminDtos.DictDataRequest r) {
    required(dictDataService.selectDictDataById(id));
    var v = input(r, id);
    dictDataService.updateDictData(v);
    return view(required(dictDataService.selectDictDataById(id)));
  }

  @Transactional
  public void deleteDictData(Long id) {
    required(dictDataService.selectDictDataById(id));
    dictDataService.deleteDictDataByIds(new Long[] {id});
  }

  private SysNotice view(com.ruoyi.system.domain.SysNotice v) {
    var item = new SysNotice();
    item.setId(v.getNoticeId());
    item.setNoticeTitle(v.getNoticeTitle());
    item.setNoticeType(v.getNoticeType());
    item.setNoticeContent(v.getNoticeContent());
    item.setNoticeStatus(enabled(v.getStatus()));
    item.setGmtCreate(local(v.getCreateTime()));
    item.setGmtModified(local(v.getUpdateTime()));
    return item;
  }

  private com.ruoyi.system.domain.SysNotice input(RuoYiAdminDtos.NoticeRequest r, Long id) {
    var v = new com.ruoyi.system.domain.SysNotice();
    v.setNoticeId(id);
    v.setNoticeTitle(r.getTitle());
    v.setNoticeType(r.getType());
    v.setNoticeContent(r.getContent());
    v.setStatus(flag(r.getStatus()));
    v.setCreateBy(SecurityUtils.getUsername());
    v.setUpdateBy(SecurityUtils.getUsername());
    return v;
  }

  public List<SysNotice> listNotices() {
    return noticeService.selectNoticeList(new com.ruoyi.system.domain.SysNotice()).stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public SysNotice createNotice(RuoYiAdminDtos.NoticeRequest r) {
    var v = input(r, null);
    noticeService.insertNotice(v);
    return view(required(noticeService.selectNoticeById(v.getNoticeId())));
  }

  @Transactional
  public SysNotice updateNotice(Long id, RuoYiAdminDtos.NoticeRequest r) {
    required(noticeService.selectNoticeById(id));
    var v = input(r, id);
    noticeService.updateNotice(v);
    return view(required(noticeService.selectNoticeById(id)));
  }

  @Transactional
  public void deleteNotice(Long id) {
    required(noticeService.selectNoticeById(id));
    noticeService.deleteNoticeById(id);
  }

  public List<SysConfig> listConfigs() {
    return appConfigs.selectList(
        new LambdaQueryWrapper<SysConfig>()
            .orderByAsc(SysConfig::getConfigGroup)
            .orderByAsc(SysConfig::getConfigKey));
  }

  private SysConfig config(Long id, RuoYiAdminDtos.ConfigRequest r) {
    var v = id == null ? new SysConfig() : required(appConfigs.selectById(id));
    var other =
        appConfigs.selectOne(
            new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, r.getConfigKey().trim())
                .last("LIMIT 1"));
    ensure(other == null || java.util.Objects.equals(other.getId(), id), "参数键名已存在");
    v.setConfigKey(r.getConfigKey().trim());
    v.setConfigValue(r.getConfigValue());
    v.setConfigGroup(r.getConfigGroup().trim());
    v.setValueType(r.getValueType());
    v.setRemark(r.getRemark());
    v.setModifierName(SecurityUtils.getUsername());
    return v;
  }

  @Transactional
  public SysConfig createConfig(RuoYiAdminDtos.ConfigRequest r) {
    var v = config(null, r);
    appConfigs.insert(v);
    return v;
  }

  @Transactional
  public SysConfig updateConfig(Long id, RuoYiAdminDtos.ConfigRequest r) {
    var v = config(id, r);
    appConfigs.updateById(v);
    return v;
  }

  @Transactional
  public void deleteConfig(Long id) {
    required(appConfigs.selectById(id));
    appConfigs.deleteById(id);
  }

  public List<RuoYiAdminDtos.OnlineSessionItem> listOnline() {
    return sessions.online().stream()
        .map(
            v -> {
              var i = new RuoYiAdminDtos.OnlineSessionItem();
              i.setId(v.getToken());
              i.setOpsId(identities.opsIdOf(v.getUserId()));
              i.setUsername(v.getUsername());
              i.setNickname(v.getUser().getNickName());
              i.setClientIp(v.getIpaddr());
              i.setUserAgent(v.getBrowser() + " / " + v.getOs());
              i.setExpireAt(local(new Date(v.getExpireTime())));
              i.setCreatedAt(local(new Date(v.getLoginTime())));
              return i;
            })
        .toList();
  }

  public void forceLogout(String sessionId) {
    sessions.revokeToken(sessionId);
  }

  public List<SysLoginLog> listLoginLogs() {
    return loginLogs.selectLogininforList(new com.ruoyi.system.domain.SysLogininfor()).stream()
        .limit(500)
        .map(
            v -> {
              var i = new SysLoginLog();
              i.setId(v.getInfoId());
              i.setUsername(v.getUserName());
              i.setLoginStatus(enabled(v.getStatus()));
              i.setLoginMessage(v.getMsg());
              i.setClientIp(v.getIpaddr());
              i.setUserAgent(v.getBrowser() + " / " + v.getOs());
              i.setLoginAt(local(v.getLoginTime()));
              return i;
            })
        .toList();
  }

  public List<SysOperLog> listOperLogs() {
    return operationLogs.selectOperLogList(new com.ruoyi.system.domain.SysOperLog()).stream()
        .limit(500)
        .map(
            v -> {
              var i = new SysOperLog();
              i.setId(v.getOperId());
              i.setTitle(v.getTitle());
              i.setBusinessType(String.valueOf(v.getBusinessType()));
              i.setOperatorName(v.getOperName());
              i.setClientIp(v.getOperIp());
              i.setRequestParam(v.getOperParam());
              i.setOperStatus(v.getStatus() == 0 ? 1 : 0);
              i.setErrorMessage(v.getErrorMsg());
              i.setOperAt(local(v.getOperTime()));
              return i;
            })
        .toList();
  }

  public RuoYiAdminDtos.ServerStatus serverStatus() {
    var r = Runtime.getRuntime();
    var s = new RuoYiAdminDtos.ServerStatus();
    s.setApplication("GlucoPilot / RuoYi");
    s.setJavaVersion(System.getProperty("java.version"));
    s.setOsName(System.getProperty("os.name"));
    s.setProcessors(r.availableProcessors());
    s.setHeapUsedBytes(r.totalMemory() - r.freeMemory());
    s.setHeapMaxBytes(r.maxMemory());
    s.setUptimeMillis(ManagementFactory.getRuntimeMXBean().getUptime());
    s.setActiveSessions(sessions.online().size());
    return s;
  }

  public RuoYiAdminDtos.CacheStatus cacheStatus() {
    var s = new RuoYiAdminDtos.CacheStatus();
    s.setPolicy("仅操作本应用缓存；不执行 FLUSHDB/FLUSHALL");
    try (var c = redisConnectionFactory.getConnection()) {
      c.ping();
      s.setRedisReachable(true);
      s.setDetail("Redis reachable");
    } catch (Exception ex) {
      s.setRedisReachable(false);
      s.setDetail("Redis unavailable");
    }
    return s;
  }
}
