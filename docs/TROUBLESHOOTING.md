# 诊断与排障

[文档索引](README.md) · [安装与配置](INSTALLATION.md)

## 先确认日志入口

基础诊断日志默认输出，无需开启 `debug=true`。先检查：

1. Velocity 是否加载了 `1.0.3-diagnostic` 与 PacketEvents `2.13.0`，且没有同插件的重复 JAR。
2. `Enabled. Worker = ...` 是否为实际 Worker 地址。
3. 玩家是否正常进入业务子服，是否出现 `BACKEND_CONNECTED`。
4. Worker 是否正常加载 NeoForge、Worker Mod 与 YSM。

登录后保持在线约 35 秒，保留 Velocity 与 Worker 同一时段的完整日志。会话创建或复用后安排 10 秒、30 秒的 `STATUS` 输出；玩家离线或会话关闭后不再输出。搜索 `[Freesia]` / `[Freesia-DIAG]` 时也保留附近异常堆栈。

完全没有 `BACKEND_CONNECTED` 时，尚未走到本插件创建 Worker 会话的入口，先排查业务服登录链路。

## 连接阶段

```text
TCP_CONNECT
    → MINECRAFT_LOGIN_OR_CONFIGURATION
    → WORKER_YSM_HANDSHAKE
    → CLIENT_YSM_REPLY
    → YSM_HANDSHAKE_OBSERVED
```

| STATUS 中的 stage | 表示 | 优先检查 |
| --- | --- | --- |
| `TCP_CONNECT` | 尚未观察到 Worker TCP 建立 | 地址、端口、DNS、防火墙、拒绝连接和超时 |
| `MINECRAFT_LOGIN_OR_CONFIGURATION` | TCP 已建立，尚未收到进入 Worker 的游戏包 | `lastRx` / `lastTx`、Worker 拒绝原因、Minecraft / NeoForge 版本 |
| `WORKER_YSM_HANDSHAKE` | Worker 实体 ID 已知，尚未看到 YSM 51 | Worker YSM 加载、前置、版本与通道 |
| `CLIENT_YSM_REPLY` | 收到 Worker YSM 51，尚未看到客户端 52 | 客户端 YSM、代理转发、业务服连接是否中断 |
| `YSM_HANDSHAKE_OBSERVED` | 两个方向的握手包都被观察到 | 继续验证资源同步与游戏内模型显示 |

stage 是观察标记的汇总，不是完整协议成功认证。客户端 52 的观察标记在实际发送前记录；如果同时出现 `YSM_C2S_NOT_SENT`，不能据此认定 Worker 收到了回复。

## 关键日志

| 日志 | 含义与判读 |
| --- | --- |
| `BACKEND_CONNECTED` | 玩家已到业务子服，开始创建或复用 Worker 会话 |
| `Worker CONNECTING` | 开始连接 Worker |
| `Worker TCP_CONNECTED` | TCP 已建立，不表示 Minecraft 或 YSM 登录成功 |
| `WORKER_RX` / `WORKER_TX` | 登录 / 配置阶段收发包的类名 |
| `Worker PLAY_READY` | 收到 Worker 进入游戏包，获得 Worker 实体 ID |
| `YSM_WORKER_RX packetId=51` | 观察到 Worker 的 YSM 握手 |
| `YSM_CLIENT_SEND ... accepted=true` | Velocity 接受发送请求，不表示客户端已处理 |
| `YSM_CLIENT_RX packetId=52` | 观察到真实客户端的握手回复 |
| `YSM_WORKER_TX_CONFIRMED` | MCProtocolLib 发送完成回调，不表示 Worker 已处理 |
| `YSM_C2S_NOT_SENT` | 收到客户端 YSM 包时 Worker 连接不存在或已断开 |
| `Worker PACKET_ERROR` | Worker 网络包处理异常，结合堆栈检查 |
| `Worker DISCONNECTED` | 断开原因、最后阶段、最后收发包及底层异常 |

部分日志按阶段或包 ID 去重，日志行数不能直接当成负载包数量；`STATUS` 的 `ysmRx` / `ysmTx` 提供会话计数。Worker 的 `Dropping packet` 表示出站白名单过滤了某包，需结合包类型与失败阶段判断。

## 模型恢复问题

将代理配置设为 `debug=true` 并重启，复现 [游戏内验收](INSTALLATION.md#游戏内验收) 中对应场景。检查：

| 调试日志 | 意义 |
| --- | --- |
| `New client world ... self entity ...` | 当前世界与自身实体 ID |
| `Restored N model(s)` | N=0 表示没有成功补发；N>0 只证明发送调用成功 |
| `Track ... entity=... revision=...` | 接收者建立了实体可见映射 |
| `Retrack model ... cached=... sent=...` | 视野重试时是否有缓存、是否成功调用发送 |
| `Dropped unmapped Worker entity ...` | 找不到 Worker 实体归属，包被丢弃 |

分别记录“自己看自己默认”与“其他人看你默认”。若握手都观察到但仍没有模型，应检查 YSM 资源日志、客户端实际收到的数据、缓存与可见映射，不能只凭 TCP 或握手日志判断成功。

## 常见问题

**启动后 Worker 地址不是配置中的地址**

确认修改的是 `plugins/freesianeo/config.properties`，使用完整 `主机:端口` 格式，且文件可读写。配置异常时可能回退到内置地址，详见 [代理配置](INSTALLATION.md#配置-velocity)。修改后重启。

**出现 `NoClassDefFoundError`**

确认部署的是包含 MCProtocolLib 的完整 JAR，而非 `*-thin.jar`；同时检查报错类所属依赖与 PacketEvents 是否加载。

**出现 `Unknown component type named farmersdelight:backstabbing`**

这是既有文档记录的 PacketEvents 注册表解码异常，可能阻断玩家登录。保留完整堆栈，核对 PacketEvents 与整包 Mod 的兼容性。它与 Worker TCP 连接状态是两个独立问题，应分别检查。

**Worker 断开后没有立即重连**

当前没有周期性自动重连任务。修复 Worker 后可重新登录或触发新的业务服连接事件，再确认是否创建了新会话。

**退出重连后模型丢失**

检查 Worker 世界目录下 `freesia_playerdata/` 的文件与写入权限。当前 NBT 保存失败会被忽略、读取失败会回退初始模板；代理内存缓存则在玩家断开时清理。

**Actions 的 Release 被跳过**

分支 push、PR 和未填 tag 的手动构建只构建、不发布，这是预期行为。发布条件和补发方法见 [开发与发布](DEVELOPMENT.md#github-actions)。

## 报告问题

在 [Issues](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/issues) 中提供：

- Minecraft、NeoForge、Java、Velocity、PacketEvents、YSM 和两个组件的版本。
- 客户端 / 代理 / Worker / 业务服的部署关系，相关配置与 Mod 列表。
- 可重复的步骤、预期结果、实际结果，以及故障影响哪个接收者。
- 同一时段的 Velocity、Worker 日志；模型显示问题补充客户端日志。
- 本地回归、构建及游戏内验收的实际结果。

提交前移除日志和配置中的凭据等敏感信息，保留包类名、错误堆栈与阶段信息供排查。
