# 双 Android 联调验收

准备设备 A（控制端）和设备 B（被控端），各自注册并保存独立 deviceToken，然后都连接 `/ws` 完成 AUTH。

1. B 调用 `POST /api/v1/pairing/codes`，界面显示六位码和 60 秒倒计时。
2. A 调用 `POST /api/v1/pairing/verify` 提交该码；B 收到 `PAIRING_REQUEST`。
3. B 调用对应的 `/accept`；双方依次收到 `PAIRING_RESULT(ACCEPTED)` 和个性化 `PAIRING_SUCCEEDED`。双方刷新 `GET /api/v1/bindings`，确认对端摘要、在线状态和 `boundAt`。
4. A 调用 `POST /api/v1/sessions`，B 收到 `CONTROL_REQUEST`；B 接受后双方收到 `CONTROL_RESULT` 再收到 `SESSION_STARTED`。任一方调用 `/end`，双方收到 `SESSION_ENDED`。

还需验证：

- 配对码过期、重放和自配对分别返回 410/409/409。
- 第三方不能接受配对、查询/操作会话或删除别人的绑定。
- 同设备建立第二条 WebSocket 后旧连接收到 4001，且旧连接关闭不会把新连接标离线。
- 断网 30 秒内恢复时不产生上下线抖动；超过 30 秒后对端收到离线和会话终结事件。
- 重连后不期待事件重放，而是刷新绑定和按 sessionId 查询。

P4 的步骤（WebRTC offer/answer/ICE、TURN、屏幕媒体和远程输入）不属于本期验收。
