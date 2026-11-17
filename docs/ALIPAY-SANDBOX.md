# 支付宝沙箱充值联调

当前流程为：创建充值订单和金额 → 当面付二维码 → 沙箱买家付款 → 验签通知/后端主动查单 → 沙箱余额与唯一流水；患者再用钱包余额支付100分查看问诊单，医生获得问诊收入。V70增加医生沙箱提现申请、管理员审核与第5个工作日模拟发放，测试资金不会转为真实钱包余额或向支付宝实际打款。

本机目前没有公网HTTPS回调地址，保留`POST /api/v1/payments/alipay/sandbox/notify`，实际付款继续以后端主动查单结果确认；不能把localhost配置成支付宝可访问的回调地址。未来部署公网后在环境配置中填写`ALIPAY_SANDBOX_NOTIFY_URL`并重启，SDK预下单会携带该地址。回调先RSA2验签，再核对应用/商家/订单/金额/交易号和成功状态，只有`TRADE_SUCCESS`或`TRADE_FINISHED`才入账；重复通知和查单不会重复加钱。

## 一、去哪里获取配置

登录[支付宝开放平台沙箱应用](https://open.alipay.com/develop/sandbox/app)。页面需要登录，菜单以当前控制台为准。

1. **沙箱应用**：复制沙箱AppID。注意不是微信小程序AppID，也不是正式支付宝应用ID。
2. **接口加签方式 / RSA2密钥**：本项目使用普通“公钥模式”，不是公钥证书模式。若页面没有可用的应用私钥，使用[支付宝开放平台开发助手](https://opendocs.alipay.com/common/02kipk)生成一对密钥，选择 **RSA2、2048位、PKCS8（Java适用）**。将生成的“应用公钥”配置到当前沙箱应用；“应用私钥”仅留在本机，填写 `ALIPAY_SANDBOX_PRIVATE_KEY`。不能用应用公钥代替私钥，也不能从公钥还原私钥。已有密钥时先找本机原私钥，不要只换私钥而不匹配平台公钥。
3. **支付宝公钥**：从同一沙箱应用的加签配置获取。它与“应用公钥”不同，后端用它验证支付宝响应和通知。
4. **沙箱账号**：商家账号显示的 **PID就是这里需要的商家UID（seller_id）**，通常以2088开头，填写 `ALIPAY_SANDBOX_SELLER_ID`；务必使用该沙箱应用对应的商家PID，不填买家PID或正式账号PID。付款使用买家测试账号，不能用商家自己付给自己。按控制台指引安装沙箱钱包。

不要将私钥、账号密码或完整支付报文发到聊天/提交GitHub。[官方Java SDK说明](https://github.com/alipay/alipay-sdk-java-all/tree/master/v2)包含公钥模式和RSA2配置说明。

## 二、填写本机环境变量

在自己未提交的 `.env` 填写下面变量。IDEA运行时须确认该 `.env` 已被当前项目的启动配置加载；也可以在“运行配置 → 环境变量”填写。无需修改业务代码![1794813827198](image/ALIPAY-SANDBOX/1794813827198.png)![1794813828484](image/ALIPAY-SANDBOX/1794813828484.png)![1794813831544](image/ALIPAY-SANDBOX/1794813831544.png)![1794813836174](image/ALIPAY-SANDBOX/1794813836174.png)![1794813836887](image/ALIPAY-SANDBOX/1794813836887.png)![1794813951546](image/ALIPAY-SANDBOX/1794813951546.png)![1794813952494](image/ALIPAY-SANDBOX/1794813952494.png)![1794813952770](image/ALIPAY-SANDBOX/1794813952770.png)![1794813952952](image/ALIPAY-SANDBOX/1794813952952.png)![1794813953125](image/ALIPAY-SANDBOX/1794813953125.png)![1794813953279](image/ALIPAY-SANDBOX/1794813953279.png)。

```dotenv
ALIPAY_SANDBOX_ENABLED=true
ALIPAY_SANDBOX_APP_ID=你的沙箱AppID
ALIPAY_SANDBOX_PRIVATE_KEY=你的应用私钥_PKCS8
ALIPAY_SANDBOX_PUBLIC_KEY=支付宝公钥_不是应用公钥
ALIPAY_SANDBOX_SELLER_ID=沙箱商家UID
# 本地无公网地址时留空，使用主动查单确认；公网回调建议配置HTTPS。
ALIPAY_SANDBOX_NOTIFY_URL=
```

密钥可用去掉头尾和换行的Base64字符串。配置映射在 `glucopilot-business/src/main/resources/application.yaml` 的 `payments.alipay.sandbox`。

IDEA 运行配置中直接填写的环境变量也要核对。在“运行 → 编辑配置 → RuoYiApplication → 环境变量”查看 `ALIPAY_SANDBOX_PUBLIC_KEY`。如果这里仍保存旧值，需要在这里更新，并重新启动该运行配置。

网关在代码中固定为沙箱 `https://openapi-sandbox.dl.alipaydev.com/gateway.do`，本模块不能切换到生产网关。缺配置时钱包仍可查看和开通，但不创建支付订单。

## 三、回调和本地测试

- 回调路径：`POST /api/v1/payments/alipay/sandbox/notify`。可选配置格式为 `https://pay.example.com/api/v1/payments/alipay/sandbox/notify`；Nginx须转发POST表单、不重定向，不要求用户Token。
- 不要把内网地址或localhost作为支付宝公网回调地址。没有公网回调也可在付款页点击“我已付款，查询结果”，由后端调用支付宝查询接口确认。
- 只有这个第三方回调按支付宝协议返回纯文本 `success` / `failure`，其他接口保持统一JSON响应。
- [微信小程序原生支付接口](https://pay.wechatpay.cn/doc/v3/merchant/4012791898)用于微信支付。这里不调用 `wx.requestPayment` 唤起支付宝，而是展示沙箱二维码；可在电脑开发者工具展示，用手机沙箱钱包和买家账号扫码。

## 四、验收步骤

1. 重启后端，让Flyway执行V66，重新编译小程序。进入“我的→钱包管理→开通钱包”。
2. 确认页面显示“沙箱测试余额”，选择充值金额（1～1000元）。首次联调建议1元。
3. 确认后生成订单、展示二维码。用沙箱买家账号扫码付款；不要使用真实资金。
4. 付款页前台每5秒请求一次后端查单，最多3分钟后暂停；手动查询仍可用。后端同订单查单最多5秒一次，离开页面停止前端轮询。
5. 只有后端验签且金额/AppID/商家/交易号匹配后才显示已支付并到账。重复通知和查询不重复加余额；“我已付款”按钮本身不能使订单已支付。
6. 回到钱包核对余额，再在“我的订单→充值订单”核对状态。订单创建失败重试沿用请求编号，付款码加载失败可从待支付订单继续。

数据库：`wallet_recharge_order`保存订单与二维码，`wallet_sandbox_account`保存测试余额，`wallet_sandbox_ledger`保存每笔唯一入账。余额与订单、流水在同一事务中更新。未来真实资金须另外接入，不能把沙箱余额直接转为真实余额。

目前尚未接入真实资金交易、真实退款和实际提现打款。问诊单授权及医生沙箱收入已接入；提现仅做可追溯的沙箱模拟，充值不会增加可提现收入额度。申请先冻结、拒绝解冻、审核通过后按工作日历计算第5个工作日，定时任务完成模拟发放。未实际完成沙箱买家付款及后端确认前，不能声称支付宝支付到账已验证。

## 五、预下单失败排查

- `KEY_PAIR_CHECK` 表示配置中的公钥与应用私钥属于同一对密钥，需重新核对 `ALIPAY_SANDBOX_PUBLIC_KEY`。应填同一沙箱应用页面提供的支付宝公钥。运行配置中保存的旧值也可能导致这个问题。
- SDK 的 `CHECK_ALIPAY_PUBLIC_KEY_ERROR` 也表示误填应用公钥。SDK 会用应用私钥签名测试文字，再用配置的支付宝公钥验证，验证通过便触发该错误。本地检查会提前比较 RSA 模数并明确提示对应变量。

- 新增密钥格式检查：应用私钥须为RSA2、至少2048位、PKCS8；支付宝公钥须为有效RSA2公钥。支持PEM头尾、换行及配置中的字面量`\n`。
- 业务拒绝会显示支付宝安全错误码，例如`ACQ.SELLER_NOT_EXIST`，不输出原始报文或密钥；响应data仅含stage、providerCode、providerSubCode。可把这些错误码发来排查，不用提供私钥。
- 网络错误会区分`SDK_TIMEOUT`、`SDK_NETWORK`、`SDK_TLS_ERROR`，响应验签失败为`SDK_RESPONSE_SIGNATURE`。TLS错误先核对电脑时间、证书和网络代理，不关闭证书验证。
- 创建待支付订单不等于扣款，付款码生成失败时余额不会变化，可从“我的订单→充值订单”重试。
