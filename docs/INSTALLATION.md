# 安装与配置

[文档索引](README.md) · [诊断与排障](TROUBLESHOOTING.md)

## 部署前准备

准备 Java 21、可正常登录和切服的 Velocity / NeoForge 业务子服网络，以及单独的 NeoForge Worker 服务端。版本组合见 [首页兼容环境](../README.md#兼容环境)。

| 运行位置 | 安装内容 | 职责 |
| --- | --- | --- |
| 玩家客户端 | NeoForge 1.21.1、YSM 2.6.5 及其前置、业务服要求的其他 Mod | 显示模型与实际游玩 |
| Velocity 代理 | PacketEvents Velocity 2.13.0、Freesia Velocity 插件 | 玩家路由、YSM 转发与实体映射 |
| Worker | NeoForge 1.21.1、Worker Mod、YSM 2.6.5 及其前置 | 接收假客户端连接、处理模型同步 |
| 业务子服 | 正常游戏环境及适配的 Velocity 转发方案 | 世界、玩家、实体与游戏逻辑 |

业务子服卸下 YSM，使模型同步只由 Worker 承担。代理会吞掉业务子服发出的目标 YSM 通道消息。本仓库不包含业务服侧的 tracker 插件，也不替代原有 Velocity / NeoForge 登录转发配置；现有 NeoVelocity 等转发方案应继续保持正确配置。

## 获取部署文件

从 [Releases](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/releases) 获取所选版本的两个 JAR。测试构建位于 [Actions](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/actions/workflows/build.yml) 成功运行的 Artifacts 中；下载后解压。

当前源码对应：

- Velocity：`Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar`
- Worker：`freesianeoforgeworker-1.0.0.jar`

自行构建时不要部署 `*-thin.jar` 或 `*-sources.jar`。YSM 与第三方前置需要另行获取，不包含在本仓库产物中。

## 配置 Worker

新建独立 NeoForge 1.21.1 服务端；当前 Worker 构建使用 NeoForge `21.1.248`。在 `mods/` 放入 Worker Mod、`ysm-2.6.5-neoforge+mc1.21.1-release.jar` 及该 YSM 发行版声明的前置。

`server.properties` 示例：

```properties
server-port=25566
online-mode=false
difficulty=peaceful
spawn-monsters=false
level-type=minecraft:flat
view-distance=2
simulation-distance=2
spawn-protection=0
```

端口可自行调整，但应与代理配置一致。通过内网或防火墙仅允许代理访问：Worker 使用离线模式，Mod 会绕过白名单、人数上限和封禁检查。**不要把 Worker 加入 Velocity 的 `servers` 列表，也不要把它当作可游玩的子服。**

按 NeoForge 服务端流程完成首次启动与 EULA 设置，确认 NeoForge、Worker Mod 和 YSM 正常加载。

假客户端 NBT 存放于 `<世界目录>/freesia_playerdata/<uuid>.dat`，默认是 `world/freesia_playerdata/`。世界常规保存被精简，但这份玩家数据仍会写盘，应保留并备份。

## 配置 Velocity

1. 停止 Velocity。
2. 在 `plugins/` 放入 PacketEvents Velocity `2.13.0` 和本项目的 Velocity JAR，同一插件只保留一个版本。
3. 启动一次，生成 `plugins/freesianeo/config.properties`。
4. 修改配置：

   ```properties
   worker_address=localhost:25566
   debug=false
   ```

5. 重启 Velocity，确认插件版本及 `[Freesia] Enabled. Worker = 主机:端口`。

| 配置键 | 示例 | 说明 |
| --- | --- | --- |
| `worker_address` | `localhost:25566` | 代理直连 Worker 的主机与端口，需显式填写完整地址 |
| `debug` | `false` | 开启后增加世界切换、实体追踪与模型补发日志 |

`localhost` 指代理进程所在主机或容器。跨主机、跨容器时填写代理实际可达的 Worker 地址，例如 `worker:25566`。

生成模板的地址是 `localhost:25566`，但代码的异常回退地址仍是 `mc.cloudeindustry.fun:41485`。配置读写失败或地址格式不完整时可能使用内置主机，因此务必以启动日志确认最终地址。当前没有配置热重载入口，修改后重启代理。

## 游戏内验收

当前版本尚未完成真实服务器端到端验证，部署后应记录以下检查结果：

1. 使用带 YSM 的客户端登录业务服，在第三人称中选择并观察模型。
2. 确认代理日志经过 `BACKEND_CONNECTED`、`TCP_CONNECTED`、`PLAY_READY` 与 YSM 握手阶段；日志解释见 [排障指南](TROUBLESHOOTING.md)。
3. 在子服 A → B → A 切换，每次等待约 3 秒，检查自身模型。
4. 播放一次动画后再切服，检查补发的是所选模型。
5. 两位玩家在同一子服互看；离开视野后重新靠近，分别检查双方看到的模型。
6. 测试快速连续切服、跨维度、死亡重生与退出重连；重连场景同时检查 Worker 玩家数据恢复。

观察握手或看到 `accepted=true` 只能证明对应阶段被记录，不能替代游戏内模型显示验证。出现问题时保留同一时段的客户端、Velocity、Worker 日志，区分“自己看自己”和“他人看自己”的结果。

## 升级与回滚

升级前停止对应组件并备份配置和 Worker 玩家数据。替换 JAR 时移出同组件的旧版本，保留 `config.properties` 和 `freesia_playerdata/`，重启后核对版本与地址，再重复游戏内验收。

回滚时停止对应组件、放回之前的 JAR，并核对其与配套组件、YSM 的版本组合；如需恢复数据，使用升级前的备份。
