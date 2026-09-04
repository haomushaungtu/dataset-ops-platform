# Quality Service

一期质量执行服务的首个纵向切片，固定支持 `CANCER_TABULAR_V1`。它读取已冻结的数据集版本引用，在隔离工作目录中生成派生 JSONL，真实调用 Data-Juicer 1.5.5 和 Presidio Analyzer 2.2.364，只检测而不删除或改写源文件，并输出可由平台持久化的结构化报告。

当前切片包含：

- 输入 SHA-256 前后校验；
- Data-Juicer `DocumentDeduplicator` 真实源码调用及固定提交、模块哈希和配置哈希证据；
- Presidio `PatternRecognizer` 中文医疗 PII/PHI 风险提示；
- 完整性、唯一性、规范性、一致性、可用性、标注质量、隐私残留、分布代表性八维评分；
- 问题位置、严重度、规则与引擎来源；敏感命中只保存摘要哈希，不保存原文；
- SQLite 幂等账本：同一 `Idempotency-Key` 和请求返回同一结果，不同请求返回冲突；
- 报告与标准版本、数据集版本和输入哈希绑定。

运行时依赖必须从项目自有 Fork 固定提交构建或从已核验的离线制品安装。不得以远程 API 或官方镜像代替源码纳管。服务不会连接业务数据库或对象存储；平台通过服务端配置的受控只读根目录/挂载提供本次执行所需输入，请求中的绝对路径必须位于该根目录内。

```powershell
dataset-quality run --request request.json --database quality-tasks.db --workspace D:\quality-work `
  --input-root D:\readonly `
  --presidio-self-test-file D:\fixtures\medical-notes.jsonl
```

请求示例：

```json
{
  "quality_task_id": "00000000-0000-0000-0000-000000000000",
  "dataset_id": "00000000-0000-0000-0000-000000000001",
  "version_id": "00000000-0000-0000-0000-000000000002",
  "correlation_id": "00000000-0000-0000-0000-000000000003",
  "standard_code": "CANCER_TABULAR_V1",
  "tabular_path": "D:/readonly/cancer-registry.csv",
  "tabular_sha256": "64 lowercase hex chars",
  "idempotency_key": "caller-generated-key"
}
```

当前阶段不把该执行器误记为完整产品质量工作流：平台侧任务受理、人工抽样复核、整改反馈、复测状态机、报告对象存储和 OpenMetadata 回写仍需后续纵向集成。
