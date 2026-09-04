# OpenMetadata PoC PostgreSQL 变更记录

## 结论

2026-09-03 已完成专用数据库 `openmetadata_poc` 的受控解锁和 OpenMetadata 1.13.0 迁移。PostgreSQL 保持 16.15 且未重启；模板库、平台业务库、Dataverse 数据库及其他既有数据库均未修改。

最终状态：

- `server_change_log` 共 94 行，最后成功版本为 1.13.0，1.13.0 记录为 1 行；
- `pgcrypto` 1.3、`pg_trgm` 1.6 已仅在 `openmetadata_poc` 启用并通过函数冒烟；
- OpenMetadata 官方迁移退出码为 0；
- 迁移与真实合成数据验收完成后，OpenMetadata/OpenSearch 已停止，PoC 端口均关闭。

## 变更前备份

变更前在数据库主机保存了可恢复的自定义格式备份，`pg_restore -l` 校验通过：

- 路径：`/data/postgresql/backups/openmetadata-poc/openmetadata_poc_pre_v009_repair_20260903T102251Z.dump`
- SHA-256：`ae8e450a15b3227c3a43e40514865a10330b0dfc928f69f06dc59d9135a2aa45`

另保留失败修复后的取证备份和续跑前检查点备份：

- `openmetadata_poc_after_failed_repair_20260903T103549Z.dump`：`33e6ed3a970e431bdea6faa9d7b7ff22bd92ce37461a8699de9c64f1911e03f5`
- `openmetadata_poc_pre_pgtrgm_resume_20260903T104143Z.dump`：`c6c1f7414097213c78d79825726c8d767c2372576494846d796c3d40070d302a`

备份不进入代码仓库，删除须按 DBA 保留策略另行批准。

## 扩展安装记录

目标系统的完整 `postgresql16-contrib-16.15-1PGDG.rhel8.10.x86_64` RPM 还依赖主机不存在的 Perl 5.26 和 Python 3.6 运行库，直接安装会造成不满足依赖的系统包状态，因此未执行整包安装。

实施时使用 PostgreSQL 官方 PGDG 仓库的精确版本 RPM，验证 GPG 指纹、RPM 签名和 SHA-256 后，仅安装 OpenMetadata 实际需要且原路径不存在的 `pgcrypto`、`pg_trgm` 控制文件、SQL 和共享库。文件清单分别保存在：

- `/var/lib/dataset-foundry-poc/pgcrypto-16.15/manifest.sha256`
- `/var/lib/dataset-foundry-poc/pg-trgm-16.15/manifest.sha256`

RPM SHA-256 为 `b17e719fade93e65f3bdea42f81591dd4aa0c611d973ee8d9a58f2a1c9f23a73`，PGDG Key 指纹为 `D4BF08AE67A0B4C7A1DBCCD240BCA2B408B40D20`。未覆盖已有文件，未重启 PostgreSQL。

## 迁移恢复说明

最初的 0.0.9 失败状态不是普通“账本未写入”：`server_migration_sql_logs` 已记录 9 条成功语句。删除这些语句创建的对象后，官方迁移器会依据语句校验点跳过 DDL，造成对象缺失并在后续 DML 失败。

因此已采取以下安全恢复方式：

1. 停止续跑并保存失败状态取证备份；
2. 仅删除并重建专用空 PoC 数据库，按原 owner、编码、排序规则恢复变更前备份；
3. 启用 `pgcrypto`，续跑到明确暴露 `pg_trgm` 依赖；
4. 安装并启用 `pg_trgm` 后，再次运行官方迁移并完成到 1.13.0；
5. 完成只读门禁、健康检查、索引模板和合成元数据创建/搜索/删除验收。

`deploy/sql/openmetadata-repair-partial-v009.sql` 已改为失败关闭的废弃占位脚本，禁止再执行 DROP 修复。后续若迁移中断，应保留 `server_migration_sql_logs` 和已创建对象，优先从迁移前备份恢复，不得手工修改迁移账本。

## 安全边界

- 不在其他数据库批量启用扩展；
- 不手工插入、更新或删除 `server_change_log`、`server_migration_sql_logs`；
- 不复用或修改现有 Elasticsearch；
- 不把数据库口令、连接串、Token、主机私钥或完整环境文件写入仓库和证据；
- 生产部署仍需由 DBA 将扩展纳入正式包管理、补丁、备份和恢复制度。
