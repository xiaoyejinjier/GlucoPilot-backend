# 导出任务

- `controller/DataExportController.java`：用户端与管理端导出接口、登录及管理权限检查、PDF下载响应。
- `service/DataExportService.java`：创建、查询、生成、下载和用户移除等业务规则，构造器注入MyBatis Mapper，不使用JdbcTemplate。
- `dto/DataExportDtos.java`：创建请求Create和任务返回Task，不再将接口数据对象定义在Service内部。
- `mapper/DataExportMapper.java`：绑定参数的SQL、行锁、分页与用户可见性过滤、PDF字节读写。显式`@Mapper`在两个启动入口中注册，锁查询清除缓存并参与Spring事务。
- `mapper/DataExportQuery.java`：类型化列表查询条件；用户ID为空只用于已鉴权的管理端调用。
- `entity/DataExportTaskRow.java`：任务及用户名、文件计数查询投影；日期直接映射为LocalDate/LocalDateTime。
- `entity/DataExportFileRow.java`：通过明确的byte[]字段映射读取BLOB，不把文件字节数组当作多行查询结果。
- `support/ExportPdf.java`：渲染真实健康记录为PDF；已完成文件是固定快照。旧Excel下载仅转换已存快照，不重新查询当前健康数据。

用户“删除”只写入`user_deleted_at`，管理端任务和文件继续保留；隐藏任务仍计入24小时申请限额。创建时锁账号，在同一事务内检查请求编号和额度；生成时锁任务，已完成的任务不重新覆盖文件。

本次MyBatis替换不更改HTTP接口、Task/Create响应请求结构或数据库表，无需新增迁移。测试里的JDBC仅用于隔离数据库建表、夹具和断言，实际业务调用使用真实MyBatis及Spring事务。
