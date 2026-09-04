# 一期领域事件契约

## 1. Envelope

```json
{
  "event_id": "uuid",
  "event_type": "dataset.version.admission-passed.v1",
  "occurred_at": "2026-09-02T08:00:00Z",
  "producer": "platform-service",
  "aggregate_type": "dataset_version",
  "aggregate_id": "uuid",
  "aggregate_version": 7,
  "correlation_id": "uuid",
  "causation_id": "uuid",
  "actor_id": "uuid-or-service",
  "payload": {}
}
```

规则：事件不可变；敏感原文、令牌、凭据、合同内容和问题样本不得进入 Kafka；只传稳定引用与必要摘要。破坏性变化提升事件版本。

## 2. 主题与事件

| Topic | Event types | Main consumers |
| --- | --- | --- |
| `phase1.supplier.events.v1` | submitted, approved, returned, rejected | identity, notification, audit |
| `phase1.dataset.events.v1` | version-created, admission-passed/failed, registered | repository, metadata, quality |
| `phase1.quality.events.v1` | task-created/running/passed/failed, remediation-opened | listing, metadata, notification |
| `phase1.listing.events.v1` | requested, published, paused, delisted | metadata, repository, market index |
| `phase1.order.events.v1` | submitted, supplier-confirmed, rejected, completed | workflow, notification, archive |
| `phase1.contract.events.v1` | uploaded, party-confirmed, both-confirmed | authorization, notification |
| `phase1.authorization.events.v1` | activated, exhausted, expired, revoked | repository, delivery, audit |
| `phase1.delivery.events.v1` | created, ready, downloaded, exception, confirmed | order, notification, archive |

## 3. 交付保证

- 生产者：业务事务写 Outbox；独立发布器发送后标记，允许重复发送。
- 消费者：`event_id` 去重；按聚合版本拒绝乱序旧事件；外部副作用使用幂等键。
- 重试：瞬时错误指数退避；确定性数据错误进入死信并产生运营待办。
- 回放：消费者必须支持指定事件范围回放，回放不重复发送通知或扣减下载次数。

## 4. 同步完成事件

跨系统写入以 `integration.sync-succeeded.v1` / `integration.sync-failed.v1` 表达，payload 包含 `target_system`、`resource_type`、`business_id`、`external_id`（成功时）、`attempt` 和脱敏错误码。业务状态与同步状态分离，便于对账和补偿。
