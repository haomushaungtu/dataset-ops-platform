# Open-source notices

本文件记录一期直接复用或二次开发的开源项目。提交源码或制品锁定文件时必须补充精确提交哈希、发行包/构建制品 SHA-256、许可证全文位置和实际修改文件清单；后续若启用容器，再补充镜像摘要。

| Project | Planned baseline | License | License / NOTICE source | Usage | Source modification |
| --- | --- | --- | --- | --- | --- |
| OpenMetadata | 1.13.0 release 上游 `f329dd4a7e47134a2bd5a06af6181b0ee527ddd9`；自有 Fork `c73f8fcb0c4bdab0af689c8ed9a31596caccf987` | Apache-2.0 | `opensource/openmetadata/LICENSE`、`opensource/openmetadata/NOTICE` | `opensource/openmetadata` 自有 Fork Submodule；统一门户、元数据、分类标签、质量结果索引 | OIDC/SAML 角色、管理员与 Group Team 收敛及异常原子性；修改 `conf/openmetadata.yaml`、8 个服务源码文件和 5 个测试文件，完整清单见 `docs/planning/open-source-customization.md` |
| Dataverse | 6.10.1；`300d5b518c627047f68fd277e48f1a79c5eadbed` | Apache-2.0 | `opensource/dataverse/LICENSE.md` | `opensource/dataverse` 自有 Fork Submodule；数据集草稿、文件、版本和受限访问 | 当前无修改；定制提交到 `haomushaungtu/dataverse` |
| Data-Juicer | 1.5.5；`0e40a8659a759286d9bb3899cb3ef7f6fdbc624c` | Apache-2.0 | `opensource/data-juicer/LICENSE` | `opensource/data-juicer` 自有 Fork Submodule；多模态质量检测执行 | 当前无修改；定制提交到 `haomushaungtu/data-juicer`，平台侧编排放在 `algorithm/quality-service` |
| Presidio | 2.2.364；`779dbd286d5ef4d1fbe2514275fb1bce358f2417` | MIT | `opensource/presidio/LICENSE`、`opensource/presidio/NOTICE` | `opensource/presidio` 自有 Fork Submodule；PII/PHI 风险辅助检测 | 当前无修改；定制提交到 `haomushaungtu/presidio`，官方 upstream 为 `data-privacy-stack/presidio` |
| Flowable | 7.2.0；`6026a7d7ef0ecb056a77a49ba9b50e0cecbd2a7f` | Apache-2.0 | Maven 制品 `META-INF/LICENSE`、`META-INF/NOTICE` | 审批、人工待办和流程轨迹 | BPMN 与适配代码独立维护 |
| OpenSearch | 3.4.0 Min | Apache-2.0 | 发布制品内 `LICENSE.txt`、`NOTICE.txt` | OpenMetadata 隔离搜索 PoC | 不修改上游源码；无安全插件配置禁止用于生产 |
| Apache Solr | 9.8.0 | Apache-2.0 | 发布制品内 `LICENSE.txt`、`NOTICE.txt` | Dataverse 独立搜索服务 | 使用 Dataverse 6.10.1 官方 schema/solrconfig，不修改 Solr 核心源码 |
| Payara Server Community | 7.2026.2 | CDDL-1.1 / GPL-2.0 with Classpath Exception | 发布制品内 `LICENSE.txt` | Dataverse Jakarta EE 运行时 PoC | 不修改核心源码；仅部署配置、端口和资源上限调整 |
| Keycloak | 26.7.2；`289376b142480b4d600aca7acb1e4651862ed2a1`（无现有统一身份时） | Apache-2.0 | 尚未纳管；启用前固定源码或制品许可证路径 | OIDC、角色和服务账号 | 当前未选用；一期使用自建身份服务 |
| Cleanlab | 2.9.0（条件启用） | AGPL-3.0 | 尚未纳管；启用前固定源码或制品许可证路径 | 一个有标签数据集试点 | 启用前完成许可证和部署边界评审 |

版本信息核对日期：2026-09-03。版本号与上游提交已锁定；是否可进入生产仍以许可证复核、制品摘要、内网构建和端到端验收为准。

已验证原生发行制品 SHA-256：OpenMetadata 1.13.0 `85B65E6573851FF4F8AFBAD5B857BCC6245C4BE118482E2656CF4CA072594B60`；Dataverse 6.10.1 `dvinstall.zip` `DCE00F9F1B8B0E65AB4A2E90D6899F3BC70D54ECC93732B85884E4577BD964BE`；Payara 7.2026.2 `3495D025450F2B4263F7FC91014BEA89DB9DE907AD516B675D4F47B00E1AD4DE`；OpenSearch Min 3.4.0 `A40AAAB5979CFDCF44EA556C837BA0C35C2236DBC6635D51A81D86547FF7931D`；Solr 9.8.0 `9948DCF798C196B834C4CBB420D1EA5995479431669D266C33D46548B67E69E1`。OpenSearch 与 Solr 另使用发布方 SHA-512 完成来源校验，详见 `docs/planning/poc-results.md`。
