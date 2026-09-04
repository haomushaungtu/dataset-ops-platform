# OpenMetadata 1.13 源码构建门

## 1. 构建输入

- 自有 Fork：`haomushaungtu/OpenMetadata`
- 父仓库 Submodule：`opensource/openmetadata`
- 上游基线：OpenMetadata 1.13.0
- 上游固定提交：`f329dd4a7e47134a2bd5a06af6181b0ee527ddd9`
- 当前自有 Fork 固定提交：`c73f8fcb0c4bdab0af689c8ed9a31596caccf987`
- 正式制品只能从父仓库记录的自有 Fork 提交构建；官方发行包仅作为历史 PoC 和结果对照，不能作为一期正式制品。

每次构建先确认子模块工作树干净、`origin` 为自有 Fork、`upstream` 为官方仓库，并记录完整提交、构建机 OS/架构、工具链版本、命令、退出码和制品 SHA-256。含 Secret、Token、Cookie、授权码和数据库口令的文件或输出不得进入构建日志。

## 2. 固定工具链

| Tool | Required version / range |
| --- | --- |
| JDK | 21 |
| Maven | 3.9.x（上游最低 3.6.0） |
| Node.js | 22.17.0 |
| Yarn | 1.22.18 |
| Python | 3.11、3.12 或 3.13 |
| ANTLR | 4.9.2 |
| Shell | Linux Bash 4+ |

Linux 构建节点还需要 `make`、`git`、`curl`、`jq`、`tar`、`gzip`、`unzip`、`gcc` 和 `g++`。常规后端/UI 构建不依赖容器；Playwright 浏览器仅在 UI E2E 测试阶段安装。

## 3. 可重复构建命令

```bash
# 后端服务及其 reactor 依赖
mvn clean package -DskipTests -pl openmetadata-service -am

# 上游定义的后端全包
mvn clean package -DskipTests -DonlyBackend -pl '!openmetadata-ui'

# UI 三份锁文件对应依赖缓存及正式构建
make yarn_install_cache
cd openmetadata-ui/src/main/resources/ui
yarn test
yarn build
```

离线构建包必须由同 OS/架构的联网 Linux 构建节点通过一次完整成功构建生成，至少包含完整 Maven 本地仓库、根目录/UI Core/UI 三份 Yarn lock 对应缓存，以及固定 Node/Yarn/node-gyp/ANTLR 工具。仅执行 `dependency:go-offline` 不能证明 reactor 的插件和传递依赖齐备。

## 4. 139 主机现状与执行策略

2026-09-04 只读核验和最小权限修正结果：

- 源码位于 `/szah/dataset-foundry-poc/build/openmetadata-f329dd4a/source`，固定提交归档 SHA-256 为 `C0D2E96B63952064A939216BFF045B4273DFF693D8B242C78E42A45B97533F08`；目录恢复为 `dfmetadata:dfpoc`、`0750`。
- Maven 3.9.11 位于 `/szah/dataset-foundry-poc/runtime/tools/apache-maven-3.9.11`，JDK 21 位于 `/szah/dataset-foundry-poc/runtime/java-21`。
- 主机总内存约 14.7 GiB、无 Swap；当前仅 identity/platform 两个项目 Java 服务常驻，可用内存约 4.28 GiB，没有可按授权停止的 `/home/hqd` Java 进程。
- OpenMetadata UI 构建固定 `NODE_OPTIONS=--max_old_space_size=6144`。在至少 8 GiB 可用内存前，不在 139 强行执行 UI 构建；不得停止正在提供集成能力的 identity/platform 来伪造资源满足。
- 139 无公网制品源连通性。先在兼容的联网构建节点完成源码构建并固化离线依赖，随后上传到 139 做同提交原生复构建、制品哈希校验和运行态验收。

当前状态仍为 `IN_PROGRESS`：源码纳管、自有 Fork 安全补丁构建和 139 原生运行验证已完成；同一固定提交的 Linux 完整后端/UI 构建，以及交互式 OIDC、角色/组撤销、禁用传播和登出验收尚未全部通过。

### 4.1 本轮联网构建探针

2026-09-04 在 Windows 联网节点使用 Maven 3.9.11、JDK 21 执行上游 reactor 构建，用于补齐依赖并提前发现源码编译问题。结果如下：

- `OpenMetadata Common`、`OpenMetadata Specification`、`OpenMetadata SDK`、Elasticsearch/OpenSearch shaded dependencies、`OpenMetadata Service`、Kubernetes Operator、MCP 和 Integration Tests 模块均完成构建；`openmetadata-service` 的 1578 个主源码和 431 个测试源码完成 Java 21 编译。
- reactor 最终在 `OpenMetadata UI Core Components` 的 Yarn `esbuild install.js` 失败；失败环境是 Windows 非 ASCII 工作路径，不能替代所要求的 Linux UI 构建，且不属于 Java 服务或本轮安全补丁编译失败。
- 因 UI Core 失败，Distribution、Clients、Java Client 未继续执行；本次不能记录为完整构建通过。Linux 构建门保持不变。

上述探针只证明 Java 服务源码可编译并已补充本机构建缓存。后续安全补丁测试须使用 `package` 生命周期，使两个 shaded dependency 模块先产生重定位 JAR；直接对 reactor 使用 `test` 生命周期会因上游 shade goal 尚未执行而出现 `es.*`/`os.*` 包缺失，这属于命令生命周期错误，不是源码回归。

### 4.2 自有 Fork 安全补丁构建

2026-09-04 对自有 Fork `c73f8fcb0c4bdab0af689c8ed9a31596caccf987` 使用 Maven 3.9.11、JDK 21 执行 `openmetadata-service` 及 reactor 依赖的定向 `package`：5 个测试类共 154 项测试全部通过，0 failure、0 error、0 skipped；13 个变更 Java 文件的 Spotless 定向检查和 `git diff --check` 均通过。

本次 `openmetadata-service-1.13.0.jar` SHA-256 为 `37534995A3D496CAFC96EB41225CDDAB0830DAB61027DC720E2D1B71CF21BA97`。该摘要来自 Windows 联网构建节点，仅作为源码补丁编译与测试证据；仍须在 Linux 上按同一提交完成后端/UI 正式构建并重新记录制品摘要。

### 4.3 139 原生运行验证

上述 Windows 联网节点产出的 patched `openmetadata-service-1.13.0.jar` 已部署到 139 的 `/szah/dataset-foundry-poc` 隔离目录，以 Java 21 和 systemd 原生方式运行，不依赖容器或官方远程服务。运行态复用隔离 PostgreSQL/OpenSearch，完成版本、健康、数据库迁移、索引模板和合成元数据适配器链路验证；服务单元保持 disabled，不随主机启动自动拉起。

该运行验证使用的服务 JAR SHA-256 仍为 `37534995A3D496CAFC96EB41225CDDAB0830DAB61027DC720E2D1B71CF21BA97`，对应自有 Fork 提交 `c73f8fcb0c4bdab0af689c8ed9a31596caccf987`。这证明当前补丁制品可以在 139 原生运行，但不替代出口门要求的 Linux 同提交完整后端/UI 构建；Linux 正式构建完成后必须重新记录服务 JAR、UI 静态制品和离线依赖摘要。

## 5. 出口证据

只有同时具备以下证据，才可把源码构建门标记通过：

1. 自有 Fork 定制提交和父仓库 gitlink 均可从远端读取；子模块无未提交修改。
2. 后端与 UI 构建退出码为 0；针对安全补丁的单元测试通过。
3. 记录服务 JAR、UI 静态制品和离线依赖清单的 SHA-256，不记录凭据。
4. 139 从上述源码制品原生启动，版本、健康、数据库迁移和隔离 OpenSearch 检查通过。
5. OIDC、元数据适配器、角色/组撤销、禁用传播、登出和负向令牌用例达到对应验收门。
