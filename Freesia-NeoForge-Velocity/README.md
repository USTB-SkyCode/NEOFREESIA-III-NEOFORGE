# Freesia NeoForge · Velocity 插件

[项目首页](../README.md) · [安装与配置](../docs/INSTALLATION.md) · [架构与协议](../docs/ARCHITECTURE.md) · [诊断与排障](../docs/TROUBLESHOOTING.md)

本组件拦截 YSM 通道，通过 MCProtocolLib 将消息交给独立 NeoForge Worker，再按真实客户端的视角重映射实体 ID。玩家的正常游戏连接仍由 Velocity 转发至业务子服。

| 项目 | 当前值 |
| --- | --- |
| 插件 ID | `freesianeo` |
| 版本 | `1.0.3-diagnostic` |
| 部署产物 | `build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` |
| 编译环境 | JDK 21、Velocity API `3.3.0-SNAPSHOT` |
| 运行依赖 | PacketEvents Velocity 插件 `2.13.0` |
| 配套环境 | Minecraft 1.21.1、NeoForge、YSM 2.6.5、[Worker Mod](../freesianeoforgeworker-1.21.1/README.md) |

## 安装与配置

将部署产物与 PacketEvents 放入 Velocity 的 `plugins/`，同一插件只保留一个版本。首次启动会生成 `plugins/freesianeo/config.properties`：

```properties
worker_address=localhost:25566
debug=false
```

填写代理可达的 Worker 地址，重启后确认 `[Freesia] Enabled. Worker = 主机:端口`。基础连接诊断默认输出；`debug=true` 额外记录可见性追踪和模型补发。

Worker 必须单独运行，且不加入 Velocity 的 `servers` 列表。客户端、Worker、业务子服的具体安装分工见 [安装指南](../docs/INSTALLATION.md)。

## 构建与验证

在本目录执行：

```powershell
.\gradlew.bat build
.\gradlew.bat regressionTest
```

Linux / macOS 使用 `./gradlew`。`build` 经 `check` 自动执行 `regressionTest`；第二条命令用于单独运行回归套件。部署时选择包含 MCProtocolLib 的 JAR，`*-thin.jar` 不包含运行所需的全部依赖。

`SwitchRegressionTest` 通过内存中的 Velocity 接口替身驱动真实路由与解析代码，不启动 Minecraft 客户端或服务器。真实模型同步仍需 [游戏内验收](../docs/INSTALLATION.md#游戏内验收)。

## 源码导航

路径均相对于 `src/main/java/com/nguyendevs/freesia/neoforge/`。

| 文件 / 目录 | 职责 |
| --- | --- |
| [FreesiaNeoForge.java](src/main/java/com/nguyendevs/freesia/neoforge/FreesiaNeoForge.java) | 插件入口、Velocity 事件、PacketEvents 实体包观察与延迟补发 |
| [FreesiaConfig.java](src/main/java/com/nguyendevs/freesia/neoforge/FreesiaConfig.java) | `config.properties` 的生成与读取 |
| [YsmConstants.java](src/main/java/com/nguyendevs/freesia/neoforge/YsmConstants.java) | YSM 通道与包 ID 常量 |
| [mapper/MapperManager.java](src/main/java/com/nguyendevs/freesia/neoforge/mapper/MapperManager.java) | UUID 模型缓存、接收者视图、可见性路由与会话管理 |
| [mapper/MapperSession.java](src/main/java/com/nguyendevs/freesia/neoforge/mapper/MapperSession.java) | 每玩家一条 Worker 连接、负载转发与阶段诊断 |
| [proxy/YsmPacketProxy.java](src/main/java/com/nguyendevs/freesia/neoforge/proxy/YsmPacketProxy.java) | YSM 负载解析、过滤与改写决策 |
| [util/](src/main/java/com/nguyendevs/freesia/neoforge/util/) | VarInt 工具与原生实体包的只读前缀解析 |

插件描述位于 [velocity-plugin.json](src/main/resources/velocity-plugin.json)，回归入口位于 [SwitchRegressionTest.java](src/test/java/SwitchRegressionTest.java)。

## 当前边界

当前版本以连接诊断为重点，尚未完成实服端到端验证；C2S 不做反向实体 ID 重映射，可见性依赖代理观察到的实体包。完整原理、日志判读和版本演进分别见 [架构](../docs/ARCHITECTURE.md)、[排障](../docs/TROUBLESHOOTING.md) 与 [版本历史](../docs/CHANGELOG.md)。
