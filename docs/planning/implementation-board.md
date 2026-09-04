# 一期目标模式实施看板

- 更新时间：2026-09-04
- 总体状态：`ACTIVE`
- 基线：需求与架构基线 v0.1 已于 2026-09-03 批准
- 当前原则：PoC 通过只表示技术可行；只有产品代码、持久化、真实组件调用和对应验收证据齐备后，工作流切片才能完成。

## 1. 状态定义

| 状态 | 含义 |
| --- | --- |
| `QUEUED` | 已明确范围、依赖和首个验收切片，尚未开始产品实现 |
| `IN_PROGRESS` | 正在实现，尚未通过所属切片的最小验收门 |
| `LOCAL_PASS` | 产品代码及本地自动化测试通过，尚未完成共享内网真实组件集成 |
| `INTEGRATION_PASS` | 共享内网真实组件集成通过，尚未完成一期端到端验收 |
| `DONE` | 该工作流的一期范围和异常路径均完成，并纳入端到端验收证据 |
| `BLOCKED` | 当前验收门缺少外部决定、资源或权限，无法继续完成 |

`PoC passed` 不等于 `LOCAL_PASS` 或 `INTEGRATION_PASS`。未达到下一验收门的事项不得因为已有脚本、配置或技术验证而标记完成。

## 2. 当前工作流

| 工作流 | 当前状态 | 已有证据 | 第一个验收切片 | 下一验收门 |
| --- | --- | --- | --- | --- |
| 运营与 Flowable：供应商入驻 | `DONE` | 139 上原生 `platform-service` 已接入真实 IAM、PostgreSQL 16.15、MinIO 和 Flowable；Authorization Code + PKCE 合成用户完成创建、草稿修改、两版资质材料上传及幂等重放、提交、退回、补正、重提、审批、历史/材料查询，结果 `APPROVED`；共享环境同时完成拒绝、草稿撤回异常分支。批准申请 `f842ea9a-daaf-4975-a3e3-ea7af68fe67a` 的数据库核对为版本 10、11 条状态历史、11 条审计、12 条 Outbox、2 个材料版本，MinIO 两个对象的 HEAD 大小/类型与数据库一致；IAM 状态 `SYNCED`，刷新登录后获得 `SUPPLIER`；H2 与真实 PostgreSQL 双线程同键幂等测试均只产生一个申请 | 供应商申请到运营审批、创建 ACTIVE 供应商并完成 IAM 角色同步 | SUP-001 至 SUP-005 已纳入共享环境端到端证据；后续仅在跨工作流集成回归中复验，不扩展供应商一期范围 |
| OpenMetadata | `IN_PROGRESS` | 自有 Fork/Submodule 已固定 `c73f8fcb0c4bdab0af689c8ed9a31596caccf987`；首批 OIDC/SAML 角色、管理员和 Group Team 收敛补丁已推送，154 项定向测试及 Spotless 通过。正式 `openmetadata-adapter` 已实现 client_credentials、入站 scope/audience、五项自定义属性、冲突保护和读回校验，7 项测试通过；身份服务 9 项测试通过并已在 139 原子升级，独立适配器客户端的 discovery 与机器令牌 `sub/aud/scope` 回读通过。源码已上传 139，JDK 21/Maven 3.9.11 已就绪；历史发行包、数据库迁移、OpenSearch 和合成元数据 CRUD/search 技术 PoC 已通过 | 真实 OIDC 登录、角色/退出/禁用传播；通过正式适配器写入一个数据集的癌种/模态属性并可检索 | Linux 同提交后端/UI 构建；OpenMetadata/OpenSearch/适配器受控启动；真实产品登录、角色/组收敛和元数据读写冒烟；继续修复 token URL、iss/aud/auth_version、禁用传播与 RP logout 门禁 |
| 接入与 Dataverse | `QUEUED` | Dataverse 6.10.1、Solr 9.8.0、MinIO 上传下载及哈希回读技术 PoC 已通过，运行时已停止 | 已认证供应商创建数据集版本和接入批次，单个 CSV 进入 receiving 区、形成 SHA-256 文件清单，并创建 Dataverse 草稿及外部 ID 映射 | 共享开发 PostgreSQL、MinIO 最小权限前缀、Dataverse/Solr 和 IAM 集成通过；重复完成幂等、跨供应商访问拒绝 |
| 质量测评 | `QUEUED` | Data-Juicer 1.5.5 与 Presidio 2.2.364 合成样本命令级 PoC 已通过 | 对固定数据集版本创建 `CANCER_TABULAR_V1` 任务，真实执行 Data-Juicer/Presidio，持久化进度、指标、问题样本和结构化报告 | 质量服务使用只读对象引用运行；重试不重复汇总；输入哈希不变；平台保存汇总和报告引用 |

## 3. 目录所有权

| Owner | Exclusive paths |
| --- | --- |
| 总控与架构 | `docs/architecture/**`、`docs/contracts/**`、公共状态机、核心模型及共享 Flyway 迁移编排 |
| OpenMetadata | `opensource/openmetadata/**`、`integrations/openmetadata-adapter/**` |
| 接入与 Dataverse | `backend/platform-service/**/modules/dataset/**`、`backend/platform-service/**/modules/ingestion/**`、`integrations/dataverse-adapter/**` |
| 质量测评 | `algorithm/quality-service/**`、`backend/platform-service/**/modules/quality/**`、`backend/platform-service/**/modules/compliance/**`、`integrations/quality-adapter/**` |
| 运营与 Flowable | `backend/platform-service/**/modules/supplier/**`、`backend/platform-service/**/modules/listing/**`、`backend/platform-service/**/modules/workflow/**`、`backend/platform-service/**/modules/order/**`、`backend/platform-service/**/modules/contract/**`、`backend/platform-service/**/modules/authorization/**`、`backend/platform-service/**/modules/delivery/**`、`backend/platform-service/**/modules/notification/**`、`backend/platform-service/**/modules/audit/**`、`integrations/flowable-adapter/**` |
| 基础设施与身份 | `deploy/**`、`backend/identity-service/**`、`integrations/identity-adapter/**`、Kafka/可观测性适配代码 |
| 门户前端 | `frontend/platform-web/**` |
| 测试验收 | `tests/**` 和验收报告；产品缺陷回到对应 Owner 修复 |

`backend/platform-service` 的公共 `common/**`、`config/**`、启动配置以及共享迁移文件不由业务工作流自行扩展；跨工作流修改先由总控裁决。`integrations` 中每个适配器只属于上表一个 Owner。

## 4. 当前共享工作区限制

- 当前所有协作任务共享同一工作目录，文件改动会立即互相可见；尚未建立本轮独立 Git Worktree。
- 当前 Git 主线已固化并推送源码纳管与供应商基线；并行修改仍须在提交前核对父仓库、子模块和远端提交，未推送到自有 Fork 的子模块定制不得更新父仓库 gitlink。
- 同一时刻一个路径只能有一个写入 Owner；公共文件由总控集中修改。发现重叠改动时，相关工作流暂停写入并由总控裁决。
- 不允许通过复制工程或新增临时目录规避冲突。制品、日志、上传文件、凭据和 `target` 等构建输出不得纳入版本控制。

## 5. 验收门

1. **本地切片门**：产品代码、迁移和针对性自动化测试通过；状态、幂等、并发版本、权限、审计和 Outbox 按切片风险覆盖。
2. **共享内网集成门**：使用 139 原生运行环境及复用中间件的隔离资源；真实调用相关开源组件，不以 Mock 或配置存在代替成功。
3. **跨工作流门**：稳定业务 ID 和外部映射可对账，外部失败可重试，旧事件和重复请求不会制造第二份业务结果。
4. **一期端到端门**：供应商至归档完整闭环、异常路径、越权、票据过期/耗尽、审计还原、恢复和性能要求均有证据。
5. **生产发布门**：域名/TLS、最小权限、Kafka 高可用/ACL、搜索与存储安全、恶意文件检测、备份恢复、RPO/RTO 和监控告警完成。内网开发 HTTP 例外不关闭生产发布门。

所有选定开源项目在进入产品定制或正式集成前，还必须通过源码纳管门：项目自有 Fork、`opensource` Git Submodule、40 位提交固定、许可证/修改清单/升级策略齐备。Flowable 继续优先使用 Maven 依赖和扩展接口，确认必须修改内核前不得引入完整源码。

供应商切片的共享内网门还要求：交互用户以稳定 OIDC `sub` 作为业务主体；机器令牌即使具有正确 audience 也不能调用供应商用户业务接口；角色同步只能由持有 `platform.internal` scope 的平台客户端执行，并通过 Outbox 发布状态与 IAM 回读共同证明完成。

## 6. 下一步调度

1. 总控持续按自有 Fork 提交、父仓库 gitlink、构建证据三者一致的顺序固化共享工作区，避免并行改动失联。
2. 供应商首切片已达到 `DONE`，后续只参加跨工作流回归，不继续扩大一期供应商范围。
3. OpenMetadata 已进入 `IN_PROGRESS`；接入与 Dataverse、质量测评仍保持 `QUEUED`，先完成各自首个产品纵向切片再提升状态。
4. 每通过供应商、接入、质量、上架、订单或交付一个节点即集成，不等待所有模块完成后集中联调。
