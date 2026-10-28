# WebSocket 协议

## 连接和认证

统一连接 `/ws`。URL 中出现 `token`、`access_token` 或 `authorization` 查询参数会在握手阶段被拒绝。文本帧最大 64 KiB，二进制帧不支持。

连接后的第一帧必须在 5 秒内为：

```json
{"type":"AUTH","requestId":"auth-1","payload":{"deviceToken":"..."}}
```

消息统一为：

```json
{"type":"...","requestId":"可选","sessionId":"可选","timestamp":0,"payload":{}}
```

客户端 `timestamp` 不用于权限和超时判断。服务端使用 epoch millis。认证成功的 payload 包含 `deviceId`、20 秒心跳、60 秒空闲超时和 30 秒重连宽限。

认证失败只返回业务码和通用消息，然后以 1008 关闭。同设备新连接会原子替换旧连接，旧连接以应用码 4001 关闭。客户端每 20 秒发送 `PING`，服务端回复 `PONG` 并更新 `last_seen_at`。

## 客户端 → 服务端消息

| type | 说明 |
|---|---|
| `AUTH` / `PING` | 认证与心跳 |
| `SESSION_CONNECTED` | 任一方在 WebRTC 连通后上报；首次把会话推进到 `CONNECTED` |
| `WEBRTC_OFFER` / `WEBRTC_ANSWER` | WebRTC SDP，分别只能由控制方/被控方发送 |
| `ICE_CANDIDATE` | Trickle ICE，双方均可发送；空 `candidate` 表示 end-of-candidates |
| `CONTROL` | 控制指令，仅 `CONNECTED` 且控制方可发 |
| `CONTROL_RESULT` | 被控端回传命令执行结果，`scope` 固定为 `COMMAND` |
| `PERMISSION_STATUS` | 被控端回传投屏/无障碍权限状态 |

所有 P4 消息必须带 `sessionId`；`CONTROL` / `CONTROL_RESULT` 必须带 `requestId`。发送方身份只来自已认证连接，payload 内的任何 deviceId 都会被忽略。

## 服务端事件

| type | 说明 |
|---|---|
| `DEVICE_ONLINE/OFFLINE` | 仅发给当前绑定设备；payload `{deviceId,lastSeenAt}` |
| `PAIRING_REQUEST` | 请求方设备摘要和 `expiresAt` |
| `PAIRING_RESULT` | `ACCEPTED/REJECTED/EXPIRED` |
| `PAIRING_SUCCEEDED` | 接受后给双方发送各自对端的设备摘要 |
| `CONTROL_REQUEST` | 控制方设备摘要和 `expiresAt` |
| `CONTROL_RESULT` | `scope=SESSION_REQUEST` 时为 `ACCEPTED/REJECTED/EXPIRED/FAILED`；`scope=COMMAND` 时为被控端回传的命令结果 |
| `SESSION_STARTED` | 会话进入 `CONNECTING` 后发送 |
| `SESSION_CONNECTED` | 会话进入 `CONNECTED` 后发送给双方；payload `{connectedAt}` |
| `SESSION_ENDED/SESSION_FAILED` | 主动结束、离线、超时、解绑或重启终结 |
| `WEBRTC_OFFER/ANSWER` | 转发给对端；payload `{sdp}` |
| `ICE_CANDIDATE` | 转发给对端；payload `{candidate,sdpMid?,sdpMLineIndex?,usernameFragment?}` |
| `CONTROL` | 转发给被控端；payload 为归一化后的控制指令 |
| `PERMISSION_STATUS` | 转发给控制方；payload `{screenCapture,accessibility}` |
| `ERROR` | 通用协议错误，不回显原始帧或载荷 |

接受会话时，双方均按 `CONTROL_RESULT`、`SESSION_STARTED` 顺序收到事件。

## P4 状态与方向

| 消息 | 允许状态 | 发送方 | 接收方 |
|---|---|---|---|
| `SESSION_CONNECTED` | `CONNECTING`；`CONNECTED` 幂等 | 任一参与方 | 双方收到服务端事件 |
| `WEBRTC_OFFER` | `CONNECTING` / `CONNECTED` | `controllerDeviceId` | `controlledDeviceId` |
| `WEBRTC_ANSWER` | `CONNECTING` / `CONNECTED` | `controlledDeviceId` | `controllerDeviceId` |
| `ICE_CANDIDATE` | `CONNECTING` / `CONNECTED` | 任一参与方 | 对端 |
| `PERMISSION_STATUS` | `CONNECTING` / `CONNECTED` | `controlledDeviceId` | `controllerDeviceId` |
| `CONTROL` | `CONNECTED` | `controlDeviceId`（MVP = controller） | `controlledDeviceId` |
| `CONTROL_RESULT` | `CONNECTED` | `controlledDeviceId` | `controlDeviceId` |

`CONNECTED` 只由 `SESSION_CONNECTED` 触发，不因 offer/answer 交换成功自动置位。连接阶段 120 秒超时；超时后会话进入 `FAILED(CONNECT_TIMEOUT)` 并广播 `SESSION_FAILED`。

## P4 payload 示例

```json
{"type":"WEBRTC_OFFER","requestId":"sig-1","sessionId":"session_x","payload":{"sdp":"v=0..."}}
{"type":"ICE_CANDIDATE","sessionId":"session_x","payload":{"candidate":"candidate:1 1 UDP ...","sdpMid":"0","sdpMLineIndex":0}}
{"type":"PERMISSION_STATUS","sessionId":"session_x","payload":{"screenCapture":true,"accessibility":false}}
{"type":"CONTROL","requestId":"ctl-1","sessionId":"session_x","payload":{"action":"TAP","x":0.512,"y":0.438}}
{"type":"CONTROL","requestId":"ctl-2","sessionId":"session_x","payload":{"action":"SWIPE","startX":0.5,"startY":0.75,"endX":0.5,"endY":0.25,"duration":500}}
{"type":"CONTROL_RESULT","requestId":"ctl-1","sessionId":"session_x","payload":{"scope":"COMMAND","result":"OK"}}
```

- `TAP`：`x`/`y` 为 `0.0~1.0` 的有限数字。
- `SWIPE`：四个坐标同上，`duration` 为 `50~5000` 的整数。
- `BACK` / `HOME` / `RECENTS`：只发送 `action`。
- 服务端只转发已知字段，客户端附带的额外字段会被丢弃。

## 校验、限流与安全

- `sessionId` 长度 1–64；`requestId` 长度 1–128；`CONTROL` / `CONTROL_RESULT` 的 `requestId` 必填。
- SDP 非空且 UTF-8 ≤ 32 KiB；ICE `candidate` UTF-8 ≤ 4 KiB，允许空串；`sdpMid` ≤ 64；`sdpMLineIndex` 0–255；`usernameFragment` ≤ 256；`reason` ≤ 64。
- 控制类消息（`CONTROL`+`CONTROL_RESULT`+`PERMISSION_STATUS`）每设备 600 次/分钟；信令类（`OFFER`+`ANSWER`+`ICE`）每设备 120 次/分钟。超限返回 `ERROR RATE_LIMITED` 和 `retryAfter` 秒数。
- `ERROR` 的 `code` 使用业务错误码名称（如 `CONTROL_NOT_ALLOWED`、`SESSION_INVALID_STATE`、`SESSION_CONNECT_TIMEOUT`、`DEVICE_OFFLINE`）或 `UNSUPPORTED_MESSAGE`；不回显客户端 payload。
- 服务端不解析、不保存 SDP/ICE，不接收视频媒体；日志只记录设备/会话/事件类型/结果码，不记录 SDP、ICE 或控制参数。

## 断线恢复

断开后立即把持久化在线状态设为 false，但延迟 30 秒广播离线和终结业务。宽限期内重连会取消终结任务并避免在线/离线抖动；该时段没有可投递旧连接，因此不能新建配对或会话。

重连只发送 `AUTH_SUCCESS`，不重放业务事件。客户端必须刷新绑定列表，并按已知 `sessionId` 查询会话进行对账；未收到的配对确认自然超时。P4 消息（SDP/ICE/控制）没有离线缓存，重连后需要重新建立 WebRTC 或由客户端自行恢复。
