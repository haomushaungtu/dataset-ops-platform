# OpenMetadata Adapter

一期 OpenMetadata 正式适配器的首个可运行切片。它是无状态 Spring Boot / Java 21 服务，不依赖 OpenMetadata 登录 Session，也不直写 OpenMetadata 数据库。

## 已实现契约

`POST /api/v1/openmetadata/dataset-versions:upsert`

- 入站必须携带由统一 IAM 签发、包含 `platform.internal` scope 且 audience 为 `DF_OM_ADAPTER_OIDC_AUDIENCE` 的 Bearer Token。
- 适配器使用自己的 client credentials 向 `DF_OM_ADAPTER_IAM_TOKEN_URI` 获取一次性机器 Token，再以 Bearer Token 调用 OpenMetadata REST。
- 调用方必须传入已经持久化映射的 OpenMetadata Table FQN；适配器不猜测服务、数据库或 Schema，也不直写远端数据库。
- 适配器确保 OpenMetadata `table` 类型存在五个 string 自定义属性：`platformDatasetId`、`platformVersionId`、`cancerTypes`、`modalities`、`qualitySummary`。
- 癌种、模态以排序去重后的 JSON 数组字符串保存；质量摘要以 JSON 对象字符串保存。这样不会把尚未定版的业务词表错误固化为 OpenMetadata Enum。词表定版后可迁移到 Classification/Tag，但稳定属性名保持不变。
- 若目标 Table 已映射到其他 `dataset_id`，返回 HTTP 409，绝不覆盖。
- 只有 PATCH 成功且再次 GET 回读的五个属性、外部 ID/FQN/version 全部有效时才返回 `SYNCED`。

合成请求示例：

```json
{
  "dataset_id": "30000000-0000-0000-0000-000000000001",
  "version_id": "40000000-0000-0000-0000-000000000001",
  "open_metadata_table_fqn": "poc.synthetic.cancer_registry",
  "cancer_types": ["肺癌", "乳腺癌"],
  "modalities": ["STRUCTURED", "TEXT"],
  "quality_summary": {
    "score": 92.50,
    "grade": "A",
    "gate_result": "PASS"
  }
}
```

成功响应可直接用于调用方的 `external_resource_mapping` 和同步状态回写：

```json
{
  "dataset_id": "30000000-0000-0000-0000-000000000001",
  "version_id": "40000000-0000-0000-0000-000000000001",
  "external_system": "OPENMETADATA",
  "resource_type": "TABLE",
  "external_id": "20000000-0000-0000-0000-000000000001",
  "external_fqn": "poc.synthetic.cancer_registry",
  "external_version": "0.2",
  "status": "SYNCED",
  "synced_at": "2026-09-04T02:30:00Z"
}
```

远端失败、无效响应或回读不一致返回 502；映射冲突返回 409；请求校验失败返回 400。适配器不在内存外保存 Token，不持久化业务映射，也不自行后台重试。调用方须在业务事务内落 Outbox/同步状态，并以同一 `dataset_id + version_id + FQN` 安全重放；重复调用为幂等更新。

## 配置

所有地址和凭据仅通过环境变量注入，仓库不含默认凭据：

| 环境变量 | 用途 |
| --- | --- |
| `DF_OM_ADAPTER_OIDC_ISSUER` | 入站 JWT issuer |
| `DF_OM_ADAPTER_OIDC_AUDIENCE` | 入站 audience，默认与现有平台资源一致的 `dataset-platform-api` |
| `DF_OM_ADAPTER_IAM_TOKEN_URI` | IAM OAuth2 token endpoint，例如内部 HTTP `/oauth2/token` |
| `DF_OM_ADAPTER_IAM_CLIENT_ID` | 适配器机器客户端 ID |
| `DF_OM_ADAPTER_IAM_CLIENT_SECRET` | 适配器机器客户端密钥 |
| `DF_OM_ADAPTER_IAM_SCOPE` | client credentials scope，默认 `platform.internal` |
| `DF_OM_ADAPTER_BASE_URL` | OpenMetadata API 根地址，须包含 `/api` |
| `DF_OM_ADAPTER_CONNECT_TIMEOUT` | 连接超时，默认 `PT3S` |
| `DF_OM_ADAPTER_READ_TIMEOUT` | 读取超时，默认 `PT10S` |

服务默认仅监听 `127.0.0.1:19110`，管理端口为 `127.0.0.1:19111`，避开 139 主机 OpenSearch 的 `19200` 端口。启动时配置绑定校验会拒绝空凭据、相对 URI 和非正超时。

## 构建与测试

使用 Java 21 与 Maven 3.9.11：

```bash
mvn test
mvn clean package
```

测试全部使用 Spring Mock HTTP/JWT 服务，不访问真实 IAM 或 OpenMetadata；覆盖 client credentials、不缓存 Token、创建属性定义、PATCH 后回读确认、跨数据集映射冲突，以及无 Token 401、错误 scope 403、正确机器 Token 200 且响应不泄露 Token/密钥。

## 真实集成门

部署/联调前仍需完成以下门禁，未通过时不得声称真实同步成功：

1. 在 IAM 注册独立机器客户端，固定 audience/scope，并在 OpenMetadata 将该机器主体映射为仅可读取 Table、维护上述自定义属性的最小权限主体。
2. OpenMetadata 固定源码提交构建和原生服务启动完成，`DF_OM_ADAPTER_BASE_URL` 可达；认证模式保持 `custom-oidc`，不使用登录 Session。
3. 先用合成 Table 验证五个属性的创建权限、PATCH、回读、搜索索引可见性和错误 audience/scope 拒绝。
4. platform-service 增加 Outbox 消费器，将成功响应持久化到 `external_resource_mapping`；失败记录下一次重试时间，耗尽后进入运营告警。
5. 真实联调证据只记录 request ID、业务 ID、OpenMetadata entity ID/version 和 HTTP 结果，不记录 client secret、access token 或完整受限数据。
