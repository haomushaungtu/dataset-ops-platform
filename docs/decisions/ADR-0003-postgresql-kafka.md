# ADR-0003: PostgreSQL 与 Kafka 作为一期统一数据和事件基线

- 状态：已接受
- 日期：2026-09-02

## 决策

自研运营业务仅使用 PostgreSQL，不同时维护 OceanBase 业务口径。Kafka 是唯一业务事件总线，不并行建设 RocketMQ 链路。Redis 仅用于缓存、锁、限流计数和短期任务状态，不保存业务最终状态。

## 后果

- 业务事务与 Outbox 表位于同一 PostgreSQL 数据库。
- 消费者按 `event_id` 幂等，失败进入重试和死信主题。
- OceanBase、RocketMQ、Doris、Flink、Neo4j、Iceberg、Polaris 一期不接入主流程。
