# WebRTC 信令与控制协议

## 范围

P4 的“视频流”指建立 WebRTC 视频会话所需的信令和会话状态；后端不接收、不保存、不转发音视频媒体。媒体由两台设备（必要时经独立 TURN）直接传输。

```text
Controller ── SDP/ICE over /ws ── Spring Boot ── SDP/ICE over /ws ── Agent
     │                                                                  │
     └══════════════════════ WebRTC 媒体（P2P 或 TURN）══════════════════┘
```

服务端职责：

- 校验会话存在、状态正确、发送方属于会话且方向正确；
- 校验 SDP/ICE 类型和大小，不解析内容；
- 转发给对端，失败时返回 `ERROR`；
- 维护 `CONNECTING -> CONNECTED` 状态。

## 时序

```text
Controller                  Server                    Agent
    │ POST /sessions           │                        │
    │─────────────────────────►│ CONTROL_REQUEST        │
    │                          │───────────────────────►│
    │                          │◄────────── accept ─────│
    │◄─ CONTROL_RESULT ────────│                        │
    │◄─ SESSION_STARTED ───────│───────────────────────►│
    │                          │                        │
    │ WEBRTC_OFFER             │                        │
    │─────────────────────────►│ WEBRTC_OFFER           │
    │                          │───────────────────────►│
    │                          │◄────── WEBRTC_ANSWER ──│
    │◄────── WEBRTC_ANSWER ────│                        │
    │                          │                        │
    │ ICE_CANDIDATE            │                        │
    │─────────────────────────►│ ICE_CANDIDATE          │
    │                          │───────────────────────►│
    │◄─────────────────────────│◄────── ICE_CANDIDATE ──│
    │                          │                        │
    │ SESSION_CONNECTED        │                        │
    │─────────────────────────►│                        │
    │◄─ SESSION_CONNECTED ─────│──── SESSION_CONNECTED ─│
    │                          │                        │
    │ CONTROL                  │                        │
    │─────────────────────────►│ CONTROL                │
    │                          │───────────────────────►│
    │◄────── CONTROL_RESULT ───│◄────── CONTROL_RESULT ─│
```

`SESSION_CONNECTED` 由任一参与方在 `RTCPeerConnection` 真正连通后发送；服务端只认第一次，重复发送幂等。`CONNECTED` 不会因为 offer/answer 交换成功自动置位。

## 消息

### SESSION_CONNECTED

```json
{"type":"SESSION_CONNECTED","sessionId":"session_x","payload":{}}
```

- 允许状态：`CONNECTING`；`CONNECTED` 幂等。
- 发送方：任一参与方。
- 服务端事件：给双方发送 `{"type":"SESSION_CONNECTED","sessionId":"session_x","payload":{"connectedAt":"..."}}`。
- 连接截止时间（accept 后 120 秒）已过：会话进入 `FAILED(CONNECT_TIMEOUT)`，双方收到 `SESSION_FAILED`，发送方收到 `ERROR SESSION_CONNECT_TIMEOUT`。
- 对端不可达：会话进入 `FAILED(DEVICE_OFFLINE)`，双方收到 `SESSION_FAILED`，发送方收到 `ERROR DEVICE_OFFLINE`。

### WEBRTC_OFFER / WEBRTC_ANSWER

```json
{"type":"WEBRTC_OFFER","requestId":"sig-1","sessionId":"session_x","payload":{"sdp":"v=0..."}}
```

```json
{"type":"WEBRTC_ANSWER","requestId":"sig-2","sessionId":"session_x","payload":{"sdp":"v=0..."}}
```

- 允许状态：`CONNECTING` / `CONNECTED`（支持重新协商）。
- `OFFER` 只能由 `controllerDeviceId` 发送，转发给 `controlledDeviceId`。
- `ANSWER` 只能由 `controlledDeviceId` 发送，转发给 `controllerDeviceId`。
- `sdp` 必须是字符串，非空，UTF-8 ≤ 32 KiB；服务端不解析 SDP。
- 方向错误返回 `ERROR SESSION_NOT_ALLOWED`。

### ICE_CANDIDATE

```json
{
  "type": "ICE_CANDIDATE",
  "requestId": "ice-1",
  "sessionId": "session_x",
  "payload": {
    "candidate": "candidate:1 1 UDP 1 192.0.2.1 5000 typ host",
    "sdpMid": "0",
    "sdpMLineIndex": 0,
    "usernameFragment": "abcd"
  }
}
```

- 允许状态：`CONNECTING` / `CONNECTED`。
- 双方均可发送，服务端转发给对端。
- `candidate` 允许空串（end-of-candidates）；UTF-8 ≤ 4 KiB。
- `sdpMid` ≤ 64；`sdpMLineIndex` 0–255；`usernameFragment` ≤ 256；均可省略。
- ICE 可能先于 offer/answer 到达。服务端按到达顺序转发，客户端必须在设置 remote description 前缓冲候选。

### CONTROL

```json
{"type":"CONTROL","requestId":"ctl-1","sessionId":"session_x","payload":{"action":"TAP","x":0.512,"y":0.438}}
```

```json
{"type":"CONTROL","requestId":"ctl-2","sessionId":"session_x","payload":{"action":"SWIPE","startX":0.5,"startY":0.75,"endX":0.5,"endY":0.25,"duration":500}}
```

```json
{"type":"CONTROL","requestId":"ctl-3","sessionId":"session_x","payload":{"action":"BACK"}}
```

- 允许状态：`CONNECTED`。
- 发送方：`controlDeviceId`（MVP = `controllerDeviceId`）；接收方：`controlledDeviceId`。
- `TAP`：`x`/`y` 为 `0.0~1.0` 的有限数字。
- `SWIPE`：四个坐标同上，`duration` 为 `50~5000` 的整数。
- `BACK` / `HOME` / `RECENTS`：只允许 `action`。
- 服务端只转发已知字段，额外字段被丢弃；非法 action/坐标/时长返回 `ERROR INVALID_PARAMETER`。
- 坐标由被控端映射到实际屏幕像素；服务端不关心分辨率。

### CONTROL_RESULT

```json
{"type":"CONTROL_RESULT","requestId":"ctl-1","sessionId":"session_x","payload":{"scope":"COMMAND","result":"OK"}}
```

- 允许状态：`CONNECTED`。
- 发送方：`controlledDeviceId`；接收方：`controlDeviceId`。
- `scope` 必须为 `COMMAND`；`result` 为 `OK` 或 `FAILED`；`reason` 可选，≤ 64。
- 服务端不保存命令 ID，客户端用 `requestId` 关联请求与结果。
- 服务端拒绝/投递失败时返回 `ERROR`（同一个 `requestId`），不伪造 `CONTROL_RESULT`。

### PERMISSION_STATUS

```json
{"type":"PERMISSION_STATUS","sessionId":"session_x","payload":{"screenCapture":true,"accessibility":false}}
```

- 允许状态：`CONNECTING` / `CONNECTED`。
- 发送方：`controlledDeviceId`；接收方：`controllerDeviceId`。
- 两个字段都必须是布尔值；服务端只转发，不缓存、不据此拦截控制。
- 控制方 UI 根据该状态提示“屏幕共享/远程操作”是否可用；真正的权限由系统授予，被控端负责执行侧校验。

## 方向与状态矩阵

| 消息 | REQUESTING | CONNECTING | CONNECTED | 终态 |
|---|---|---|---|---|
| `SESSION_CONNECTED` | `SESSION_INVALID_STATE` | 推进 `CONNECTED` | 幂等成功 | `SESSION_INVALID_STATE` |
| `WEBRTC_OFFER/ANSWER` | `SESSION_INVALID_STATE` | 转发 | 转发 | `SESSION_INVALID_STATE` |
| `ICE_CANDIDATE` | `SESSION_INVALID_STATE` | 转发 | 转发 | `SESSION_INVALID_STATE` |
| `PERMISSION_STATUS` | `SESSION_INVALID_STATE` | 转发 | 转发 | `SESSION_INVALID_STATE` |
| `CONTROL` | `SESSION_INVALID_STATE` | `SESSION_INVALID_STATE` | 转发 | `SESSION_INVALID_STATE` |
| `CONTROL_RESULT` | `SESSION_INVALID_STATE` | `SESSION_INVALID_STATE` | 转发 | `SESSION_INVALID_STATE` |

非参与方统一 `SESSION_NOT_ALLOWED`；非控制方发送 `CONTROL` 返回 `CONTROL_NOT_ALLOWED`。

## 错误与限制

| code | 场景 |
|---|---|
| `INVALID_PARAMETER` | payload/`sessionId`/`requestId` 不合法、SDP/ICE 超限 |
| `SESSION_NOT_FOUND` | 会话不存在 |
| `SESSION_NOT_ALLOWED` | 非参与方或方向错误 |
| `CONTROL_NOT_ALLOWED` | 非控制方发送 `CONTROL` |
| `SESSION_INVALID_STATE` | 当前状态不允许该消息 |
| `SESSION_CONNECT_TIMEOUT` | `SESSION_CONNECTED` 晚于连接截止时间 |
| `DEVICE_OFFLINE` | 对端当前不可投递 |
| `RATE_LIMITED` | 超过限流；payload 带 `retryAfter` 秒数 |
| `UNSUPPORTED_MESSAGE` | 未知 type |
| `INTERNAL_ERROR` | 未预期异常；只返回通用文案 |

- 控制类消息每设备 600 次/分钟；信令类每设备 120 次/分钟。
- 整帧上限 64 KiB；SDP 上限 32 KiB；ICE candidate 上限 4 KiB。
- `ERROR` 不回显 payload；服务端日志不记录 SDP/ICE/控制参数。

## STUN / TURN

STUN/TURN 独立于 Spring Boot 部署。后端不代理媒体、不下发 TURN 凭据；客户端在创建 `RTCPeerConnection` 时配置 ICE servers。生产环境建议使用带鉴权的 TURN（如 coturn 的长期凭据或临时凭据方案），并限制中继带宽。

## 客户端责任

- 认证后按 `sessionId` 维护会话状态，收到 `SESSION_ENDED/SESSION_FAILED` 立即停止 WebRTC 和触控。
- 收到 offer/answer 前缓冲 ICE candidate。
- 只在 `CONNECTED` 后发送 `CONTROL`；`PERMISSION_STATUS` 只由被控端发送。
- 重连不期待事件重放：按 `sessionId` 查询会话并重新建立 PeerConnection。
- 媒体采集/编码/渲染和 AccessibilityService 触控注入由客户端实现，服务端不参与。
