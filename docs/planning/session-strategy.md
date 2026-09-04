# 一期多会话开发计划

需求与架构基线 v0.1 已于 2026-09-03 批准，一期目标模式现为 `ACTIVE`。当前供应商入驻首切片已实现并通过本地测试，其余 OpenMetadata、接入与 Dataverse、质量测评工作流为 `QUEUED`；不得把已完成的技术 PoC 误记为产品实现。实时状态与验收门见 `implementation-board.md`。

## 1. 分支和目录所有权

| Workstream | Branch prefix | Exclusive paths |
| --- | --- | --- |
| 总控与架构 | `phase1/control-*` | `docs/architecture`、`docs/contracts`、公共状态机、核心模型及共享 Flyway 迁移编排 |
| OpenMetadata | `phase1/openmetadata-*` | `opensource/openmetadata`、`integrations/openmetadata-adapter` |
| 仓库与接入 | `phase1/ingestion-*` | `integrations/dataverse-adapter`、platform-service 的 ingestion/dataset 模块 |
| 质量测评 | `phase1/quality-*` | `algorithm/quality-service`、`integrations/quality-adapter`、platform-service 的 quality/compliance 模块 |
| 运营与流程 | `phase1/operations-*` | `integrations/flowable-adapter`、platform-service 的 supplier/listing/workflow/order/contract/authorization/delivery/notification/audit 模块 |
| 门户前端 | `phase1/frontend-*` | `frontend/platform-web` |
| 基础设施 | `phase1/infra-*` | `deploy`、`backend/identity-service`、`integrations/identity-adapter`、identity/kafka/observability 适配 |
| 测试验收 | `phase1/qa-*` | `tests`、验收报告；产品代码修复回归原工作流 |

不得两个会话修改同一核心模块。公共 API/事件/状态机和共享迁移变更先由总控维护，再由消费方更新。`integrations` 下每个适配器只归属一个工作流，不能用“公共集成”名义交叉写入。

当前所有协作任务共享同一工作目录，改动立即互相可见，本轮尚未建立独立 Worktree；上表分支前缀是建立隔离分支时的命名约定，不表示分支已存在。当前 Git 主线除初始文件外仍有大量未跟踪工程成果，总控应先形成可追踪基线。同一时刻一个路径只允许一个写入 Owner，公共 `common/config`、共享迁移和规划状态由总控集中修改。后续 Worktree 由 Codex 管理且位于仓库外，不在 `Code` 创建会话目录。

## 2. 实施顺序

1. 基线评审、技术 PoC 和内网只读核验已完成，目标模式已开启。
2. 供应商入驻首切片本地测试已通过；下一门是共享 PostgreSQL、真实 IAM 和 Flowable 历史集成，不代表供应商一期需求整体完成。
3. OpenMetadata、接入与 Dataverse、质量测评保持排队；固化当前基线和目录 Owner 后，最多四个工作流并行。
4. 门户、基础设施、契约测试和首条跨工作流 happy path 集成。
5. 异常路径、权限、安全、性能、恢复和最终验收。

每完成供应商、接入、质量、上架、订单、交付一个节点即进行集成，不等全部模块结束。

每个节点依次通过本地切片、共享内网集成、跨工作流和一期端到端验收门。生产域名/TLS 暂不阻塞内网开发，但仍是生产发布门；恶意文件检测、最小权限、Kafka 高可用/ACL 和备份恢复等未完成项不得标记完成。

## 3. 交付模板

每个工作流提交必须说明：完成内容、修改文件、数据库迁移、API/事件变化、配置变化、测试命令与结果、未决问题、回滚方法。总控逐个 cherry-pick/merge 并运行契约与端到端测试；不得直接并发合并主分支。
