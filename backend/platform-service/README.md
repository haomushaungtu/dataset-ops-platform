# Platform Service

一期运营业务权威服务，采用 Java 21、Spring Boot 3.5.4、PostgreSQL、Flowable 7.2.0 和模块化单体结构。

## 当前已实现纵向切片

供应商入驻：创建/修改草稿、MinIO 资质材料追加版本、材料幂等重放、提交前材料门禁、不可变快照、Flowable 审核、退回修改后重提、通过、拒绝、撤回，以及申请/材料/状态历史查询。所有命令包含数据库幂等、`If-Match` 乐观锁、状态历史、追加审计与同事务 Outbox。通过审核会创建 `ACTIVE` 供应商及所有者关系；身份 Outbox 消费器使用 `platform.internal` 服务令牌调用身份服务，成功授予 `SUPPLIER` 角色后把同步状态更新为 `SYNCED`，失败则按退避策略重试。

OpenMetadata 可靠投递：业务服务调用 `OpenMetadataSyncOutboxService.enqueue(...)` 时，待同步映射和 Outbox 事件加入调用方的 PostgreSQL 事务。独立调度器以 `FOR UPDATE SKIP LOCKED` 领取事件，使用平台机器令牌调用正式 OpenMetadata adapter；成功响应经业务 ID/FQN 校验后把 `external_id`、`external_fqn`、外部版本和同步时间原子写入 `integration.external_resource_mapping`。远端重复调用遵循 adapter 的幂等 upsert 契约；瞬时故障采用有界指数退避，确定性 4xx 或耗尽重试会进入可查询的终态。每次投递结果追加到 `integration.outbox_delivery_attempt`，运营员或管理员可查询：

- `GET /api/v1/integrations/openmetadata/deliveries/{eventId}`
- `GET /api/v1/integrations/openmetadata/deliveries/failed?limit=50`

## 构建与测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

测试使用内存数据库验证 API/权限/状态/Flowable/材料存储组合行为；设置 `DF_PLATFORM_POSTGRES_IT=true` 可在独立 PostgreSQL 测试库运行迁移和并发测试。139 共享环境的 PostgreSQL 条件测试 2/2 已通过。

## 运行配置

必填环境变量：

- `DF_PLATFORM_DB_URL`
- `DF_PLATFORM_DB_USERNAME`
- `DF_PLATFORM_DB_PASSWORD`
- `DF_PLATFORM_OIDC_ISSUER`

可选环境变量：

- `DF_PLATFORM_OIDC_AUDIENCE`，默认 `dataset-platform-api`
- `DF_PLATFORM_BIND_ADDRESS`，默认 `127.0.0.1`
- `DF_PLATFORM_PORT`，默认 `19100`
- `DF_PLATFORM_ADMIN_PORT`，默认 `19101` 且只监听回环地址
- `DF_PLATFORM_IDENTITY_SYNC_ENABLED`，默认 `false`；共享集成环境显式开启
- `DF_PLATFORM_IAM_BASE_URL`
- `DF_PLATFORM_IAM_CLIENT_ID`
- `DF_PLATFORM_IAM_CLIENT_SECRET`
- `DF_PLATFORM_OPENMETADATA_SYNC_ENABLED`，默认 `false`；共享集成环境显式开启
- `DF_PLATFORM_OPENMETADATA_ADAPTER_BASE_URL`，默认 `http://127.0.0.1:19110`
- `DF_PLATFORM_IAM_TOKEN_URI`，统一身份服务的 token endpoint
- `DF_PLATFORM_OPENMETADATA_CLIENT_ID`
- `DF_PLATFORM_OPENMETADATA_CLIENT_SECRET`
- `DF_PLATFORM_OPENMETADATA_SCOPE`，默认 `platform.internal`
- `DF_PLATFORM_OPENMETADATA_MAX_ATTEMPTS`，默认 `6`
- `DF_PLATFORM_OPENMETADATA_RETRY_BASE_DELAY`，默认 `PT2S`
- `DF_PLATFORM_OPENMETADATA_RETRY_MAX_DELAY`，默认 `PT5M`
- `DF_PLATFORM_MINIO_ENABLED`，默认 `false`；启用时配置以下全部 MinIO 项
- `DF_PLATFORM_MINIO_ENDPOINT`
- `DF_PLATFORM_MINIO_ACCESS_KEY`
- `DF_PLATFORM_MINIO_SECRET_KEY`
- `DF_PLATFORM_MINIO_BUCKET`
- `DF_PLATFORM_MINIO_PREFIX`

开发环境可将 issuer 配置为已启用的私网 HTTP 身份服务；生产环境必须使用 HTTPS。数据库凭据只写入服务器受控环境文件，不得提交到仓库。

## 已知集成门

- Kafka Outbox 发布器尚未实现，Outbox 事件目前可靠落库但不会自动发布。
- OpenMetadata 投递已通过本地事务、重放、重试耗尽、审计和权限测试，但尚未部署到共享 PostgreSQL/OpenMetadata 环境；机器账号在生产前仍须收敛为最小权限。
- 共享开发环境当前复用 `dataset-landing/phase1/platform/supplier-qualifications`；生产仍需专用最小权限账号、恶意文件检测、加密和生命周期策略。
