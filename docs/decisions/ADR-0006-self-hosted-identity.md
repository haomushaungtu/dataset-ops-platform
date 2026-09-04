# ADR-0006：自建统一身份服务

- 状态：已接受
- 日期：2026-09-03

## 决策

一期不复用现有 `hqd-auth`，也不引入 Keycloak。建设独立的 `dataset-identity-service`，以 Spring Boot 3.5.4、Spring Authorization Server 和 Spring Security 提供标准 OIDC/OAuth2 能力，并以独立 PostgreSQL 数据库持久化用户、角色、客户端、授权、同意和审计数据。

浏览器客户端强制 Authorization Code + S256 PKCE；服务间客户端只允许 Client Credentials。令牌使用外部 PKCS12 RSA 私钥签名，生产 issuer 与回调地址必须为 HTTPS。访问令牌默认 5 分钟，刷新令牌默认 8 小时并轮换。

内网开发测试阶段允许显式启用 RFC1918 IPv4 字面地址的 HTTP issuer/回调；该开关默认关闭，不接受公网 IP 或普通主机名，且不改变生产 HTTPS 要求。

## 原因

只读核验确认现有 `hqd-auth` 未暴露标准 discovery/JWKS，也未发现 OAuth2 Authorization Server 能力，不能直接供 OpenMetadata 和 Dataverse 接入。自建服务可保持协议标准、部署轻量，并与一期 Java 21 / Spring Boot 技术栈一致。

“自建”仅指身份领域服务、数据模型和运营接口由项目维护；OIDC 协议、密码哈希、JWT/JWK 和授权服务器安全链均复用成熟框架，不自行实现密码学。

## 约束与后果

- 身份库、数据库账号、操作系统账号和签名密钥必须独立；密钥和客户端 Secret 只进入 0600 运行时文件。
- 管理员不能禁用自身或移除自身最后的 ADMIN 角色；登录连续失败 5 次锁定 15 分钟。
- 身份管理 API 在线检查用户启用状态与 `auth_version`；OpenMetadata、Dataverse 等外部系统的旧访问令牌最长可继续有效 5 分钟，除非后续增加主动会话撤销适配。
- 生产上线前必须补齐 HTTPS、反向代理、签名密钥轮换、备份恢复、告警和两个产品的真实登录/登出验收。
