# 统一身份 OIDC PoC 验收手册

## 1. 当前状态

Dataverse 6.10.1 运行态已确认存在 `oidc` 认证工厂，OpenMetadata 1.13.0 支持自定义 OIDC；两套无凭据配置模板已经提供。

2026-09-03 的补充只读定位发现，`10.100.165.170` 上存在 `/home/hqd/hqd-auth.jar`，监听 9210，且从 139 可达。但它的 `/.well-known/openid-configuration` 和 `/oauth2/.well-known/openid-configuration` 都返回统一的业务 401 JSON 包装而非 OIDC discovery；JAR 中可见自定义 Token/社交登录控制器和 `spring-security-crypto`，未发现 OIDC/OAuth2 Authorization Server 依赖。结合 Nacos 111 个命名空间中 `authentication-api` 服务和实例均为 0，可判定该服务目前不是可直接供 OpenMetadata/Dataverse 使用的标准 OIDC IdP。

因此决定不复用或改造 `hqd-auth`，改为自建 `backend/identity-service`。2026-09-03 已在 139 以 Java 21、独立 PostgreSQL 和外部 RSA-3072 PKCS12 完成标准 discovery/JWKS 与完整 OIDC 协议 PoC。2026-09-04 切换为 `http://10.100.165.139:19000` 私网开发模式并复验通过；服务当前运行但未设为开机启动。

技术 PoC 已通过授权码 + S256 PKCE、UserInfo、身份/角色 Claim、刷新令牌轮换、服务账号隔离、OIDC 登出、未登记回调拒绝及合成用户禁用即时作用于身份管理 API。开发阶段可直接用内网 IP 完成两个产品接入；B-01 在生产域名/TLS 和产品真实验收完成后关闭。

## 2. 生产接入前需确认

- 统一身份服务生产域名、可信 TLS 证书、反向代理和网络访问边界；
- OpenMetadata、Dataverse 的生产访问地址及精确回调/登出回调；
- `ADMIN`、`OPERATOR`、`SUPPLIER`、`QUALITY_REVIEWER`、`DATA_REVIEWER`、`BUYER` 到两个产品权限的最小映射；
- 签名密钥轮换、身份库备份恢复、审计保留和告警责任人；
- 禁用用户在两个产品中的最大允许传播时延；当前离线 JWT 上限为 5 分钟。

真实客户端密钥和测试用户口令只能进入 0600 运行时文件或批准的密钥系统，不写入本仓库、命令行、测试输出或证据文件。

## 3. 无凭据只读门禁

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

## 4. 两个客户端的实际验收

1. 将 OpenMetadata 客户端参数写入基于 `deploy/config/openmetadata-oidc.env.example` 创建的 0600 运行时文件；保持自注册关闭，先不启用 IdP 角色导入。
2. 将 Dataverse 客户端参数通过批准的 Payara/MicroProfile Secret 来源注入，配置项以 `deploy/config/dataverse-oidc.env.example` 为准；保留本地紧急管理员直至验收结束。
3. 每个系统分别验证授权码和 S256 PKCE，确认回调地址严格匹配且没有 Token 出现在 URL、页面、应用日志或代理日志中。
4. 用同一个普通用户分别访问统一门户、OpenMetadata 和 Dataverse，验证 IdP 会话复用，不再次输入口令。
5. 验证普通用户不能获得管理员权限；再用管理员映射用户验证最小角色映射。只有 Claim 和映射结果确认后，才考虑开启提供方角色导入。
6. 执行单系统登出和全局登出，确认本地 Session、Cookie 和 IdP Session 的预期边界。
7. 禁用专用测试用户，等待约定的 Token/Session 失效窗口后，确认两个系统均拒绝新访问；记录实际失效时延。
8. 验证错误 issuer、错误 audience、过期 Token、错误签名、缺少 email Claim 和回调地址不匹配均被拒绝。

## 5. 验收证据与清理

记录 discovery/JWKS 门禁文件、两个客户端的回调 HTTP 状态、登录/登出结论、角色映射、负向用例、禁用传播时延和应用日志脱敏检查。不得保存真实 Token、Cookie、授权码、客户端密钥或完整用户资料。

验收后删除临时测试客户端、测试用户和本地临时 Secret 文件，撤销所有测试 Token；恢复配置快照并停止 PoC 服务。只有在清理回读确认完成后，才能把 B-01 标记为关闭。
