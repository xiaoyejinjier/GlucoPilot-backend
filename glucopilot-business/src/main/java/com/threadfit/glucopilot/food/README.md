# 食品营养库实现与Redis缓存

食品分类和食品详情使用Redis旁路读缓存，正式数据仍由MySQL和MyBatis维护。分页检索、管理端列表及图片二进制读取保持原有链路。接口、DTO字段和食品图片优先级不变。

## 按功能查找实现

| 功能 | 代码入口 |
| --- | --- |
| 小程序分类缓存入口 | `service/FoodService.listFoodCategories` |
| 分类未命中时的MyBatis查询 | `service/FoodService.loadFoodCategories` |
| 小程序食品详情缓存入口 | `service/FoodService.getFoodDetail` |
| 详情未命中时的上架校验和DTO转换 | `service/FoodService.loadFoodDetail` |
| 版本检查、读缓存、查库、写缓存 | `service/FoodCatalogCache.getOrLoad` |
| Redis异常回源、5秒退避 | `service/FoodCatalogCache.read/write/redisUnavailable` |
| 分类和食品修改后的缓存失效 | `service/AdminFoodService.saveCategory/removeCategory/saveFood/removeFood` |
| 同事务推进目录版本 | `service/FoodCatalogCache.advanceCatalogVersion` |
| 版本查询与递增SQL | `mapper/FoodCatalogCacheVersionMapper` |
| Redis内部JSON结构 | `dto/FoodCacheSnapshots` |
| 缓存开关、过期时间、键前缀 | `config/FoodCatalogCacheProperties`、`application.yaml`的`food.catalog-cache` |
| 版本表初始化 | `db/migration/V52__food_catalog_cache_version.sql`，位于resources目录 |

## 读缓存的流程

1. 关闭缓存、Redis暂时故障或当前已有数据库事务时，直接执行原数据库查询。
2. 用MyBatis按主键读取目录的数据库身份和已提交版本。
3. 根据“键前缀、目录身份、版本、资源”组成Redis键。
4. 缓存命中时反序列化JSON，转换成原有HTTP DTO。
5. 未命中时执行原MyBatis查询，保存JSON并设置TTL。Redis读写或解析失败不改变数据库业务结果；数据库抛出的404等异常正常返回。

键结构如下，花括号表示占位说明：

```text
glucopilot:food:v1:{目录身份}:{版本}:categories
glucopilot:food:v1:{目录身份}:{版本}:detail:{食品ID}
```

空分类列表可以缓存；不存在、下架和删除食品的错误不缓存。Redis只保存公开食品字段及图片地址，不保存照片、视频、用户资料或健康记录。

## 修改食品后如何避免旧缓存

管理端四个写入口在原有事务内调用`advanceCatalogVersion`，同时提交食品内容和目录版本。版本方法要求已有事务，不能在食品写入后独立提交版本。

- 提交：后续新请求读取新版本，使用新的缓存键。
- 回滚：食品和版本都回滚，原缓存继续对应已提交数据。
- 并发旧查询：即使修改后才完成回填，也只能写到旧版本键，不能覆盖新版本缓存。
- 分类改名或换图：整个目录版本变化，分类列表与所有食品详情中的分类名称一并失效。
- Redis宕机：数据库版本照常更新，恢复后也不会重新采用旧版本缓存。

旧版本条目通过TTL回收，不使用`KEYS`扫描删除。每次缓存读取仍保留一次轻量版本查询；减少的是完整分类/详情查询及结果组装，并非完全不访问数据库。

`FoodService`处于其他事务内时不读写Redis，避免把未提交食品或旧事务快照发布到共享缓存。分页查询和食品图片鉴权仍按原逻辑执行。

## 配置与启动

复用已有`spring.data.redis`连接，默认开启缓存，TTL为10分钟，故障后退避5秒。可通过环境变量`FOOD_CACHE_ENABLED`、`FOOD_CACHE_TTL`、`FOOD_CACHE_KEY_PREFIX`调整，TTL必须大于0且不超过1天。关闭某实例的读缓存后，写操作仍推进版本，以兼容其他启用缓存的实例。

重启后端时由Flyway执行V52，创建并初始化单行版本表。Redis中不需要手动建表或创建键。复制数据库后若多个独立数据库共用同一个Redis，应设置不同的键前缀；恢复旧数据库备份后应清理对应缓存命名空间。

通过业务Service修改食品会自动推进版本。直接SQL或外部程序修改食品表时，需要在同一事务中递增`food_catalog_cache_version.revision`，或先关闭食品读缓存。

## 验证代码

- `FoodCatalogCacheTest`：JSON命中、空列表、十进制与图片地址、Redis故障、损坏JSON、事务绕过、不同目录隔离、迟到回填。
- `FoodCatalogCacheIntegrationTest`：实际V52迁移、真实MyBatis及Spring事务，验证换图、上下架、删除、分类改名和事务回滚；设置测试Redis端口后还会验证真实Redis的GET/SET、TTL和版本切换。
- 真实Redis验证使用随机测试前缀，只删除明确的测试键，不扫描或清空现有Redis数据。
