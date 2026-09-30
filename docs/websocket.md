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

## 服务端事件

| type | 说明 |
|---|---|
| `DEVICE_ONLINE/OFFLINE` | 仅发给当前绑定设备；payload `{deviceId,lastSeenAt}` |
| `PAIRING_REQUEST` | 请求方设备摘要和 `expiresAt` |
| `PAIRING_RESULT` | `ACCEPTED/REJECTED/EXPIRED` |
| `PAIRING_SUCCEEDED` | 接受后给双方发送各自对端的设备摘要 |
| `CONTROL_REQUEST` | 控制方设备摘要和 `expiresAt` |
| `CONTROL_RESULT` | `scope=SESSION_REQUEST`，结果为 `ACCEPTED/REJECTED/EXPIRED/FAILED` |
| `SESSION_STARTED` | 会话进入 `CONNECTING` 后发送 |
| `SESSION_ENDED/SESSION_FAILED` | 主动结束、离线、超时、解绑或重启终结 |
| `ERROR` | 通用协议错误，不回显原始帧或载荷 |

接受会话时，双方均按 `CONTROL_RESULT`、`SESSION_STARTED` 顺序收到事件。P4 会复用 `CONTROL_RESULT`，但 `scope=COMMAND`。

## 断线恢复

断开后立即把持久化在线状态设为 false，但延迟 30 秒广播离线和终结业务。宽限期内重连会取消终结任务并避免在线/离线抖动；该时段没有可投递旧连接，因此不能新建配对或会话。

重连只发送 `AUTH_SUCCESS`，不重放业务事件。客户端必须刷新绑定列表，并按已知 `sessionId` 查询会话进行对账；未收到的配对确认自然超时。
