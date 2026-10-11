# 架构与协议

[文档索引](README.md) · [Velocity 源码导航](../Freesia-NeoForge-Velocity/README.md#源码导航) · [Worker 实现](../freesianeoforgeworker-1.21.1/README.md#运行机制)

## 组件与连接

系统有两条独立的连接路径：

- **游戏路径**：真实客户端 → Velocity → 业务子服。
- **模型路径**：真实客户端 ↔ Velocity 插件 ↔ MCProtocolLib 假客户端 ↔ Worker 的 YSM。

插件在 `ServerConnectedEvent` 后为玩家创建或复用 `MapperSession`，以真实玩家 UUID 与用户名直连 Worker；玩家切业务子服时保留该会话。Worker 接收的是单独的 Minecraft 连接，YSM 模型消息经代理转交真实客户端。

对 `yes_steve_model:2_6_0` 通道，代理将双向插件消息标记为 `handled()`。来自玩家的消息送入 Worker 会话，来自业务子服的同通道消息被丢弃，避免两个服务端同时负责模型同步。

Worker 的 Mod 通过 Mixin 停止世界 tick，保留连接与玩家列表 tick，并过滤出站包。它仍是需要完整启动的 NeoForge 服务端；YSM 的实际工作和连接兼容性需要实服验证。

## 身份与接收者视图

| 身份 | 来源 | 用途 |
| --- | --- | --- |
| 玩家 UUID | 真实玩家账号 | 模型缓存与玩家归属的稳定键 |
| Worker 实体 ID | 假客户端进入 Worker 后获得 | 识别 Worker 回传模型的归属 |
| 业务实体 ID | 业务服发给某个客户端的实体包 | 该客户端应用模型时使用的 ID |

实体 ID 可能在切服、重生或重连后变化，不适合作为持久模型身份。不同子服也可能使用相同整数 ID。

`MapperManager` 维护：

| 数据 | 含义 |
| --- | --- |
| `sessions: UUID → MapperSession` | 每位玩家的 Worker 会话 |
| `workerOwners: Worker ID → UUID` | Worker 实体归属 |
| `models: UUID → byte[]` | 最新模型的 Worker 原始载荷 |
| `views: 接收者 UUID → View` | 每个客户端当前世界的视图 |
| `View.entities: UUID → 实体 ID` | 某接收者目前看到的玩家与 ID |
| `View.trackingRevisions` | 每次建立可见关系时分配的追踪代次 |

## 缓存与投递

以玩家 A 切换模型、玩家 B 在同服可见为例：

1. A 发出 `C2S_SWITCH_MODEL(5)`，代理交给 A 的 Worker 会话。
2. Worker 的 YSM 回传 `S2C_SET_MODEL(4)`，载荷携带 Worker 实体 ID。
3. 代理查出该实体所属 UUID，以 UUID 为键保存原始载荷的副本。
4. 模型变化时尝试向所有符合条件的接收者投递。
5. 每次发送都使用该接收者视图中的实体 ID 重编码。

缓存保留 Worker 原始形态；投递时才替换第二个 VarInt：

```text
[包 ID][Worker 实体 ID][不透明正文]
                ↓
[包 ID][接收者视角的业务实体 ID][相同正文]
```

此改写适用于当前已知的模型与动画包前缀，正文原样复制；它不构成对其他 YSM 版本的兼容保证。

仅模型状态 `4` 进入持久缓存，动画 `21` 作为即时事件路由，不覆盖模型。新模型按内容比较去重，避免 Worker 多条连接回传相同模型时重复触发全量广播。Molang 包 `3` 则逐个过滤并重映射实体 ID，保留表达式正文。

每次模型投递均检查：视图仍有效、客户端已就绪、双方处于同一业务子服、接收者有对应实体映射。未知 Worker 实体的模型与动画包会丢弃。

## 世界切换与视野恢复

代理观察业务服发出的原生包：

| 包 | 动作 |
| --- | --- |
| `JOIN_GAME` | 建立新视图，记录自身实体 ID，安排模型补发 |
| `RESPAWN` | 替换当前视图，沿用已知自身实体 ID，安排补发 |
| `CONFIGURATION_START` | 建立未就绪视图，使旧世界任务失效 |
| `SPAWN_ENTITY` / `SPAWN_PLAYER` | 读实体 ID 与 UUID；若是代理在线玩家，建立可见映射 |
| `DESTROY_ENTITIES` | 移除该视图中的实体映射与追踪代次 |

`EntityPacketReader` 在缓冲区的 `duplicate()` 上读取最小前缀，保持原包字节与读取位置不变；不依赖完整实体类型注册表。观察异常会记录日志，插件不会因此取消或重写业务包。

包的后置任务安排有限次数的补发：

| 场景 | 延迟 |
| --- | --- |
| 新世界就绪 | 100、500、1500、3000 ms |
| 玩家进入视野 | 50、250、1000、3000 ms |

每次执行都读取最新模型，并校验视图对象仍是当前登记对象；视野补发还校验追踪代次。离开又重新进入视野，即便实体 ID 相同，旧任务也失效。世界就绪补发还检查编码器处于 PLAY 阶段。

玩家断开代理时清理其会话、模型缓存和其他视图中的映射。Worker 会话断开会移除该会话；当前没有独立的定时自动重连机制，后续业务服连接事件可重新触发创建会话。

## YSM 协议参考

当前常量定义见 [YsmConstants.java](../Freesia-NeoForge-Velocity/src/main/java/com/nguyendevs/freesia/neoforge/YsmConstants.java)。通道使用下划线形式 `yes_steve_model:2_6_0`，协议版本字符串为 `2.6.0`；不要与其他发行版的点号通道混用。

负载由 VarInt 包 ID 和消息体组成：

| ID | 方向 | 常量 | 当前处理 |
| --- | --- | --- | --- |
| 1 | S2C | `S2C_MODEL_SYNC` | 原样转发模型二进制 |
| 2 | C2S | `C2S_MODEL_SYNC` | 原样转发模型二进制 |
| 3 | S2C | `S2C_MOLANG` | 过滤不可见实体、重映射列表 |
| 4 | S2C | `S2C_SET_MODEL` | 按 UUID 缓存、按接收者路由 |
| 5 | C2S | `C2S_SWITCH_MODEL` | 原样转发切换请求 |
| 7 | C2S | `C2S_ANIMATION` | 原样转发，未反向重映射实体 ID |
| 17 | C2S | `C2S_MOLANG_REQ` | 原样转发，未反向重映射实体 ID |
| 21 | S2C | `S2C_ANIMATION` | 路由，不写模型缓存 |
| 51 | S2C | `S2C_HANDSHAKE` | 记录握手并转发 |
| 52 | C2S | `C2S_HANDSHAKE` | 记录握手并转发 |

S2C / C2S 分别指服务端到客户端、客户端到服务端。这里的“转发”均经过代理与 Worker，不表示交给业务服处理。解析器对其他包 ID 默认原样转发，畸形负载记录警告并丢弃。

## 与上游的边界

本实现借鉴 Freesia II 的 Worker 拓扑，当前提供 Velocity 插件和 NeoForge Worker Mod。它没有上游的后端 tracker 上报、多 Worker 管理或 mTLS 配置，也不声明兼容 Waterfall / BungeeCord。可见性来自 PacketEvents 观察，不是跨服实体追踪同步。

本地回归检查可以验证映射、缓存与旧任务失效等 Java 逻辑；真实 NeoForge 登录、YSM 资源同步与网络行为需按 [安装指南](INSTALLATION.md#游戏内验收) 联调。
