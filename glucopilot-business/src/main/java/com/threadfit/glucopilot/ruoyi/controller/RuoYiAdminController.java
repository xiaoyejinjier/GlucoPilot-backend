package com.threadfit.glucopilot.ruoyi.controller;

import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.SysConfig;
import com.threadfit.glucopilot.ruoyi.entity.SysDept;
import com.threadfit.glucopilot.ruoyi.entity.SysDictData;
import com.threadfit.glucopilot.ruoyi.entity.SysDictType;
import com.threadfit.glucopilot.ruoyi.entity.SysJob;
import com.threadfit.glucopilot.ruoyi.entity.SysJobLog;
import com.threadfit.glucopilot.ruoyi.entity.SysLoginLog;
import com.threadfit.glucopilot.ruoyi.entity.SysMenu;
import com.threadfit.glucopilot.ruoyi.entity.SysNotice;
import com.threadfit.glucopilot.ruoyi.entity.SysOperLog;
import com.threadfit.glucopilot.ruoyi.entity.SysPost;
import com.threadfit.glucopilot.ruoyi.service.RuoYiAuditService;
import com.threadfit.glucopilot.ruoyi.service.RuoYiGeneratorService;
import com.threadfit.glucopilot.ruoyi.service.RuoYiJobService;
import com.threadfit.glucopilot.ruoyi.service.RuoYiSystemService;
import com.threadfit.glucopilot.ruoyi.service.RuoYiUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Full RuoYi management surface, preserved behind GlucoPilot's versioned admin API contract. */
@RestController
@RequestMapping("/admin/v1/ruoyi")
@RequiredArgsConstructor
public class RuoYiAdminController {

    private final RuoYiUserService userService;
    private final RuoYiSystemService systemService;
    private final RuoYiJobService jobService;
    private final RuoYiGeneratorService generatorService;
    private final RuoYiAuditService auditService;

    @GetMapping("/users") @PreAuthorize("@ss.hasPermi('system:user:manage')")
    public ApiResponse<List<RuoYiAdminDtos.UserItem>> users() { return ApiResponse.ok(userService.list()); }
    @PostMapping("/users") @PreAuthorize("@ss.hasPermi('system:user:manage')")
    public ApiResponse<RuoYiAdminDtos.UserItem> createUser(@Valid @RequestBody RuoYiAdminDtos.UserRequest request) { var result = userService.create(request); auditService.recordOperation("系统用户", "INSERT", result.getUsername()); return ApiResponse.ok(result); }
    @PutMapping("/users/{id}") @PreAuthorize("@ss.hasPermi('system:user:manage')")
    public ApiResponse<RuoYiAdminDtos.UserItem> updateUser(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.UserRequest request) { var result = userService.update(id, request); auditService.recordOperation("系统用户", "UPDATE", result.getUsername()); return ApiResponse.ok(result); }
    @DeleteMapping("/users/{id}") @PreAuthorize("@ss.hasPermi('system:user:manage')")
    public ApiResponse<Void> disableUser(@PathVariable Long id) { userService.disable(id); auditService.recordOperation("系统用户", "DISABLE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/roles") @PreAuthorize("@ss.hasPermi('system:role:manage')")
    public ApiResponse<List<RuoYiAdminDtos.RoleItem>> roles() { return ApiResponse.ok(systemService.listRoles()); }
    @PostMapping("/roles") @PreAuthorize("@ss.hasPermi('system:role:manage')")
    public ApiResponse<RuoYiAdminDtos.RoleItem> createRole(@Valid @RequestBody RuoYiAdminDtos.RoleRequest request) { var result = systemService.createRole(request); auditService.recordOperation("系统角色", "INSERT", result.getRoleCode()); return ApiResponse.ok(result); }
    @PutMapping("/roles/{id}") @PreAuthorize("@ss.hasPermi('system:role:manage')")
    public ApiResponse<RuoYiAdminDtos.RoleItem> updateRole(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.RoleRequest request) { var result = systemService.updateRole(id, request); auditService.recordOperation("系统角色", "UPDATE", result.getRoleCode()); return ApiResponse.ok(result); }
    @DeleteMapping("/roles/{id}") @PreAuthorize("@ss.hasPermi('system:role:manage')")
    public ApiResponse<Void> deleteRole(@PathVariable Long id) { systemService.deleteRole(id); auditService.recordOperation("系统角色", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/menus") @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public ApiResponse<List<SysMenu>> menus() { return ApiResponse.ok(systemService.listMenus()); }
    @PostMapping("/menus") @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public ApiResponse<SysMenu> createMenu(@Valid @RequestBody RuoYiAdminDtos.MenuRequest request) { var result = systemService.createMenu(request); auditService.recordOperation("系统菜单", "INSERT", result.getMenuName()); return ApiResponse.ok(result); }
    @PutMapping("/menus/{id}") @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public ApiResponse<SysMenu> updateMenu(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.MenuRequest request) { var result = systemService.updateMenu(id, request); auditService.recordOperation("系统菜单", "UPDATE", result.getMenuName()); return ApiResponse.ok(result); }
    @DeleteMapping("/menus/{id}") @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id) { systemService.deleteMenu(id); auditService.recordOperation("系统菜单", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/depts") @PreAuthorize("@ss.hasPermi('system:dept:manage')")
    public ApiResponse<List<SysDept>> depts() { return ApiResponse.ok(systemService.listDepts()); }
    @PostMapping("/depts") @PreAuthorize("@ss.hasPermi('system:dept:manage')")
    public ApiResponse<SysDept> createDept(@Valid @RequestBody RuoYiAdminDtos.DeptRequest request) { var result = systemService.createDept(request); auditService.recordOperation("系统部门", "INSERT", result.getDeptName()); return ApiResponse.ok(result); }
    @PutMapping("/depts/{id}") @PreAuthorize("@ss.hasPermi('system:dept:manage')")
    public ApiResponse<SysDept> updateDept(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.DeptRequest request) { var result = systemService.updateDept(id, request); auditService.recordOperation("系统部门", "UPDATE", result.getDeptName()); return ApiResponse.ok(result); }
    @DeleteMapping("/depts/{id}") @PreAuthorize("@ss.hasPermi('system:dept:manage')")
    public ApiResponse<Void> deleteDept(@PathVariable Long id) { systemService.deleteDept(id); auditService.recordOperation("系统部门", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/posts") @PreAuthorize("@ss.hasPermi('system:post:manage')")
    public ApiResponse<List<SysPost>> posts() { return ApiResponse.ok(systemService.listPosts()); }
    @PostMapping("/posts") @PreAuthorize("@ss.hasPermi('system:post:manage')")
    public ApiResponse<SysPost> createPost(@Valid @RequestBody RuoYiAdminDtos.PostRequest request) { var result = systemService.createPost(request); auditService.recordOperation("系统岗位", "INSERT", result.getPostCode()); return ApiResponse.ok(result); }
    @PutMapping("/posts/{id}") @PreAuthorize("@ss.hasPermi('system:post:manage')")
    public ApiResponse<SysPost> updatePost(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.PostRequest request) { var result = systemService.updatePost(id, request); auditService.recordOperation("系统岗位", "UPDATE", result.getPostCode()); return ApiResponse.ok(result); }
    @DeleteMapping("/posts/{id}") @PreAuthorize("@ss.hasPermi('system:post:manage')")
    public ApiResponse<Void> deletePost(@PathVariable Long id) { systemService.deletePost(id); auditService.recordOperation("系统岗位", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/dict-types") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<List<SysDictType>> dictTypes() { return ApiResponse.ok(systemService.listDictTypes()); }
    @PostMapping("/dict-types") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<SysDictType> createDictType(@Valid @RequestBody RuoYiAdminDtos.DictTypeRequest request) { var result = systemService.createDictType(request); auditService.recordOperation("系统字典", "INSERT_TYPE", result.getDictType()); return ApiResponse.ok(result); }
    @PutMapping("/dict-types/{id}") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<SysDictType> updateDictType(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.DictTypeRequest request) { var result = systemService.updateDictType(id, request); auditService.recordOperation("系统字典", "UPDATE_TYPE", result.getDictType()); return ApiResponse.ok(result); }
    @DeleteMapping("/dict-types/{id}") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<Void> deleteDictType(@PathVariable Long id) { systemService.deleteDictType(id); auditService.recordOperation("系统字典", "DELETE_TYPE", String.valueOf(id)); return ApiResponse.ok(); }
    @GetMapping("/dict-data") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<List<SysDictData>> dictData(@RequestParam(required = false) String type) { return ApiResponse.ok(systemService.listDictData(type)); }
    @PostMapping("/dict-data") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<SysDictData> createDictData(@Valid @RequestBody RuoYiAdminDtos.DictDataRequest request) { var result = systemService.createDictData(request); auditService.recordOperation("系统字典", "INSERT_DATA", result.getDictType()); return ApiResponse.ok(result); }
    @PutMapping("/dict-data/{id}") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<SysDictData> updateDictData(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.DictDataRequest request) { var result = systemService.updateDictData(id, request); auditService.recordOperation("系统字典", "UPDATE_DATA", result.getDictType()); return ApiResponse.ok(result); }
    @DeleteMapping("/dict-data/{id}") @PreAuthorize("@ss.hasPermi('system:dict:manage')")
    public ApiResponse<Void> deleteDictData(@PathVariable Long id) { systemService.deleteDictData(id); auditService.recordOperation("系统字典", "DELETE_DATA", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/configs") @PreAuthorize("@ss.hasPermi('system:config:manage')")
    public ApiResponse<List<SysConfig>> configs() { return ApiResponse.ok(systemService.listConfigs()); }
    @PostMapping("/configs") @PreAuthorize("@ss.hasPermi('system:config:manage')")
    public ApiResponse<SysConfig> createConfig(@Valid @RequestBody RuoYiAdminDtos.ConfigRequest request) { var result = systemService.createConfig(request); auditService.recordOperation("系统参数", "INSERT", result.getConfigKey()); return ApiResponse.ok(result); }
    @PutMapping("/configs/{id}") @PreAuthorize("@ss.hasPermi('system:config:manage')")
    public ApiResponse<SysConfig> updateConfig(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.ConfigRequest request) { var result = systemService.updateConfig(id, request); auditService.recordOperation("系统参数", "UPDATE", result.getConfigKey()); return ApiResponse.ok(result); }
    @DeleteMapping("/configs/{id}") @PreAuthorize("@ss.hasPermi('system:config:manage')")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) { systemService.deleteConfig(id); auditService.recordOperation("系统参数", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/notices") @PreAuthorize("@ss.hasPermi('system:notice:manage')")
    public ApiResponse<List<SysNotice>> notices() { return ApiResponse.ok(systemService.listNotices()); }
    @PostMapping("/notices") @PreAuthorize("@ss.hasPermi('system:notice:manage')")
    public ApiResponse<SysNotice> createNotice(@Valid @RequestBody RuoYiAdminDtos.NoticeRequest request) { var result = systemService.createNotice(request); auditService.recordOperation("通知公告", "INSERT", result.getNoticeTitle()); return ApiResponse.ok(result); }
    @PutMapping("/notices/{id}") @PreAuthorize("@ss.hasPermi('system:notice:manage')")
    public ApiResponse<SysNotice> updateNotice(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.NoticeRequest request) { var result = systemService.updateNotice(id, request); auditService.recordOperation("通知公告", "UPDATE", result.getNoticeTitle()); return ApiResponse.ok(result); }
    @DeleteMapping("/notices/{id}") @PreAuthorize("@ss.hasPermi('system:notice:manage')")
    public ApiResponse<Void> deleteNotice(@PathVariable Long id) { systemService.deleteNotice(id); auditService.recordOperation("通知公告", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }

    @GetMapping("/online") @PreAuthorize("@ss.hasPermi('monitor:online:list')")
    public ApiResponse<List<RuoYiAdminDtos.OnlineSessionItem>> online() { return ApiResponse.ok(systemService.listOnline()); }
    @DeleteMapping("/online/{id}") @PreAuthorize("@ss.hasPermi('monitor:online:list')")
    public ApiResponse<Void> forceLogout(@PathVariable String id) { systemService.forceLogout(id); auditService.recordOperation("在线用户", "FORCE_LOGOUT", String.valueOf(id)); return ApiResponse.ok(); }
    @GetMapping("/login-logs") @PreAuthorize("@ss.hasPermi('monitor:logininfor:list')")
    public ApiResponse<List<SysLoginLog>> loginLogs() { return ApiResponse.ok(systemService.listLoginLogs()); }
    @GetMapping("/oper-logs") @PreAuthorize("@ss.hasPermi('monitor:operlog:list')")
    public ApiResponse<List<SysOperLog>> operLogs() { return ApiResponse.ok(systemService.listOperLogs()); }
    @GetMapping("/monitor/server") @PreAuthorize("@ss.hasPermi('monitor:server:list')")
    public ApiResponse<RuoYiAdminDtos.ServerStatus> server() { return ApiResponse.ok(systemService.serverStatus()); }
    @GetMapping("/monitor/cache") @PreAuthorize("@ss.hasPermi('monitor:cache:manage')")
    public ApiResponse<RuoYiAdminDtos.CacheStatus> cache() { return ApiResponse.ok(systemService.cacheStatus()); }

    @GetMapping("/jobs") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<List<SysJob>> jobs() { return ApiResponse.ok(jobService.list()); }
    @PostMapping("/jobs") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<SysJob> createJob(@Valid @RequestBody RuoYiAdminDtos.JobRequest request) { var result = jobService.create(request); auditService.recordOperation("定时任务", "INSERT", result.getJobName()); return ApiResponse.ok(result); }
    @PutMapping("/jobs/{id}") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<SysJob> updateJob(@PathVariable Long id, @Valid @RequestBody RuoYiAdminDtos.JobRequest request) { var result = jobService.update(id, request); auditService.recordOperation("定时任务", "UPDATE", result.getJobName()); return ApiResponse.ok(result); }
    @DeleteMapping("/jobs/{id}") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<Void> deleteJob(@PathVariable Long id) { jobService.delete(id); auditService.recordOperation("定时任务", "DELETE", String.valueOf(id)); return ApiResponse.ok(); }
    @PostMapping("/jobs/{id}/run") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<Void> runJob(@PathVariable Long id) { jobService.runNow(id); auditService.recordOperation("定时任务", "RUN", String.valueOf(id)); return ApiResponse.ok(); }
    @GetMapping("/job-logs") @PreAuthorize("@ss.hasPermi('monitor:job:manage')")
    public ApiResponse<List<SysJobLog>> jobLogs(@RequestParam(required = false) Long jobId) { return ApiResponse.ok(jobService.listLogs(jobId)); }

    @GetMapping("/generator/tables") @PreAuthorize("@ss.hasPermi('tool:gen:manage')")
    public ApiResponse<List<RuoYiAdminDtos.GeneratorTableItem>> generatorTables() { return ApiResponse.ok(generatorService.listDatabaseTables()); }
    @PostMapping("/generator/tables/{tableName}/sync") @PreAuthorize("@ss.hasPermi('tool:gen:manage')")
    public ApiResponse<RuoYiAdminDtos.GeneratorPreview> syncGeneratorTable(@PathVariable String tableName) { var result = generatorService.sync(tableName); auditService.recordOperation("代码生成", "SYNC", tableName); return ApiResponse.ok(result); }
    @GetMapping("/generator/tables/{id}/preview") @PreAuthorize("@ss.hasPermi('tool:gen:manage')")
    public ApiResponse<RuoYiAdminDtos.GeneratorPreview> generatorPreview(@PathVariable Long id) { return ApiResponse.ok(generatorService.preview(id)); }
}
