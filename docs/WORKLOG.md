# 拆仓工作记录

日期：2026-10-13
执行：Codex；页面、接口验收负责人由团队登记。
范围：独立构建、共享网络部署、配置与文档迁移。
依赖：运维提供远程 MySQL、Redis、密钥、HTTPS 域名及 Docker 环境。
验收：各仓库独立构建；Nginx 代理两类 API；小程序真机登录与主要流程通过。
风险：未连接远程基础设施，不代表生产联调通过。
次日计划：负责人登记验收结果，运维部署已验收版本。

验证：管理端类型检查与生产构建通过；配置 YAML 与 Git 空白检查通过；小程序 JS 语法检查通过。后端结果见最终交付记录。Docker 和真机联调待运维/页面负责人执行。

最终验证：使用本机 Corretto Java 21，mvn -B -ntp -pl ruoyi-admin -am verify 全部九个模块成功，已生成 ruoyi-admin/target/ruoyi-admin.jar。未执行远程数据库迁移集成测试或 Docker 启动。

无 Key 部署：新增文本 AI 关闭模式，保留现有开启行为；调用未启用模型时明确业务拒绝，需重新构建并交运维部署。

课程交付成本约束：不新增 OSS 服务。后端增加显式 disabled 存储模式，保持 API 契约，存储操作业务拒绝；上传功能不在本模式验收范围。依赖：新镜像、服务器 STORAGE_PROVIDER=disabled、有效 OPS_TOKEN_SECRET。
