# High-quality Dataset Operations Platform

面向癌症高质量数据集的一期管理运营平台。本仓库只承载一期正式代码、配置、文档和测试；需求与架构基线 v0.1 已通过，当前已进入目标模式工程实施阶段。

## 一期业务闭环

供应商入驻 → 数据集接入 → 准入检测 → 数据集登记 → 质量测评 → 整改复测 → 上架审核 → 数据集市场 → 用户订购 → 合同记录 → 使用授权 → 受控交付 → 交付确认 → 业务归档。

## 目录职责

- `docs/requirements`：一期范围、需求、权限和质量规则。
- `docs/architecture`：系统架构、数据模型和状态机。
- `docs/contracts`：API 与领域事件契约。
- `docs/decisions`：架构决策记录。
- `docs/planning`：开源二开、中间件、会话与实施计划。
- `frontend/platform-web`：统一门户和运营页面（基线评审后创建）。
- `backend/identity-service`：自建统一身份服务，提供 OIDC 授权码 + PKCE、服务间客户端凭据、用户/角色管理和审计。
- `backend/platform-service`：一期模块化单体业务服务；首个供应商入驻纵向切片已实现并通过本地集成测试。
- `algorithm/quality-service`：质量检测编排和癌症专项规则（基线评审后创建）。
- `integrations`：OpenMetadata、Dataverse、Flowable、身份等适配器（按实现创建）。
- `opensource/openmetadata`、`opensource/dataverse`、`opensource/data-juicer`、`opensource/presidio`：项目自有 Fork 的 Git Submodule；父仓库固定批准提交，定制必须提交回对应 Fork。
- `deploy`：Linux 原生进程、systemd、反向代理和数据库初始化配置（不含密钥；当前阶段不使用容器）。
- `tests`：跨模块集成、端到端和性能测试。

## 当前阶段

1. 阅读 `docs/requirements/phase1-srs.md` 与 `docs/architecture/phase1-architecture.md`。
2. 阅读 `docs/planning/poc-results.md` 与 `docs/planning/baseline-review.md`，确认未关闭的环境阻塞项。
3. 按 `docs/planning/session-strategy.md` 的目录归属实施；真实集成不得绕过环境门禁。

统一身份服务的启动方式见 `backend/identity-service/README.md`。任何环境凭据必须经环境变量或受控密钥文件注入，不得提交到 Git。

首次检出后执行 `git submodule update --init --recursive` 获取已固定的开源源码。同步上游时先在子模块中从 `upstream` 评估和合并，完成独立构建、许可证与回归记录后，再提交到自有 `origin` 并更新父仓库 gitlink；不得直接把父仓库指向官方上游。
