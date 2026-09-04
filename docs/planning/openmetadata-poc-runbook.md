# OpenMetadata 1.13 原生 PoC 续跑手册

## 1. 适用范围与当前状态

本手册只用于 `10.100.165.139` 上 `/szah/dataset-foundry-poc` 的隔离原生 PoC，不是生产部署手册。固定组合为 OpenMetadata 1.13.0、Java 21、专用数据库 `openmetadata_poc` 和回环监听的 OpenSearch 3.4.0。禁止连接清单中的现有 Elasticsearch 8.17.4，禁止并行启动 Solr，禁止使用容器。

2026-09-03 已完成实际集成 PoC：PostgreSQL 16.15 专用库已启用 `pgcrypto` 1.3 和 `pg_trgm` 1.6，官方迁移退出码为 0，迁移账本最后版本为 1.13.0；OpenMetadata 1.13.0 健康检查通过，并在隔离 OpenSearch 3.4.0 中创建 60 个索引和 61 个索引模板。合成数据库服务—数据库—Schema—表元数据已完成创建、读取、搜索、递归硬删除和清理核验。当日验收完成后 OpenMetadata/OpenSearch 曾按计划停止、PoC 端口关闭并恢复内核参数；这是历史清理结果，不再代表当前运行状态。

2026-09-04 已按原生非容器方式重新启用 OpenMetadata、OpenSearch、`identity-service`、`platform-service` 和 OpenMetadata 集成适配器，均由 systemd 托管但保持 `disabled`，不随主机启动；Dataverse/Payara 与 Solr 继续停止。OpenSearch 堆已下调为 768 MiB。OpenMetadata 当前运行自有 Fork 固定提交构建的定制 JAR，集成适配器也使用项目源码构建产物；重启后 `verify` 门禁再次通过，OpenMetadata `18585/18586`、适配器 `19110/19111` 和 OpenSearch `19200` 均正常。这些运行态仍只用于内网开发 PoC，不代表生产部署就绪。

## 2. 数据库前置条件与中断恢复

`openmetadata_poc` 必须保持 PostgreSQL 16，并同时具备 `pgcrypto`、`pg_trgm`。扩展安装、备份和恢复证据见 `docs/planning/postgresql-dba-handoff.md`；不得在模板库、业务库或其他现有数据库中批量启用扩展。

OpenMetadata 会把每条成功迁移语句记录到 `server_migration_sql_logs`。如果版本账本尚未推进但语句日志已存在，已创建的对象就是续跑检查点的一部分，不能删除后直接重跑。仓库中的 `deploy/sql/openmetadata-repair-partial-v009.sql` 已废弃并会主动报错，防止再次执行破坏性 DROP。

迁移中断时必须停止应用，保存脱敏日志，并从本轮迁移前的专用数据库备份恢复；只有在确认对象和语句校验点一致时才允许官方迁移器续跑。不得手工改写 `server_change_log` 或 `server_migration_sql_logs`。

## 3. 运行前门禁

主机上的门禁固定为 `/szah/dataset-foundry-poc/run/openmetadata/openmetadata-poc-gate.sh`。运行时配置继续使用专属 0600 文件：

```bash
bash /szah/dataset-foundry-poc/run/openmetadata/openmetadata-poc-gate.sh \
  /szah/dataset-foundry-poc/config/openmetadata/openmetadata.env \
  preflight
```

门禁只读检查：配置文件所有者和权限、固定 PoC 路径/数据库/端口、Java 21、可用内存、Solr 与 OpenMetadata 停机状态、PostgreSQL 16、`pgcrypto`/`pg_trgm` 可用且已安装、应用账号数据库 `CREATE` 权限，以及迁移账本与语句检查点是否一致。任何检查失败均应停止；不得修改上游迁移 SQL或伪造迁移账本。

运行时文件只能由 `root` 或 `dfmetadata` 持有且权限必须为 0600。凭据只存在该文件，不写入命令行、日志、仓库、Shell 历史或进程参数。正式环境需改接密钥托管；当前 `sslmode=disable` 也只适用于已批准的内网 PoC。

## 4. 串行续跑顺序

以下步骤只在第 2 节的扩展安装、专用库备份、部分状态修复及第 3 节门禁全部通过后执行，一次只启动一个搜索栈。

1. 确认 `dfsolr` 无进程；若要重做迁移，先通过精确单元名停止 `dataset-openmetadata-poc.service`，确认 18585/18586 关闭且可用内存至少 3072 MiB。
2. 使用仓库模板 `deploy/systemd/dataset-opensearch.service.example` 安装或核对 `dataset-opensearch-poc.service`，将 `vm.max_map_count` 的原值记录到会话文件后执行 `systemctl start dataset-opensearch-poc.service`。单元保持 `disabled`，只允许 `127.0.0.1:19200/19300`，当前 PoC 堆固定为 768 MiB；本机冷启动实测约 92 秒，须等健康状态达到 yellow/green 后再继续。
3. 运行搜索阶段门禁：

   ```bash
   bash /szah/dataset-foundry-poc/run/openmetadata/openmetadata-poc-gate.sh \
     /szah/dataset-foundry-poc/config/openmetadata/openmetadata.env \
     search
   ```

4. 以 `dfmetadata` 身份加载同一 0600 配置，在发行包目录执行一次官方迁移：

   ```bash
   runuser -u dfmetadata -- bash -c '
     set -a
     . "$1"
     set +a
     cd "$OPENMETADATA_HOME"
     exec ./bootstrap/openmetadata-ops.sh migrate
   ' bash /szah/dataset-foundry-poc/config/openmetadata/openmetadata.env
   ```

5. 仅当迁移退出码为 0 且脱敏后的日志没有 `ERROR`、`Exception`、`FAILED` 时，才使用仓库模板 `deploy/systemd/dataset-openmetadata.service.example` 对应的原生单元启动服务：

   ```bash
   systemctl start dataset-openmetadata-poc.service
   systemctl is-enabled dataset-openmetadata-poc.service  # 必须仍为 disabled
   ```

6. 服务稳定后运行验证阶段门禁：

   ```bash
   bash /szah/dataset-foundry-poc/run/openmetadata/openmetadata-poc-gate.sh \
     /szah/dataset-foundry-poc/config/openmetadata/openmetadata.env \
     verify
   ```

验证必须同时满足：OpenMetadata 版本为 1.13.0、应用端口 18585 和管理端口 18586 仅回环监听、管理端口 `/healthcheck` 返回成功、OpenSearch 版本为 3.4.0 且健康状态为 green/yellow、迁移产生至少一个搜索模板。

## 5. 最小功能证据

运行态门禁通过后，使用 `deploy/scripts/openmetadata_synthetic_smoke.py` 调用 OpenMetadata 官方 API。脚本从 `tests/fixtures/synthetic/cancer-registry.csv` 生成一组名称带唯一时间戳的合成数据库服务、数据库、Schema 和表元数据，验证创建、读取、OpenSearch 搜索命中，然后对顶层服务执行递归硬删除并确认 API 404 和搜索文档消失。它不会连接声明中的虚构数据源，也不会读取业务库或既有索引。

将 `deploy/config/openmetadata-smoke-credentials.env.example` 复制到仓库外的 0600 文件，只填写隔离 PoC 管理员登录信息。脚本用 Base64 编码密码调用 `/api/v1/users/login`，访问令牌只保留在进程内存中，不写入证据文件：

```bash
python3 deploy/scripts/openmetadata_synthetic_smoke.py \
  --credentials-file /szah/dataset-foundry-poc/config/openmetadata/smoke-credentials.env \
  --fixture tests/fixtures/synthetic/cancer-registry.csv \
  --evidence /szah/dataset-foundry-poc/logs/openmetadata-smoke-$(date -u +%Y%m%dT%H%M%SZ).json
```

脚本只接受 `http://127.0.0.1:18585/api`；凭据文件必须位于 PoC 专属配置目录，由 `root` 或 `dfmetadata` 持有且权限为 0600。输入 CSV 还必须匹配仓库合成 Fixture 的固定 SHA-256。证据文件仅允许写入 PoC 日志目录下的 `openmetadata-smoke-*.json`，同样使用 0600 权限，只包含 HTTP 状态、实体 ID、Fixture 哈希/行列数、搜索次数和清理结论，不记录 Token、密码、Cookie、Authorization 头或完整响应体。若清理失败，必须保留失败证据并人工按精确 ID 处理，不能继续停机清理步骤。

最小验收证据如下：

| Gate | Pass condition |
| --- | --- |
| Database | 官方迁移退出码 0；`pgcrypto`、`pg_trgm` 可用且已安装；最后版本 1.13.0 |
| Runtime | `/api/v1/system/version` 为 1.13.0；管理健康检查成功 |
| Search | 仅连接回环 OpenSearch 3.4.0；模板存在；合成实体可搜索 |
| Isolation | 既有 Elasticsearch 8.17.4 无新增索引；Solr 未运行 |
| Cleanup | 合成实体、临时 Token/用户和临时索引数据精确删除 |
| Host | 当前运行的 OpenMetadata/OpenSearch/identity/platform/adapter 均为原生 systemd 服务且 `disabled`；Dataverse/Payara 与 Solr 保持停止；需停机验收时再检查 PoC 端口关闭和内核参数恢复原值 |

本轮真实验收证据为 `/szah/dataset-foundry-poc/logs/openmetadata-smoke/openmetadata-smoke-20260903T105314Z.json`，SHA-256 为 `c8d86e7ace3225cd990d2f8dde2d13cdc49687b0ecdbac1241f6b8bb8b7054a0`；结果为 `passed-and-cleaned`。证据不含密码、Token 或响应正文。

2026-09-04 又使用 `deploy/scripts/openmetadata_adapter_e2e_smoke.py` 完成统一身份服务账号机器令牌、适配器和 OpenMetadata 的真实闭环：机器令牌直接访问 OpenMetadata 返回 200，五个自定义属性写入与回读一致，幂等重放成功，冲突映射返回 409 且原映射保持不变，搜索可见，最后递归清理并确认 API 404 与搜索结果为 0。OpenSearch 迁入原生 systemd 单元后的最新证据为 `/szah/dataset-foundry-poc/logs/openmetadata-adapter/adapter-e2e-20260904T045405Z.json`，SHA-256 为 `f7cd2adb269d6f5b6d9124bd954cfef29a401de1204c036eb0b560efb03c3f96`。该技术闭环当前使用 PoC 管理权限服务账号，生产前必须收敛为仅具备目标实体和自定义属性操作所需的最小权限。

## 6. 停机、清理与回滚

1. 先通过官方 API 删除合成实体并确认搜索结果归零；只删除本轮创建且 ID 精确匹配的资源。
2. 撤销本轮临时 Token/用户并验证旧 Token 被拒绝；脱敏或删除含认证材料的临时日志。
3. 以精确单元名执行 `systemctl stop dataset-openmetadata-poc.service`，并核对原 MainPID、OS 用户和命令路径已经退出；不得使用宽泛 `pkill java`。
4. 确认 OpenMetadata 停止后执行 `systemctl stop dataset-opensearch-poc.service`，再恢复会话前记录的 `vm.max_map_count`。
5. 检查 `dfmetadata`、`dfsearch` 进程均为 0，18585、18586、19200、19300 端口关闭；确认 `/home/hqd` Java 服务状态未被改变。

若迁移失败，立即保留脱敏日志并停止，不自动重跑、不手工改写 `SERVER_CHANGE_LOG`、不删除专用数据库。本次已经证明失败迁移可能在写账本前提交部分 DDL，因此不能再用“账本无版本号”推断数据库未变化。由于迁移会修改专用数据库，回滚依据是执行前由 DBA 创建的 `openmetadata_poc` 备份或快照；恢复动作须由 DBA 明确批准。PoC 搜索目录仅在确认没有需保留证据、目标路径精确且服务已停机后清理。

## 7. 当前 PoC 边界与未验收项

- 不把 OpenSearch 的无认证 HTTP 配置升级为生产配置；生产需另做 TLS、认证、备份、监控和容量设计。
- 自建 `backend/identity-service` 已提供标准 discovery/JWKS，OpenMetadata 已用客户端凭据机器令牌完成验签和 API 访问；但浏览器授权码登录、角色/团队增删与降权、禁用传播、RP-Initiated Logout 仍未完成产品级验收，不能把机器令牌通过等同于统一登录验收完成。
- OpenMetadata 的安全配置会从数据库持久化记录加载并覆盖 YAML/环境变量。调整 OIDC 时必须先读取并备份 `/api/v1/system/security/config`，通过受控接口更新后再回读验证；仅修改 systemd 环境文件不会使已有数据库配置自动生效。
- 不恢复或重启已停止的 `/home/hqd` 服务，除非用户另行明确要求。
