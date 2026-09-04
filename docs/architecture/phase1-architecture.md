# 高质量数据集管理运营平台一期技术架构说明书

- 文档状态：评审稿 v0.1
- 日期：2026-09-02

## 1. 架构目标

架构围绕“一个统一入口、一套业务状态、一条可审计链路”建设。OpenMetadata 是统一门户和元数据底座；自研模块化单体是交易与交付业务权威；Dataverse 管理文件和版本；质量服务真实执行检测；Flowable 管理人工任务；身份系统统一认证。

## 2. 逻辑架构

```text
Browser
  │ OIDC / HTTPS
  ▼
Unified Gateway ── Platform Web / Customized OpenMetadata UI
  │                         │
  │ REST                    └── OpenMetadata API
  ▼
Platform Service (modular monolith)
  ├─ supplier / dataset / ingestion / compliance / quality
  ├─ listing / order / contract / authorization / delivery
  ├─ workflow / notification / audit / integration
  ├─ PostgreSQL + Outbox ── Kafka
  ├─ Flowable 7.2 (business references only)
  ├─ Dataverse adapter ── Dataverse ── MinIO
  ├─ Metadata adapter ── OpenMetadata
  ├─ Identity adapter ── Dataset Identity Service (OIDC)
  └─ Quality adapter ── Quality Service
                           ├─ Data-Juicer
                           ├─ Presidio medical recognizers
                           └─ optional Cleanlab pilot
```

## 3. 组件职责

### 3.1 统一门户与 OpenMetadata Fork

- `opensource/openmetadata` 是项目自有 Fork 的 Git Submodule，父仓库固定 40 位提交；不得直接跟踪官方浮动分支或仅部署官方镜像代替源码纳管。
- 优先通过自定义属性、分类、API、事件、导航扩展和插件实现癌种、样本类型、模态、来源、场景、质量摘要。
- `frontend/platform-web` 承载供应商、接入、登记、测评、订单、合同、授权、交付和运营页面；通过统一反向代理、主题和导航与 OpenMetadata 形成单入口体验。
- 用户不直接访问 Dataverse、Flowable 或质量服务原生后台。

### 3.2 自研业务服务

采用端口与适配器结构。每个业务模块内部包含 `api`、`application`、`domain`、`infrastructure`，禁止以全局 `controller/service/common` 堆放业务。

核心命令在同一事务内完成：状态校验、领域数据变更、审计写入、Outbox 写入。外部调用由异步处理器或带超时的协调器执行，结果回写同步状态。

### 3.3 Dataverse 与 MinIO

`opensource/dataverse` 保存项目自有 Fork 的固定源码，Dataverse 可独立编译和原生部署；平台业务适配放在 `integrations/dataverse-adapter`，不得复制 Dataverse 源码或直接写其数据库。

上传先进入 MinIO `dataset-receiving` 受控前缀；准入通过并登记后由 Dataverse 形成权威草稿/版本记录。平台保存 `dataset_id + version_id + dataverse_dataset_id + dataverse_version + file_id` 映射。发布、访问授权和下载记录通过官方 API 同步，禁止直接写 Dataverse 数据库。

建议逻辑存储区：`dataset-receiving`、`dataset-curated`、`dataset-samples`、`dataset-delivery`；创建前必须只读核验是否已存在并确认所有者、版本策略和生命周期规则。

### 3.4 质量服务

`opensource/data-juicer` 与 `opensource/presidio` 保存项目自有 Fork 的固定源码，可分别独立构建；平台编排、癌症规则和适配代码放在 `algorithm/quality-service` 与 `integrations`，上游定制只提交到对应 Fork。

Python 服务暴露稳定任务 API，Data-Juicer 负责算子流水线，Presidio 负责 PII/PHI 风险提示。输入使用只读对象引用，输出原始指标、问题样本和进度。平台业务库只保存任务关联、汇总得分、门槛结论和报告引用。

质量执行器使用 Linux 受控 Python 虚拟环境和离线依赖包固定依赖与模型摘要，启动后禁止访问公网或动态安装 Python 包。Windows 仅用于合成样本兼容性 PoC，不作为生产运行环境。

### 3.5 Flowable

流程包括供应商入驻、接入问题复核、登记、质量任务/复核/整改、上架、订单确认、合同确认、交付异常和下架。Flowable 变量只存业务引用；候选人与角色来自身份映射；业务状态由平台服务执行命令后变更，流程监听器不得直接改业务表。一期优先通过 Maven 依赖、BPMN 和扩展接口集成，只有确认必须修改引擎内核后才引入完整 Fork/Submodule。

## 4. 数据与一致性

- PostgreSQL 是唯一自研业务库；模块拥有自己的 Schema 或表前缀。
- 所有业务表使用 UUID、`created_at`、`updated_at`、`version` 乐观锁；状态事件单独追加。
- Kafka 主题按领域命名，Outbox 保证“业务提交后最终发布”；消费至少一次、业务幂等。
- 外部系统同步使用 Saga/过程管理器，不尝试跨库分布式事务。
- 每日对账：平台版本映射 ↔ Dataverse；发布索引 ↔ OpenMetadata；质量摘要 ↔ 质量服务；流程任务 ↔ Flowable。

## 5. API 与安全

- 浏览器使用 OIDC Authorization Code + PKCE；服务间使用 Client Credentials。
- 统一身份由自建 `identity-service` 提供，协议与密码学由 Spring Authorization Server / Spring Security 实现；用户、客户端、授权和审计持久化到独立 PostgreSQL 数据库。
- 访问令牌默认 5 分钟，刷新令牌轮换且默认 8 小时；身份管理 API 对禁用状态和 `auth_version` 在线复核，外部系统最迟在访问令牌过期后生效。
- 网关验证令牌，业务服务仍执行角色和对象级权限校验。
- 上传下载不信任扩展名；校验魔数、大小、压缩路径、文件数和递归深度。
- 下载票据短期有效、绑定用户/授权/交付/文件，服务端原子消费；MinIO 内部凭据不下发。
- 合同、资质、问题样本和审计属于受限资源，按数据范围控制并记录查看行为。
- 配置只引用环境变量名；不得把部署清单中的密码、密钥或完整连接串写入 Git、日志和测试报告。

## 6. 部署拓扑

一期最小部署单元：网关/门户、OpenMetadata、platform-service、quality-service、Dataverse、Flowable（可嵌入或独立）、身份系统、PostgreSQL、MinIO、Redis、Kafka、OpenMetadata 兼容搜索服务，以及 Dataverse 所需 Solr。每个开源组件使用独立数据库或 Schema 和服务账号。OpenMetadata 1.13 使用隔离的 Elasticsearch 9.3 或经验证的 OpenSearch 3.x，不得连接现有 Elasticsearch 8.17.4 集群；Dataverse 6.10.1 使用独立 Solr 9.8.0，不得用 Elasticsearch 替代。

当前阶段暂不使用 Docker、Podman 或 Kubernetes。运行时采用 Linux 主机原生进程和上游发行包：网关与前端静态资源由反向代理托管；platform-service/Flowable 使用受控 JAR；OpenMetadata 使用固定提交构建的发行包；Dataverse 使用固定版本发行包及其应用服务器；quality-service 使用受控 Python 虚拟环境和离线依赖目录。服务由 systemd 管理，并使用独立操作系统账号、安装目录、数据/日志目录、端口和最小权限中间件账号。后续容器化作为独立变更评审，不影响当前接口和制品锁定要求。

正式部署前先完成只读核验：数据库/Schema、Bucket/前缀、Kafka Topic、Redis DB、搜索服务 REST 地址与版本、网络 ACL、TLS 与证书、身份平台是否已存在。未经确认不得创建、覆盖或删除资源。

## 7. 日志、监控、备份

- 结构化日志字段：时间、级别、服务、trace_id、request_id、actor_id、business_type、business_id、result；禁止记录令牌和文件内容。
- 指标：HTTP 延迟/错误、数据库池、Outbox 延迟、Kafka lag、上传吞吐、质量任务、流程待办、交付成功率、授权拒绝。
- 告警：死信、同步连续失败、异常待办超时、质量任务卡住、交付校验失败、审计写入失败。
- PostgreSQL 使用全量备份 + WAL/PITR；MinIO/Dataverse/OpenMetadata 按组件指南备份。验收前做一次隔离恢复演练。

## 8. 开源升级策略

每个源码子模块的 `origin` 指向项目自有 Fork，`upstream` 指向官方仓库；上游版本只通过专用 `upstream/*` 分支引入。定制修改先提交到自有 Fork，父仓库再更新 gitlink。每次升级记录原版本、目标版本、提交哈希、许可证变化、改动文件、冲突、迁移、独立构建、回归和回滚。Fork 修改尽量集中在主题、导航、扩展和适配层；对核心源码的修改必须有补丁测试与升级说明。

## 9. 一期与二期边界

一期不部署 EDC、MLflow、Kubeflow、KServe，不实现跨域连接器或模型市场。接口契约可保留 `delivery_mode`、`external_resource_ref` 等扩展字段，但不得创建无业务代码的工程。
