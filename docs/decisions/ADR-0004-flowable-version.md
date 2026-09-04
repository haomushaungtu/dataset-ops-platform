# ADR-0004: Flowable 固定 7.2.0 候选基线

- 状态：已接受
- 日期：2026-09-03

## 决策

一期候选版本为 Flowable 7.2.0，并以独立 Schema 保存流程数据。业务对象仅向流程变量传递 `business_type`、`business_id`、`applicant_id`、`correlation_id` 等引用，不传完整业务数据。

## 理由

Flowable 8.0.0 已切换到 Spring Boot 4、Spring Framework 7 和 Jackson 3，与一期 Spring Boot 3.5 基线存在断代升级。7.2.0 与 Spring Boot 3.5 兼容，更适合作为一期稳定候选。

## 退出条件

若 PoC 发现 7.2.0 的安全或兼容性问题，先评估独立部署 Flowable 8 的 REST 集成，不直接升级业务单体。

## 验证记录

2026-09-03 使用 Java 21、Spring Boot 3.5.4、Flowable 7.2.0 完成最小工程编译，构建成功。系统 Maven 3.6.0 不满足当前编译插件要求；PoC 使用校验 SHA-512 后的 Maven 3.9.11，正式工程因此要求 Maven Wrapper 固定 3.9.11 或等效受控版本。
