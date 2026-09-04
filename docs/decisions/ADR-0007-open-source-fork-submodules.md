# ADR-0007: 一期开源源码使用自有 Fork 和 Git Submodule

- 状态：Accepted
- 日期：2026-09-04

## 背景

一期需要对 OpenMetadata、Dataverse、Data-Juicer、Presidio 做可持续二次开发。仅部署官方镜像、发行包或远程服务无法保证源码可审计、补丁可追踪和上游升级可重复，也无法满足项目独立构建与受控修改要求。

## 决策

OpenMetadata、Dataverse、Data-Juicer、Presidio 先 Fork 到项目自有代码托管空间，再分别以 Git Submodule 纳入 `Code/opensource`。父仓库固定 40 位 gitlink；子模块 `origin` 指向项目自有 Fork，`upstream` 指向官方仓库。所有定制修改先在对应 Fork 提交并完成独立构建、许可证和回归记录，父仓库随后更新 gitlink。

自研前端、后端、算法服务和集成适配器分别位于 `frontend`、`backend`、`algorithm`、`integrations`，不得复制第三方源码。Flowable 一期继续使用 Maven 依赖、BPMN 和扩展接口；只有确认必须修改引擎内核并通过架构评审后，才引入完整 Fork/Submodule。

固定版本、提交、许可证、当前修改和官方 upstream 以 `LICENSE-NOTICES.md` 与 `docs/planning/open-source-customization.md` 为准。

## 影响

- 新检出必须执行 `git submodule update --init --recursive`。
- 开源升级需要同时评审上游差异、许可证变化、数据库迁移、构建结果、回归和回滚。
- 官方发行制品仍可用于技术 PoC 或与源码构建结果对照，但不能替代正式源码纳管和源码构建证据。
- 子模块改动不得只保留在父仓库工作树；未推送到自有 Fork 的提交不得更新父仓库 gitlink。
