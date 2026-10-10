# Freesia NeoForge (Velocity) — 1.0.3-diagnostic

面向 **NeoForge 1.21.1 + Yes Steve Model (YSM) 2.6.5** 的 Velocity 代理插件。

它在代理处**终结** YSM 的模型同步协议，把流量改道到一个独立的 **Worker 节点**计算，
再把结果回灌给真实客户端，并在此过程中完成**实体 ID 重映射**。

| | |
|---|---|
| 版本 | `1.0.3-diagnostic`（与 `src/main/resources/velocity-plugin.json` 一致） |
| 产物 | `build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` |
| 编译目标 | Java 21 · Velocity API `3.3.0-SNAPSHOT` · PacketEvents **`2.13.0`** |
| 必需运行时依赖 | Velocity ≥ 3.3 · PacketEvents Velocity 插件 **2.13.0** |

> **本版定位：连接诊断版。** 它在 1.0.2 的切服/视野恢复能力之上加入完整连接阶段日志，
> 用于定位「Worker 到底卡在哪一步」。开发者原说明：
> *「尚未连接您的运行服务器。本版用于查明卡点，不能声称已修好实际 Worker 连接。」*

---

# 第一部分：问题

## 1.1 为什么普通转发插件不行

Velocity 默认就会转发未知插件消息，所以一个「注册通道 + 双向透传」的插件看起来应该够用。
但对 YSM 而言不够，原因在**服务端**侧：

1. **流控依赖真实 Netty 连接。** YSM 服务端用
   `connection.channel().unsafe().outboundBuffer()` 判断发送队列积压情况，据此决定模型数据
   （可能数 MB）的分片节奏。经过代理的连接，其 `Channel` 已经不是客户端真正的那个 Channel，
   这套判断失去依据。
2. **严格的连接处理器校验。** YSM 会检查连接管线中的处理器类名，代理改写过的管线会被判为不合法。
3. **模型是按「服务端视角的实体」计算的。** YSM 服务端为每个它跟踪的实体维护模型状态，
   而「跟踪」关系由服务端自己的实体追踪器（tracker）决定。代理对它不可见。

**结论**：不是包格式问题，而是「YSM 假设自己直接连着客户端」这个前提被代理破坏了。
所以修复方向不是修补透传，而是**改变拓扑**。

## 1.2 一个具体的连锁故障

1.0.0 的实现暴露了问题的另一面。`analysis/切服模型恢复默认分析.md` 记录了证据链：
玩家切子服后模型恢复默认，是四个独立缺陷叠加的结果：

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | 模型缓存键是**后端实体 ID** | 切服后 ID 变了（如 100 → 200），`modelCache[200]` 查不到 |
| 2 | 包内实体 ID 在**缓存时**就已写死 | 即使查到旧缓存，包里仍指向旧 ID，发过去也没用 |
| 3 | 动画包 `21` 与模型包 `4` 共用缓存写入路径 | 一次动画就把模型缓存覆盖成动画 |
| 4 | 只监听 `SPAWN_PLAYER` | 1.21.1 走的是 `SPAWN_ENTITY`，补发分支根本不触发 |

这四个缺陷指向同一个根因：**把「网络包」当成了「持久状态」**。
网络包里的实体 ID 是**临时且易变**的；真正的持久身份只有一个 —— **玩家 UUID**。

---

# 第二部分：架构

## 2.1 拓扑总览

```
                    真实 NeoForge 客户端 (YSM 2.6.5)
                              │  Minecraft 协议
                              ▼
        ┌──────────────────────────────────────────────────────────┐
        │                    Velocity 代理                          │
        │  ┌────────────────────────────────────────────────────┐  │
        │  │  freesianeo 插件 (1.0.3-diagnostic)                │  │
        │  │                                                    │  │
        │  │  FreesiaNeoForge    入口/事件装配/PacketEvents 观察 │  │
        │  │  MapperManager      会话表·模型缓存·View·可见性路由 │  │
        │  │  MapperSession      假客户端(连 Worker)+诊断状态机  │  │
        │  │  YsmPacketProxy     YSM 负载解析与改写决策          │  │
        │  │  EntityPacketReader 只读原生包前缀                  │  │
        │  └────────────────────────────────────────────────────┘  │
        └───────────┬──────────────────────────────┬──────────────┘
                    │                              │
     MCPRotocolLib 假客户端连接                正常 MC 协议
     （每玩家一条，直接 TCP）                        │
                    ▼                              ▼
     ┌──────────────────────────┐   ┌────────────────────────────┐
     │  Worker 节点              │   │  业务后端子服 (NeoForge)     │
     │  NeoForge 1.21.1          │   │  玩家实际游玩的位置          │
     │  + YSM 2.6.5              │   │  不对外同步模型（卸掉 YSM）  │
     │  + freesianeoforgeworker  │   │  NeoVelocity 现代转发        │
     │  「只算模型，不 tick 世界」│   └────────────────────────────┘
     └──────────────────────────┘
```

**关键点**：Worker 是独立服务端，代理用 MCPRotocolLib 以**真实玩家身份**直连它。
这条连接是**真正的端到端 Minecraft 连接**，不是被代理改写的连接 ——
因此 §1.1 的三个前提（真实 Channel、合法管线、服务端自己的 tracker）**全部重新成立**。

## 2.2 三个组件各做什么

### Velocity 插件（本项目）

- 注册并拦截 `yes_steve_model:2_6_0` 通道，**两向都标记 `handled()`**，不让它到达业务后端；
- 为每个玩家维护一条 `MapperSession`（到 Worker 的假客户端）；
- 从 PacketEvents 观察**业务后端**发出的实体包，维护「这个客户端现在能看到谁、ID 是多少」；
- 把 Worker 回传的模型按**接收者视角**改写实体 ID 后投递；
- 切服/重生/重新进入视野时按 UUID 重放模型。

### Worker 端 mod（`freesianeoforgeworker`）

Worker 本身是个完整 NeoForge 服务端，mod 只负责把它「削」成纯计算节点：

| Mixin | 作用 |
|---|---|
| `MinecraftServerMixin` | `tickChildren` HEAD 直接 cancel，只保留连接与玩家列表 tick → **不 tick 世界、不存盘** |
| `ConnectionMixin` | 出站包白名单 + `FakeCompressionEncoder`（不压缩） |
| `PlayerDataStorageMixin` | 假客户端 NBT 落到 `world/freesia_playerdata/<uuid>.dat`，重连恢复 |
| `PlayerListMixin` | 绕过白名单/人数上限/封禁 |
| `ServerCommonPacketListenerImplMixin` | 廉价接受 keep-alive |
| `LivingEntityMixin` | 假客户端无敌 |

### 业务后端子服

**不参与 YSM 流量**。插件把它发来的同通道消息一并丢弃 —— 代码注释写得很直接：
*「The Worker owns YSM, not the backend.」* 它只负责让玩家正常游玩。

## 2.3 一次完整的模型同步（时序）

以玩家 A 选中模型、玩家 B 应当看到为例：

```
① A 的客户端 ──[C2S_SWITCH_MODEL(5)]──▶ Velocity 插件
② 插件 onChannelMsg：命中 YSM 通道 → setResult(handled()) 吞掉
   └─▶ A 的 MapperSession.relayC2S() ──▶ Worker（A 的假客户端连接）
③ Worker 的 YSM 处理切换请求，计算模型
   └─▶ 经 A 的假客户端连接回传 [S2C_SET_MODEL(4), workerEntityId=A-W, 模型数据]
④ MapperSession.packetReceived → YsmPacketProxy.processS2C
   └─ 识别 packetId=4，读出 workerEntityId，交给 MapperManager.routeEntityPacket
⑤ MapperManager 把载荷**按 A 的 UUID 原样缓存**（保留 Worker 形态，不改 ID）
   └─ 因为是新模型（changedModel=true）→ 唤醒所有可见接收者的 View
⑥ 对每个 View 调 send()：校验【View 有效 + 已就绪 + 同子服 + 该 View 已映射 A】
   └─ 用**该接收者自己的**实体 ID 调 rewriteEntity() 重新编码
⑦ A 收到（自己的 ID）、B 收到（B 视角下 A 的 ID）—— 两次编码互不相同
```

第 ⑤ 步是核心：**缓存与投递分离**。缓存保存的是「与实体 ID 无关的模型事实」，
实体 ID 只在第 ⑥ 步、针对每个接收者单独写入。

---

# 第三部分：核心原理

## 3.1 原理一：三套身份空间，只有 UUID 是持久的

这是整个设计的基石。系统里同时存在三种「实体标识」：

| 身份 | 归属 | 生命周期 | 能否作为持久键 |
|---|---|---|---|
| **后端实体 ID** | 业务服的实体追踪器 | 每次切服/跨维度/重生都可能变 | ❌ 绝不能 |
| **Worker 实体 ID** | Worker 上该玩家的假客户端 | 每次重连 Worker 会变 | ❌ 不能 |
| **玩家 UUID** | Minecraft 账号 | 会话内稳定 | ✅ **唯一的持久身份** |

`MapperManager` 的两张映射表：

```java
Map<Integer, UUID> workerOwners;                  // Worker 实体 ID → 玩家 UUID
Map<UUID, Integer>  View.entities;                // 玩家 UUID → 该接收者看到的实体 ID
```

**为什么不能只用一张双向表？** 因为「实体 ID → UUID」在不同接收者视角下答案不同。
子服 A 和子服 B 的实体 ID 可以重号（都可能是 100）。必须**按接收者分别维护**，
这就是 `View`（每个客户端一份）存在的原因。

1.0.0 的 `Map<Integer,Integer> workerToBackend` 正是因为缺少接收者维度，
导致 `broadcastToOthers` 向所有子服发同一份字节而串号。

## 3.2 原理二：缓存 Worker 形态，投递时才改写

`MapperManager` 中的模型缓存：

```java
private final Map<UUID, byte[]> models = new HashMap<>();   // 按 UUID，不按实体 ID
```

**缓存的内容是 Worker 发来的原始载荷**（其中实体 ID 仍是 Worker 的 ID）。
投递时用 `rewriteEntity` 现场重写：

```java
public static byte[] rewriteEntity(byte[] raw, int entityId) {
    ByteBuf in = Unpooled.wrappedBuffer(raw);
    ByteBuf out = Unpooled.buffer(raw.length + 5);
    BufUtil.writeVarInt(out, BufUtil.readVarInt(in));  // ① 原样复制判别符
    BufUtil.readVarInt(in);                            // ② 丢弃旧的实体 ID
    BufUtil.writeVarInt(out, entityId);                // ③ 写入该接收者的实体 ID
    out.writeBytes(in);                                // ④ 原样复制其余不透明正文
    return BufUtil.readRemaining(out);
}
```

第 ④ 步很关键：**正文完全不解析**。模型数据（可能数 MB）、贴图引用、状态字段
全部原样搬运。插件只理解「前两个 VarInt」，对其余字节零假设 ——
这也是它对 YSM 内部格式演进不敏感的原因。

**收益**：

- 切服后 ID 变化不影响缓存有效性（缓存本来就不含目标 ID）；
- 同一份模型能同时投递给多个接收者，各自编码不同 ID；
- 不解析正文 → 不会因模型格式变化（YSM 升级）而失效。

## 3.3 原理三：View —— 每个客户端一份世界快照

```java
public static final class View {
    private final Player player;
    private final Map<UUID, Integer> entities = new HashMap<>();        // 可见实体
    private final Map<UUID, Long> trackingRevisions = new HashMap<>();  // 追踪代次
    private boolean ready;                                              // 客户端是否可收包
}
```

`View` 回答四个问题：

1. **这个客户端现在能看到哪些玩家，各自的实体 ID 是多少？**（`entities`）
2. **这个客户端现在能收包吗？**（`ready`，需等 PLAY 阶段）
3. **这份 View 还是当前有效的吗？**（`isCurrent`：仍是 `views` 中登记的那一份 **且** 玩家在线）
4. **上一次针对某玩家的补发任务还有效吗？**（`trackingRevisions`）

`isCurrent` 用**引用相等**（`views.get(uuid) == view`）判断代次，而不是 ID 相等：

```java
public synchronized boolean isCurrent(View view) {
    return view != null && views.get(view.player.getUniqueId()) == view && view.player.isActive();
}
```

这是「**世界代次令牌**」模式。`beginWorld` 每次新建一个 `View` 对象并登记；
此后所有延迟任务都持有**创建时的那个引用**。旧世界的任务运行时，`views` 里已经不是它了，
于是 `isCurrent` 返回 `false`，任务自动作废 —— 无需任何显式取消。

**快速连续切服为什么不串？** 因为每个任务绑定的是具体对象，而不是可变状态。

## 3.4 原理四：tracking revision —— 过期补发自动失效

`View.ready` 和 `isCurrent` 管的是「世界级」失效，但视野进出是**更细粒度**的：
玩家 B 离开视野又回来，可能是**同一个实体 ID**。仅靠 ID 无法区分「同一个追踪关系」
和「一次全新的追踪」。

```java
public synchronized long trackForReplay(View view, UUID owner, int entityId) {
    if (!isCurrent(view)) return -1;
    view.entities.entrySet().removeIf(e -> e.getValue() == entityId && !e.getKey().equals(owner));
    view.trackingRevisions.keySet().retainAll(view.entities.keySet());
    view.entities.put(owner, entityId);
    long revision = ++nextTrackingRevision;      // 全局自增，只增不减
    view.trackingRevisions.put(owner, revision);
    return revision;
}
```

投递时校验：

```java
public synchronized void replayTracked(View view, UUID owner, long revision) {
    if (!isCurrent(view) || !Objects.equals(view.trackingRevisions.get(owner), revision)) return;
    ...
}
```

**每次 `trackForReplay` 都换新号**，所以：

- 离开视野 → `untrack` 移除映射 → 代次号被 `retainAll` 清掉；
- 带着**新**代次号重新进入 → 旧任务持有旧号，`Objects.equals` 失败 → 作废；
- 即使实体 ID 完全相同，旧任务也不会「复活」。

`trackForReplay` 第一行 `removeIf` 还解决了**实体 ID 重用**：若某 ID 现在属于别人，
先把旧归属删掉再写入新归属。

## 3.5 原理五：有界重试，每次读最新

客户端进入新世界后，不是「立刻」就能收模型包 —— 它要先初始化世界与 YSM 资源。
过早发送会被丢弃。所以插件不猜时机，而是**在几个时间点各试一次**：

```java
// FreesiaNeoForge.worldReadyAfterSend —— 世界就绪（JOIN_GAME / RESPAWN）
for (long delay : new long[]{100, 500, 1500, 3000}) {
    this.server.getScheduler().buildTask(this, () -> {
        if (user.getEncoderState() != ConnectionState.PLAY) return;   // 还没到 PLAY 就跳过
        this.mapperManager.markReady(view);
        this.mapperManager.replay(view);
    }).delay(delay, TimeUnit.MILLISECONDS).schedule();
}

// FreesiaNeoForge.trackAfterSend —— 玩家进入视野（SPAWN_ENTITY / SPAWN_PLAYER）
for (long delay : new long[]{50, 250, 1000, 3000}) {
    ... this.mapperManager.replayTracked(view, owner, revision) ...
}
```

三个设计要点：

1. **有界**（各 4 次），不会无限重试；
2. **每次都从 `models` 读最新值**，不做快照 —— 恢复过程中玩家又换了模型，重试发的是新模型；
3. **`markReady` 幂等**，且每次执行都重新校验 `isCurrent` 与 `trackingRevisions`。

用 `event.getPostTasks()` 而非直接执行，是因为要等包**真正写出编码器**之后再安排补发
（「玩家会看到这个实体」这件事必须以包已发出为前提）。

## 3.6 原理六：可见性路由与广播去重

投递闸门 `send()` 有四道校验：

```java
private boolean send(View view, UUID owner, byte[] raw) {
    if (!isCurrent(view) || !view.ready || !sameServer(view.player, owner)) return false;
    Integer id = view.entities.get(owner);
    if (id == null) return false;                       // 该接收者看不到本人
    return view.player.sendPluginMessage(CHANNEL_KEY, rewriteEntity(raw, id));
}
```

| 校验 | 拦截的问题 |
|---|---|
| `isCurrent(view)` | 旧世界 / 已离线客户端的补发 |
| `view.ready` | 客户端尚未进入 PLAY，发了也没用 |
| `sameServer(...)` | **跨子服串号**：子服 A 的 100 不该收到子服 B 的模型 |
| `entities.get(owner) == null` | 该玩家当前不在接收者视野内 |

广播范围由 `routeEntityPacket` 决定：

```java
if (changedModel || (packetId != S2C_SET_MODEL && owner.equals(receiver.getUniqueId()))) {
    for (View view : views.values()) send(view, owner, raw);   // 唤醒所有可见接收者
} else {
    send(views.get(receiver.getUniqueId()), owner, raw);       // 只回给投递者自己
}
```

**为什么需要「唤醒所有」？** 因为 Worker 侧每条假客户端是独立连接，
某个玩家的模型可能先在**别人**的连接上到达，或在 B 进视野**之前**就已缓存。
只在投递者的 View 里找会漏发。

**为什么又要去重？** `changedModel` 用内容比较判断：

```java
changedModel = !Arrays.equals(models.put(owner, raw.clone()), raw);
```

`clone()` 保证缓存不与入参数组别名（回归测试专门验证了这点）。
若模型没变就**不**触发全量广播，避免 N 个玩家 × N 个接收者的 N² 放大。

## 3.7 原理七：只读观察，绝不改动后端包

插件要读业务服的实体包来建立可见性映射，但**不能干扰它们**。
1.0.0 用 PacketEvents 的 `WrapperPlayServerSpawnPlayer` 解析，这在 1.21.1 上有两个坑：

- 1.20.2 起玩家生成走 `SPAWN_ENTITY`（带实体类型），旧 API 不匹配；
- 解析**模组实体类型**需要完整注册表，缺失时会产生 `null entityType` 并导致重编码异常。

1.0.3 改为只读最小前缀：

```java
public static Spawn spawn(ByteBuf packet) {
    ByteBuf in = packet.duplicate();          // duplicate：共享内容，独立读写指针
    int id = BufUtil.readVarInt(in);
    return new Spawn(id, new UUID(in.readLong(), in.readLong()));
}
```

设计要点：

- **`duplicate()` 而非直接读**：获得独立 `readerIndex`，解析不会移动原缓冲位置；
- **只读约 24 字节**（VarInt ID + 两个 long 的 UUID），绝不解析实体类型；
- **用 UUID 而非实体类型识别玩家**：`proxy.getPlayer(spawn.uuid()).isPresent()`
  直接判断「这是不是在线的代理玩家」，完全绕开模组注册表。

整个 `onPacketSend` 还包在 `try/catch (RuntimeException)` 里，注释说明了原则：

```java
// Observation failure must never cancel or replace a backend's packet.
```

**观察失败绝不能影响业务包** —— 最坏情况是丢一次模型补发，而不是崩掉玩家连接。

## 3.8 原理八：模型包与动画包必须分开对待

`YsmPacketProxy` 里 `4`（`S2C_SET_MODEL`）和 `21`（`S2C_ANIMATION`）走同一分支路由，
但**只有 `4` 会写缓存**：

```java
if (packetId == YsmConstants.S2C_SET_MODEL) {
    changedModel = !Arrays.equals(models.put(owner, raw.clone()), raw);
}
```

因为语义不同：

- `4` 是**持久状态**：这个玩家现在长什么样 —— 可缓存、可重放、切服后仍有效；
- `21` 是**瞬时事件**：播一次动画 —— 重放它毫无意义，且会污染模型缓存。

这是 1.0.0 第四个缺陷的直接修正。

两者都返回 `DROP` 而不是 `MODIFY`，是因为路由（`routeEntityPacket`）**已经负责发送**了：

```java
case S2C_SET_MODEL, S2C_ANIMATION -> {
    int workerId = BufUtil.readVarInt(buf);
    mapper.routeEntityPacket(player, packetId, workerId, data);
    return Result.drop();     // 已由路由投递，避免重复发送
}
```

`S2C_MOLANG (3)` 则不同 —— 它是**一批实体 ID + 表达式**，需要逐 ID 过滤并重新编码：

- 读出 `count` 与前 `count` 个 VarInt；
- 用 `entityForWorker()` 逐个映射，**丢弃接收者看不到的实体**；
- 若一个都不剩 → 整包丢弃；
- 否则重编码 `[3][新 count][映射后 ID...]` + 原样表达式正文。

---

# 第四部分：协议参考

## 4.1 通道与编码

对 `ysm-2.6.5-neoforge+mc1.21.1-release.jar` 反编译确认：

- **通道名**：`yes_steve_model:2_6_0`（**下划线**形式）
  ```java
  MinecraftChannelIdentifier.create("yes_steve_model", "2_6_0")
  ```
  注意 Forge 1.20.1 的 OpenYSM 用的是 `2.6.0`（点号）形式，NeoForge 1.21.1 不是。
- **协议版本串**：`2.6.0`
- **负载结构**：`VarInt 判别符` + 消息体（NeoForge payload 系统的 `StreamCodec`）

## 4.2 包 ID 表

| ID | 方向 | 常量 | 说明 | 本插件处理 |
|---|---|---|---|---|
| 1 | S→C | `S2C_MODEL_SYNC` | 模型二进制 | 透传 |
| 2 | C→S | `C2S_MODEL_SYNC` | 模型二进制 | 透传 |
| 3 | S→C | `S2C_MOLANG` | 实体 ID 列表 + 表达式 | **过滤 + 重映射** |
| 4 | S→C | `S2C_SET_MODEL` | 实体 ID + 模型 + 贴图 + 状态 | **缓存 + 路由** |
| 5 | C→S | `C2S_SWITCH_MODEL` | 切换模型请求 | 透传 |
| 7 | C→S | `C2S_ANIMATION` | 实体 ID + 动画 | 透传 |
| 17 | C→S | `C2S_MOLANG_REQ` | 表达式 + 实体 ID | 透传 |
| 21 | S→C | `S2C_ANIMATION` | 实体 ID + 动画/状态 | **路由（不缓存）** |
| 51 | S→C | `S2C_HANDSHAKE` | 服务端下发版本 | 记录 + 透传 |
| 52 | C→S | `C2S_HANDSHAKE` | 客户端回报版本 | 记录 + 透传 |

## 4.3 实体包改写的不变量

`rewriteEntity` 假定 `[VarInt 判别符][VarInt 实体 ID][不透明正文]`。
**正文长度可变**（`VarInt` 编码，ID 缩小或增大都不影响正确性），
回归测试 `ID shrink preserves opaque body bytes` 专门覆盖了 `Integer.MAX_VALUE → 1` 的情形。

---

# 第五部分：诊断

## 5.1 阶段日志（默认开启，无需 `debug=true`）

| 日志 | 含义 |
|---|---|
| `Enabled. Worker = 主机:端口` | 当前实际使用的 Worker 地址，先确认配置 |
| `BACKEND_CONNECTED` | 玩家已连上业务子服，即将创建/复用 Worker 会话 |
| `Worker CONNECTING` | 开始连接 Worker |
| `Worker TCP_CONNECTED` | **仅** TCP 已建立，**不能**证明 Minecraft/YSM 登录成功 |
| `WORKER_RX` / `WORKER_TX` | 登录与配置阶段收发包的**类名**（不记录正文与凭据） |
| `Worker PLAY_READY` | 收到 Worker 进入游戏包，记录 Worker 实体 ID |
| `YSM_WORKER_RX packetId=51` | 收到 Worker 的 YSM 握手 |
| `YSM_CLIENT_SEND ... accepted=true` | Velocity **接受**了发送请求，≠ 客户端已处理 |
| `YSM_CLIENT_RX packetId=52` | 收到真实客户端的 YSM 握手回复 |
| `YSM_WORKER_TX_CONFIRMED` | MCProtocolLib 发送完成回调，≠ Worker 已处理 |
| `YSM_C2S_NOT_SENT` | 客户端发来 YSM 包时 Worker 连接不存在或已断开 |
| `Worker PACKET_ERROR` | Worker 网络包处理异常，附堆栈 |
| `Worker DISCONNECTED` | 断开原因、最后阶段、最后收发包与底层异常 |

会话分配后 **10 秒、30 秒**输出 `STATUS`（玩家离线或会话已断开则不再输出）。

## 5.2 stage 状态机

```
TCP_CONNECT
     │ 观察到 TCP 建立
     ▼
MINECRAFT_LOGIN_OR_CONFIGURATION
     │ 收到 ClientboundLoginPacket（Worker 实体 ID 已知）
     ▼
WORKER_YSM_HANDSHAKE
     │ 收到 YSM 51
     ▼
CLIENT_YSM_REPLY
     │ 收到 YSM 52
     ▼
YSM_HANDSHAKE_OBSERVED
```

| stage | 卡在这里说明 | 往哪查 |
|---|---|---|
| `TCP_CONNECT` | 尚未观察到 TCP 建立 | `UnknownHostException` / `ConnectException` / 超时 |
| `MINECRAFT_LOGIN_OR_CONFIGURATION` | TCP 已建但没进游戏 | 最后的 `WORKER_RX/TX`、Worker 拒绝原因 |
| `WORKER_YSM_HANDSHAKE` | 进了 Worker 但没收到 51 | Worker 的 YSM 是否加载、通道版本 |
| `CLIENT_YSM_REPLY` | Worker 发了 51 但没收到 52 | 客户端、代理转发、业务服连接是否被 PacketEvents 中断 |
| `YSM_HANDSHAKE_OBSERVED` | 两向握手都观察到 | **不代表**资源同步/模型应用成功，继续看计数与 Worker 日志 |

> ⚠️ 若**完全没有** `BACKEND_CONNECTED`，说明还没走到创建 Worker 会话的入口 ——
> **不要**仅凭这一点判定 Worker 地址不可达。

## 5.3 调试日志（`debug=true`）

| 日志 | 含义 |
|---|---|
| `New client world ... self entity <id>` | 进入新世界的自身实体 ID |
| `Restored N model(s)` | 补发数量。**N=0** = 无可恢复的缓存/可见映射；**N>0 仅代表发送调用成功** |
| `Track <uuid> -> <player> entity=<id> revision=<n>` | 进入视野并分配追踪代次 |
| `Retrack model ... cached= sent=` | 追踪重试结果 |
| `Dropped unmapped Worker entity <id> packet <id>` | 未知 Worker 实体，已丢弃（不再透传） |

---

# 第六部分：构建与验证

```shell
cd Freesia-NeoForge-Velocity
gradlew.bat build            # 产物: build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar
gradlew.bat regressionTest   # 45 项本地回归
gradlew.bat check            # 含 regressionTest
```

## 6.1 回归套件如何接入

```groovy
tasks.register('regressionTest', JavaExec) {
    dependsOn  tasks.named('testClasses')
    classpath  = sourceSets.test.runtimeClasspath
    mainClass  = 'SwitchRegressionTest'
}
tasks.named('check') { dependsOn tasks.named('regressionTest') }
```

`SwitchRegressionTest` 是普通 `main()` 程序（**无 JUnit 依赖**）：
用 `Proxy.newProxyInstance` 构造内存中的 Velocity 接口替身，
直接驱动**真实的** `MapperManager` 与 `YsmPacketProxy`。

`testImplementation` 里额外声明了 `velocity-api` 与 `slf4j-api`，
因为 `compileOnly` 的依赖不会进入 `testRuntimeClasspath`。

## 6.2 覆盖的 45 项检查（按主题）

| 主题 | 检查内容 |
|---|---|
| 缓存时机 | 世界就绪前只缓存不发送；初始重放 |
| 切服恢复 | 新旧实体 ID 分别恢复；`3` 包过滤未知实体并保留表达式 |
| 异步竞态 | 旧世界延迟任务失效；迟到 spawn 回调不能污染替代世界 |
| 清理 | 实体消失 / ID 重用 / 断线的缓存与映射清理 |
| 数据完整性 | 重试读**最新**模型；缓存不与入参数组别名；ID 缩短保留正文 |
| 路由隔离 | 同服多接收者各自重写 ID；跨服同整数 ID 不串模型 |
| 状态机 | 配置阶段只缓存不发；配置期状态存活到新世界 |
| 视野反复 | 20 次连续进出都可恢复；同 ID 重进不复活旧重试 |
| 健壮性 | 畸形负载不逃出解析器；模型选择请求仍转发 |
| 诊断 | 五阶段判定；TCP 不被误报为登录成功 |
| 只读观察 | 模组未知实体类型无需注册表解码；原字节与 readerIndex 不变 |

## 6.3 验证边界（重要）

这 45 项**直接运行 Java 逻辑**，**没有启动真实 Minecraft 客户端与服务器**。因此**不覆盖**：

- YSM 的实际资源缓存与握手生命周期；
- 网络延迟与时钟影响；
- 真实 PacketEvents 在完整模组环境下的注册表解码行为。

---

# 第七部分：部署

## 7.1 Worker 节点

1. 新建 **NeoForge 1.21.1** 服务端；
2. `mods/`：`freesianeoforgeworker-1.0.0.jar` + `ysm-2.6.5-neoforge+mc1.21.1-release.jar`
   及其前置（McLib / GeckoLib）；
3. `server.properties`：

   ```properties
   online-mode=false
   difficulty=peaceful
   spawn-monsters=false
   level-type=minecraft:flat
   view-distance=2
   simulation-distance=2
   spawn-protection=0
   ```

4. **不要**加进 Velocity 的 `servers` 列表 —— 代理主动直连它，玩家不应被路由进去。

## 7.2 业务后端子服

卸掉 YSM（或至少停止其对外同步），避免与 Worker 双重计算；保留 NeoVelocity 现代转发。

## 7.3 Velocity

1. 安装 **PacketEvents `2.13.0`**；
2. `plugins/` 放入本插件 jar，**同一插件只保留一个 JAR**（移出 1.0.0 / 1.0.1 / 1.0.2）；
3. `plugins/freesianeo/config.properties`：

   ```properties
   worker_address=localhost:25566
   debug=false
   ```

   > ⚠️ **务必显式填写**。代码内置字段默认值是硬编码的 `mc.cloudeindustry.fun:41485`，
   > 而 `saveDefault()` 写出的模板是 `localhost:25566` —— 二者不一致，只有读不到配置文件时才落到前者。

4. 启动后确认加载版本 `1.0.3-diagnostic`，并出现 `[Freesia] Enabled. Worker = ...`。

---

# 第八部分：验收与排障

## 8.1 收集日志

登录一次并**保持在线约 35 秒**，收集：

- Velocity 从登录到断开（或状态摘要）的**完整**日志；
- **同一时间**的 Worker 日志。

搜索 `[Freesia]` 与 `[Freesia-DIAG]`，**并一并保留附近异常堆栈**。按 §5.2 的 stage 表定位。

## 8.2 游戏内测试

1. A → B → A，第三人称观察自身，切服后等约 3 秒；
2. **播放一次动画后再切服**（确认恢复的是模型而非动画）；
3. 两名玩家在目标服互看；离开视野后重新靠近；
4. 快速连续切服、跨维度、死亡重生。

若仍恢复默认，保留切服前后各约 20 秒的三方日志，并区分
「自己看自己默认」还是「其他人看你默认」，重点看 `New client world` 的实体 ID
与 `Restored ... model(s)` 的数量。

## 8.3 回滚

停止 Velocity → 移出本版 JAR → 放回旧版 → 启动。

---

# 第九部分：已知限制

| 限制 | 说明 |
|---|---|
| **C2S 无反向重映射** | `7` / `17` 等上行包按原样转发给 Worker，未做「后端 ID → Worker ID」反向改写 |
| **无跨服 tracker 同步** | 可见性靠 PacketEvents 观察 `SPAWN_ENTITY` / `DESTROY_ENTITIES` 推断，没有 Freesia II 的后端上报通道 |
| **未在真实服务器验证** | 见 §6.3 |
| **依赖 Worker 的 YSM 正常加载** | 插件只是搬运工，Worker 端 YSM 握手失败则整条链路无模型 |
| **外部阻塞** | `Unknown component type named farmersdelight:backstabbing` 是 **PacketEvents 自身**的注册表解码问题，可能阻断玩家连接。它与「Worker TCP 是否建立」是**两个独立问题**，需分开确认 |

---

# 第十部分：许可与来源

- 设计思路源自 [Freesia II](https://github.com/NguyenDevs/FreesiaII)（MrHua269 / NguyenDevs 等）。
- YSM 协议事实来自对 YSM 发行 jar 的反编译与 MIT 许可的 OpenYSM 源码；本项目**不含** YSM 或 OpenYSM 代码。
- 仓库根 `LICENSE`：MIT License，Copyright (c) 2026 YIYU0321。
