# GlucoPilot MVP API V1

> 状态：草案。本文定义后端 MVP 的接口契约；不代表已经实现。
>
> 范围：账号、健康档案、文件上传、食物库、饮食记录、手动血糖记录、Mock 食物识别、基础预测和统计查询。

## 1. MVP 核心闭环

```text
录入餐前血糖
  -> 上传食物图片
  -> AI 返回候选食物（不写入正式餐记录）
  -> 用户确认食物和重量
  -> 创建正式饮食记录并绑定餐前血糖
  -> 创建预测记录
  -> 录入餐后 30/60/120 分钟血糖
  -> 系统更新用餐响应和预测误差
```

本版本只有食物图片识别，**没有血糖图片 OCR**。设备数据先通过 Mock Adapter 写入与手动记录相同的血糖模型，真实设备接入不属于 MVP 接口。

## 2. 通用约定

### 2.1 基础信息

| 项目 | 约定 |
|---|---|
| 基础路径 | `/api/v1` |
| 数据格式 | `application/json; charset=utf-8`，上传文件除外 |
| 认证 | 除注册、登录外，均携带 `Authorization: Bearer <accessToken>` |
| 用户归属 | 服务端从 JWT 得到当前用户；请求体和路径中不传 `userId` |
| ID 表达 | 数据库存储为 `BIGINT`，接口中统一以字符串返回和传入，避免 JavaScript 精度丢失 |
| 时间 | `LocalDateTime`，格式 `yyyy-MM-dd'T'HH:mm:ss`，例如 `2026-09-04T12:00:00` |
| 血糖单位 | 内部和 API 统一使用 `mmol/L`；第三方 `mg/dL` 仅能在设备 Adapter 内转换 |
| 重量与营养 | 重量单位为 `g`；碳水、蛋白、脂肪单位为 `g`；热量单位为 `kcal` |

### 2.2 统一响应

成功响应：

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {},
  "traceId": "a1b2c3d4",
  "timestamp": "2026-09-04T12:00:00"
}
```

失败响应：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "mealTime must not be null",
  "data": null,
  "traceId": "a1b2c3d4",
  "timestamp": "2026-09-04T12:00:00"
}
```

HTTP 状态码表达 HTTP 语义（`400`、`401`、`403`、`404`、`409`、`422`、`500`）；`code` 表达稳定的业务错误类型，前端应优先按 `code` 处理。

分页响应的 `data` 结构：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

分页参数默认 `page=1`、`pageSize=20`，`pageSize` 最大为 `100`。

### 2.3 通用错误码

| code | HTTP | 含义 |
|---|---:|---|
| `SUCCESS` | 200 | 请求成功 |
| `VALIDATION_FAILED` | 400 | 字段缺失、格式或值域不合法 |
| `AUTH_PHONE_ALREADY_EXISTS` | 409 | 手机号已注册 |
| `AUTH_INVALID_CREDENTIALS` | 401 | 手机号或密码错误 |
| `AUTH_TOKEN_INVALID` | 401 | Token 无效或过期 |
| `RESOURCE_NOT_FOUND` | 404 | 当前用户不可访问或资源不存在 |
| `FILE_TYPE_NOT_ALLOWED` | 422 | 文件类型不在允许范围内 |
| `FILE_NOT_OWNED` | 403 | 文件不属于当前用户 |
| `MEAL_FOODS_REQUIRED` | 422 | 一顿饭至少应包含一种食物 |
| `MEAL_NOT_EDITABLE` | 409 | 已生成预测或真实响应的餐不能直接修改 |
| `GLUCOSE_VALUE_INVALID` | 422 | 血糖值不在合理范围内 |
| `PRE_GLUCOSE_REQUIRED` | 422 | 创建预测前未绑定有效餐前血糖 |
| `AI_TASK_FAILED` | 502 | AI 服务调用失败 |
| `PREDICTION_SAMPLE_INSUFFICIENT` | 422 | 个人历史样本不足，不能生成个人预测 |

## 3. 业务枚举

| 字段 | 可选值 |
|---|---|
| `mealType` | `BREAKFAST`、`LUNCH`、`DINNER`、`SNACK` |
| `mealStatus` | `CONFIRMED`、`VOIDED` |
| `glucoseSourceType` | `MANUAL`、`GLUCOSE_METER`、`CGM`、`SMART_WATCH`、`THIRD_PARTY` |
| `mealRelation` | `UNBOUND`、`PRE_MEAL`、`POST_30`、`POST_60`、`POST_120`、`OTHER` |
| `taskType` | `FOOD_RECOGNITION`、`GLUCOSE_PREDICTION`、`DIET_RECOMMENDATION`、`CHAT` |
| `taskStatus` | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED` |
| `filePurpose` | `AVATAR`、`MEAL_IMAGE` |
| `recordStatus` | `ACTIVE`、`VOIDED` |

## 4. 认证与账号

首期认证采用 **手机号 + 密码**。短信验证码、第三方登录、刷新 Token 和角色权限不纳入 V1。

### 4.1 注册

`POST /api/v1/auth/register`

```json
{
  "phone": "13800138000",
  "password": "GlucoPilot123",
  "nickname": "小王"
}
```

规则：手机号唯一；密码长度 8 至 64 位；服务端只存储 BCrypt 哈希。

成功 `data`：

```json
{
  "id": "10001",
  "phone": "13800138000",
  "nickname": "小王",
  "status": "ACTIVE",
  "createdAt": "2026-09-04T12:00:00"
}
```

### 4.2 登录

`POST /api/v1/auth/login`

```json
{
  "phone": "13800138000",
  "password": "GlucoPilot123"
}
```

成功 `data`：

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresIn": 7200,
  "user": {
    "id": "10001",
    "nickname": "小王",
    "avatarFileId": null
  }
}
```

### 4.3 当前用户资料

`GET /api/v1/users/me`

`PATCH /api/v1/users/me`

```json
{
  "nickname": "小王",
  "avatarFileId": "20001"
}
```

只允许修改昵称和头像。手机号与密码变更从 V1 中排除，避免在没有短信校验的情况下留下安全漏洞。

## 5. 健康档案

### 5.1 查询健康档案

`GET /api/v1/health-profile`

不存在档案时返回 `data: null`，仍是成功响应。

### 5.2 新建或覆盖健康档案

`PUT /api/v1/health-profile`

```json
{
  "height": 172.5,
  "weight": 68.2,
  "targetWeight": 65.0,
  "gender": "MALE",
  "birthday": "1998-05-20",
  "remark": "餐后血糖偏高"
}
```

每个用户最多一份档案。`gender`、`birthday` 的权威来源是健康档案，不与账号表重复保存。

## 6. 文件

### 6.1 上传文件

`POST /api/v1/files`

请求类型为 `multipart/form-data`：

| 参数 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 文件二进制内容 |
| `purpose` | 是 | `AVATAR` 或 `MEAL_IMAGE` |

成功 `data`：

```json
{
  "id": "20001",
  "purpose": "MEAL_IMAGE",
  "originalFilename": "lunch.jpg",
  "contentType": "image/jpeg",
  "size": 238491,
  "accessUrl": "https://minio.example.com/...",
  "accessUrlExpiresAt": "2026-09-04T13:00:00"
}
```

服务端保存 MinIO 对象键，不将永久对象地址当作业务字段。`accessUrl` 是临时访问地址。

### 6.2 获取临时访问地址

`GET /api/v1/files/{fileId}/access-url`

只允许文件所有者访问。

## 7. 食物营养库

### 7.1 搜索食物

`GET /api/v1/foods?keyword=米饭&category=STAPLE&page=1&pageSize=20`

成功 `items` 的单项：

```json
{
  "id": "30001",
  "name": "熟米饭",
  "category": "STAPLE",
  "carbohydratePer100g": 25.9,
  "proteinPer100g": 2.6,
  "fatPer100g": 0.3,
  "caloriesPer100g": 116.0,
  "source": "CN_FOOD_COMPOSITION"
}
```

### 7.2 查询食物详情

`GET /api/v1/foods/{foodId}`

V1 只读。食物库的导入与后台维护不对普通用户开放。

## 8. 血糖记录

用户端接口仅创建手动记录；设备同步写入同一模型时由服务端指定来源和厂商，客户端不能伪造设备来源。

### 8.1 新建手动血糖记录

`POST /api/v1/glucose-records`

```json
{
  "glucoseValue": 5.8,
  "measureTime": "2026-09-04T11:55:00",
  "mealId": null,
  "mealRelation": "UNBOUND"
}
```

创建时服务端固定：`sourceType=MANUAL`、`unit=MMOL_L`、`recordStatus=ACTIVE`。血糖合理范围由服务端统一校验；首期建议为 `1.0` 至 `33.3 mmol/L`。

### 8.2 查询血糖记录

`GET /api/v1/glucose-records?startTime=2026-09-01T00:00:00&endTime=2026-09-07T23:59:59&mealId=40001&page=1&pageSize=20`

记录项示例：

```json
{
  "id": "50001",
  "glucoseValue": 5.8,
  "unit": "MMOL_L",
  "measureTime": "2026-09-04T11:55:00",
  "sourceType": "MANUAL",
  "provider": "GENERIC",
  "mealId": null,
  "mealRelation": "UNBOUND",
  "recordStatus": "ACTIVE"
}
```

### 8.3 修改与作废

`PUT /api/v1/glucose-records/{glucoseRecordId}` 使用与创建相同的字段。

`DELETE /api/v1/glucose-records/{glucoseRecordId}` 为逻辑作废，不做物理删除。若这条记录已参与用餐响应或预测评估，服务端须重算关联结果；设备同步数据不能通过用户端修改或作废。

### 8.4 录入餐后血糖

仍调用 `POST /api/v1/glucose-records`，但必须携带已确认的 `mealId` 和对应关系：

```json
{
  "glucoseValue": 8.4,
  "measureTime": "2026-09-04T13:00:00",
  "mealId": "40001",
  "mealRelation": "POST_60"
}
```

服务端在事务提交后更新该餐的 `meal_response`，并回填该餐预测记录的实际值与误差。

## 9. AI 食物识别

### 9.1 发起识别

`POST /api/v1/ai/food-recognitions`

```json
{
  "fileId": "20001"
}
```

MVP 的 Mock 实现可以同步返回结果，但仍需创建 `ai_task`。未来换成异步模型时，接口语义不变。

成功 `data`：

```json
{
  "taskId": "60001",
  "taskStatus": "SUCCEEDED",
  "foods": [
    {
      "foodId": "30001",
      "name": "熟米饭",
      "estimatedWeight": 150.0,
      "confidence": 0.91,
      "carbohydrate": 38.85,
      "protein": 3.9,
      "fat": 0.45,
      "calories": 174.0
    }
  ]
}
```

识别接口只产生候选结果。客户端必须让用户确认或修改重量后，才能调用创建饮食记录接口。

### 9.2 查询 AI 任务

`GET /api/v1/ai/tasks/{taskId}`

仅允许查询当前用户创建的任务。`outputJson` 不直接透传；服务端按 `taskType` 转成稳定 DTO 返回。

## 10. 饮食记录

### 10.1 创建正式饮食记录

`POST /api/v1/meals`

```json
{
  "mealType": "LUNCH",
  "mealTime": "2026-09-04T12:00:00",
  "imageFileId": "20001",
  "preGlucoseRecordId": "50001",
  "foods": [
    {
      "foodId": "30001",
      "aiWeight": 150.0,
      "confirmedWeight": 180.0
    },
    {
      "foodId": "30002",
      "aiWeight": 100.0,
      "confirmedWeight": 100.0
    }
  ]
}
```

规则：

- `foods` 至少一项，`confirmedWeight` 必填且大于 0。
- `foodId` 必须存在于食物库；营养值由服务端按食物库和确认重量计算，客户端不能直接提交总营养。
- `aiWeight` 可为空，手动录入食物时不需要伪造 AI 识别结果。
- `preGlucoseRecordId` 可为空；但没有有效餐前血糖时不能创建预测。
- 指定餐前记录时，服务端检查其归属、未作废、尚未绑定其他餐，并将其关系更新为 `PRE_MEAL`。

成功 `data`：

```json
{
  "id": "40001",
  "mealType": "LUNCH",
  "mealTime": "2026-09-04T12:00:00",
  "imageFileId": "20001",
  "preGlucoseRecordId": "50001",
  "totalCarbohydrate": 65.2,
  "totalProtein": 28.6,
  "totalFat": 13.4,
  "totalCalories": 510.0,
  "status": "CONFIRMED",
  "foods": []
}
```

### 10.2 查询列表与详情

`GET /api/v1/meals?startTime=2026-09-01T00:00:00&endTime=2026-09-07T23:59:59&mealType=LUNCH&page=1&pageSize=20`

`GET /api/v1/meals/{mealId}`

详情必须返回餐内食物、关联餐前血糖、已有预测摘要和 30/60/120 分钟真实血糖摘要，避免前端再拼装多个接口。

### 10.3 修改与作废

`PUT /api/v1/meals/{mealId}` 使用与创建相同的请求体。

只允许在尚未生成预测、尚未形成真实用餐响应时修改。已参与预测的数据不可静默篡改，服务端返回 `MEAL_NOT_EDITABLE`。

`DELETE /api/v1/meals/{mealId}` 逻辑作废并设置 `status=VOIDED`；不做物理删除。

## 11. 血糖预测

### 11.1 创建或获取当前预测

`POST /api/v1/predictions`

```json
{
  "mealId": "40001"
}
```

服务端读取已确认餐、已绑定的餐前血糖和当前用户历史 `meal_response`，计算 `ΔG30`、`ΔG60`、`ΔG120`。同一餐在 V1 只有一个有效预测；重复请求返回已有预测，保证接口幂等。

成功 `data`：

```json
{
  "id": "70001",
  "mealId": "40001",
  "preGlucose": 5.8,
  "predictedDelta30": 1.8,
  "predictedDelta60": 2.5,
  "predictedDelta120": 1.1,
  "predictedGlucose30": 7.6,
  "predictedGlucose60": 8.3,
  "predictedGlucose120": 6.9,
  "confidence": 0.73,
  "modelVersion": "knn-v1",
  "actualGlucose30": null,
  "actualGlucose60": null,
  "actualGlucose120": null,
  "error30": null,
  "error60": null,
  "error120": null,
  "createdAt": "2026-09-04T12:01:00"
}
```

### 11.2 查询预测

`GET /api/v1/predictions/{predictionId}`

`GET /api/v1/predictions?mealId=40001`

预测接口不接收前端提交的真实血糖。真实血糖只能经由血糖记录接口进入系统，随后由服务端计算误差。

## 12. 统计

统计数据直接聚合业务表，不单独建立统计表。

### 12.1 概览

`GET /api/v1/statistics/overview?startDate=2026-09-01&endDate=2026-09-07`

返回平均血糖、有效血糖记录数、用餐数、已评估预测数和 MAE。没有足够数据的指标返回 `null`，不返回误导性的 `0`。

### 12.2 血糖趋势

`GET /api/v1/statistics/glucose-trend?startDate=2026-09-01&endDate=2026-09-07&granularity=DAY`

`granularity` 在 V1 支持 `DAY`。响应按日期返回平均值、最小值、最大值和记录数。

## 13. 非 MVP：已预留但暂不实现

| 功能 | 原因 |
|---|---|
| 真实设备授权、绑定、同步接口 | 先通过 `MockDeviceAdapter` 验证统一血糖模型与幂等处理 |
| AI 聊天、饮食建议 | 不影响“用餐—预测—真实结果”核心闭环 |
| 多图片一餐 | V1 仅用 `imageFileId` 保存封面图；后续再引入 `meal_file` 关联表 |
| 短信登录、刷新 Token、第三方登录 | 需要额外安全与外部服务设计 |
| 血糖图片 OCR | 不属于产品需求 |

## 14. 前端 Mock 调用顺序

1. 登录并保存 `accessToken`。
2. 调用 `POST /glucose-records` 创建 `UNBOUND` 的餐前血糖，保存返回的 `id`。
3. 上传图片，拿到 `fileId`；需要时调用食物识别接口，展示候选值。
4. 用户确认食物和重量后，调用 `POST /meals`，同时带上 `fileId` 和餐前血糖记录 ID。
5. 调用 `POST /predictions`，展示预测的 30/60/120 分钟血糖。
6. 用户或设备录入餐后血糖时，调用血糖记录接口并携带 `mealId` 和 `POST_30`、`POST_60` 或 `POST_120`。
7. 再查询餐详情或预测详情，获得系统更新后的真实值和误差。

## 15. 待数据库设计确认的约束

1. `app_user.phone` 唯一。
2. `health_profile.user_id` 唯一，一位用户一份健康档案。
3. `meal_food.meal_id`、`glucose_record(user_id, measure_time)`、`meal_response.meal_id`、`prediction_record.meal_id` 必须建立索引。
4. 设备同步的去重键为 `(user_id, provider, external_record_id)`；手动记录没有 `external_record_id`。
5. `meal_response` 一餐一条，由系统从有效血糖记录推导，不开放直接写入接口。
6. 营养字段在 `meal_food` 和 `meal_record` 中保存快照，食物库后续变化不得改写历史。
