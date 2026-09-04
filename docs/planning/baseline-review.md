# 一期需求与架构基线评审单

- 评审版本：v0.1
- 当前结论：**基线 v0.1 已于 2026-09-03 获用户批准，一期目标模式为 `ACTIVE`。供应商入驻首切片已完成共享内网 IAM/PostgreSQL/MinIO/Flowable 闭环，资质材料版本、历史查询、拒绝和撤回均已验证，SUP-001 至 SUP-005 达到 `DONE`；OpenMetadata 已进入源码纳管和产品实现，接入与 Dataverse、质量测评仍为排队状态。既有技术 PoC 证明组件可行，不代表一期总体业务闭环已经完成。**

## 1. 已冻结建议

- 一期范围严格限制为供应商—接入—检测—登记—测评—上架—订单—合同—授权—文件交付—归档。
- 自研后端采用 Java 21 / Spring Boot 3.5 模块化单体。
- PostgreSQL 为唯一业务库，Kafka 为唯一事件总线。
- Flowable 7.2.0 是一期基线；OpenMetadata 为主要 UI/元数据底座。
- OpenMetadata、Dataverse、Data-Juicer、Presidio 必须先进入项目自有 Fork，再以固定提交的 Git Submodule 纳入 `Code/opensource`；Flowable 继续优先使用 Maven 依赖和扩展接口。
- 当前阶段采用 Linux 主机原生进程/发行包部署，暂不使用 Docker、Podman 或 Kubernetes。
- 文件、版本、元数据、质量、流程、身份和业务权威边界明确。
- 核心状态后端控制；Outbox、幂等、乐观锁、对象级授权和追加审计为强制要求。

## 2. 实施与发布门禁

未关闭条目不一律阻塞本地编码：开发集成门在对应纵向切片联调前关闭，生产门在生产发布前关闭。不得因为当前允许内网 HTTP、使用合成数据或技术 PoC 已通过而关闭生产安全门。

| ID | Question / evidence | Owner | Gate | Resolution required |
| --- | --- | --- | --- | --- |
| B-01 | 现有 `hqd-auth` 不能作为标准 IdP；用户已决定自建统一登录。`backend/identity-service` 已实现并通过 139 原生 JVM + 独立 PostgreSQL 技术 PoC；当前以 `10.100.165.139:19000` 私网 HTTP 模式运行供开发接入 | 实施 | 产品集成；TLS 属于生产发布 | 开发阶段完成 OpenMetadata、Dataverse 登录、角色、登出和禁用传播验收；生产前再补域名/TLS 后关闭 |
| B-02 | 技术 PoC 已关闭：PostgreSQL 16.15 专用库已启用 `pgcrypto` 1.3、`pg_trgm` 1.6，迁移从检查点安全恢复并完成到 OpenMetadata 1.13.0；变更前和检查点备份均已校验保留 | DBA | 生产发布 | 生产前将扩展纳入正式包管理、补丁、备份和恢复制度；禁止使用已废弃的 DROP 修复脚本 |
| B-03 | 技术 PoC 已关闭：独立前缀对象版本/SHA-256 回读通过；Dataverse 6.10.1 以 `s3poc` 驱动完成临时专用 Bucket 的合成 CSV 上传、下载、对象哈希和清理 | 存储管理员 | 接入集成；治理属于生产发布 | 接入联调前分配隔离开发前缀和最小权限账号；生产前批准专用 Bucket，落实归属、默认加密和生命周期 |
| B-04 | 部分核验：Kafka 可连接，但当前仅单 Broker；存在既有 `dataset-foundry.*` 命名 | 中间件管理员 | 跨工作流；高可用属于生产发布 | 批准 `dataset-foundry.ops.*` Topic、ACL、分区、副本、保留和 DLQ 方案，并给出生产高可用安排 |
| B-05 | 技术 PoC 已关闭：139 隔离 OpenSearch 3.4.0 与 OpenMetadata 1.13.0 完成迁移、健康、60 个索引/61 个模板和合成元数据创建—搜索—删除验收 | 中间件管理员/DBA | OpenMetadata 集成；安全治理属于生产发布 | 集成使用隔离 OpenSearch；生产前补齐认证/TLS、监控、备份和容量方案，禁止接入现有 Elasticsearch 集群 |
| B-06 | 内网恶意文件检测引擎是否存在 | 安全管理员 | 准入流程最终验收 | 提供真实适配目标或确认验收阻塞策略；未就绪时必须明确标记阻塞/未执行，不得伪造通过 |
| B-07 | 测试数据的脱敏级别、授权和允许的人工查看范围 | 数据负责人 | 一期端到端验收 | 批准端到端真实测试数据；当前自建合成数据只用于开发和技术验证 |
| B-08 | 站内信之外是否启用邮件/短信及网关 | 运营/基础设施 | 可选通道决策 | 决定可选通知通道；未启用不阻塞一期站内信实现 |
| B-09 | 备份保留、审计保留和 RPO/RTO | 运维/安全 | 生产发布 | 确认生产非功能基线并完成隔离恢复演练 |
| B-10 | PoC 已关闭：139 的 Dataverse 6.10.1 / Payara 7.2026.2 / Solr 9.8.0 已完成版本 API、合成数据集创建/读取/索引/删除，数据库与索引清理通过；全部活动监听为回环地址 | 基础设施/实施 | 接入集成；安全治理属于生产发布 | 集成前轮换默认管理员或改接 OIDC；生产前补齐 Solr 认证/TLS、网络策略、监控、备份和容量方案，不得使用 Elasticsearch 替代 |

## 3. 评审检查

- [x] 需求有唯一编号并含异常与验收。
- [x] 一期/二期边界明确。
- [x] 数据权威和跨系统同步边界明确。
- [x] 状态机禁止前端任意修改。
- [x] 权限覆盖菜单、接口和对象范围。
- [x] 质量评分、硬门槛和整改复测明确。
- [x] API/事件包含幂等、并发、错误和敏感信息约束。
- [x] 中间件只选一套业务库和一条消息链路。
- [x] B-01 至 B-10 已按开发集成或生产发布阶段持续跟踪，当前未全部关闭。
- [x] 用户确认基线进入实施（2026-09-03）。
- [x] PostgreSQL、MinIO、Redis、Kafka、Elasticsearch 等完成只读核验，未创建或修改资源。
- [x] Flowable 7.2.0 / Spring Boot 3.5.4 / Java 21 编译 PoC 通过。
- [x] Data-Juicer 1.5.5 与 Presidio 2.2.364 合成样本 PoC 通过。
- [x] OpenMetadata 1.13.0 `openmetadata-spec` 最小源码编译通过。
- [x] OpenMetadata 1.13.0 完整运行时 PoC：Java 21、PostgreSQL 16.15、`pgcrypto`/`pg_trgm`、隔离 OpenSearch 3.4.0、迁移、健康、搜索和合成元数据清理均已验证，服务已停止。
- [x] OpenSearch 3.4.0 基础 CRUD 与 OpenMetadata Java Client 连接通过；测试后索引数为 0，进程已停止。
- [x] Solr 9.8.0 已加载 Dataverse 6.10.1 专用 schema，文档 CRUD 通过并清理，进程已停止。
- [x] Dataverse 6.10.1 / Payara 7.2026.2 原生运行、版本 API、PostgreSQL 初始化及 Solr 数据集索引链路通过；合成数据和令牌已清理，进程已停止。
- [x] 自建统一身份服务在 139 以 Java 21、独立 PostgreSQL 和外部 RSA 密钥完成 discovery/JWKS、授权码 + PKCE、UserInfo、刷新轮换、服务认证、禁用和登出 PoC；当前按用户决定以 `http://10.100.165.139:19000` 提供内网开发访问，systemd 未启用。
- [x] 平台主控供应商切片在 139 以 Java 21 原生运行，真实接入统一身份、PostgreSQL 16.15 和 Flowable；初始 `BUYER` 申请人/运营员完成创建、提交、审批、查询和重新登录角色回读。平台保存的申请人 ID 与 IAM 不可变用户 UUID/令牌 `sub` 一致，受限 `platform.internal` 接口真实授予 `SUPPLIER`，`supplier.identity-role.requested.v1` 已发布且供应商同步状态为 `SYNCED`；旧用户名主体事件通过兼容解析重试后也成功发布。
- [x] 机器令牌边界已在共享环境验证：带 `platform.internal` scope 和正确 audience 的平台客户端可获得服务令牌；身份 `/api/v1/me` 返回 403，平台 `/api/v1/me` 可用于机器主体自检，供应商业务接口返回 403。机器令牌不能冒充交互用户创建或读取供应商申请。
- [x] PostgreSQL Flyway V1/V2 均成功，`integration.idempotency_lock` 已生效；专用 PostgreSQL 集成测试以两个线程、相同 actor/scope/key 并发创建，返回同一申请 ID 且只保留一条申请。本地普通测试默认跳过该环境门测试，必须显式启用 `DF_PLATFORM_POSTGRES_IT=true`。
- [x] PostgreSQL Flyway V3 已创建追加式供应商资质材料表；共享环境首次/重放上传分别返回 201/200，退回后新增第二版本，MinIO 对象 HEAD 与数据库大小、类型一致；无材料提交返回 `422 QUALIFICATION_MATERIAL_REQUIRED`。
- [x] 供应商共享环境批准分支保存版本 10、11 条状态历史、11 条审计、12 条 Outbox、2 个材料版本，历史 API 稳定升序；拒绝和草稿撤回分支不创建供应商或 IAM 角色事件。
- [x] OpenMetadata、Dataverse、Data-Juicer、Presidio 已建立项目自有 GitHub Fork，并以 Git Submodule 分别纳入 `opensource/openmetadata`、`opensource/dataverse`、`opensource/data-juicer`、`opensource/presidio`，固定到许可证清单中的 40 位提交；各子模块均配置自有 `origin` 和官方 `upstream`。

## 4. 目标模式实施状态

- `ACTIVE`：目标模式已经开启，实施状态以 `implementation-board.md` 为准。
- `DONE`：供应商首切片已在共享 PostgreSQL、MinIO、真实 IAM 和 139 原生服务完成 SUP-001 至 SUP-005，包括稳定 OIDC `sub`、受限角色同步、机器令牌隔离、数据库并发幂等、两版材料、退回补正、拒绝、撤回和历史查询。
- `IN_PROGRESS`：OpenMetadata 已完成自有 Fork/Submodule/固定提交的源码纳管并开始正式产品实现；质量测评已形成真实调用固定 Data-Juicer/Presidio 源码的首个本地执行切片，但尚未完成平台与共享环境集成。
- `QUEUED`：接入与 Dataverse 尚未开始正式产品实现；完成源码纳管本身不等于业务切片开工。
- 当前共享工作区尚未建立本轮独立 Worktree，且多数工程成果仍未形成已跟踪基线；总控在并行写入前必须固化基线和目录 Owner。

## 5. 变更控制

评审通过后，范围变化先修改 SRS 并记录影响；API、事件、状态机和数据模型变化由总控会话维护。新增二期能力不得作为一期缺陷修复夹带进入。
