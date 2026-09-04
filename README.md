# clan-service — 家庭远程助手后端

家庭远程助手（Family Remote Assistant）服务端，MVP 架构见 `后端设计1.txt`，
本仓库当前实现 **Phase 1：Device 模块**（见 `后端设计2.txt`）。

- 语言：Kotlin（设计文档为 Java，实现保持 Kotlin 惯例：DTO 用 data class、实体用普通 class）
- 框架：Spring Boot 4.1.1 / Spring Web MVC / Spring Data JPA / Hibernate / Flyway / Spring Validation
- 数据库：MySQL 8.x（时间列 DATETIME(6)，`ddl-auto=validate`，schema 只由 Flyway 管理）
- 实时通信：WebSocket（依赖已引入，Phase 1 不实现业务）、WebRTC（仅 Signaling，服务器不碰视频流）

## 模块结构

```text
src/main/kotlin/com/fantasysea/clanservice/
├── ClanServiceApplication.kt
├── common/
│   ├── config/WebMvcConfig.kt                    # 注册认证拦截器（/api/**，排除 register）
│   ├── exception/                                # BusinessException / ErrorCode / GlobalExceptionHandler
│   ├── response/ApiResponse.kt                   # 统一响应信封 { code, message, data }
│   ├── security/                                 # DeviceTokenService / 认证拦截器
│   └── util/UlidGenerator.kt                     # dev_{ULID} 生成器（Crockford Base32）
└── device/
    ├── controller/DeviceController.kt
    ├── dto/                                      # Register/Update 请求与 Device/Register 响应
    ├── entity/                                   # Device / DevicePlatform
    ├── repository/DeviceRepository.kt
    └── service/                                  # DeviceService / DeviceServiceImpl
src/main/resources/
├── application.yml
└── db/migration/V1__init.sql                     # 只建 devices 表
```

## 运行

前置：MySQL 5.7.25 与数据库 `clan_service` 已存在（Flyway 只建表，不建库）：

```sql
CREATE DATABASE IF NOT EXISTS clan_service CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

真实连接信息通过每台开发机自己的本地配置文件提供，仓库不提交任何真实凭据：

1. 复制模板为本地配置（该文件已被 `.gitignore` 忽略，永远不会上传 GitHub）：

   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```

2. 编辑 `application-local.yml`，把 `your-*` 占位值替换为真实的主机/端口/库名/用户/密码。
3. 启动时激活 local profile：

   ```bash
   # PowerShell
   $env:SPRING_PROFILES_ACTIVE='local'; .\mvnw.cmd spring-boot:run

   # bash / macOS
   SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
   ```

说明：`application-local.yml` 会覆盖 `application.yml` 中的占位默认值（localhost:3306 等），无需改动 `application.yml`；`MYSQL_HOST/PORT/DATABASE/USER/PASSWORD` 环境变量仍被支持，作为备选。注意 `application-local.yml` 构建时会打进 jar，请勿公开分发构建产物。

```bash
# 测试（H2 内存库，不依赖 MySQL）
./mvnw clean test        # Windows: .\mvnw.cmd clean test

# 启动（Flyway 自动迁移 V1，创建 flyway_schema_history + devices）
./mvnw spring-boot:run   # Windows: .\mvnw.cmd spring-boot:run
```

默认监听 **8081**（本机 8080 已被占用；可用 `SERVER_PORT` 环境变量覆盖）。
下文 curl 示例使用 `http://localhost:8081`。

## API

统一响应信封：成功 `{"code":0,"message":"success","data":{...}}`；失败 `{"code":40001,"message":"...","data":null}`。

### 注册设备（无需认证）

```bash
curl -X POST http://localhost:8081/api/v1/devices/register \
  -H "Content-Type: application/json" \
  -d '{"deviceName":"爸爸的手机","platform":"ANDROID","model":"Pixel 8","androidVersion":"16"}'
# => {"code":0,"message":"success","data":{"deviceId":"dev_01ARZ3N...","deviceToken":"xxxx"}}
```

`deviceToken` 只在注册时返回一次，客户端须安全保存；服务端只保存 SHA-256 哈希。

### 查询当前设备

```bash
curl http://localhost:8081/api/v1/devices/me -H "Authorization: Bearer <deviceToken>"
```

### 更新当前设备（PATCH，null 字段不修改）

```bash
curl -X PATCH http://localhost:8081/api/v1/devices/me \
  -H "Authorization: Bearer <deviceToken>" \
  -H "Content-Type: application/json" \
  -d '{"deviceName":"爸爸的新手机"}'
```

### 注销当前设备

```bash
curl -X DELETE http://localhost:8081/api/v1/devices/me -H "Authorization: Bearer <deviceToken>"
```

## 错误码（Phase 1）

| code | HTTP | 含义 |
|---|---|---|
| 40001 | 400 | 参数无效 / 请求体损坏 |
| 40002 | 401 | 设备令牌无效或缺失 |
| 40003 | 404 | 设备不存在 |
| 50001 | 500 | 服务器内部错误 |

后续 Phase 将按设计加入配对码（401xx）、绑定（402xx）、会话（403xx）等错误码。

## 安全约定

- 设备身份只来自 `Authorization: Bearer <token>`，绝不信任请求体中的 deviceId。
- 数据库不保存明文 token；比较用常量时间方法。
- `DeviceResponse` 不包含 deviceToken / deviceTokenHash。
- 角色（CONTROLLER/AGENT）不属于设备，属于未来的 remote_sessions 表。
