# 生产部署与 TLS

## 必需配置

生产使用 `prod` profile，并从环境注入所有凭据；`application-prod.yml` 没有数据库或 HMAC 密钥默认值。

```text
SPRING_PROFILES_ACTIVE=prod
MYSQL_HOST, MYSQL_PORT, MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD
PAIRING_HMAC_KEY   # 至少 32 UTF-8 字节的独立随机密钥
SERVER_PORT=8080
```

本服务强制单实例。连接索引、重连宽限和限流都在本机内存中，多实例会破坏可达性与启动清理语义。

## 反向代理 TLS（推荐）

后端只监听受防火墙保护的内网/loopback `8080`，公网仅开放代理的 443。示例 Nginx 片段：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}

location /ws {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 75s;
}
```

`server.forward-headers-strategy=framework` 已启用。只有受信代理可以访问后端，否则客户端可伪造转发头并绕过 IP 限流。

## Spring keystore TLS（可选）

激活 `prod,tls`，并设置 `TLS_KEY_STORE`、`TLS_KEY_STORE_PASSWORD`、`TLS_KEY_STORE_TYPE` 和 `TLS_KEY_ALIAS`。证书/keystore 文件不得提交。

日志仅允许关联 ID、设备 ID、事件类型、状态和结果码；禁止 Authorization、AUTH payload、完整帧、配对码、SDP、ICE 和控制参数。

## STUN / TURN

STUN/TURN 独立部署（例如 coturn），不放进 Spring Boot 进程。后端只转发 SDP/ICE，不代理媒体、不保存 TURN 凭据；客户端在创建 `RTCPeerConnection` 时自行配置 ICE servers。生产环境应使用带鉴权的 TURN，避免公开中继被滥用。

## 数据与运维

- 迁移采用只增表的 V2/V3；P4 不新增迁移，回滚应用时保留新表，不反向删除。
- 每 5 秒推进配对和会话超时；配对终态保留 24 小时，会话保留 7 天，码在过期后额外保留 10 分钟。
- 启动会清空遗留在线状态，并用 `SERVER_RESTART` 终结遗留活动会话。
- 发布后检查 Flyway 版本为 3、Hibernate validate 成功、`/ws` Upgrade 返回 101，且后端 8080 无法从公网直接访问。P4 只增加 WebSocket 消息类型，不改变数据库结构；视频媒体不经过后端。
- 启动日志不得出现 `Pessimistic-lock SQL is rejected by the database` 或 `SQLSyntaxErrorException`（`for update of <alias>` 是 MySQL 8 语法，5.7 必须走 `MySQL57Dialect`）；再调用一次 `POST /api/v1/pairing/codes` 确认返回 200。
