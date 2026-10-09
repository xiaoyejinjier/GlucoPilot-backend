-- Official RuoYi-Vue 3.9.2 at 13db1fcef36bee9ce45d2d636a1d4e8f5ed5bbc3.
-- Back up the target database and freeze writes before applying this structural migration.
-- Preserve original system data in gp_legacy_*; business table IDs and C-end sessions are untouched.
SET NAMES utf8mb4;
RENAME TABLE
  sys_role TO gp_legacy_sys_role,
  sys_permission TO gp_legacy_sys_permission,
  sys_ops_role TO gp_legacy_sys_ops_role,
  sys_role_permission TO gp_legacy_sys_role_permission,
  sys_dept TO gp_legacy_sys_dept,
  sys_post TO gp_legacy_sys_post,
  sys_ops_post TO gp_legacy_sys_ops_post,
  sys_menu TO gp_legacy_sys_menu,
  sys_dict_type TO gp_legacy_sys_dict_type,
  sys_dict_data TO gp_legacy_sys_dict_data,
  sys_notice TO gp_legacy_sys_notice,
  sys_login_log TO gp_legacy_sys_login_log,
  sys_oper_log TO gp_legacy_sys_oper_log,
  sys_job TO gp_legacy_sys_job,
  sys_job_log TO gp_legacy_sys_job_log,
  gen_table TO gp_legacy_gen_table,
  gen_table_column TO gp_legacy_gen_table_column,
  sys_config TO gp_app_config;

create table sys_dept (
  dept_id           bigint(20)      not null auto_increment    comment '部门id',
  parent_id         bigint(20)      default 0                  comment '父部门id',
  ancestors         varchar(50)     default ''                 comment '祖级列表',
  dept_name         varchar(64)     default ''                 comment '部门名称',
  order_num         int(4)          default 0                  comment '显示顺序',
  leader            varchar(64)     default null               comment '负责人',
  phone             varchar(20)     default null               comment '联系电话',
  email             varchar(128)     default null               comment '邮箱',
  status            char(1)         default '0'                comment '部门状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (dept_id)
) engine=innodb auto_increment=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '部门表';


create table sys_user (
  user_id           bigint(20)      not null auto_increment    comment '用户ID',
  dept_id           bigint(20)      default null               comment '部门ID',
  user_name         varchar(64)     not null                   comment '用户账号',
  nick_name         varchar(64)     not null                   comment '用户昵称',
  user_type         varchar(2)      default '00'               comment '用户类型（00系统用户）',
  email             varchar(128)     default ''                 comment '用户邮箱',
  phonenumber       varchar(20)     default ''                 comment '手机号码',
  sex               char(1)         default '0'                comment '用户性别（0男 1女 2未知）',
  avatar            varchar(100)    default ''                 comment '头像地址',
  password          varchar(255)    default ''                 comment '密码',
  status            char(1)         default '0'                comment '账号状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  login_ip          varchar(128)    default ''                 comment '最后登录IP',
  login_date        datetime                                   comment '最后登录时间',
  pwd_update_date   datetime                                   comment '密码最后更新时间',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (user_id)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '用户信息表';


create table sys_post
(
  post_id       bigint(20)      not null auto_increment    comment '岗位ID',
  post_code     varchar(64)     not null                   comment '岗位编码',
  post_name     varchar(64)     not null                   comment '岗位名称',
  post_sort     int(4)          not null                   comment '显示顺序',
  status        char(1)         not null                   comment '状态（0正常 1停用）',
  create_by     varchar(64)     default ''                 comment '创建者',
  create_time   datetime                                   comment '创建时间',
  update_by     varchar(64)     default ''			       comment '更新者',
  update_time   datetime                                   comment '更新时间',
  remark        varchar(500)    default null               comment '备注',
  primary key (post_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '岗位信息表';


create table sys_role (
  role_id              bigint(20)      not null auto_increment    comment '角色ID',
  role_name            varchar(64)     not null                   comment '角色名称',
  role_key             varchar(100)    not null                   comment '角色权限字符串',
  role_sort            int(4)          not null                   comment '显示顺序',
  data_scope           char(1)         default '1'                comment '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  menu_check_strictly  tinyint(1)      default 1                  comment '菜单树选择项是否关联显示',
  dept_check_strictly  tinyint(1)      default 1                  comment '部门树选择项是否关联显示',
  status               char(1)         not null                   comment '角色状态（0正常 1停用）',
  del_flag             char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by            varchar(64)     default ''                 comment '创建者',
  create_time          datetime                                   comment '创建时间',
  update_by            varchar(64)     default ''                 comment '更新者',
  update_time          datetime                                   comment '更新时间',
  remark               varchar(500)    default null               comment '备注',
  primary key (role_id)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '角色信息表';


create table sys_menu (
  menu_id           bigint(20)      not null auto_increment    comment '菜单ID',
  menu_name         varchar(128)     not null                   comment '菜单名称',
  parent_id         bigint(20)      default 0                  comment '父菜单ID',
  order_num         int(4)          default 0                  comment '显示顺序',
  path              varchar(256)    default ''                 comment '路由地址',
  component         varchar(256)    default null               comment '组件路径',
  query             varchar(256)    default null               comment '路由参数',
  route_name        varchar(50)     default ''                 comment '路由名称',
  is_frame          int(1)          default 1                  comment '是否为外链（0是 1否）',
  is_cache          int(1)          default 0                  comment '是否缓存（0缓存 1不缓存）',
  menu_type         char(1)         default ''                 comment '菜单类型（M目录 C菜单 F按钮）',
  visible           char(1)         default 0                  comment '菜单状态（0显示 1隐藏）',
  status            char(1)         default 0                  comment '菜单状态（0正常 1停用）',
  perms             varchar(128)    default null               comment '权限标识',
  icon              varchar(100)    default '#'                comment '菜单图标',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default ''                 comment '备注',
  primary key (menu_id)
) engine=innodb auto_increment=2000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '菜单权限表';


create table sys_user_role (
  user_id   bigint(20) not null comment '用户ID',
  role_id   bigint(20) not null comment '角色ID',
  primary key(user_id, role_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '用户和角色关联表';


create table sys_role_menu (
  role_id   bigint(20) not null comment '角色ID',
  menu_id   bigint(20) not null comment '菜单ID',
  primary key(role_id, menu_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '角色和菜单关联表';


create table sys_role_dept (
  role_id   bigint(20) not null comment '角色ID',
  dept_id   bigint(20) not null comment '部门ID',
  primary key(role_id, dept_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '角色和部门关联表';


create table sys_user_post
(
  user_id   bigint(20) not null comment '用户ID',
  post_id   bigint(20) not null comment '岗位ID',
  primary key (user_id, post_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '用户与岗位关联表';


create table sys_oper_log (
  oper_id           bigint(20)      not null auto_increment    comment '日志主键',
  title             varchar(50)     default ''                 comment '模块标题',
  business_type     int(2)          default 0                  comment '业务类型（0其它 1新增 2修改 3删除）',
  method            varchar(200)    default ''                 comment '方法名称',
  request_method    varchar(10)     default ''                 comment '请求方式',
  operator_type     int(1)          default 0                  comment '操作类别（0其它 1后台用户 2手机端用户）',
  oper_name         varchar(50)     default ''                 comment '操作人员',
  dept_name         varchar(64)     default ''                 comment '部门名称',
  oper_url          varchar(255)    default ''                 comment '请求URL',
  oper_ip           varchar(128)    default ''                 comment '主机地址',
  oper_location     varchar(255)    default ''                 comment '操作地点',
  oper_param        varchar(2000)   default ''                 comment '请求参数',
  json_result       varchar(2000)   default ''                 comment '返回参数',
  status            int(1)          default 0                  comment '操作状态（0正常 1异常）',
  error_msg         varchar(2000)   default ''                 comment '错误消息',
  oper_time         datetime                                   comment '操作时间',
  cost_time         bigint(20)      default 0                  comment '消耗时间',
  primary key (oper_id),
  key idx_sys_oper_log_bt (business_type),
  key idx_sys_oper_log_s  (status),
  key idx_sys_oper_log_ot (oper_time)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '操作日志记录';



create table sys_dict_type
(
  dict_id          bigint(20)      not null auto_increment    comment '字典主键',
  dict_name        varchar(100)    default ''                 comment '字典名称',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_id),
  unique (dict_type)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '字典类型表';


create table sys_dict_data
(
  dict_code        bigint(20)      not null auto_increment    comment '字典编码',
  dict_sort        int(4)          default 0                  comment '字典排序',
  dict_label       varchar(100)    default ''                 comment '字典标签',
  dict_value       varchar(100)    default ''                 comment '字典键值',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  css_class        varchar(100)    default null               comment '样式属性（其他样式扩展）',
  list_class       varchar(100)    default null               comment '表格回显样式',
  is_default       char(1)         default 'N'                comment '是否默认（Y是 N否）',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_code)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '字典数据表';


create table sys_config (
  config_id         int(5)          not null auto_increment    comment '参数主键',
  config_name       varchar(100)    default ''                 comment '参数名称',
  config_key        varchar(100)    default ''                 comment '参数键名',
  config_value      varchar(500)    default ''                 comment '参数键值',
  config_type       char(1)         default 'N'                comment '系统内置（Y是 N否）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (config_id)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '参数配置表';


create table sys_logininfor (
  info_id        bigint(20)     not null auto_increment   comment '访问ID',
  user_name      varchar(64)    default ''                comment '用户账号',
  ipaddr         varchar(128)   default ''                comment '登录IP地址',
  login_location varchar(255)   default ''                comment '登录地点',
  browser        varchar(50)    default ''                comment '浏览器类型',
  os             varchar(50)    default ''                comment '操作系统',
  status         char(1)        default '0'               comment '登录状态（0成功 1失败）',
  msg            varchar(255)   default ''                comment '提示消息',
  login_time     datetime                                 comment '访问时间',
  primary key (info_id),
  key idx_sys_logininfor_s  (status),
  key idx_sys_logininfor_lt (login_time)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '系统访问记录';



create table sys_job (
  job_id              bigint(20)    not null auto_increment    comment '任务ID',
  job_name            varchar(64)   default ''                 comment '任务名称',
  job_group           varchar(64)   default 'DEFAULT'          comment '任务组名',
  invoke_target       varchar(500)  not null                   comment '调用目标字符串',
  cron_expression     varchar(255)  default ''                 comment 'cron执行表达式',
  misfire_policy      varchar(20)   default '3'                comment '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  concurrent          char(1)       default '1'                comment '是否并发执行（0允许 1禁止）',
  status              char(1)       default '0'                comment '状态（0正常 1暂停）',
  create_by           varchar(64)   default ''                 comment '创建者',
  create_time         datetime                                 comment '创建时间',
  update_by           varchar(64)   default ''                 comment '更新者',
  update_time         datetime                                 comment '更新时间',
  remark              varchar(500)  default ''                 comment '备注信息',
  primary key (job_id, job_name, job_group)
) engine=innodb auto_increment=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '定时任务调度表';


create table sys_job_log (
  job_log_id          bigint(20)     not null auto_increment    comment '任务日志ID',
  job_name            varchar(64)    not null                   comment '任务名称',
  job_group           varchar(64)    not null                   comment '任务组名',
  invoke_target       varchar(500)   not null                   comment '调用目标字符串',
  job_message         varchar(500)                              comment '日志信息',
  status              char(1)        default '0'                comment '执行状态（0正常 1失败）',
  exception_info      varchar(2000)  default ''                 comment '异常信息',
  start_time          datetime                                  comment '执行开始时间',
  end_time            datetime                                  comment '执行结束时间',
  create_time         datetime                                  comment '创建时间',
  primary key (job_log_id)
) engine=innodb DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '定时任务调度日志表';



create table sys_notice (
  notice_id         int(4)          not null auto_increment    comment '公告ID',
  notice_title      varchar(100)     not null                   comment '公告标题',
  notice_type       char(1)         not null                   comment '公告类型（1通知 2公告）',
  notice_content    longblob        default null               comment '公告内容',
  status            char(1)         default '0'                comment '公告状态（0正常 1关闭）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(255)    default null               comment '备注',
  primary key (notice_id)
) engine=innodb auto_increment=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '通知公告表';


create table sys_notice_read (
  read_id          bigint(20)       not null auto_increment    comment '已读主键',
  notice_id        int(4)           not null                   comment '公告id',
  user_id          bigint(20)       not null                   comment '用户id',
  read_time        datetime         not null                   comment '阅读时间',
  primary key (read_id),
  unique key uk_user_notice (user_id, notice_id)   comment '同一用户同一公告只记录一次'
) engine=innodb auto_increment=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='公告已读记录表';



create table gen_table (
  table_id          bigint(20)      not null auto_increment    comment '编号',
  table_name        varchar(200)    default ''                 comment '表名称',
  table_comment     varchar(500)    default ''                 comment '表描述',
  sub_table_name    varchar(64)     default null               comment '关联子表的表名',
  sub_table_fk_name varchar(64)     default null               comment '子表关联的外键名',
  class_name        varchar(100)    default ''                 comment '实体类名称',
  tpl_category      varchar(200)    default 'crud'             comment '使用的模板（crud单表操作 tree树表操作）',
  tpl_web_type      varchar(30)     default ''                 comment '前端模板类型（element-ui模版 element-plus模版）',
  package_name      varchar(100)                               comment '生成包路径',
  module_name       varchar(30)                                comment '生成模块名',
  business_name     varchar(30)                                comment '生成业务名',
  function_name     varchar(50)                                comment '生成功能名',
  function_author   varchar(50)                                comment '生成功能作者',
  form_col_num      int(1)          default 1                  comment '表单布局（单列 双列 三列）',
  gen_type          char(1)         default '0'                comment '生成代码方式（0zip压缩包 1自定义路径）',
  gen_path          varchar(256)    default '/'                comment '生成路径（不填默认项目路径）',
  options           varchar(1000)                              comment '其它生成选项',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (table_id)
) engine=innodb auto_increment=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '代码生成业务表';



create table gen_table_column (
  column_id         bigint(20)      not null auto_increment    comment '编号',
  table_id          bigint(20)                                 comment '归属表编号',
  column_name       varchar(200)                               comment '列名称',
  column_comment    varchar(500)                               comment '列描述',
  column_type       varchar(100)                               comment '列类型',
  java_type         varchar(500)                               comment 'JAVA类型',
  java_field        varchar(200)                               comment 'JAVA字段名',
  is_pk             char(1)                                    comment '是否主键（1是）',
  is_increment      char(1)                                    comment '是否自增（1是）',
  is_required       char(1)                                    comment '是否必填（1是）',
  is_insert         char(1)                                    comment '是否为插入字段（1是）',
  is_edit           char(1)                                    comment '是否编辑字段（1是）',
  is_list           char(1)                                    comment '是否列表字段（1是）',
  is_query          char(1)                                    comment '是否查询字段（1是）',
  query_type        varchar(200)    default 'EQ'               comment '查询方式（等于、不等于、大于、小于、范围）',
  html_type         varchar(200)                               comment '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  dict_type         varchar(200)    default ''                 comment '字典类型',
  sort              int                                        comment '排序',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (column_id)
) engine=innodb auto_increment=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '代码生成业务表字段';
CREATE TABLE gp_ops_identity_map (
  legacy_ops_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  ruoyi_user_id BIGINT NOT NULL UNIQUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_dept(dept_id,parent_id,ancestors,dept_name,order_num,leader,phone,email,status,del_flag,create_time,update_time)
SELECT id,parent_id,ancestors,dept_name,sort_no,leader,phone,email,IF(dept_status=1,'0','1'),IF(is_deleted=0,'0','2'),gmt_create,gmt_modified FROM gp_legacy_sys_dept;
INSERT INTO sys_post(post_id,post_code,post_name,post_sort,status,create_time,update_time,remark)
SELECT id,post_code,post_name,post_sort,IF(post_status=1,'0','1'),gmt_create,gmt_modified,remark FROM gp_legacy_sys_post WHERE is_deleted=0;
INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,email,phonenumber,sex,password,status,del_flag,login_ip,login_date,create_time,update_time)
SELECT a.id,p.dept_id,a.username,COALESCE(p.real_name,a.username),COALESCE(p.email,''),COALESCE(p.mobile,''),'2',a.password_hash,IF(a.account_status=1,'0','1'),IF(a.is_deleted=0,'0','2'),COALESCE(a.last_login_ip,''),a.last_login_at,a.gmt_create,a.gmt_modified
FROM ops_account a LEFT JOIN ops_profile p ON p.ops_id=a.id AND p.is_deleted=0;
INSERT INTO gp_ops_identity_map(legacy_ops_id,ruoyi_user_id) SELECT id,id FROM ops_account;
INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,del_flag,create_time,update_time,remark)
SELECT id,role_name,role_code,sort_no,'1',IF(role_status=1,'0','1'),IF(is_deleted=0,'0','2'),gmt_create,gmt_modified,remark FROM gp_legacy_sys_role;
INSERT INTO sys_user_role(user_id,role_id) SELECT DISTINCT ops_id,role_id FROM gp_legacy_sys_ops_role WHERE is_deleted=0;
INSERT INTO sys_user_post(user_id,post_id) SELECT DISTINCT ops_id,post_id FROM gp_legacy_sys_ops_post WHERE is_deleted=0;
INSERT INTO sys_menu(menu_id,parent_id,menu_name,order_num,path,component,query,is_frame,is_cache,menu_type,visible,status,perms,icon,create_time,update_time,remark)
SELECT id,parent_id,menu_name,sort_no,COALESCE(path_url,''),component_name,query_string,is_frame,IF(is_cache=1,'0','1'),menu_type,IF(visible=1,'0','1'),IF(menu_status=1,'0','1'),perm_code,icon_name,gmt_create,gmt_modified,remark
FROM gp_legacy_sys_menu WHERE is_deleted=0;
INSERT INTO sys_menu(parent_id,menu_name,order_num,path,menu_type,visible,status,perms,create_time)
SELECT 0,p.perm_name,p.sort_no,COALESCE(p.path_url,''),IF(p.perm_type=1,'C','F'),'0',IF(p.perm_status=1,'0','1'),p.perm_code,p.gmt_create
FROM gp_legacy_sys_permission p WHERE p.is_deleted=0 AND NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.perms=p.perm_code);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rp.role_id,m.menu_id FROM gp_legacy_sys_role_permission rp
JOIN gp_legacy_sys_permission p ON p.id=rp.perm_id AND p.is_deleted=0
JOIN sys_menu m ON m.perms=p.perm_code WHERE rp.is_deleted=0;
-- Super-role members receive every known menu, including originally unbound system entries.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_key='super_admin' AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
INSERT INTO sys_dict_type(dict_id,dict_name,dict_type,status,create_time,update_time,remark)
SELECT id,dict_name,dict_type,IF(dict_status=1,'0','1'),gmt_create,gmt_modified,remark FROM gp_legacy_sys_dict_type WHERE is_deleted=0;
INSERT INTO sys_dict_data(dict_code,dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_time,update_time,remark)
SELECT id,dict_sort,dict_label,dict_value,dict_type,css_class,list_class,IF(is_default=1,'Y','N'),IF(dict_status=1,'0','1'),gmt_create,gmt_modified,remark FROM gp_legacy_sys_dict_data WHERE is_deleted=0;
INSERT INTO sys_notice(notice_id,notice_title,notice_type,notice_content,status,create_time,update_time)
SELECT id,notice_title,notice_type,notice_content,IF(notice_status=1,'0','1'),gmt_create,gmt_modified FROM gp_legacy_sys_notice WHERE is_deleted=0;
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_time)
VALUES ('后台验证码','sys.account.captchaEnabled','false','Y',NOW()),('允许用户注册','sys.account.registerUser','false','Y',NOW());
INSERT INTO sys_logininfor(info_id,user_name,status,ipaddr,browser,msg,login_time)
SELECT id,LEFT(username,50),IF(login_status=1,'0','1'),COALESCE(client_ip,''),LEFT(COALESCE(user_agent,''),50),LEFT(login_message,255),login_at FROM gp_legacy_sys_login_log;
INSERT INTO sys_oper_log(oper_id,title,business_type,oper_name,oper_ip,oper_param,status,error_msg,oper_time,cost_time)
SELECT id,LEFT(title,50),0,LEFT(COALESCE(operator_name,''),50),COALESCE(client_ip,''),LEFT(request_param,2000),IF(oper_status=1,0,1),LEFT(COALESCE(error_message,''),2000),oper_at,0 FROM gp_legacy_sys_oper_log;
-- Only convert known task keys; other historical definitions stay archived for manual inspection.
-- Pause migrated tasks so an operator verifies cron semantics before enabling Quartz execution.
INSERT INTO sys_job(job_id,job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_time,update_time,remark)
SELECT id,job_name,job_group,CASE invoke_target WHEN 'system.cleanup-sessions' THEN 'gpTasks.cleanupSessions()' WHEN 'system.refresh-permissions' THEN 'gpTasks.refreshPermissions()' WHEN 'system.health-check' THEN 'gpTasks.healthCheck()' END,
cron_expression,'3',IF(concurrent_allowed=1,'0','1'),'1',gmt_create,gmt_modified,remark
FROM gp_legacy_sys_job WHERE is_deleted=0 AND invoke_target IN('system.cleanup-sessions','system.refresh-permissions','system.health-check');
-- Old ops_session tokens are obsolete; retained rows are historical records, not active authentication.
UPDATE ops_session SET session_status=0 WHERE session_status=1;
