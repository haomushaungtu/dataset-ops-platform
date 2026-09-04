# ADR-0001: 一期业务后端采用模块化单体

- 状态：已接受
- 日期：2026-09-02

## 决策

一期自研业务后端使用 Java 21、Spring Boot 3.5 系列的模块化单体。代码按 `identity`、`supplier`、`dataset`、`ingestion`、`compliance`、`quality`、`listing`、`workflow`、`order`、`contract`、`authorization`、`delivery`、`notification`、`audit`、`integration` 业务能力拆分，模块之间只能通过应用接口和领域事件协作。

## 理由

一期重点是贯通交易和交付状态闭环。模块化单体可在单数据库事务内保证核心状态变更与审计写入一致，降低分布式事务和运维复杂度，同时保留后续拆分边界。

## 后果

- 不使用 Nacos 做一期服务注册发现。
- 使用事务性 Outbox 向 Kafka 发布跨系统事件。
- 禁止跨模块直接访问对方数据表。
- 质量执行服务仍保持独立 Python 服务，以隔离重依赖与算力任务。
