# Freesia NeoForge (Velocity) — 1.0.3-diagnostic

类 **Freesia II** 的 YSM 代理插件，面向 **NeoForge 1.21.1 + YSM 2.6.5**。

它把 `yes_steve_model` 流量从业务后端改道到一个专门的 **Worker 节点**，并在两侧做实体 ID 重映射，
从而绕开 YSM 模型同步对「服务端直连 Netty 连接」的依赖。

> 纯转发插件修不了 YSM 模型同步：YSM 用服务端 `connection.channel().unsafe().outboundBuffer()`
> 加严格连接处理器类名检查做模型流控。本插件复刻 Freesia II 的
> 「代理终结协议 + 路由到 Worker + 实体 ID 重映射」思路。

- 版本：**`1.0.3-diagnostic`**（与 `src/main/resources/velocity-plugin.json` 一致）
- 产物：`build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar`
- 编译目标：Java 21，Velocity API `3.3.0-SNAPSHOT`，PacketEvents **`2.13.0`**

---

## 1. 本版定位

1.0.3 是**连接诊断版**：在 1.0.2 的切服/视野恢复能力之上，加入完整的连接阶段日志，
便于定位「Worker 到底卡在哪一步」。它**不声称已修好实际 Worker 连接**。

开发者的原话（`1.0.3诊断版使用说明.md`）：

> 尚未连接您的运行服务器。本版用于查明卡点，不能声称已修好实际 Worker 连接。

---

## 2. 架构

```
真实 NeoForge 客户端 (YSM 2.6.5)
        │  Minecraft 协议
        ▼
┌───────────────────────────────────────────────────────────┐
│                      Velocity 代理                         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  freesianeo 插件 (1.0.3-diagnostic)                  │  │
│  │   · 注册并拦截 yes_steve_model:2_6_0（两向 handled） │  │
│  │   · 每个玩家一条 MapperSession（MCPRotocolLib 假客户端）│ │
│  │   · 按 UUID 缓存模型，投递时按接收者实体 ID 重写      │  │
│  │   · PacketEvents 只读副本前缀取实体 ID               │  │
│  │   · [Freesia-DIAG] 阶段日志                          │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────┬──────────────────────────────┬─────────────┘
     MCPRotocolLib 假客户端连接              正常 MC 协议
               ▼                               ▼
   ┌───────────────────────┐       ┌────────────────────────┐
   │ Worker 节点            │       │ 业务后端子服 (NeoForge) │
   │ NeoForge 1.21.1        │       │ 玩家实际游玩位置         │
   │ + YSM 2.6.5            │       │ 不对外同步模型          │
   │ + freesianeoforgeworker│       │ (NeoVelocity 转发)      │
   └───────────────────────┘       └────────────────────────┘
```

---

## 3. 源码结构

```
src/main/java/com/nguyendevs/freesia/neoforge/
├── FreesiaNeoForge.java        主类：装配、拦截插件消息、观察实体包、安排补发
├── FreesiaConfig.java          config.properties（worker_address / debug）
├── YsmConstants.java           通道 yes_steve_model:2_6_0 + 包 ID
├── mapper/
│   ├── MapperManager.java      会话表、UUID 模型缓存、View 可见性表、tracking revision
│   └── MapperSession.java      假客户端连 Worker + 诊断状态机
├── proxy/
│   └── YsmPacketProxy.java     S2C 负载改写/路由决策
└── util/
    ├── BufUtil.java            VarInt / UTF / UUID 读写
    └── EntityPacketReader.java 只读 1.21.1 原生包最小前缀（本版新增）
src/test/java/
└── SwitchRegressionTest.java   45 项本地回归检查（见 §7）
```

---

## 4. 相对 1.0.0 的关键修复

1.0.0 的缺陷在 `analysis/切服模型恢复默认分析.md` 中有完整证据链。本版逐条修正：

| 1.0.0 问题 | 1.0.3 做法 |
|---|---|
| 模型缓存键是**后端实体 ID**，切服后 ID 变化即失效 | 缓存键改为**玩家 UUID**；实体 ID 在**投递时**按接收者当前可见映射写入（`rewriteEntity`） |
| 动画包 `21` 与模型包 `4` 共用缓存写入，动画**覆盖**模型 | 只有 `S2C_SET_MODEL (4)` 写模型缓存；动画不再污染 |
| 只处理 `SPAWN_PLAYER`，1.21.1 实际走 `SPAWN_ENTITY` | 同时支持 `SPAWN_ENTITY` 与 `SPAWN_PLAYER`，按 **UUID** 匹配在线代理玩家，故无需解码模组实体类型注册表 |
| 切服后无恢复路径，旧缓存无法重放 | `JOIN_GAME` / `RESPAWN` 后按 `100/500/1500/3000ms` 有限重试重放；每次都读**最新**模型 |
| 快速连切时旧延迟任务污染新世界 | `View` 令牌 + 世界代次校验；`CONFIGURATION_START` 使旧世界整体失效 |
| 广播遍历 `getAllPlayers()`，跨子服串号 | 只向**同子服**且已进入 PLAY 的接收者发送；每个接收者**各自**重写实体 ID |
| 未知 Worker 实体 ID 原样透传 | 未知实体**不再下发**；已知 UUID 但客户端未就绪的模型仍留在缓存 |
| Worker 旧连接断开回调会删掉替代会话 | `removeSession` 改为条件删除（`sessions.remove(uuid, session)`） |
| `broadcastToOthers` 造成 N² 重复广播 | 模型变更时唤醒所有可见接收者，并做去重 |
| 无连接诊断能力 | 新增 `[Freesia-DIAG]` 阶段日志与 `STATUS` 状态机（§6） |

### 4.1 实体包观察改为只读副本

1.0.0 用 PacketEvents 的 `WrapperPlayServerJoinGame` / `WrapperPlayServerSpawnPlayer` 解析实体 ID。
本版改用 `EntityPacketReader`，**只读副本的最小前缀**：

```java
public static Spawn spawn(ByteBuf packet) {
    ByteBuf in = packet.duplicate();          // duplicate：不动原 readerIndex
    int id = BufUtil.readVarInt(in);
    return new Spawn(id, new UUID(in.readLong(), in.readLong()));
}
public static int join(ByteBuf packet) { return packet.duplicate().readInt(); }
```

这样**不创建实体 wrapper、不解码模组实体类型**，避免本插件引入 `null entityType` 再编码异常；
回归测试专门验证了「读取模组未知实体类型后原字节与 readerIndex 均不被修改」。

---

## 5. YSM 线协议

对 `ysm-2.6.5-neoforge+mc1.21.1-release.jar` 反编译确认：

- 通道：`yes_steve_model:2_6_0`（**下划线**形式）
- 协议版本串：`2.6.0`
- 负载 = `VarInt 判别符` + 消息体

| ID | 方向 | 常量 | 说明 |
|---|---|---|---|
| 1 | S→C | `S2C_MODEL_SYNC` | 模型二进制 |
| 2 | C→S | `C2S_MODEL_SYNC` | 模型二进制 |
| 3 | S→C | `S2C_MOLANG` | 实体 ID 列表 + 表达式 |
| 4 | S→C | `S2C_SET_MODEL` | 实体 ID + 模型 + 贴图 + 状态（**唯一**写缓存） |
| 5 | C→S | `C2S_SWITCH_MODEL` | 切换模型请求 |
| 7 | C→S | `C2S_ANIMATION` | 实体 ID + 动画 |
| 17 | C→S | `C2S_MOLANG_REQ` | 表达式 + 实体 ID |
| 21 | S→C | `S2C_ANIMATION` | 实体 ID + 动画/状态 |
| 51 | S→C | `S2C_HANDSHAKE` | 服务端下发版本 |
| 52 | C→S | `C2S_HANDSHAKE` | 客户端回报版本 |

### 处理规则（`YsmPacketProxy`）

| 包 | 处理 |
|---|---|
| `4` / `21` | 读出 Worker 实体 ID → `routeEntityPacket` 路由。**不**在此处改写，改由 `MapperManager.send` 按目标客户端重写；返回 `DROP`（避免与路由重复发送） |
| `3` | 逐实体映射；**过滤**接收者不可见的实体；无剩余则丢弃；有剩余则重编码并保留表达式正文 |
| `51` / `52` | 标记握手状态，正文透传 |
| 畸形负载 | 捕获 `RuntimeException` → **丢弃**并告警，不让异常逃出解析器 |

**C2S 仍只做透传**（含 `5` 模型切换请求），未做反向实体 ID 改写 —— 见 §8。

---

## 6. 诊断日志（本版核心）

一次成型输出，默认开启，**不需要** `debug=true`：

| 日志 | 含义 |
|---|---|
| `Enabled. Worker = 主机:端口` | 当前实际使用的 Worker 地址，先确认配置 |
| `BACKEND_CONNECTED` | 玩家已连上业务子服，即将创建/复用 Worker 会话 |
| `Worker CONNECTING` | 开始连接 Worker，含玩家与目标地址 |
| `Worker TCP_CONNECTED` | **仅** TCP 已建立，不能证明 Minecraft/YSM 登录成功 |
| `WORKER_RX` / `WORKER_TX` | 登录与配置阶段实际收发包的**类名**（不记录正文与凭据） |
| `Worker PLAY_READY` | 收到 Worker 进入游戏包，记录 Worker 实体 ID |
| `YSM_WORKER_RX packetId=51` | 收到 Worker 的 YSM 握手 |
| `YSM_CLIENT_SEND ... accepted=true` | Velocity **接受**了发送请求，不等价于客户端已处理 |
| `YSM_CLIENT_RX packetId=52` | 收到真实客户端的 YSM 握手回复 |
| `YSM_WORKER_TX_CONFIRMED` | MCProtocolLib 触发发送完成回调，仍不等价于 Worker 已处理 |
| `YSM_C2S_NOT_SENT` | 客户端发来 YSM 包时 Worker 连接不存在或已断开 |
| `Worker PACKET_ERROR` | Worker 网络包处理异常，附堆栈 |
| `Worker DISCONNECTED` | 断开原因、最后阶段、最后收发包与底层异常 |

会话分配/复用后 **10 秒、30 秒**输出 `STATUS`（玩家离线或会话已断开则不再输出）。
`stage` 状态机：

| stage | 含义 |
|---|---|
| `TCP_CONNECT` | 尚未观察到 TCP 建立 → 结合 `UnknownHostException` / `ConnectException` / 超时判断 |
| `MINECRAFT_LOGIN_OR_CONFIGURATION` | TCP 已建，但未收到进入游戏包 → 看最后的 `WORKER_RX/TX` 与 Worker 拒绝原因 |
| `WORKER_YSM_HANDSHAKE` | 已进入 Worker，未收到 YSM `51` → 检查 Worker 的 YSM 加载与通道版本 |
| `CLIENT_YSM_REPLY` | Worker 已发握手，但未见客户端 `52` → 检查客户端、代理转发、业务服连接是否被 PacketEvents 中断 |
| `YSM_HANDSHAKE_OBSERVED` | 两向握手都已观察到；**不代表**资源同步与模型应用成功 |

> 若完全没有 `BACKEND_CONNECTED`，说明尚未走到创建 Worker 会话的入口 ——
> **不要**仅凭这一点判断 Worker 地址不可达。

### 调试用（`debug=true`）

| 日志 | 含义 |
|---|---|
| `New client world ... self entity <id>` | 进入新世界的自身实体 ID |
| `Restored N model(s)` | 补发数量。**N=0** 表示无可恢复缓存/可见映射；**N>0 只代表发送调用成功** |
| `Track <uuid> -> <player> entity=<id> revision=<n>` | 进入视野并分配追踪代次 |
| `Retrack model ... cached= sent=` | 追踪重试结果，`sent` 不代表客户端已应用 |
| `Dropped unmapped Worker entity <id> packet <id>` | 未知 Worker 实体，已丢弃 |

---

## 7. 构建与验证

```shell
cd Freesia-NeoForge-Velocity
gradlew.bat build            # 产物: build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar
gradlew.bat regressionTest   # 单独跑 45 项回归
```

`build.gradle` 已把回归套件接入 Gradle：

```groovy
tasks.register('regressionTest', JavaExec) {
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass  = 'SwitchRegressionTest'
}
tasks.named('check') { dependsOn tasks.named('regressionTest') }
```

`SwitchRegressionTest` 是普通 `main()` 程序（**无 JUnit 依赖**）：它用
`Proxy.newProxyInstance` 构造内存中的 Velocity 接口替身，直接驱动**真实的**
`MapperManager` 与 `YsmPacketProxy`。

### 覆盖的 45 项检查（节选）

- 缓存先于世界就绪时**不**提前发送；初始模型重放
- 切服后按**新、旧实体 ID** 分别恢复；`3` 包过滤未知实体并保留表达式
- 旧世界延迟任务失效；迟到 spawn 回调不能污染替代世界
- 实体消失 / ID 重用 / 断线 的缓存与映射清理
- 重试读取**最新**模型而非陈旧快照；缓存**不**与入参数组别名
- 同服多接收者各自重写实体 ID；跨服同整数 ID **不**串模型
- 配置阶段只缓存不发；配置期状态能存活到新世界
- 20 次连续进出视野都可恢复且无需重选模型
- 同一 ID 重进**不**会复活上一次的追踪重试
- 畸形负载不逃出解析器；模型选择请求仍转发给 Worker
- 诊断状态机五阶段判定；观察模组 spawn 不修改原字节与 readerIndex

**验证状态（如实说明）**：这 45 项检查**直接运行 Java 逻辑**，
**没有启动真实 Minecraft 客户端与服务器**，因此**不覆盖**：

- YSM 的实际资源缓存与握手生命周期；
- 网络延迟与时钟影响；
- 真实 PacketEvents 在完整模组环境下的注册表解码行为。

---

## 8. 已知限制

- **C2S 不做反向实体 ID 重映射**：`7` / `17` 等上行包按原样转发给 Worker；
- **不含跨服 tracker 可见性同步**：本版靠 PacketEvents 观察业务子服的
  `SPAWN_ENTITY` / `DESTROY_ENTITIES` 推断可见性，没有 Freesia II 那套后端上报通道；
- **未在真实服务器验证**：见 §1；
- **外部阻塞（与本插件无关）**：`Unknown component type named farmersdelight:backstabbing`
  是 **PacketEvents 自身的注册表解码问题**。本版未修改 PacketEvents，故该错误仍可能阻断玩家连接。
  它与「Worker TCP 是否建立」是**两个需要分开确认**的问题。

---

## 9. 部署

1. 安装 **PacketEvents `2.13.0`**（本插件编译所依据的版本）；
2. 把 `Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` 放进 Velocity 的 `plugins/`，
   **同一插件只保留一个 JAR**（移出旧的 1.0.0 / 1.0.1 / 1.0.2）；
3. Worker 端与既有 `config.properties` **无需替换**；
4. `plugins/freesianeo/config.properties`：

   ```properties
   worker_address=localhost:25566
   debug=false
   ```

   > ⚠️ 代码内置默认值是硬编码的 `mc.cloudeindustry.fun:41485`，而 `saveDefault()` 写出的
   > 模板是 `localhost:25566`。**请务必显式填写**，不要依赖默认值。

5. 启动 Velocity，确认加载版本为 `1.0.3-diagnostic`，并看到
   `[Freesia] Enabled. Worker = 主机:端口`。

---

## 10. 验收与排障

登录一次并**保持在线约 35 秒**，收集 Velocity 从登录到断开（或状态摘要）的**完整**日志，
以及**同一时间**的 Worker 日志。搜索 `[Freesia]` 与 `[Freesia-DIAG]`，
并**一并保留附近异常堆栈**。按 §6 的 `stage` 表定位卡点。

游戏内依次测试：

1. A → B → A，第三人称观察自身，切服后等约 3 秒；
2. 播放一次模型动画后再切服（确认恢复的是**模型**而非动画）；
3. 两名玩家在目标服互看；离开视野后重新靠近；
4. 快速连续切服、跨维度、死亡重生。

若仍恢复默认，保留切服前后约 20 秒的三方日志，并区分
「自己看自己默认」还是「其他人看你默认」，重点看 `New client world` 的实体 ID
与 `Restored ... model(s)` 的数量。

---

## 11. 回滚

停止 Velocity，移出本版 JAR，把旧版（如 `Freesia-NeoForge-Velocity-1.0.0.jar`）放回，再启动。

---

## 12. 许可与来源

- 设计思路源自 [Freesia II](https://github.com/NguyenDevs/FreesiaII)（MrHua269 / NguyenDevs 等）。
- YSM 协议事实来自对 YSM 发行 jar 的反编译与 MIT 许可的 OpenYSM 源码；本项目**不含** YSM 或 OpenYSM 代码。
- 仓库根 `LICENSE`：MIT License，Copyright (c) 2026 YIYU0321。
