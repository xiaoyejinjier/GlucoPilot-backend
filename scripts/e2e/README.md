# E2E / 联调辅助脚本（dev only）

这些脚本是**开发期的验证工具**，把"AI 助手能不能真的说对话、后台改配置到底有没有生效"这类问题
变成可重复执行的断言。它们会真的登录、真的调模型、真的改数据库，**不要对生产库/生产应用运行**。

## 为什么放在 `scripts/e2e/` 而不是 `target/`

原先它们散落在 `target/e2e/` 下（`mvn clean` 一跑就没了，且里面带着明文密钥）。
现在入库并**脱敏**：所有凭据都来自环境变量或仓库之外的文件，脚本本身不含任何密钥。

## 环境变量

| 变量 | 用途 | 默认 |
| --- | --- | --- |
| `E2E_BASE_URL` | 被测后端地址 | `http://127.0.0.1:8080` |
| `E2E_APP_LOG` | 后端日志文件路径（脚本要断言日志里的运行痕迹） | `<repo>/target/e2e-app.log` |
| `E2E_MYSQL_CNF` | 已有的 mysql `--defaults-extra-file`（**推荐放仓库之外**） | 由下面 4 个变量生成临时文件 |
| `E2E_MYSQL_HOST` / `PORT` / `USER` / `PASSWORD` | 未给 `E2E_MYSQL_CNF` 时用它生成临时 cnf | `127.0.0.1` / `3306` / `root` / 无 |
| `E2E_MYSQL_EXE` | mysql 客户端路径 | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |
| `E2E_ADMIN_PASSWORD` | 后台 admin 密码 | `admin123`（开发种子口令） |
| `E2E_DEEPSEEK_KEY` | 真实 DeepSeek 密钥（仅 `cipher-e2e.ps1` 需要） | 无（缺了直接报错） |
| `E2E_USER_CODE` | C 端 mock 登录用的 code | `mock-glucose-demo` |

推荐在本机一次性建一个仓库外的 defaults 文件（例如 `%USERPROFILE%\.glucopilot\my.cnf`）：

```ini
[client]
host=127.0.0.1
port=3306
user=root
password=<你的库口令>
default-character-set=utf8mb4
```

然后：`$env:E2E_MYSQL_CNF = "$env:USERPROFILE\.glucopilot\my.cnf"`。

## 脚本

| 脚本 | 做什么 | 需要的额外前置 |
| --- | --- | --- |
| `ai-smoke.ps1` | C 端链路冒烟：登录 → 建会话 → 落用户消息 → SSE 补全；并验证「用药问题被医疗护栏硬拦且不调工具」 | 后端在跑 |
| `image-round.ps1` | 完整图片轮：登记 media → 视觉模型看图 → 回写 caption → 再对话 | MinIO/本地存储可用；`ai.vision.enabled=true` |
| `default-model-e2e.ps1` | 默认模型切换：停用旧模型、启用新模型后，实际调用与落库 `model_code` 是否都切过去了（含反向回落） | 数据库可连（会改 `ai_model_config`） |
| `cipher-e2e.ps1` | 密钥链路：保存密钥 → 密文落库 → `/test` 解密可用 → 换无效密钥必须 AUTH → 清空回落 yaml | `E2E_DEEPSEEK_KEY`、`E2E_MYSQL_CNF` |
| `window-check.ps1` | 会话记忆窗口：连续多轮后检查送给模型的历史条数符合 `ai.memory.window-messages` | 后端在跑 |
| `prompt-e2e.ps1` | 提示词管理 + 场景绑定：阻断式校验（3 个反例）→ 新增草稿 → 草稿原地编辑 + 乐观锁 409 → 启用 → **新会话落库的 `chat_session.system_prompt_ver` 变成新版**（端到端证明"下一条消息用新提示词"）→ 回滚 → 场景参数保存/乐观锁/清空覆盖 | 后端在跑、`E2E_MYSQL_CNF`（会改库：临时提示词版本与场景参数，脚本结束前自行清理/还原；不需 `E2E_DEEPSEEK_KEY`，全程不调模型） |
| `mock-data.ps1` | 生成近一个月的血糖/饮食/健康档案模拟数据（标记 `【模拟数据】`，便于清理） | `E2E_MYSQL_CNF` |

示例：

```powershell
$env:E2E_MYSQL_CNF = "$env:USERPROFILE\.glucopilot\my.cnf"
$env:E2E_BASE_URL  = 'http://127.0.0.1:8091'
$env:E2E_APP_LOG   = 'E:\dsh-workdir\GlucoPilot-backend\target\e2e-app.log'
$env:E2E_DEEPSEEK_KEY = '<真实密钥>'
.\scripts\e2e\default-model-e2e.ps1
.\scripts\e2e\cipher-e2e.ps1
```

## 注意

- 这些脚本会**改数据库**（`ai_model_config` 的启停/默认标记、密钥密文；`ai_prompt_template` 与 `ai_scene_config`
  的临时改动；`mock-data.ps1` 插入模拟数据）。它们会尽量把状态还原
  （例如 `cipher-e2e` 结束时清空密钥回到 yaml，`prompt-e2e` 结束时删掉临时版本、把场景配置改回继承），
  但请只对自己的开发库运行。
- `prompt-e2e.ps1` 为了造出"下一条消息落到新会话"，会把这个 mock 用户的**进行中会话归档**
  （`chat_session.session_status` 置 2）——它只影响开发库里的 mock 用户。
- 临时产物（请求体 JSON、SSE 原文、答案）写在 `<repo>/target/e2e/`，该目录已被 `.gitignore` 忽略。
- `MockGlucoseData.java` 在早期文档里提过，但项目里并不存在；模拟数据由 `mock-data.ps1` + SQL 生成。
