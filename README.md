# clan-service — 家庭远程助手后端

`clan-service` 是双 Android 设备远程协助的单实例后端。本期已实现 P0–P3：设备、配对、绑定、在线状态和远程会话协商；WebRTC、TURN、远程输入及命令消息属于 P4，暂未实现。

仓库根目录的早期设计稿仅作历史参考；事件名和对外契约以本 README 及 `docs/` 为准。

## 技术与约束

- Kotlin 2.3、Java 21 字节码目标（本机使用 Java 25 构建验证）、Spring Boot 4.1、Spring Web MVC、原生 WebSocket
- Spring Data JPA、Hibernate（`ddl-auto=validate`）、Flyway V1–V3
- MySQL 5.7，所有持久化时间为 UTC `DATETIME(6)`
- 单实例内存连接表与限流器；禁止横向扩容，不使用 STOMP、Redis、队列或离线推送
- REST 成功统一返回 HTTP 200 和 `{code,message,data}`；受保护接口使用 `Authorization: Bearer <deviceToken>`

详细契约：

- [REST、状态机与错误码](docs/api.md)
- [WebSocket 协议](docs/websocket.md)
- [生产部署与 TLS](docs/deployment.md)
- [双 Android 联调验收](docs/android-acceptance.md)

## 本地运行

需要 JDK 21+、MySQL 5.7 和数据库 `clan_service`：

```sql
CREATE DATABASE IF NOT EXISTS clan_service
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

复制本地配置模板并填写数据库连接和至少 32 UTF-8 字节的 HMAC 密钥：

```powershell
Copy-Item src/main/resources/application-local.example.yml src/main/resources/application-local.yml
$env:SPRING_PROFILES_ACTIVE='local'
.\mvnw.cmd spring-boot:run
```

默认监听 `8080`，可通过 `SERVER_PORT` 覆盖。Flyway 会依次执行 V1–V3；不要修改已经应用的迁移。

```powershell
# 快速测试（H2 仅用于上下文和轻量测试）
.\mvnw.cmd test

# 包括可用 Docker 时的 MySQL 5.7 集成测试
.\mvnw.cmd verify
```

## 核心入口

| 功能 | 方法与路径 |
|---|---|
| 注册设备 | `POST /api/v1/devices/register` |
| 当前设备 | `GET/PATCH/DELETE /api/v1/devices/me` |
| 生成/验证配对码 | `POST /api/v1/pairing/codes`、`POST /api/v1/pairing/verify` |
| 接受/拒绝配对 | `POST /api/v1/pairing/requests/{requestId}/accept\|reject` |
| 绑定列表/解绑 | `GET /api/v1/bindings`、`DELETE /api/v1/bindings/{deviceId}` |
| 会话 | `POST /api/v1/sessions`、`GET /api/v1/sessions/{sessionId}` |
| 会话操作 | `POST /api/v1/sessions/{sessionId}/accept\|reject\|end` |
| 实时连接 | `ws://localhost:8080/ws` |

设备令牌仅在注册时返回一次，数据库只存 SHA-256。配对码明文也不入库，只保存 HMAC-SHA256；日志不得写入 Authorization、AUTH payload、完整 WebSocket 帧、配对码或未来的 SDP/ICE/控制参数。

## P4 边界

当前保留 `CONNECTED` 状态和 `CONTROL_RESULT.scope=COMMAND` 契约位置，但不接受 P4 消息。未知 WebSocket 消息只返回通用协议错误且不会回显载荷。
