# 一期 API 契约

## 1. 通用约定

- Base path：`/api/v1`；JSON 使用 `snake_case`；时间为 UTC ISO-8601；ID 为 UUID。
- 状态变更使用动作端点，不提供任意状态 PATCH。
- 创建/命令请求必须支持 `Idempotency-Key`；更新携带 `If-Match` 或 `version`。
- 成功响应：`{ "data": ..., "request_id": "..." }`。
- 错误响应：`{ "code": "STATE_CONFLICT", "message": "...", "details": [], "request_id": "..." }`。
- 典型状态码：400 校验，401 未认证，403 越权，404 不存在，409 幂等/状态/版本冲突，422 业务规则，429 限流，503 外部依赖暂不可用。
- 分页：`page` 从 0 开始，`size ≤ 100`，返回 `items/total/page/size`。

## 2. 主要端点

| Method / path | Purpose | Roles |
| --- | --- | --- |
| `GET /me` | 当前用户、角色和数据范围 | all |
| `POST /supplier-applications` | 创建入驻草稿 | authenticated |
| `POST /supplier-applications/{id}:submit` | 提交入驻 | owner |
| `POST /supplier-applications/{id}:approve|return|reject` | 入驻审核 | OPERATOR |
| `POST /datasets` | 创建数据集草稿 | SUPPLIER |
| `POST /datasets/{id}/versions` | 创建初始/更新版本 | owner supplier |
| `POST /ingestions` | 创建接入批次与上传会话 | owner supplier |
| `POST /ingestions/{id}/parts` | 获取/登记分片 | owner supplier |
| `POST /ingestions/{id}:complete` | 幂等完成上传 | owner supplier |
| `POST /admission-runs` | 对固定版本启动准入 | owner/operator |
| `POST /admission-runs/{id}:review` | 提交人工意见 | REVIEWER |
| `POST /admission-runs/{id}:conclude` | 形成最终结论 | OPERATOR |
| `POST /registrations` | 创建登记草稿 | owner supplier |
| `POST /registrations/{id}:submit|withdraw` | 提交/撤回 | owner supplier |
| `POST /registrations/{id}:approve|return|reject` | 登记审核 | OPERATOR |
| `POST /quality-tasks` | 创建质量任务 | supplier/operator |
| `POST /quality-tasks/{id}:accept|retry|cancel` | 管理执行 | QUALITY_ASSESSOR |
| `POST /quality-tasks/{id}/reviews` | 人工样本复核 | REVIEWER |
| `GET /quality-tasks/{id}/report` | 获取结构化报告 | scoped |
| `POST /remediations/{id}:submit` | 提交整改反馈 | owner supplier |
| `POST /remediations/{id}:retest` | 创建复测 | owner/operator |
| `POST /listing-applications` | 创建上架申请 | owner supplier |
| `POST /listing-applications/{id}:submit|approve|return` | 上架流程 | scoped |
| `POST /listings/{id}:pause|resume|delist` | 暂停/恢复/下架 | supplier/operator |
| `GET /market/datasets` | 市场检索筛选 | visible users |
| `GET /market/datasets/{id}` | 数据集详情 | visible users |
| `POST /orders` | 提交订单 | DEMANDER |
| `POST /orders/{id}:supplier-confirm|return|reject|cancel` | 订单动作 | scoped |
| `POST /orders/{id}/contracts` | 上传合同记录 | supplier/operator |
| `POST /contracts/{id}:confirm` | 确认当前文件哈希 | order party |
| `POST /orders/{id}/authorizations` | 生成授权 | OPERATOR/system |
| `POST /authorizations/{id}:revoke` | 撤销授权 | OPERATOR |
| `POST /deliveries` | 创建交付 | system/operator |
| `POST /deliveries/{id}/tickets` | 获取短时下载票据 | authorized demander |
| `GET /deliveries/download/{ticket}` | 受控流式下载 | ticket subject |
| `POST /deliveries/{id}:confirm|report-exception|redeliver|close` | 交付动作 | scoped |
| `GET /tasks` | 统一待办 | assigned roles |
| `GET /audit-events` | 审计查询 | ADMIN/OPERATOR scoped |

## 3. 关键请求示例

### 3.1 提交订单

```json
{
  "dataset_id": "uuid",
  "dataset_version_id": "uuid",
  "purpose": "肿瘤科研分析",
  "requested_valid_from": "2026-09-15T00:00:00Z",
  "requested_valid_to": "2026-12-15T00:00:00Z",
  "download_limit": 2
}
```

服务端必须再次确认 listing 为 ACTIVE 且版本与 listing 一致，保存数据集、版本、条款和需求方快照。

### 3.2 双方确认合同

```json
{
  "file_sha256": "64 lowercase hex chars",
  "confirmation": true
}
```

若当前合同文件哈希不同返回 409，要求用户重新查看，不允许确认旧文件。

### 3.3 生成授权

```json
{
  "subject_id": "uuid",
  "valid_from": "2026-09-15T00:00:00Z",
  "valid_to": "2026-12-15T00:00:00Z",
  "download_limit": 2
}
```

服务端校验合同已确认、主体/版本一致、有效期和次数不超过合同。

## 4. 上传与下载

上传创建返回 `upload_id`、分片大小和已接收分片；完成请求包含每片 ETag/哈希与全文件 SHA-256。完成操作幂等，同一上传不得绑定两个业务文件。

下载票据响应只返回 `ticket` 和 `expires_at`；对象存储凭据、Bucket 和内部对象键不得出现在浏览器响应。流式下载成功前用数据库条件更新或短事务预留次数；失败按明确规则释放，防止并发超限。

## 5. 外部适配端口

- `MetadataPort`：upsert dataset/version/quality summary、publish visibility。
- `RepositoryPort`：create draft、upload/reference files、publish version、grant/revoke access、read download audit。
- `QualityExecutionPort`：submit/status/cancel/result。
- `WorkflowPort`：start process、complete task、query tasks/history。
- `IdentityPort`：resolve user/roles/status、service token。

适配器 DTO 不得直接泄漏到领域模型；所有外部调用必须带 `correlation_id`、超时、可重试分类和审计摘要。
