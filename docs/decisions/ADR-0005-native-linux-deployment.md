# ADR-0005：一期暂不使用容器部署

- 状态：已接受
- 日期：2026-09-03

## 背景

内网网络已连通，但当前阶段不计划使用 Docker、Podman 或 Kubernetes。OpenMetadata UI 构建已确认依赖 POSIX/Linux 环境；Dataverse 和 OpenMetadata 运行时需要隔离的数据库、对象存储和兼容搜索资源。

## 决策

一期构建和运行统一使用受控 Linux 主机，不以容器运行时或容器编排为前置条件：

- Java 服务使用固定 JDK/Maven 和可校验的 JAR/发行包，由 systemd 管理。
- OpenMetadata 使用固定提交在 Linux 构建的发行包；Dataverse 使用固定版本发行包及应用服务器。
- quality-service 使用固定 Python 版本、虚拟环境和离线依赖目录；运行时禁止联网安装依赖。
- 网关和前端静态资源由反向代理托管。
- 每个服务使用独立操作系统账号、安装目录、配置目录、日志目录、端口，以及独立数据库或 Schema 和服务账号。
- 密钥和完整连接串不进入 Git；发行包、构建制品和依赖清单记录版本与 SHA-256，并保留可回滚的上一版本。

## 影响

当前 PoC 和部署门禁改为核验 Linux 主机、systemd、JDK/Python/应用服务器、目录与端口隔离，不再把缺少容器运行时列为阻塞。139 已完成隔离 OpenSearch 3.4.0 基础 CRUD 和 OpenMetadata Java Client 连接，以及 Dataverse 6.10.1 / Payara 7.2026.2 / Solr 9.8.0 的版本 API、专用数据库和合成数据集索引链路；OpenMetadata 数据库迁移仍要求 DBA 补齐 `pgcrypto`，并在备份后恢复或修复已部分提交但未记账的 0.0.9 状态，正式对象存储授权、身份集成和安全边界仍是前置条件。

PoC 主机确定为 `10.100.165.139`，运行目录为 `/szah/dataset-foundry-poc`。该主机资源为 4 核、14 GiB 内存、无 Swap，释放已授权的 hqd Java 进程后可用内存约 4.8 GiB，因此各组件必须逐个启动验证，禁止把全栈同时常驻作为当前验收条件。主机无公网制品源连通性，所有工具链和依赖必须离线传输并校验摘要。

搜索 PoC 使用独立 `dfsearch`/`dfsolr` 账号、1 GiB 堆和回环端口。OpenSearch Min 不含安全插件，Solr 未启用认证；两者只用于本机兼容性验证，禁止对外监听或作为生产配置。验证结束后两个进程均已停止，临时 `vm.max_map_count` 已恢复；生产启用前必须补齐认证、TLS、持久化内核参数、systemd、监控、备份和恢复演练。

Dataverse PoC 使用独立 `dfdataverse` 账号、1.5 GiB Payara 堆和回环 Web/Admin 端口。OpenMQ 已收紧到回环；Payara Data Grid 同时固定接口并关闭 Hazelcast `socket.bind.any` 后，内核监听检查确认 4900 只绑定回环。MinIO 集成使用 path-style S3 驱动、独立临时 Bucket 和 OS 账号私有的 0600 凭据文件，合成 CSV 上传、对象哈希和下载回读已通过，测试资源随后全部清理。共享或生产环境必须保留网络约束，改用专用最小权限存储身份，并轮换默认管理员凭据。

主机环境漂移风险通过版本锁定、离线制品、安装记录、健康检查和最小权限降低。后续容器化属于独立架构变更，需要重新评审部署、安全、监控、备份和回滚方案；应用 API 与数据权威边界保持不变。
