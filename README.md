# clan-service — 家庭远程助手后端

`clan-service` 是双 Android 设备远程协助的单实例后端。本期已实现 P0–P4：设备、配对、绑定、在线状态、远程会话协商，以及 WebRTC 信令（offer/answer/ICE）、会话 `CONNECTED` 状态和远程控制消息转发。服务端只做信令与控制转发，视频媒体仍由两端 WebRTC 直接传输（必要时经独立 TURN）。

仓库根目录的早期设计稿仅作历史参考；事件名和对外契约以本 README 及 `docs/` 为准。

## 技术与约束

- Kotlin 2.3、Java 21 字节码目标（本机使用 Java 25 构建验证）、Spring Boot 4.1、Spring Web MVC、原生 WebSocket
- Spring Data JPA、Hibernate（`ddl-auto=validate`）、Flyway V1–V3
- MySQL 5.7，所有持久化时间为 UTC `DATETIME(6)`；Hibernate 7 默认方言会生成 MySQL 8 才支持的 `FOR UPDATE OF <alias>`，必须显式使用 `MySQL57Dialect`（见 `application.yml`），否则所有悲观锁查询（配对码生成、WebSocket 认证等）都会 1064
- 单实例内存连接表与限流器；禁止横向扩容，不使用 STOMP、Redis、队列或离线推送
- REST 成功统一返回 HTTP 200 和 `{code,message,data}`；受保护接口使用 `Authorization: Bearer <deviceToken>`

详细契约：

- [REST、状态机与错误码](docs/api.md)
- [WebSocket 协议](docs/websocket.md)
- [WebRTC 信令与控制协议](docs/webrtc.md)
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
| 取消配对 | `POST /api/v1/pairing/requests/{requestId}/cancel` |
| 绑定列表/查询/改名/解绑 | `GET /api/v1/bindings`、`GET/PATCH/DELETE /api/v1/bindings/{deviceId}` |
| 会话 | `POST /api/v1/sessions`、`GET /api/v1/sessions/{sessionId}` |
| 会话操作 | `POST /api/v1/sessions/{sessionId}/accept\|reject\|end` |
| 实时连接 | `ws://localhost:8080/ws` |
| P4 信令/控制 | WebSocket `WEBRTC_OFFER/ANSWER`、`ICE_CANDIDATE`、`SESSION_CONNECTED`、`CONTROL`、`CONTROL_RESULT`、`PERMISSION_STATUS`，见 `docs/websocket.md` |

设备令牌仅在注册时返回一次，数据库只存 SHA-256。配对码明文也不入库，只保存 HMAC-SHA256；日志不得写入 Authorization、AUTH payload、完整 WebSocket 帧、配对码、SDP、ICE 或控制参数。

## P4 实时能力

- `WEBRTC_OFFER` / `WEBRTC_ANSWER` / `ICE_CANDIDATE`：服务端只校验会话、方向和大小后转发，不解析、不保存 SDP/ICE。
- `SESSION_CONNECTED`：任一参与方在 WebRTC 真正连通后发送；服务端把会话从 `CONNECTING` 推进到 `CONNECTED`，并向双方广播。重复发送幂等。
- `CONTROL`：仅 `CONNECTED` 且控制方（MVP = `controllerDeviceId`）可发；支持 `TAP/SWIPE/BACK/HOME/RECENTS`，坐标归一化到 `0.0~1.0`。
- `CONTROL_RESULT` / `PERMISSION_STATUS`：仅被控端可回传，分别用于命令执行结果和权限状态展示。
- 服务端不接收、不保存、不转发视频媒体；不使用 WebSocket 二进制帧；TURN 独立部署。
- 控制/信令按设备限流，错误统一返回 `ERROR`，不回显 payload。未知 WebSocket 消息只返回通用协议错误。

协议细节、方向矩阵和验收步骤见 [WebRTC 信令与控制协议](docs/webrtc.md)。
