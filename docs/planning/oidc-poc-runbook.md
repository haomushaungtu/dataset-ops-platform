# 统一身份 OIDC PoC 验收手册

## 1. 当前状态

Dataverse 6.10.1 运行态已确认存在 `oidc` 认证工厂，OpenMetadata 1.13.0 支持自定义 OIDC；两套无凭据配置模板已经提供。

2026-09-03 的补充只读定位发现，`10.100.165.170` 上存在 `/home/hqd/hqd-auth.jar`，监听 9210，且从 139 可达。但它的 `/.well-known/openid-configuration` 和 `/oauth2/.well-known/openid-configuration` 都返回统一的业务 401 JSON 包装而非 OIDC discovery；JAR 中可见自定义 Token/社交登录控制器和 `spring-security-crypto`，未发现 OIDC/OAuth2 Authorization Server 依赖。结合 Nacos 111 个命名空间中 `authentication-api` 服务和实例均为 0，可判定该服务目前不是可直接供 OpenMetadata/Dataverse 使用的标准 OIDC IdP。

因此决定不复用或改造 `hqd-auth`，改为自建 `backend/identity-service`。2026-09-03 已在 139 以 Java 21、独立 PostgreSQL 和外部 RSA-3072 PKCS12 完成标准 discovery/JWKS 与完整 OIDC 协议 PoC。2026-09-04 切换为 `http://10.100.165.139:19000` 私网开发模式并复验通过；服务按原生非容器 systemd 方式运行但保持 `disabled`，不随主机启动。

技术 PoC 已通过授权码 + S256 PKCE、UserInfo、身份/角色 Claim、刷新令牌轮换、服务账号隔离、OIDC 登出、未登记回调拒绝及合成用户禁用即时作用于身份管理 API。开发阶段可直接用内网 IP 完成两个产品接入；B-01 在生产域名/TLS 和产品真实验收完成后关闭。

2026-09-04 已进一步完成 OpenMetadata 服务账号的客户端凭据机器令牌验证：令牌可从自建身份服务签发，OpenMetadata 可从当前 JWKS 取得签名 Key、完成验签并成功访问 API。该结果证明 discovery/JWKS、客户端凭据和机器到机器链路可用，不代表浏览器交互式 OIDC 登录、权限映射、禁用传播或全局登出已经通过。

以上“已通过”仅指自建身份服务协议 PoC 及机器到机器技术链路，不代表 OpenMetadata 1.13.0 的交互式产品接入已经通过。OpenMetadata 接入的安全、撤销和全局登出门禁以第 5 节为准；其中标记为预期失败的项目必须先在项目自有 Fork 修复并回归，不能以风险接受代替通过。

## 2. OpenMetadata 1.13.0 精确配置契约

当前固定源码 `f329dd4a7e47134a2bd5a06af6181b0ee527ddd9` 的 confidential custom OIDC 配置必须满足：

- `AUTHENTICATION_PROVIDER=custom-oidc`，不是 UI 枚举名 `customOidc`；
- `AUTHENTICATION_CALLBACK_URL` 与 `OIDC_CALLBACK` 必须完全相同，均为 `http://<openmetadata-host>:8585/callback`；不得使用 `/api/v1/callback`；
- 关闭自注册使用 `AUTHENTICATION_ENABLE_SELF_SIGNUP=false`，变量名中的 `SIGNUP` 不分隔；关闭后应在 OpenMetadata 预创建验收用户，未知用户登录被拒绝才是预期行为；
- 当前隔离内网 HTTP PoC 必须使用 `FORCE_SECURE_SESSION_COOKIE=false`，否则浏览器不会回送 Secure Session Cookie；生产 HTTPS 切换时必须改为 `true`；
- Scope 固定为 `openid email profile groups`；主体候选顺序固定为 `[preferred_username,email,sub]`；显式映射固定为 `[username:preferred_username,email:email]`，两条映射必须同时存在；
- `AUTHORIZER_USE_ROLES_FROM_PROVIDER=false` 保持关闭。身份 Claim 与最小权限映射通过验收前，不允许直接把 IdP `roles` 当作 OpenMetadata 角色导入。

模板中的 HTTP 地址只适用于已批准的隔离内网开发测试；不能复制到生产配置。客户端 ID 和 Secret 仍由 0600 运行时文件注入。

OpenMetadata 1.13.0 会优先加载数据库中持久化的系统安全配置并覆盖 YAML/环境变量。2026-09-04 实测中，历史数据库记录仍为 `basic` 且包含错误的 issuer/JWKS 地址，导致仅修改 systemd 环境后机器令牌验签失败。修正流程必须是：通过受控 API 读取并备份 `/api/v1/system/security/config`，更新为目标 OIDC 配置，随后回读并以真实令牌复验；不得把“环境变量正确”视为运行态配置已经生效。当前内部 HTTP discovery 因产品 URL 安全校验会被拒绝，只允许在已独立验证地址可达和 JWKS 正确的隔离 PoC 中精确绕过该单项校验，生产环境不得绕过。

## 3. 生产接入前需确认

- 统一身份服务生产域名、可信 TLS 证书、反向代理和网络访问边界；
- OpenMetadata、Dataverse 的生产访问地址及精确回调/登出回调；
- `ADMIN`、`OPERATOR`、`SUPPLIER`、`QUALITY_REVIEWER`、`DATA_REVIEWER`、`BUYER` 到两个产品权限的最小映射；
- 签名密钥轮换、身份库备份恢复、审计保留和告警责任人；
- 禁用用户在两个产品中的最大允许传播时延；当前离线 JWT 上限为 5 分钟。

真实客户端密钥和测试用户口令只能进入 0600 运行时文件或批准的密钥系统，不写入本仓库、命令行、测试输出或证据文件。

## 4. 无凭据只读门禁

生产 HTTPS issuer 就绪后，先在 139 运行：

```bash
python3 deploy/scripts/oidc_readiness_check.py \
  --discovery-url 'https://idp.example.internal/issuer/.well-known/openid-configuration' \
  --expected-issuer 'https://idp.example.internal/issuer' \
  --evidence /szah/dataset-foundry-poc/logs/oidc-readiness-$(date -u +%Y%m%dT%H%M%SZ).json
```

该工具只发出 discovery 和 JWKS GET 请求，不访问授权页面、不调用 Token/UserInfo/登出接口、不提交客户端 ID、密钥或用户凭据。它验证：

- discovery 与期望 issuer 完全一致；
- authorization、token、userinfo、logout 和 JWKS URL 均为绝对地址；
- 声明支持 `openid email profile`、授权码流和 S256 PKCE；
- 声明支持 `client_secret_post`、RS256、`sub` 和 `email`；
- JWKS 可回读、至少包含一个带 `kid` 的 RSA Key；
- HTTPS 证书由目标机系统信任。

仅在明确接受的隔离内网 PoC 中可以增加 `--poc-allow-http`。即使设置该参数，工具仍拒绝解析到公网地址的明文 HTTP；生产验收必须使用 HTTPS。证据只允许写入 PoC 日志目录的 `oidc-readiness-*.json`，权限为 0600，URL 会移除查询参数和片段。

门禁通过只证明发现文档和验签 Key 就绪，不证明客户端登记、登录、角色、登出或禁用行为通过。

2026-09-04 的内网 HTTP readiness 复验已通过，证据为 `/szah/dataset-foundry-poc/logs/oidc/oidc-readiness-20260904T044048Z.json`，SHA-256 为 `673567fee3d4fbc3252b2be9cc0747e8d8b43a649be42655dce7d7eb91aaa36d`。随后以专用服务账号机器令牌直接访问 OpenMetadata 返回 200；OpenSearch 迁入原生 systemd 单元后的最新适配器闭环证据为 `/szah/dataset-foundry-poc/logs/openmetadata-adapter/adapter-e2e-20260904T045405Z.json`，SHA-256 为 `f7cd2adb269d6f5b6d9124bd954cfef29a401de1204c036eb0b560efb03c3f96`。该服务账号当前为 PoC 管理权限，生产前必须收敛到最小权限。

## 5. 两个客户端的实际验收

1. 将 OpenMetadata 客户端参数写入基于 `deploy/config/openmetadata-oidc.env.example` 创建的 0600 运行时文件；保持自注册关闭，先不启用 IdP 角色导入。
2. 将 Dataverse 客户端参数通过批准的 Payara/MicroProfile Secret 来源注入，配置项以 `deploy/config/dataverse-oidc.env.example` 为准；保留本地紧急管理员直至验收结束。
3. 每个系统分别验证授权码和 S256 PKCE，确认回调地址严格匹配且没有 Token 出现在 URL、页面、应用日志或代理日志中。
4. 用同一个普通用户分别访问统一门户、OpenMetadata 和 Dataverse，验证 IdP 会话复用，不再次输入口令。
5. 验证普通用户不能获得管理员权限；再用管理员映射用户验证最小角色映射。只有 Claim 和映射结果确认后，才考虑开启提供方角色导入。
6. 执行单系统登出和全局登出，确认本地 Session、Cookie 和 IdP Session 的预期边界。
7. 禁用专用测试用户，等待约定的 Token/Session 失效窗口后，确认两个系统均拒绝新访问；记录实际失效时延。
8. 验证错误 issuer、错误 audience、过期 Token、错误签名、缺少 email Claim 和回调地址不匹配均被拒绝。

### 5.1 OpenMetadata Fork 安全门禁

下列项目是固定上游源码的已知缺口。已完成源码修复但尚未在 139 运行态验证的项目记录为 `SOURCE_FIXED_RUNTIME_PENDING`；其余仍记录为 `EXPECTED_FAIL`，不得标记为通过：

| 门禁 | 当前状态 | 自有 Fork 的关闭条件 |
| --- | --- | --- |
| Token 不进入 URL | confidential OIDC 回调后的 Token 会出现在查询串，存在浏览器历史、Referer、代理与访问日志泄露面 | Token 仅在服务端 Session 中保存；URL、浏览器历史及脱敏后的各层日志均无 Token，并补负向回归测试 |
| JWT issuer / audience / 用户版本校验 | OpenMetadata JWT Filter 验签但未强制校验 `iss`、`aud`、`auth_version`；错误 issuer/audience 或禁用前签发的旧 Token 可能继续被接受 | 固定 issuer、OpenMetadata audience，并在线或通过可证明的缓存策略校验 `auth_version`；三类负向用例全部拒绝 |
| 撤销与禁用传播 | IAM 的刷新令牌轮换和身份管理 API 禁用已通过，但 OpenMetadata 既有 Session/旧访问令牌不会因此立即撤销 | 禁用、密码/角色安全版本变化后，在约定窗口内撤销 OpenMetadata Session 和旧 Token，并记录最大传播时延 |
| RP-Initiated 全局登出 | OpenMetadata 本地退出未调用 IAM discovery 中的 `end_session_endpoint`，IAM SSO Session 仍在，重新登录可能无需口令 | 本地 Session 清理后调用 IAM `end_session_endpoint`，校验允许的登出回调；再次访问必须重新完成身份认证 |
| 角色和团队移除传播 | `SOURCE_FIXED_RUNTIME_PENDING`：自有 Fork `c73f8fcb0c4bdab0af689c8ed9a31596caccf987` 已实现三态收敛、Admin 降权和失败原子性，154 项定向测试通过 | 在 139 真实 OIDC 登录中验证角色/团队增删、空集合清理、未知组拒绝和越权回归 |
| `groups` 到 Team 的环境配置 | `SOURCE_FIXED_RUNTIME_PENDING`：同一提交已增加 `AUTHENTICATION_JWT_TEAM_CLAIM_MAPPING` 映射和测试 | 在 139 绑定 `groups`，验证只匹配已存在的 Group Team，并回读新增、移除和未知组结果 |

过期 Token、错误签名、缺少 email 和回调不匹配仍应按正常门禁拒绝；若这些项目失败，不属于上述预期失败范围，应停止验收并修复配置或实现。

## 6. 验收证据与清理

记录 discovery/JWKS 门禁文件、两个客户端的回调 HTTP 状态、登录/登出结论、角色映射、负向用例、禁用传播时延和应用日志脱敏检查。不得保存真实 Token、Cookie、授权码、客户端密钥或完整用户资料。

验收后删除临时测试客户端、测试用户和本地临时 Secret 文件，撤销所有测试 Token；恢复配置快照并停止 PoC 服务。只有在清理回读确认完成后，才能把 B-01 标记为关闭。
