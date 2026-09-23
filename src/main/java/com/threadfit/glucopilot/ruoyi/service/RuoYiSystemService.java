package com.threadfit.glucopilot.ruoyi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.auth.entity.OpsAccount;
import com.threadfit.glucopilot.auth.entity.OpsProfile;
import com.threadfit.glucopilot.auth.entity.OpsSession;
import com.threadfit.glucopilot.auth.entity.SysPermission;
import com.threadfit.glucopilot.auth.entity.SysOpsRole;
import com.threadfit.glucopilot.auth.entity.SysRole;
import com.threadfit.glucopilot.auth.entity.SysRolePermission;
import com.threadfit.glucopilot.auth.mapper.OpsAccountMapper;
import com.threadfit.glucopilot.auth.mapper.OpsProfileMapper;
import com.threadfit.glucopilot.auth.mapper.OpsSessionMapper;
import com.threadfit.glucopilot.auth.mapper.SysPermissionMapper;
import com.threadfit.glucopilot.auth.mapper.SysOpsRoleMapper;
import com.threadfit.glucopilot.auth.mapper.SysRolePermissionMapper;
import com.threadfit.glucopilot.auth.mapper.SysRoleMapper;
import com.threadfit.glucopilot.auth.service.OpsRoleResolver;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.common.model.BaseEntity;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.SysConfig;
import com.threadfit.glucopilot.ruoyi.entity.SysDept;
import com.threadfit.glucopilot.ruoyi.entity.SysDictData;
import com.threadfit.glucopilot.ruoyi.entity.SysDictType;
import com.threadfit.glucopilot.ruoyi.entity.SysLoginLog;
import com.threadfit.glucopilot.ruoyi.entity.SysMenu;
import com.threadfit.glucopilot.ruoyi.entity.SysNotice;
import com.threadfit.glucopilot.ruoyi.entity.SysOperLog;
import com.threadfit.glucopilot.ruoyi.entity.SysOpsPost;
import com.threadfit.glucopilot.ruoyi.entity.SysPost;
import com.threadfit.glucopilot.ruoyi.mapper.SysConfigMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysDeptMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysDictDataMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysDictTypeMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysLoginLogMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysMenuMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysNoticeMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysOperLogMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysOpsPostMapper;
import com.threadfit.glucopilot.ruoyi.mapper.SysPostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Complete RuoYi system and monitor functions adapted to existing GlucoPilot identities and API conventions. */
@Service
@RequiredArgsConstructor
public class RuoYiSystemService {

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysOpsRoleMapper sysOpsRoleMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysPostMapper sysPostMapper;
    private final SysOpsPostMapper sysOpsPostMapper;
    private final SysDictTypeMapper sysDictTypeMapper;
    private final SysDictDataMapper sysDictDataMapper;
    private final SysNoticeMapper sysNoticeMapper;
    private final SysConfigMapper sysConfigMapper;
    private final SysLoginLogMapper sysLoginLogMapper;
    private final SysOperLogMapper sysOperLogMapper;
    private final OpsSessionMapper opsSessionMapper;
    private final OpsAccountMapper opsAccountMapper;
    private final OpsProfileMapper opsProfileMapper;
    private final RedisConnectionFactory redisConnectionFactory;

    public List<RuoYiAdminDtos.RoleItem> listRoles() {
        Map<Long, List<String>> permissions = rolePermissionCodes();
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getSortNo).orderByAsc(SysRole::getId))
                .stream().map(role -> toRoleItem(role, permissions.getOrDefault(role.getId(), List.of()))).toList();
    }

    @Transactional
    public RuoYiAdminDtos.RoleItem createRole(RuoYiAdminDtos.RoleRequest request) {
        String code = requireRoleCode(request.getRoleCode());
        if (sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, code).last("LIMIT 1")) != null) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        SysRole role = new SysRole();
        applyRole(role, request, code);
        sysRoleMapper.insert(role);
        replaceRolePermissions(role.getId(), request.getPermissionCodes());
        return toRoleItem(role, permissionCodesOf(role.getId()));
    }

    @Transactional
    public RuoYiAdminDtos.RoleItem updateRole(Long id, RuoYiAdminDtos.RoleRequest request) {
        SysRole role = required(sysRoleMapper, id, "角色不存在");
        if (OpsRoleResolver.CODE_SUPER_ADMIN.equals(role.getRoleCode())
                && (!OpsRoleResolver.CODE_SUPER_ADMIN.equals(request.getRoleCode().trim()) || request.getStatus() != 1)) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "超级管理员角色不能更名或停用");
        }
        String code = requireRoleCode(request.getRoleCode());
        SysRole duplicate = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, code).last("LIMIT 1"));
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在");
        }
        applyRole(role, request, code);
        sysRoleMapper.updateById(role);
        replaceRolePermissions(id, request.getPermissionCodes());
        return toRoleItem(role, permissionCodesOf(id));
    }

    @Transactional
    public void deleteRole(Long id) {
        SysRole role = required(sysRoleMapper, id, "角色不存在");
        if (OpsRoleResolver.CODE_SUPER_ADMIN.equals(role.getRoleCode())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "超级管理员角色不能删除");
        }
        if (sysOpsRoleMapper.selectCount(new LambdaQueryWrapper<SysOpsRole>().eq(SysOpsRole::getRoleId, id)) > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "角色仍被运营用户使用，不能删除");
        }
        long inUse = sysRolePermissionMapper.selectCount(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
        if (inUse > 0) {
            retire(sysRolePermissionMapper, sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id)));
        }
        softDelete(sysRoleMapper, role);
    }

    public List<SysMenu> listMenus() {
        return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSortNo).orderByAsc(SysMenu::getId));
    }

    @Transactional
    public SysMenu createMenu(RuoYiAdminDtos.MenuRequest request) {
        validateMenu(request, null);
        SysMenu menu = new SysMenu();
        applyMenu(menu, request);
        sysMenuMapper.insert(menu);
        syncPermission(menu);
        return menu;
    }

    @Transactional
    public SysMenu updateMenu(Long id, RuoYiAdminDtos.MenuRequest request) {
        SysMenu menu = required(sysMenuMapper, id, "菜单不存在");
        validateMenu(request, id);
        String oldPerm = menu.getPermCode();
        applyMenu(menu, request);
        sysMenuMapper.updateById(menu);
        if (StringUtils.hasText(oldPerm) && !oldPerm.equals(menu.getPermCode())) {
            disablePermission(oldPerm);
        }
        syncPermission(menu);
        return menu;
    }

    @Transactional
    public void deleteMenu(Long id) {
        SysMenu menu = required(sysMenuMapper, id, "菜单不存在");
        if (sysMenuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id)) > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "请先删除子菜单");
        }
        softDelete(sysMenuMapper, menu);
        disablePermission(menu.getPermCode());
    }

    public List<SysDept> listDepts() {
        return sysDeptMapper.selectList(new LambdaQueryWrapper<SysDept>().orderByAsc(SysDept::getParentId)
                .orderByAsc(SysDept::getSortNo).orderByAsc(SysDept::getId));
    }

    @Transactional
    public SysDept createDept(RuoYiAdminDtos.DeptRequest request) {
        validateDepartment(request.getParentId(), null);
        SysDept dept = new SysDept();
        applyDept(dept, request);
        dept.setAncestors(ancestorsFor(dept.getParentId()));
        sysDeptMapper.insert(dept);
        return dept;
    }

    @Transactional
    public SysDept updateDept(Long id, RuoYiAdminDtos.DeptRequest request) {
        SysDept dept = required(sysDeptMapper, id, "部门不存在");
        validateDepartment(request.getParentId(), id);
        applyDept(dept, request);
        dept.setAncestors(ancestorsFor(dept.getParentId()));
        sysDeptMapper.updateById(dept);
        return dept;
    }

    @Transactional
    public void deleteDept(Long id) {
        SysDept dept = required(sysDeptMapper, id, "部门不存在");
        if (sysDeptMapper.selectCount(new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, id)) > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "请先删除子部门");
        }
        if (opsProfileMapper.selectCount(new LambdaQueryWrapper<OpsProfile>().eq(OpsProfile::getDeptId, id)) > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "部门下仍有关联用户");
        }
        softDelete(sysDeptMapper, dept);
    }

    public List<SysPost> listPosts() {
        return sysPostMapper.selectList(new LambdaQueryWrapper<SysPost>().orderByAsc(SysPost::getPostSort).orderByAsc(SysPost::getId));
    }

    @Transactional
    public SysPost createPost(RuoYiAdminDtos.PostRequest request) {
        if (sysPostMapper.selectCount(new LambdaQueryWrapper<SysPost>().eq(SysPost::getPostCode, request.getPostCode().trim())) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "岗位编码已存在");
        }
        SysPost post = new SysPost();
        applyPost(post, request);
        sysPostMapper.insert(post);
        return post;
    }

    @Transactional
    public SysPost updatePost(Long id, RuoYiAdminDtos.PostRequest request) {
        SysPost post = required(sysPostMapper, id, "岗位不存在");
        SysPost duplicate = sysPostMapper.selectOne(new LambdaQueryWrapper<SysPost>().eq(SysPost::getPostCode, request.getPostCode().trim()).last("LIMIT 1"));
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException(ErrorCode.CONFLICT, "岗位编码已存在");
        }
        applyPost(post, request);
        sysPostMapper.updateById(post);
        return post;
    }

    @Transactional
    public void deletePost(Long id) {
        SysPost post = required(sysPostMapper, id, "岗位不存在");
        if (sysOpsPostMapper.selectCount(new LambdaQueryWrapper<SysOpsPost>().eq(SysOpsPost::getPostId, id)) > 0) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "岗位已被运营用户使用");
        }
        softDelete(sysPostMapper, post);
    }

    public List<SysDictType> listDictTypes() { return sysDictTypeMapper.selectList(new LambdaQueryWrapper<SysDictType>().orderByAsc(SysDictType::getDictType)); }
    public List<SysDictData> listDictData(String type) {
        LambdaQueryWrapper<SysDictData> query = new LambdaQueryWrapper<SysDictData>().orderByAsc(SysDictData::getDictType).orderByAsc(SysDictData::getDictSort);
        if (StringUtils.hasText(type)) query.eq(SysDictData::getDictType, type.trim());
        return sysDictDataMapper.selectList(query);
    }

    @Transactional
    public SysDictType createDictType(RuoYiAdminDtos.DictTypeRequest request) {
        ensureDictTypeUnused(request.getDictType(), null);
        SysDictType type = new SysDictType(); applyDictType(type, request); sysDictTypeMapper.insert(type); return type;
    }
    @Transactional
    public SysDictType updateDictType(Long id, RuoYiAdminDtos.DictTypeRequest request) {
        SysDictType type = required(sysDictTypeMapper, id, "字典类型不存在");
        ensureDictTypeUnused(request.getDictType(), id);
        String oldType = type.getDictType(); applyDictType(type, request); sysDictTypeMapper.updateById(type);
        if (!oldType.equals(type.getDictType())) {
            for (SysDictData data : sysDictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>().eq(SysDictData::getDictType, oldType))) {
                data.setDictType(type.getDictType()); sysDictDataMapper.updateById(data);
            }
        }
        return type;
    }
    @Transactional
    public void deleteDictType(Long id) {
        SysDictType type = required(sysDictTypeMapper, id, "字典类型不存在");
        retire(sysDictDataMapper, sysDictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>().eq(SysDictData::getDictType, type.getDictType())));
        softDelete(sysDictTypeMapper, type);
    }
    @Transactional
    public SysDictData createDictData(RuoYiAdminDtos.DictDataRequest request) {
        validateDictData(request, null); SysDictData data = new SysDictData(); applyDictData(data, request); sysDictDataMapper.insert(data); return data;
    }
    @Transactional
    public SysDictData updateDictData(Long id, RuoYiAdminDtos.DictDataRequest request) {
        SysDictData data = required(sysDictDataMapper, id, "字典数据不存在"); validateDictData(request, id); applyDictData(data, request); sysDictDataMapper.updateById(data); return data;
    }
    @Transactional
    public void deleteDictData(Long id) { softDelete(sysDictDataMapper, required(sysDictDataMapper, id, "字典数据不存在")); }

    public List<SysNotice> listNotices() { return sysNoticeMapper.selectList(new LambdaQueryWrapper<SysNotice>().orderByDesc(SysNotice::getGmtCreate)); }
    @Transactional
    public SysNotice createNotice(RuoYiAdminDtos.NoticeRequest request) { validateNotice(request); SysNotice item = new SysNotice(); applyNotice(item, request); sysNoticeMapper.insert(item); return item; }
    @Transactional
    public SysNotice updateNotice(Long id, RuoYiAdminDtos.NoticeRequest request) { SysNotice item = required(sysNoticeMapper, id, "公告不存在"); validateNotice(request); applyNotice(item, request); sysNoticeMapper.updateById(item); return item; }
    @Transactional
    public void deleteNotice(Long id) { softDelete(sysNoticeMapper, required(sysNoticeMapper, id, "公告不存在")); }

    public List<SysConfig> listConfigs() { return sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getConfigGroup).orderByAsc(SysConfig::getConfigKey)); }
    @Transactional
    public SysConfig createConfig(RuoYiAdminDtos.ConfigRequest request) {
        if (sysConfigMapper.selectCount(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, request.getConfigKey().trim())) > 0) throw new BizException(ErrorCode.CONFLICT, "参数键名已存在");
        SysConfig config = new SysConfig(); applyConfig(config, request); sysConfigMapper.insert(config); return config;
    }
    @Transactional
    public SysConfig updateConfig(Long id, RuoYiAdminDtos.ConfigRequest request) {
        SysConfig config = required(sysConfigMapper, id, "系统参数不存在");
        SysConfig duplicate = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, request.getConfigKey().trim()).last("LIMIT 1"));
        if (duplicate != null && !duplicate.getId().equals(id)) throw new BizException(ErrorCode.CONFLICT, "参数键名已存在");
        applyConfig(config, request); sysConfigMapper.updateById(config); return config;
    }
    @Transactional
    public void deleteConfig(Long id) { softDelete(sysConfigMapper, required(sysConfigMapper, id, "系统参数不存在")); }

    public List<RuoYiAdminDtos.OnlineSessionItem> listOnline() {
        List<OpsSession> sessions = opsSessionMapper.selectList(new LambdaQueryWrapper<OpsSession>().eq(OpsSession::getSessionStatus, 1)
                .gt(OpsSession::getExpireAt, LocalDateTime.now()).orderByDesc(OpsSession::getGmtCreate));
        if (sessions.isEmpty()) return List.of();
        Set<Long> ids = sessions.stream().map(OpsSession::getOpsId).collect(Collectors.toSet());
        Map<Long, OpsAccount> accounts = opsAccountMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(OpsAccount::getId, Function.identity()));
        Map<Long, OpsProfile> profiles = opsProfileMapper.selectList(new LambdaQueryWrapper<OpsProfile>().in(OpsProfile::getOpsId, ids))
                .stream().collect(Collectors.toMap(OpsProfile::getOpsId, Function.identity(), (a, b) -> a));
        return sessions.stream().map(session -> {
            OpsAccount account = accounts.get(session.getOpsId()); OpsProfile profile = profiles.get(session.getOpsId());
            RuoYiAdminDtos.OnlineSessionItem item = new RuoYiAdminDtos.OnlineSessionItem();
            item.setId(session.getId()); item.setOpsId(session.getOpsId()); item.setUsername(account == null ? "unknown" : account.getUsername());
            item.setNickname(profile == null ? item.getUsername() : profile.getRealName()); item.setClientIp(session.getClientIp()); item.setUserAgent(session.getUserAgent());
            item.setExpireAt(session.getExpireAt()); item.setCreatedAt(session.getGmtCreate()); return item;
        }).toList();
    }
    @Transactional
    public void forceLogout(Long sessionId) { OpsSession session = required(opsSessionMapper, sessionId, "在线会话不存在"); session.setSessionStatus(0); opsSessionMapper.updateById(session); }
    public List<SysLoginLog> listLoginLogs() { return sysLoginLogMapper.selectList(new LambdaQueryWrapper<SysLoginLog>().orderByDesc(SysLoginLog::getLoginAt).last("LIMIT 500")); }
    public List<SysOperLog> listOperLogs() { return sysOperLogMapper.selectList(new LambdaQueryWrapper<SysOperLog>().orderByDesc(SysOperLog::getOperAt).last("LIMIT 500")); }

    public RuoYiAdminDtos.ServerStatus serverStatus() {
        Runtime runtime = Runtime.getRuntime();
        RuoYiAdminDtos.ServerStatus status = new RuoYiAdminDtos.ServerStatus();
        status.setApplication("GlucoPilot Backend"); status.setJavaVersion(System.getProperty("java.version")); status.setOsName(System.getProperty("os.name"));
        status.setProcessors(runtime.availableProcessors()); status.setHeapUsedBytes(runtime.totalMemory() - runtime.freeMemory()); status.setHeapMaxBytes(runtime.maxMemory());
        status.setUptimeMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        status.setActiveSessions(opsSessionMapper.selectCount(new LambdaQueryWrapper<OpsSession>().eq(OpsSession::getSessionStatus, 1).gt(OpsSession::getExpireAt, LocalDateTime.now())));
        return status;
    }

    public RuoYiAdminDtos.CacheStatus cacheStatus() {
        RuoYiAdminDtos.CacheStatus status = new RuoYiAdminDtos.CacheStatus();
        status.setPolicy("仅刷新本服务缓存；禁止对团队共享 Redis 执行 FLUSHDB/FLUSHALL。");
        try {
            if (redisConnectionFactory == null) throw new IllegalStateException("Redis connection factory unavailable");
            try (var connection = redisConnectionFactory.getConnection()) { connection.ping(); }
            status.setRedisReachable(true); status.setDetail("Redis connection is reachable");
        } catch (Exception ex) {
            status.setRedisReachable(false); status.setDetail("Redis unavailable: " + safe(ex.getMessage(), 160));
        }
        return status;
    }

    private void applyRole(SysRole role, RuoYiAdminDtos.RoleRequest request, String code) { role.setRoleCode(code); role.setRoleName(request.getRoleName().trim()); role.setSortNo(request.getSortNo()); role.setRoleStatus(request.getStatus()); role.setRemark(trim(request.getRemark())); }
    private RuoYiAdminDtos.RoleItem toRoleItem(SysRole role, List<String> permissions) { RuoYiAdminDtos.RoleItem item = new RuoYiAdminDtos.RoleItem(); item.setId(role.getId()); item.setRoleCode(role.getRoleCode()); item.setRoleName(role.getRoleName()); item.setSortNo(role.getSortNo()); item.setStatus(role.getRoleStatus()); item.setRemark(role.getRemark()); item.setPermissionCodes(permissions); return item; }
    private void applyMenu(SysMenu menu, RuoYiAdminDtos.MenuRequest r) { menu.setMenuName(r.getMenuName().trim()); menu.setParentId(r.getParentId() == null ? 0 : r.getParentId()); menu.setSortNo(r.getSortNo()); menu.setPathUrl(trim(r.getPathUrl())); menu.setComponentName(trim(r.getComponentName())); menu.setQueryString(trim(r.getQueryString())); menu.setMenuType(r.getMenuType().trim().toUpperCase()); menu.setIsFrame(r.getIsFrame()); menu.setIsCache(r.getIsCache()); menu.setVisible(r.getVisible()); menu.setMenuStatus(r.getStatus()); menu.setPermCode(trim(r.getPermCode())); menu.setIconName(trim(r.getIconName())); menu.setRemark(trim(r.getRemark())); }
    private void applyDept(SysDept dept, RuoYiAdminDtos.DeptRequest r) { dept.setDeptName(r.getDeptName().trim()); dept.setParentId(r.getParentId() == null ? 0 : r.getParentId()); dept.setSortNo(r.getSortNo()); dept.setLeader(trim(r.getLeader())); dept.setPhone(trim(r.getPhone())); dept.setEmail(trim(r.getEmail())); dept.setDeptStatus(r.getStatus()); dept.setRemark(trim(r.getRemark())); }
    private void applyPost(SysPost post, RuoYiAdminDtos.PostRequest r) { post.setPostCode(r.getPostCode().trim()); post.setPostName(r.getPostName().trim()); post.setPostSort(r.getPostSort()); post.setPostStatus(r.getStatus()); post.setRemark(trim(r.getRemark())); }
    private void applyDictType(SysDictType item, RuoYiAdminDtos.DictTypeRequest r) { item.setDictName(r.getDictName().trim()); item.setDictType(r.getDictType().trim()); item.setDictStatus(r.getStatus()); item.setRemark(trim(r.getRemark())); }
    private void applyDictData(SysDictData item, RuoYiAdminDtos.DictDataRequest r) { item.setDictLabel(r.getDictLabel().trim()); item.setDictValue(r.getDictValue().trim()); item.setDictType(r.getDictType().trim()); item.setDictSort(r.getDictSort()); item.setCssClass(trim(r.getCssClass())); item.setListClass(trim(r.getListClass())); item.setIsDefault(r.getIsDefault()); item.setDictStatus(r.getStatus()); item.setRemark(trim(r.getRemark())); }
    private void applyNotice(SysNotice item, RuoYiAdminDtos.NoticeRequest r) { item.setNoticeTitle(r.getTitle().trim()); item.setNoticeType(r.getType().trim()); item.setNoticeContent(r.getContent().trim()); item.setNoticeStatus(r.getStatus()); }
    private void applyConfig(SysConfig item, RuoYiAdminDtos.ConfigRequest r) { item.setConfigKey(r.getConfigKey().trim()); item.setConfigValue(r.getConfigValue()); item.setValueType(r.getValueType()); item.setConfigGroup(r.getConfigGroup().trim()); item.setRemark(trim(r.getRemark())); item.setModifierName("ops-" + RequestContext.operatorIdOrZero()); }

    private void replaceRolePermissions(Long roleId, List<String> requested) {
        List<String> codes = requested == null ? List.of() : requested.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
        List<SysPermission> permissions = codes.isEmpty() ? List.of() : sysPermissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().in(SysPermission::getPermCode, codes).eq(SysPermission::getPermStatus, 1));
        if (permissions.size() != codes.size()) throw new BizException(ErrorCode.BAD_REQUEST, "存在无效的权限标识");
        retire(sysRolePermissionMapper, sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId)));
        for (SysPermission permission : permissions) { SysRolePermission link = new SysRolePermission(); link.setRoleId(roleId); link.setPermId(permission.getId()); sysRolePermissionMapper.insert(link); }
    }
    private Map<Long, List<String>> rolePermissionCodes() {
        Map<Long, String> permissions = sysPermissionMapper.selectList(null).stream().collect(Collectors.toMap(SysPermission::getId, SysPermission::getPermCode));
        return sysRolePermissionMapper.selectList(null).stream().collect(Collectors.groupingBy(SysRolePermission::getRoleId,
                Collectors.mapping(link -> permissions.get(link.getPermId()), Collectors.filtering(java.util.Objects::nonNull, Collectors.collectingAndThen(Collectors.toList(), list -> list.stream().sorted().toList())))));
    }
    private List<String> permissionCodesOf(Long roleId) { return rolePermissionCodes().getOrDefault(roleId, List.of()); }
    private void syncPermission(SysMenu menu) {
        if (!StringUtils.hasText(menu.getPermCode())) return;
        SysPermission permission = sysPermissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, menu.getPermCode()).last("LIMIT 1"));
        if (permission == null) { permission = new SysPermission(); permission.setPermCode(menu.getPermCode()); }
        permission.setPermName(menu.getMenuName()); permission.setPermType("F".equals(menu.getMenuType()) ? 2 : 1); permission.setParentId(menu.getParentId()); permission.setPathUrl(menu.getPathUrl()); permission.setSortNo(menu.getSortNo()); permission.setPermStatus(menu.getMenuStatus());
        if (permission.getId() == null) sysPermissionMapper.insert(permission); else sysPermissionMapper.updateById(permission);
    }
    private void disablePermission(String code) { if (!StringUtils.hasText(code)) return; SysPermission permission = sysPermissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, code).last("LIMIT 1")); if (permission != null) { permission.setPermStatus(0); sysPermissionMapper.updateById(permission); } }
    private void validateMenu(RuoYiAdminDtos.MenuRequest r, Long self) { String type = r.getMenuType().trim().toUpperCase(); if (!Set.of("M", "C", "F").contains(type)) throw new BizException(ErrorCode.BAD_REQUEST, "菜单类型仅支持 M、C、F"); if (r.getParentId() != null && r.getParentId() != 0 && (self != null && r.getParentId().equals(self) || sysMenuMapper.selectById(r.getParentId()) == null)) throw new BizException(ErrorCode.BAD_REQUEST, "父菜单不存在或非法"); }
    private void validateDepartment(Long parentId, Long self) { if (parentId == null || parentId == 0) return; if (self != null && self.equals(parentId)) throw new BizException(ErrorCode.BAD_REQUEST, "部门不能选择自身作为上级"); if (sysDeptMapper.selectById(parentId) == null) throw new BizException(ErrorCode.BAD_REQUEST, "上级部门不存在"); }
    private String ancestorsFor(Long parentId) { if (parentId == null || parentId == 0) return "0"; SysDept parent = required(sysDeptMapper, parentId, "上级部门不存在"); return parent.getAncestors() + "," + parent.getId(); }
    private void ensureDictTypeUnused(String type, Long self) { SysDictType existing = sysDictTypeMapper.selectOne(new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getDictType, type.trim()).last("LIMIT 1")); if (existing != null && !existing.getId().equals(self)) throw new BizException(ErrorCode.CONFLICT, "字典类型编码已存在"); }
    private void validateDictData(RuoYiAdminDtos.DictDataRequest r, Long self) { if (sysDictTypeMapper.selectCount(new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getDictType, r.getDictType().trim()).eq(SysDictType::getDictStatus, 1)) == 0) throw new BizException(ErrorCode.BAD_REQUEST, "字典类型不存在或已停用"); SysDictData exist = sysDictDataMapper.selectOne(new LambdaQueryWrapper<SysDictData>().eq(SysDictData::getDictType, r.getDictType().trim()).eq(SysDictData::getDictValue, r.getDictValue().trim()).last("LIMIT 1")); if (exist != null && !exist.getId().equals(self)) throw new BizException(ErrorCode.CONFLICT, "同一字典类型下的值不能重复"); }
    private void validateNotice(RuoYiAdminDtos.NoticeRequest request) { if (!Set.of("1", "2").contains(request.getType().trim())) throw new BizException(ErrorCode.BAD_REQUEST, "公告类型仅支持通知或公告"); }
    private static String requireRoleCode(String code) { String value = code == null ? "" : code.trim(); if (!value.matches("[A-Za-z][A-Za-z0-9:_-]{1,63}")) throw new BizException(ErrorCode.BAD_REQUEST, "角色编码格式不合法"); return value; }
    private static String trim(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private static String safe(String value, int limit) { if (value == null) return ""; return value.length() <= limit ? value : value.substring(0, limit); }
    private static <T> T required(BaseMapper<T> mapper, Long id, String message) { T item = mapper.selectById(id); if (item == null) throw new BizException(ErrorCode.NOT_FOUND, message); return item; }
    private static <T extends BaseEntity> void softDelete(BaseMapper<T> mapper, T item) { item.setIsDeleted(1); item.setDeletedBy(RequestContext.operatorIdOrZero()); item.setDeletedAt(LocalDateTime.now()); mapper.updateById(item); }
    private static <T extends BaseEntity> void retire(BaseMapper<T> mapper, Collection<T> items) { for (T item : items) softDelete(mapper, item); }
}
