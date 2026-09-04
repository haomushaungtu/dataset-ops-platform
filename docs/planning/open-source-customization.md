# 一期开源项目二开清单

## 0. 源码纳管规则

- OpenMetadata、Dataverse、Data-Juicer、Presidio 均先 Fork 到项目 GitHub 空间 `haomushaungtu`，再以 Git Submodule 纳入 `Code/opensource`；不得用官方镜像、发行包或远程服务替代正式源码纳管。
- `.gitmodules` 只指向项目自有 Fork；各子模块本地 `origin` 指向自有 Fork，`upstream` 指向官方仓库。父仓库 gitlink 固定 40 位提交，不跟随默认分支漂移。
- 定制修改只在对应 Fork 的功能分支完成并提交，再由父仓库更新 gitlink；禁止把第三方源码复制到 `backend`、`algorithm` 或 `integrations`。
- Flowable 一期使用 Maven 依赖、BPMN 和扩展接口；只有架构评审确认必须修改引擎内核后，才按同一 Fork/Submodule 流程引入完整源码。

| Project | Submodule | Fork / origin | Official upstream | Fixed commit | Current modifications |
| --- | --- | --- | --- | --- | --- |
| OpenMetadata | `opensource/openmetadata` | `haomushaungtu/OpenMetadata` | `open-metadata/OpenMetadata` | `c73f8fcb0c4bdab0af689c8ed9a31596caccf987` | `phase1/oidc-claim-convergence`：OIDC/SAML 角色、管理员和 Group Team 三态收敛，异常原子性与环境变量映射；14 个源码/测试/配置文件 |
| Dataverse | `opensource/dataverse` | `haomushaungtu/dataverse` | `IQSS/dataverse` | `300d5b518c627047f68fd277e48f1a79c5eadbed` | 无，等待适配需求确认 |
| Data-Juicer | `opensource/data-juicer` | `haomushaungtu/data-juicer` | `datajuicer/data-juicer` | `0e40a8659a759286d9bb3899cb3ef7f6fdbc624c` | 无，等待算子/流水线定制 |
| Presidio | `opensource/presidio` | `haomushaungtu/presidio` | `data-privacy-stack/presidio` | `779dbd286d5ef4d1fbe2514275fb1bce358f2417` | 无，等待中文医疗识别器定制 |

## 1. 版本候选

| Project | Candidate | Phase-1 action | Exit evidence |
| --- | --- | --- | --- |
| OpenMetadata | 1.13.0 release + 自有 Fork `c73f8fcb` | 自有 Fork/Submodule 已固定；在 Linux 完成后端/UI 构建；主题/中文/菜单/自定义属性/质量摘要/SSO；使用隔离搜索实例 | 完整构建日志、提交哈希、补丁清单、UI/API 冒烟 |
| Dataverse | 6.10.1 (`300d5b51`) | Linux 发行包、Payara 7.2026.2、独立 Solr 9.8.0 与 MinIO S3 兼容存储已完成原生集成 PoC；继续官方 API 适配 | 已通过版本 API、合成数据集 CRUD/索引、CSV 上传、MinIO 对象哈希和 Dataverse 下载回读；后续补版本发布、受限下载与最小权限测试 |
| Data-Juicer | 1.5.5 (`0e40a865`) | Linux 受控虚拟环境与离线依赖目录；统一任务 API；癌症规则与外部算子路径；生产运行时禁止动态安装依赖 | 固定配置对真实样例执行并输出结构化结果 |
| Presidio | 2.2.364 (`779dbd28`) | 自定义中文医疗识别器、词典和正则 | PII/PHI 阳性/阴性测试集及人工复核 |
| Flowable | 7.2.0 (`6026a7d7`) | Spring Boot 3.5 PoC 已通过；BPMN、任务与历史适配 | 关键流程部署、任务完成、业务引用验证 |
| Dataset Identity Service | 0.1.0（Spring Boot 3.5.4 / Spring Authorization Server 1.5.x） | 自建轻量统一身份服务；复用独立 PostgreSQL，原生 JVM 部署；提供 OIDC、PKCE、角色、服务账号和审计 | discovery/JWKS、授权码、PKCE、登出、禁用用户和服务认证测试 |
| Cleanlab | 2.9.0 | 条件试点；启用前完成 AGPL 许可证边界评审 | 一个有标签数据集可重复试点报告 |

## 2. OpenMetadata 修改优先级

1. 配置/API：自定义属性、分类、标签、数据质量结果、事件订阅。
2. 扩展层：统一导航、业务卡片、反向代理路由和 OIDC。
3. 受控源码修改：品牌、必要中文与无法扩展的页面；每处建立补丁测试。

禁止复制一套脱离 OpenMetadata 的元数据权威。业务页面从 platform-service 读取交易状态，从 OpenMetadata 读取元数据索引，并通过稳定数据集 ID 关联。

## 3. Dataverse 适配边界

实现产品/版本/文件 ID 映射、草稿/登记同步、发布/上架同步、授权访问同步和下载记录同步。Dataverse 不管理订单、合同、价格、上架审批或运营状态。未经准入和质量门槛通过，适配器必须拒绝 publish 命令。

## 4. 升级记录模板

每次上游同步必须记录：原始 tag/commit、目标 tag/commit、发行包或构建制品摘要、许可证变化、修改文件、修改原因、冲突解决、数据库迁移、构建/安全/回归结果、回滚方法和后续风险。没有这些证据不得更新生产基线。

版本短哈希仅用于阅读；实际拉取、构建和制品清单必须记录完整 40 位提交哈希与制品 SHA-256；后续若启用容器，再补充镜像摘要。

OpenMetadata UI 上游构建固定 Node 22.17.0 / Yarn 1.22.18 且脚本使用 POSIX shell 语法；正式编译节点必须为 Linux，并为 Maven、Node、Yarn、npm/Yarn 依赖配置经审计的内网镜像。

固定工具链、离线依赖、139 资源门和构建出口证据见 `docs/planning/openmetadata-source-build.md`。后端或 UI 任一项未产生同一自有 Fork 提交的成功构建日志和制品摘要前，不得以历史官方发行包 PoC 代替源码构建通过。

### 4.1 OpenMetadata 首批定制记录

- 上游基线：`f329dd4a7e47134a2bd5a06af6181b0ee527ddd9`；自有 Fork 固定提交：`c73f8fcb0c4bdab0af689c8ed9a31596caccf987`。
- 修改范围：`conf/openmetadata.yaml`；用户资源、OIDC/SAML handler、JWT/Security 工具、`CatalogSecurityContext`、SAML consumer、`UserUtil`，以及对应 5 个测试类，共 14 个文件。
- 行为：缺失 claim 保留既有映射，显式空集合清除 provider 管理的角色/Group Team，非空集合先完整解析再原子替换；静态管理员与 provider Admin 合并；未知团队/角色或仓储异常不进入用户创建分支。
- 回归：Maven 3.9.11、JDK 21 定向 `package` 共 154 项测试通过，13 个变更 Java 文件 Spotless 定向检查通过。
- 升级策略：后续从 `upstream` 新版本建立升级分支，先重放上述 14 文件的语义测试，再解决冲突和更新父仓库 gitlink；禁止直接在父仓库复制源码或跟随上游浮动分支。
