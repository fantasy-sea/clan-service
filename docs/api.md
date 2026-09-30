# REST 契约、状态机与错误码

## 公共规则

- 除注册外，所有 `/api/**` 请求都必须携带 `Authorization: Bearer <deviceToken>`。
- 成功始终为 HTTP 200：`{"code":0,"message":"success","data":...}`。
- 失败使用对应 HTTP 状态：`{"code":业务码,"message":"通用消息","data":null}`。
- REST 时间使用 UTC ISO-8601。业务 ID 为不可解析的 `pairreq_{ULID}`、`session_{ULID}`。
- 限流响应为 429，并在 HTTP `Retry-After` 中给出秒数。

## 接口

| 接口 | 请求/响应 `data` |
|---|---|
| `POST /api/v1/pairing/codes` | `{pairingCode,expiresAt}`；六位码有效 60 秒 |
| `POST /api/v1/pairing/verify` | 请求 `{pairingCode}`；响应 `{requestId,expiresAt}` |
| `POST /api/v1/pairing/requests/{requestId}/accept\|reject` | `{requestId,status,expiresAt,handledAt}` |
| `GET /api/v1/bindings` | `[{deviceId,deviceName,platform,model,androidVersion,online,lastSeenAt,boundAt}]` |
| `DELETE /api/v1/bindings/{deviceId}` | `null` |
| `POST /api/v1/sessions` | 请求 `{targetDeviceId}`；响应完整 `SessionResponse` |
| `GET /api/v1/sessions/{sessionId}` | 仅会话双方可读 |
| `POST /api/v1/sessions/{sessionId}/accept\|reject\|end` | 更新后的 `SessionResponse` |

`SessionResponse` 固定包含：

```text
sessionId, controllerDeviceId, controlledDeviceId, status,
expiresAt, createdAt, acceptedAt?, connectExpiresAt?, connectedAt?,
rejectedAt?, endedAt?, failedAt?, failureReason?
```

## 状态机

配对请求：

```text
PENDING -> ACCEPTED
        -> REJECTED
        -> EXPIRED
```

只有目标设备可以接受或拒绝。接受会原子创建规范化绑定；重复处理不会恢复配对码。

远程会话：

```text
REQUESTING -> CONNECTING -> CONNECTED (P4 预留)
     |            |            |
     +-> REJECTED +-----------> ENDED
     +------------------------> FAILED
```

创建会话要求双方已绑定、都有可投递 WebSocket，且任一方都没有活动会话。只有被控端可接受/拒绝；任一参与方可结束非终态会话。请求阶段 60 秒超时，连接阶段 120 秒超时。

## 错误码

| code | HTTP | 名称 |
|---:|---:|---|
| 40001 | 400 | `INVALID_PARAMETER` |
| 40002 | 401 | `INVALID_DEVICE_TOKEN` |
| 40003 | 404 | `DEVICE_NOT_FOUND` |
| 40004 | 409 | `DEVICE_OFFLINE` |
| 40005 | 429 | `RATE_LIMITED` |
| 40101 | 400 | `PAIRING_CODE_INVALID` |
| 40102 | 410 | `PAIRING_CODE_EXPIRED` |
| 40103 | 409 | `PAIRING_CODE_USED` |
| 40104 | 409 | `SELF_PAIRING` |
| 40105 | 404 | `PAIRING_REQUEST_NOT_FOUND` |
| 40106 | 410 | `PAIRING_REQUEST_EXPIRED` |
| 40107 | 409 | `PAIRING_REQUEST_ALREADY_HANDLED` |
| 40108 | 403 | `PAIRING_REQUEST_NOT_ALLOWED` |
| 40109 | 409 | `PAIRING_REQUEST_ALREADY_EXISTS` |
| 40201 | 404 | `BINDING_NOT_FOUND` |
| 40202 | 409 | `BINDING_ALREADY_EXISTS` |
| 40301 | 404 | `SESSION_NOT_FOUND` |
| 40302 | 409 | `SESSION_INVALID_STATE` |
| 40303 | 403 | `SESSION_NOT_ALLOWED` |
| 40304 | 409 | `SESSION_CONFLICT` |
| 40305 | 409 | `DEVICES_NOT_BOUND` |
| 40306 | 410 | `SESSION_REQUEST_EXPIRED` |
| 40307 | 403 | `CONTROL_NOT_ALLOWED`（P4 预留） |
| 50001 | 500 | `INTERNAL_ERROR` |

限流：注册为每 IP 5 次/小时；配对验证为每设备 5 次/分钟且每 IP 20 次/分钟；WebSocket AUTH 为每 IP 10 次/分钟。
