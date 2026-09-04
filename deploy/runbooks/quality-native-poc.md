# Quality Service 原生 Linux PoC

## 已验证环境

- 主机：`10.100.165.139`，Kylin V10，x86_64，glibc 2.28，4 CPU；未使用容器。
- 系统 Python 保持 3.7.9 不变；独立 Python 3.12.14 安装在 `/szah/dataset-foundry-poc/runtime/python-3.12.14-20260901`。
- Python 运行时使用 `python-build-standalone` 的 `20260901` GNU/Linux stripped 制品，SHA-256：`72748da13197c1fb161e3afeef20a6a385ff24f2165e6e2758e47008e7faba4c`。
- 隔离虚拟环境：`/szah/dataset-foundry-poc/venv/quality-d6c62a4`；依赖从版本化 wheelhouse 以 `--no-index` 安装。
- 源码目录：`/szah/dataset-foundry-poc/src/quality-401742d-lf1`，同时包含平台质量服务、自有 Data-Juicer Fork 与自有 Presidio Fork 的固定源码。
- 本次安装和验证未停止 hqd Java 进程，也未修改现有 OpenMetadata、OpenSearch、平台和 IAM 服务。

## 固定源码与制品

| Component | Fixed revision | Archive SHA-256 |
| --- | --- | --- |
| quality-service | `401742d` | `4aa846c42d7b0bf4d5f8afbe3073f1572637b5f4803bf8716a2bbf0285c645fe` |
| Data-Juicer | `0e40a8659a759286d9bb3899cb3ef7f6fdbc624c` | `c98bc6412d9983c2385f501c9480fc18d6bc523ed84678e578cba93292a99c1c` |
| Presidio | `779dbd286d5ef4d1fbe2514275fb1bce358f2417` | `1e55bb8b797e6d6669ffaef82ceecdb200fa7414765470f89da68b882980297d` |

源码归档必须使用 Git 规范化 LF 内容；不能让 Windows `core.autocrlf` 改写归档，否则固定提交的模块与 fixture 哈希会在 Linux 上产生假差异。

Data-Juicer 1.5.5 的算子注册表会在导入时触达可选 Ray 模块，上游 `LazyLoader` 在依赖缺失时默认尝试动态安装。本 PoC 将 Ray 2.52.0 作为离线依赖固定安装，但不启用 Ray 模式；执行环境必须同时设置：

```bash
export PIP_NO_INDEX=1
export UV_OFFLINE=1
export HF_HUB_OFFLINE=1
export DATASETS_OFFLINE=1
```

这些变量是禁止动态安装和外部模型访问的运行边界，不得从 systemd 环境中删除。

## 验证结果

Linux 上真实运行四项集成测试并通过：Data-Juicer `DocumentDeduplicator` 将 8 条记录去重为 7 条并定位 `ROW-002/ROW-006`；Presidio `AnalyzerEngine` 扫描 78 个非空单元格且无误报，医学文本自检命中数为 `3/0/2/0/2`；同时验证幂等重放、异请求冲突、输入哈希篡改、只读根目录越界和源文件不变。

独立 CLI 端到端证据：

- 证据：`/szah/dataset-foundry-poc/logs/quality/quality-e2e-401742d-001/evidence.json`
- 证据 SHA-256：`0e27b440b87fb7596f6cc8b6000545fe4523d530f3628ee4801a719569853d76`
- 报告 SHA-256：`ded435aaef539bd179512203d1640bef6eb0828a77a4a6b8a25e4efd7df50112`
- 执行结果：`94.38 / B / FAIL`，5 个问题、2 个 HIGH 阻断，`listing_eligible=false`。
- 执行完成后可用内存约 1.7 GiB。

## 尚未完成

当前是原生 CLI 执行切片，不是常驻生产服务。下一步仍需把请求中的根目录内绝对路径替换为平台签发的 storage profile、对象键和对象版本引用，接入共享 PostgreSQL 任务账本、MinIO 报告对象、平台状态与事件；随后再建立独立低权限账号和保持 `disabled` 的 systemd 单元。完成这些门槛前不得把质量工作流标记为 `DONE`。
