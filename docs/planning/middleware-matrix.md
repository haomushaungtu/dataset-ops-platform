# 一期中间件使用矩阵

部署清单核对日期：2026-09-03。本文件只记录能力选择和只读核验结论，不复制密码、密钥或完整含凭据连接串。任何资源创建前必须获得目标命名和所有者确认。

| Middleware | Phase 1 | Modules | Decision / precondition |
| --- | --- | --- | --- |
| PostgreSQL 16 | 使用 | platform-service、OpenMetadata、Dataverse、Flowable、质量结果 | 自研业务唯一数据库；各组件独立 database/schema 和服务账号；禁止复用超级用户；OpenMetadata 库必须启用 `pgcrypto` |
| MinIO | 使用 | ingestion、Dataverse storage、sample、delivery | 分离 receiving/curated/samples/delivery 前缀或 Bucket；确认版本、加密、生命周期和最小权限 |
| Redis | 条件使用 | cache、lock、rate limit、upload/delivery short state | 实测 5.0.7 standalone；功能可连通，但版本老旧且单实例，不作为生产基线直接接受 |
| Kafka | 条件使用 | Outbox domain events、integration events | 实测单 Broker；可做开发 PoC，生产前必须确认副本、ACL、保留和死信策略 |
| OpenSearch 3.4 | 使用（隔离 PoC） | OpenMetadata search | OpenMetadata 1.13 已完成迁移、健康、60 个索引/61 个模板和合成元数据搜索清理。现有 Elasticsearch 8.17.4 不复用，生产安全配置仍待完成 |
| Solr 9.8 | 使用（隔离 PoC） | Dataverse search | 139 已完成 Dataverse 6.10.1 专用 `collection1` schema、独立 CRUD 和应用 API 创建/索引/删除链路；不得用 Elasticsearch 替代 |
| Nacos | 不使用 | — | 模块化单体无需注册发现；配置由环境/受控配置注入 |
| OceanBase | 不使用 | — | 避免形成 PostgreSQL/OceanBase 两套业务口径 |
| RocketMQ | 不使用 | — | Kafka 已选为唯一业务事件链路 |
| Spark | 按需使用 | quality-service | 仅当结构化试点规模超过单机/Ray 资源时启用；先做基准测试 |
| Doris | 不使用 | — | 一期无明确分析仓场景 |
| Flink | 不使用 | — | 一期无实时流质量主流程 |
| Neo4j | 不使用 | — | 一期不建设复杂图谱 |
| Iceberg Catalog | 不使用 | — | 一期文件版本由 Dataverse 管理 |
| Apache Polaris | 不使用 | — | 一期无 Iceberg REST Catalog 场景 |

## 资源命名建议（待内网确认）

- PostgreSQL：`platform_ops`；Schema `business`、`workflow`、`quality`、`audit`、`integration`。OpenMetadata/Dataverse 使用独立数据库。
- Kafka：结合现有命名建议使用 `dataset-foundry.ops.<domain>.events.v1`、`dataset-foundry.ops.<domain>.events.v1.dlq`，最终由中间件管理员批准。
- Redis：`dataset-foundry:ops:<environment>:<module>:...`。
- MinIO：优先复用经批准的专用 Bucket，并使用 `phase1/<environment>/<zone>/` 精确前缀；不得根据本文直接创建。

## 只读核验清单

1. 列出目标数据库/Schema 及所有者，不读取业务表内容。
2. 列出拟用 Bucket、版本/对象锁/生命周期/加密和策略，不读取对象内容。
3. 列出 Kafka Topic、分区、副本、保留和 ACL，不消费现有消息。
4. 查询 Redis 版本、TLS、ACL、内存策略和拟用命名空间，不扫描业务 key/value。
5. 查询搜索 REST 版本、健康、TLS 和认证方式，不更改索引。
6. 所有测试资源使用明确前缀和到期清理计划，删除必须精确到已创建对象并二次核验。

## 只读核验结果（2026-09-03）

| Component | Observed | Conclusion / unresolved item |
| --- | --- | --- |
| PostgreSQL | 16.15；`openmetadata_poc` 已启用 `pgcrypto` 1.3、`pg_trgm` 1.6，迁移账本 94 行且最后版本 1.13.0；变更前与检查点备份均已校验 | 扩展仅在专用库启用，PostgreSQL 未重启。生产前须纳入正式包管理和恢复制度；`server_migration_sql_logs` 是语句级检查点，禁止删除对象后直接续跑 |
| MinIO | 可列出 19 个既有 Bucket；7 个 `dataset-*` Bucket 已开启版本控制；独立前缀对象版本/SHA-256 验证通过；Dataverse 6.10.1 以临时专用 Bucket 完成实际 S3 驱动上传、回读和删除 | Dataverse 侧、MinIO 侧回读 SHA-256 均与合成 CSV 一致；临时 Bucket 删除前对象、版本和删除标记均为 0，随后精确删除。现有访问凭据权限过宽，生产接入仍需批准的专用 Bucket、最小权限账号、默认加密和生命周期 |
| Redis | 5.0.7、standalone，认证可用 | 仅兼容性通过；生产需升级或提供受支持的高可用实例，并核验 TLS、ACL 和淘汰策略 |
| Kafka | 单 Broker、12 个现有 Topic；未消费消息 | 不创建/复用现有 Topic；生产高可用和新 Topic 命名、ACL、分区、副本、保留待批准 |
| Elasticsearch | 8.17.4、3 节点、集群健康 green；46 个现有索引 | 只读健康通过，但不得供 OpenMetadata 1.13 使用；需隔离 Elasticsearch 9.3 或兼容 OpenSearch 3.x |
| OpenSearch | 3.4.0 Min，独立账号 `dfsearch`，回环端口 `19200/19300`，1 GiB 堆；OpenMetadata 1.13.0 创建 60 个索引和 61 个模板，合成实体搜索及删除通过 | 安装保留在 `/szah/dataset-foundry-poc/runtime/opensearch-3.4.0`，验证后已停机；无安全插件的 Min 配置只限本机 PoC，生产必须启用认证/TLS |
| Solr | 9.8.0，独立账号 `dfsolr`，Java 21，回环端口 `18983`，1 GiB 堆；Dataverse 6.10.1 `collection1` schema、独立 CRUD 与应用 API 索引链路通过 | 合成数据集删除后 `entityId` 索引归零，安装保留在 `/szah/dataset-foundry-poc/runtime/solr-9.8.0`，验证后已停机；生产需认证/TLS、监控和备份 |
| Dataverse / Payara | Dataverse 6.10.1、Payara 7.2026.2、Java 21、1.5 GiB 堆；专用库 120 张表，版本 API、合成数据集 CRUD 与 Solr 索引通过；全部 Payara 活动监听经内核检查为回环地址 | 安装保留在 `/szah/dataset-foundry-poc/runtime`，测试数据为 0，API 令牌已撤销，进程已停；正式环境必须保留 Hazelcast `socket.bind.any=false` 约束并轮换默认管理员口令 |
| Nacos / IAM | Nacos 2.5.1 健康，111 个命名空间中 `authentication-api` 匹配为 0；170:9210 的 `hqd-auth.jar` 从 139 可达，但 discovery 路径返回业务 401 包装且包内未发现 OIDC/OAuth2 Authorization Server 依赖 | Dataverse/OpenMetadata 产品能力兼容，但现有 `hqd-auth` 不是可直接接入的标准 IdP；需提供实际 discovery/issuer/JWKS 和客户端登记，或另立 `hqd-auth` OIDC 改造任务 |
| Linux PoC 主机 | `10.100.165.139:2222`，Kylin V10、4 核、14 GiB、无 Swap；`/szah` 可用 81 GiB | 已建立 `/szah/dataset-foundry-poc`；停止 7 个明确位于 `/home/hqd` 的 `hqd-*.jar` 后可用内存约 4.8 GiB，只允许组件逐个启动 PoC |
| MinIO / Nacos / Kibana / Polaris | 健康端点返回成功 | 仅证明网络和基础服务可达，不代表资源授权与生产就绪 |

经用户明确授权，本次 PoC 创建了三个带 `_poc` 后缀的 PostgreSQL 数据库及非特权账号、平台库五个 Schema，向 MinIO 全新前缀写入 5 个合成对象，并创建后删除一个唯一临时 Dataverse PoC Bucket；没有读取业务表/对象内容，没有修改或删除既有数据库、Schema、Bucket、对象、Topic、索引和中间件配置。139 主机停止了 7 个 `hqd-*.jar` 进程，并创建隔离 PoC 目录、上传合成测试数据、安装 OpenMetadata/OpenSearch、Dataverse/Payara/Solr。Dataverse 合成数据已从 API、数据库、Solr 和临时 MinIO Bucket 精确清理，临时用户已删除，API Token 已撤销，AWS 凭据与 Payara 临时配置已清除；所有 PoC 进程和端口均已关闭，`vm.max_map_count` 已恢复为 65530。Kafka/ZooKeeper、`/szah/sgy` 及原有 8080 服务保持运行。
