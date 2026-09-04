# Dataset Identity Service

平台自建的标准 OAuth 2.0 / OpenID Connect 1.0 身份服务，供统一门户、OpenMetadata、Dataverse 和平台服务使用。协议实现基于 Spring 官方 Authorization Server；本项目负责用户、角色、客户端配置、签名密钥装载、禁用策略和安全审计，不自行实现密码学或协议解析。

## 已实现

- Authorization Code + PKCE、Refresh Token、Client Credentials；
- OIDC discovery、JWKS、UserInfo、RP-Initiated Logout；
- PostgreSQL 持久化用户、角色、客户端、授权、授权同意和审计；
- RS256 外部 PKCS#12 签名密钥，启动时缺失即失败；
- BCrypt strength 12，连续 5 次密码失败锁定 15 分钟；
- JWT 使用不可变用户 UUID 作为 `sub`，并提供 `email`、`preferred_username`、`roles`、`groups`、`auth_version` Claim；
- 5 分钟访问令牌、8 小时刷新令牌、刷新令牌轮换；
- 管理员创建/禁用用户、角色替换和密码重置 API；
- 防止管理员禁用自身或移除自身 `ADMIN` 角色。
- `platform.internal` 服务账号不能调用用户 `/me` 或管理员接口；平台服务账号用于受限角色增量，OpenMetadata 适配器使用独立机器客户端，二者不共享密钥。

## 本地验证

```bash
./mvnw test
```

测试使用内存数据库和仅测试环境生成的临时 RSA Key。真实运行必须使用 PostgreSQL、HTTPS issuer 和仓库外 0600 PKCS#12/环境文件。

2026-09-03 已在 139 使用 Java 21、真实 PostgreSQL 16.15 和外部 PKCS12 完成原生进程 PoC，覆盖 discovery/JWKS、授权码 + S256 PKCE、UserInfo、Claim、刷新令牌轮换、服务账号隔离、用户禁用、登出和回调白名单。2026-09-04 按内网开发约束改为 `http://10.100.165.139:19000` 并重新通过完整协议流；服务当前运行，但未启用开机启动。详情见 `docs/planning/poc-results.md`。

## 运行前置

1. 创建独立数据库和非超级用户，例如 `platform_identity` / `platform_identity_app`；应用账号只拥有该数据库的 DDL/DML 权限。
2. 将 `deploy/config/identity-service.env.example` 复制到仓库外的 0600 文件并替换所有占位值。
3. 使用 `deploy/scripts/generate-identity-keystore.sh` 创建不入库的 PKCS#12；生产应接入组织密钥托管和轮换制度。
4. 仅通过 TLS 反向代理公开 19000，19001 管理健康端口保持回环；代理必须限速登录和 Token 端点。
5. 首次启动确认管理员和四个客户端（OpenMetadata、Dataverse、platform-service、openmetadata-adapter）创建成功后，从环境文件删除 bootstrap 管理员密码。

内网开发阶段可显式设置 `DF_IAM_ALLOW_INSECURE_PRIVATE_NETWORK=true`，issuer 和回调地址必须使用 RFC1918 IPv4 字面地址，同时设置 `DF_IAM_COOKIE_SECURE=false`。该例外不会接受公网 IP、主机名、非 HTTP 协议或默认未授权的明文地址，禁止用于生产。

## 失效边界

用户禁用、角色变更和密码重置会递增 `auth_version` 并阻止后续登录/刷新。已签发 JWT 无法被离线验证方即时撤销，因此当前最坏失效窗口为访问令牌 TTL（默认 5 分钟）。若生产要求即时失效，资源服务必须增加 `auth_version` 在线校验或改用 Token Introspection，不能仅依赖长寿命 JWT。
