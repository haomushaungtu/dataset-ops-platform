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
| OpenMetadata | `IN_PROGRESS` | 自有 Fork/Submodule 已固定 `c73f8fcb0c4bdab0af689c8ed9a31596caccf987`；首批 OIDC/SAML 角色、管理员和 Group Team 收敛补丁已推送，154 项定向测试及 Spotless 通过，源码 JAR SHA-256 为 `37534995A3D496CAFC96EB41225CDDAB0830DAB61027DC720E2D1B71CF21BA97` 并已部署至 139。OpenMetadata/OpenSearch/正式适配器均纳入原生受控服务且未启用开机自启；已确认数据库安全配置覆盖 YAML/环境变量是初始 JWKS 失败根因，切换 `custom-oidc` 后独立机器令牌直连 OpenMetadata 返回 200。适配器已修复 HttpURLConnection 不支持 PATCH 的运行时缺陷并新增真实回环回归，8 项测试通过；OpenSearch 迁入 systemd 单元后，共享内网 E2E 严格校验五项属性键和值，完成幂等重放、冲突 409、搜索与递归清理，`extension_values_verified=true`，最新证据 SHA-256 为 `F7CD2ADB269D6F5B6D9124BD954CFEF29A401DE1204C036EB0B560EFB03C3F96` | 机器认证与正式适配器五属性技术切片已通过；继续完成交互式浏览器 OIDC 登录、角色/组收敛、退出和禁用传播，并由平台 Outbox 驱动正式元数据同步 | Linux 同提交后端/UI 完整构建；验证 token URL、iss/aud/auth_version、角色/组映射、禁用传播与 RP logout；将当前临时管理员服务账号收敛到最小权限；实现平台 Outbox、重试和对账后再提升状态 |
| 接入与 Dataverse | `QUEUED` | Dataverse 6.10.1、Solr 9.8.0、MinIO 上传下载及哈希回读技术 PoC 已通过，运行时已停止 | 已认证供应商创建数据集版本和接入批次，单个 CSV 进入 receiving 区、形成 SHA-256 文件清单，并创建 Dataverse 草稿及外部 ID 映射 | 共享开发 PostgreSQL、MinIO 最小权限前缀、Dataverse/Solr 和 IAM 集成通过；重复完成幂等、跨供应商访问拒绝 |
| 质量测评 | `IN_PROGRESS` | `algorithm/quality-service` 已实现 `CANCER_TABULAR_V1@0.1.0` 本地执行切片：直接调用自有 Submodule 固定源码中的 Data-Juicer `DocumentDeduplicator` 与 Presidio `AnalyzerEngine`，记录提交、模块和配置哈希；合成 CSV 实测 8→7、Presidio 扫描 78 个非空单元格且零误报，医学文本启动自检命中 3/0/2/0/2；精确输出 5 个预期问题、94.38/B、2 个 HIGH 阻断。SQLite 幂等重放、异请求冲突、输入哈希篡改、只读根目录越界和源文件不变共 4 项集成测试通过 | 对固定数据集版本创建 `CANCER_TABULAR_V1` 任务，真实执行 Data-Juicer/Presidio，持久化进度、指标、问题样本和结构化报告 | 将任意本地路径请求替换为平台签发的存储 profile/对象版本引用；接入共享 PostgreSQL、MinIO 报告对象和平台任务状态；139 使用固定 Python 与离线 wheelhouse 原生部署并通过重试不重复汇总、输入哈希不变验收 |

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
3. OpenMetadata 与质量测评已进入 `IN_PROGRESS`；接入与 Dataverse 仍保持 `QUEUED`，先完成首个产品纵向切片再提升状态。
4. 每通过供应商、接入、质量、上架、订单或交付一个节点即集成，不等待所有模块完成后集中联调。
