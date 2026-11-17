# 问诊功能与联调步骤

已实现患者求助与配图、医生资质申请与审核、接诊后的医患交流及正式问诊单、患者使用沙箱钱包支付1元解锁。医生收入进入沙箱钱包，可申请沙箱提现，由管理员审核后第5个工作日模拟发放；不涉及真实资金转账。

## V70 新增流程

- 发布求助可添加最多6张图片，每张最多5MB，可在发布前预览、删除。图片使用独立业务类型9保存，附件与求助在同一事务登记；广场不公开健康图片，只有患者本人及有效接诊医生可鉴权读取。
- 接诊后，患者和医生可以直接在详情“问诊交流”发送文字，医生后台也有同一交流区和图片预览。普通回复不会生成或解锁问诊单；问诊单继续单独填写和付费查看。页面前台每5秒增量获取回复，离页停止；发送失败保留内容和请求编号以免重复。
- 医生可从小程序“医生工作台→问诊收入与提现记录”或“我的→钱包管理→医生提现”申请。后台医生从“我的收入→收入提现”申请；管理员从“医生管理→提现审核”处理。金额以分保存，提交先创建订单，再冻结对应收入；拒绝退回，通过后第5个工作日到期自动模拟发放，管理员也可在到期后手动触发模拟发放。
- `income_available_cents`单独维护可提现问诊收入，充值不增加该额度；消费后额度随剩余余额收缩，避免再次充值恢复已消费收入。提现冻结、解冻、模拟发放均通过事务、行锁和唯一流水防止并发重复操作。
- 工作日默认周一至周五，管理员在提现审核的“工作日历”设置节假日和调休；已批准订单保留预计日期快照。页面与流水均明确标记沙箱模拟，不调用支付宝真实打款接口。
- 用户确认目前没有公网HTTPS地址。充值继续先建订单再扫码，验签通知或后端主动查单确认成功后才加余额。回调保留为`POST /api/v1/payments/alipay/sandbox/notify`，上线公网后再配置通知地址；点击“我已付款”不代表支付成功。

## 双账号联调

1. 重建并重启 IDEA 的 `RuoYiApplication`，重新编译小程序，刷新并重新登录管理端。V70升级需在旧后端停止后备份并切换，避免旧代码在收入额度拆分期间继续入账；其他环境由Flyway随后端部署执行迁移。
2. 准备患者、医生两个不同的小程序账号。医生在“我的 → 医生工作台”填写姓名、执业机构、科室、执业证号、资质说明并提交。没有预设医生或自动通过审核。
3. 管理员进入“医生管理 → 医生审核”，核验资质后填写审核意见并通过。该页面仅管理员且具备 `doctor:review:manage` 权限时可用。审核检查版本，申请已更新时提示刷新并保留当前输入的意见。
4. 医生在“我的 → 钱包管理”开通沙箱钱包。患者在“问诊 → 发布求助”填写问题、血糖与用药情况。广场仅展示公开标题、主题、状态及昵称，匿名时隐藏原昵称；健康正文仅本人和接诊医生可见。
5. 医生进入详情点击“我要接诊”，填写评估、用药建议、复查与就医建议并确认出单。只有审核通过的接诊医生可以操作，同一求助只能被一位医生接诊，出单后不能修改。
6. 患者从“我的问诊”进入详情，未支付时服务器不返回问诊单正文。点击“查看完整问诊单”，核对医生后使用沙箱余额支付 1 元。余额不足时前往钱包充值；充值仍须由支付宝沙箱验签/查单确认，不添加假余额。
7. 支付成功后患者余额减 100 分、医生余额加 100 分，同时保存两条收支流水并开放本人阅读权限。患者可从“我的订单 → 问诊订单”继续查看；医生重新打开钱包可看到余额增加。

## 核心文件

以下 Java 路径均相对 `glucopilot-business/src/main/java/com/threadfit/glucopilot/`。

| 功能 | 文件 |
| --- | --- |
| 业务校验、接诊、出单、支付 | `consultation/service/ConsultationService.java` |
| 小程序接口 | `consultation/controller/ConsultationController.java` |
| 管理端审核 | `consultation/controller/AdminConsultationController.java` |
| MyBatis 接口 | `consultation/mapper/ConsultationMapper.java` |
| SQL 与行锁 | `src/main/resources/com/threadfit/glucopilot/consultation/mapper/ConsultationMapper.xml` |
| 表结构 | `src/main/resources/db/migration/V67__consultation_workflow.sql` |
| 测试 | `src/test/java/com/threadfit/glucopilot/consultation/ConsultationWorkflowTest.java` |

资源及测试路径相对业务模块。小程序实现为 `services/consultation.js`、`pages/consultation*`；管理端为 `src/views/users/ConsultationManagementView.vue`、`src/api/consultations.ts`。

## 数据与一致性

- 新增 `consultation_doctor`、`consultation_request`、`consultation_report`、`consultation_wallet_entry`，分别保存资质审核、患者求助、医生问诊单、双方收支流水。
- 沿用 V64 的 `consultation_order` 和 V66 的 `wallet_sandbox_account`。金额统一按分保存，固定 100 分，收款方由报告确定；客户端不能指定金额和医生账号。
- 求助状态为 `WAITING → IN_PROGRESS → REPORT_READY`。发布与出单使用 `clientRequestId` 和唯一约束实现幂等，重试校验参数一致后返回原记录。
- 支付在 Spring 事务中锁订单，按相同顺序锁双方有效账号和余额，再检查余额并条件扣款。同订单支付重试只返回已完成状态，不重复扣款。
- 患者扣款、医生入账、两条流水、订单支付结算状态和阅读授权在同一事务完成。余额不足、状态冲突或流水保存失败全部回滚；不同订单也不能并发花掉同一笔余额。
- 医生被停用后不能继续接诊、出单或接受新的问诊付款；患者已购买的本人报告仍可阅读。

## 验证范围

测试覆盖真实 MyBatis XML 与事务、权限隔离、审核版本冲突、并发接诊、重复支付、两个订单争用最后 1 元、余额不足及流水失败回滚。

V67 先在临时 MySQL 库执行并校验唯一约束，再备份本机业务库后迁移。本机未添加测试医生、假问诊或测试资金。实际双账号页面联调需重启后端后完成；充值是否成功仍以支付宝沙箱查单结果为准。

医生后台账号、菜单和权限配置见 [DOCTOR-PORTAL.md](DOCTOR-PORTAL.md)。
