# 一期核心数据模型

## 1. 设计规则

- 主键使用 UUID；对外业务编号独立且不可复用。
- 所有可变聚合包含 `version` 乐观锁和审计时间。
- 文件与版本不可就地覆盖；状态历史只追加。
- 外部 ID 映射独立建表，避免把外部系统 ID 当平台主键。

## 2. 核心聚合

| Aggregate | Key fields | Authority / invariant |
| --- | --- | --- |
| `supplier_application` | application_no, applicant_id, organization_snapshot, status, flowable_process_id | 已提交快照不可改；通过才创建 supplier |
| `supplier` | supplier_no, organization_id, status | 只有 ACTIVE 可提交数据集和确认订单 |
| `dataset` | dataset_no, supplier_id, name, cancer_types, modalities, status | 永久产品主体，不承载可变文件 |
| `dataset_version` | dataset_id, version_no, source_type, dataverse ids, status | 文件集合不可变；唯一当前有效版本 |
| `ingestion_batch` | version_id, upload_session_id, status, received_at | 一次接入尝试，不覆盖历史 |
| `dataset_file` | batch_id, dataverse_file_id, object_ref, size, sha256, media_type | `(version_id, relative_path)` 唯一；哈希必填 |
| `admission_run` | version_id, run_no, auto_result, final_result, status | 自动/人工/最终结果分离 |
| `risk_issue` | run_id, file_id, type, location, confidence, severity, resolution | 位置只在授权范围展示 |
| `registration_application` | dataset_id, version_id, snapshot, status, process_id | 仅准入通过版本可提交 |
| `quality_task` | version_id, standard_version_id, engine_version, status, score, grade | 一任务固定一个不可变版本和标准版本 |
| `quality_metric_result` | task_id, metric_code, auto_value, reviewed_value, adopted_value, score | 采用值有来源和理由 |
| `quality_issue` | task_id, sample_ref, severity, category, remediation | 只引用受限样本，不复制全量数据 |
| `remediation` | failed_task_id, target_version_id, status, feedback | 复测生成新 task，原报告不覆盖 |
| `listing_application` | dataset_id, version_id, terms_snapshot, status, process_id | 质量门槛通过才可提交 |
| `listing` | dataset_id, version_id, status, published_at | 同一产品最多一个 ACTIVE listing |
| `order` | order_no, demander_id, supplier_id, dataset_id, version_id, purpose, term, status | 创建后版本固定；状态只经命令变更 |
| `contract` | contract_no, order_id, file_ref, sha256, valid_from/to, status | 双方确认同一哈希才 CONFIRMED |
| `authorization` | order_id, subject_id, version_id, valid_from/to, download_limit/used, status | 范围不可宽于合同；计数原子更新 |
| `delivery_task` | delivery_no, order_id, authorization_id, status, attempt_no | 每次重交付新 attempt，不覆盖旧任务 |
| `delivery_file` | delivery_id, dataset_file_id, expected_sha256, status | 只允许订单固定版本中的文件 |
| `delivery_receipt` | delivery_id, confirmer_id, result, confirmed_at, issue_id | 确认或异常二选一 |
| `audit_event` | event_id, actor, action, object, before/after hash, request_id, occurred_at | 仅追加，不允许业务删除 |
| `outbox_event` | event_id, aggregate, type, payload, occurred_at, published_at | 与业务事务同库提交 |

## 3. 外部映射

`external_resource_mapping(system, resource_type, business_id, external_id, external_version, sync_status, last_synced_at)` 对 OpenMetadata、Dataverse、Flowable、质量服务建立唯一映射。`(system, resource_type, external_id, external_version)` 唯一。

## 4. 关键关系

```text
supplier 1─* dataset 1─* dataset_version 1─* dataset_file
                         │        ├─* admission_run ─* risk_issue
                         │        ├─* quality_task ─* quality_metric_result
                         │        └─* listing_application
                         └─* registration_application

listing 1─* order 1─1 contract 1─1 authorization 1─* delivery_task
                                                    └─* delivery_file
```

## 5. 数据保留与删除

草稿可逻辑删除；已提交申请、检测结果、审批、合同、授权、交付、下载和审计不得物理删除。文件到期处置必须由独立生命周期任务按精确对象引用执行，并在删除前确认无生效合同、授权、调查或保留义务。
