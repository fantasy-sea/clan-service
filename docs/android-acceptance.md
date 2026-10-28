# 双 Android 联调验收

准备设备 A（控制端）和设备 B（被控端），各自注册并保存独立 deviceToken，然后都连接 `/ws` 完成 AUTH。

1. B 调用 `POST /api/v1/pairing/codes`，界面显示六位码和 60 秒倒计时。
2. A 调用 `POST /api/v1/pairing/verify` 提交该码；B 收到 `PAIRING_REQUEST`。
3. B 调用对应的 `/accept`；双方依次收到 `PAIRING_RESULT(ACCEPTED)` 和个性化 `PAIRING_SUCCEEDED`。双方刷新 `GET /api/v1/bindings`，确认对端摘要、在线状态、`boundAt` 和 `createdAt`；再用 `GET /api/v1/bindings/{对端 deviceId}` 校验单条绑定查询。A 用 `PATCH /api/v1/bindings/{对端 deviceId}` 提交新 `deviceName`，双方刷新后应看到同一个新名称。
4. A 调用 `POST /api/v1/sessions`，B 收到 `CONTROL_REQUEST`；B 接受后双方收到 `CONTROL_RESULT` 再收到 `SESSION_STARTED`。
5. A 发送 `WEBRTC_OFFER`，B 收到同一 SDP；B 发送 `WEBRTC_ANSWER`，A 收到同一 SDP；双方交换 `ICE_CANDIDATE`，空 `candidate` 表示 end-of-candidates。B 发送 `PERMISSION_STATUS`，A 收到 `{screenCapture,accessibility}`。
6. 任一方发送 `SESSION_CONNECTED`，双方都收到服务端 `SESSION_CONNECTED`；`GET /api/v1/sessions/{sessionId}` 显示 `CONNECTED`。重复发送不产生第二个事件。
7. A 发送 `CONTROL`（`TAP`/`SWIPE`/`BACK`/`HOME`/`RECENTS`），B 收到归一化坐标；B 发送 `CONTROL_RESULT(scope=COMMAND)`，A 收到。B 若发送 `CONTROL`，应收到 `ERROR CONTROL_NOT_ALLOWED`。任一方调用 `/end`，双方收到 `SESSION_ENDED`。

还需验证：

- 配对码过期、重放和自配对分别返回 410/409/409。
- 请求方在等待期间调用 `/cancel`；双方弹窗关闭、收到 `PAIRING_RESULT(REJECTED)`，且不产生绑定。
- 请求过期或任一方离线时，双方弹窗关闭并收到 `PAIRING_RESULT(EXPIRED)`。
- 第三方不能接受配对、查询/操作会话或删除别人的绑定。
- 同设备建立第二条 WebSocket 后旧连接收到 4001，且旧连接关闭不会把新连接标离线。
- 断网 30 秒内恢复时不产生上下线抖动；超过 30 秒后对端收到离线和会话终结事件。
- 重连后不期待事件重放，而是刷新绑定和按 sessionId 查询。
- 未进入 `CONNECTED` 时发送 `CONTROL` 返回 `SESSION_INVALID_STATE`；第三方设备向会话发送信令/控制返回 `SESSION_NOT_ALLOWED`；连接阶段超过 120 秒时双方收到 `SESSION_FAILED(CONNECT_TIMEOUT)`，此后发送 `SESSION_CONNECTED` 返回 `SESSION_CONNECT_TIMEOUT`。
- ICE 可能先于 offer/answer 到达，客户端必须自行缓冲到设置 remote description 之后。

本期验收覆盖后端 WebRTC 信令转发和远程控制消息；真实屏幕采集、视频编码/渲染和 AccessibilityService 触控注入属于客户端实现，需要在双真机联调时另行验收。TURN 独立部署，媒体不经过后端。
