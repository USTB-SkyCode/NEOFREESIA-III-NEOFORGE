# NEOFREESIA III · NeoForge

[![Build](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/actions/workflows/build.yml/badge.svg)](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62b47a)](#兼容环境)
[![Loader](https://img.shields.io/badge/Loader-NeoForge-e8752d)](#兼容环境)
[![Java](https://img.shields.io/badge/Java-21-437291)](#从源码构建)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**面向 Minecraft 1.21.1、NeoForge 与 Yes Steve Model 的 Velocity 模型转发方案。**

将 YSM 的模型同步交给独立 Worker 服务端处理，玩家继续在业务子服游玩。代理负责转发 YSM 消息、重映射实体 ID，并在切服或重新进入视野时补发模型状态。

[下载 Release](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/releases) · [安装指南](docs/INSTALLATION.md) · [文档索引](docs/README.md) · [报告问题](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/issues)

> **当前状态：诊断版。** Velocity 插件版本为 `1.0.3-diagnostic`，Worker Mod 版本为 `1.0.0`。仓库提供本地逻辑回归测试，但尚未完成真实 Minecraft 客户端与服务器的端到端验证。

## 功能

- **独立 Worker**：通过 MCProtocolLib 为每位玩家建立 Worker 连接，承接 YSM 模型同步。
- **实体 ID 重映射**：把 Worker 实体 ID 转换为各接收者在业务子服中看到的实体 ID。
- **模型状态恢复**：以玩家 UUID 缓存模型，切服、重生和重新进入视野时尝试补发。
- **可见性隔离**：观察后端实体生成与销毁包，仅向同子服、当前可见的接收者投递。
- **连接诊断**：记录 TCP、Minecraft 登录、YSM 握手、断开原因与异常堆栈。
- **精简 Worker**：停止世界 tick 与常规区块保存，过滤出站包，并单独保存假客户端的玩家 NBT。

实现细节见 [架构与协议](docs/ARCHITECTURE.md)。

## 工作方式

```text
Minecraft 客户端（NeoForge + YSM）
                │
                ▼
Velocity + PacketEvents + Freesia 插件
                ├── 正常游戏连接 ──▶ 业务子服（玩家实际游玩）
                │
                └── 每玩家一条假客户端连接 ──▶ Worker（NeoForge + YSM + Worker Mod）
                                               │
                         ◀── YSM 模型数据 ─────┘
```

YSM 模型同步涉及服务端连接、流控和实体身份，单纯透传插件消息不足以完成本项目需要的同步。本项目沿用 [Freesia II](https://github.com/NguyenDevs/FreesiaII) 的独立 Worker 思路，由代理在投递模型时匹配业务子服中的实体。

**Worker 不加入 Velocity 的 `servers` 列表；本仓库提供一个代理插件和一个 Worker Mod。**

## 兼容环境

以下是当前源码的目标版本与依赖声明，不代表已通过这些环境的实服兼容性认证。

| 项目 | 当前目标 / 要求 |
| --- | --- |
| Minecraft | `1.21.1` |
| Java | `21`，编译需要 JDK |
| NeoForge | Worker 构建使用 `21.1.248`；Mod 元数据声明 `[21.1.248,)` |
| Velocity | 编译使用 API `3.3.0-SNAPSHOT`；部署按 Velocity 3.3 系列配置 |
| PacketEvents | Velocity 版 `2.13.0`，代理插件的必需依赖 |
| Yes Steve Model | 客户端与 Worker 使用 NeoForge 1.21.1 版 `2.6.5`，并安装该发行版要求的前置 Mod |
| YSM 通道 | `yes_steve_model:2_6_0`；协议版本串为 `2.6.0` |

其他 Minecraft、YSM 版本及其他加载器尚未列为本项目的支持目标。

## 下载与安装

从 [Releases](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/releases) 下载两个组件；如需测试某次提交，可在 [Actions](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/actions/workflows/build.yml) 的成功运行中下载 Artifacts。

| 组件 | 当前部署文件 | 安装位置 |
| --- | --- | --- |
| [Velocity 插件](Freesia-NeoForge-Velocity/README.md) | `Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` | Velocity 的 `plugins/` |
| [Worker Mod](freesianeoforgeworker-1.21.1/README.md) | `freesianeoforgeworker-1.0.0.jar` | 独立 Worker 服务端的 `mods/` |

1. 准备可正常登录和切服的 Velocity / NeoForge 业务子服网络，在 Velocity 安装 PacketEvents。
2. 单独部署 NeoForge Worker，安装 Worker Mod、YSM 及其前置，设为离线模式，并限制连接来源为代理。
3. 业务子服卸下 YSM，让模型同步由 Worker 处理；客户端保留 YSM。
4. 在 Velocity 安装本插件，首次启动后修改 `plugins/freesianeo/config.properties`：

   ```properties
   worker_address=localhost:25566
   debug=false
   ```

5. 重启 Velocity，确认 `Enabled. Worker = ...` 显示实际 Worker 地址，再按 [验收步骤](docs/INSTALLATION.md#游戏内验收) 测试模型与切服。

`localhost:25566` 仅适用于 Worker 与代理运行在同一主机、该端口确实由 Worker 监听的情况。跨主机或容器部署请填写代理可达的地址。完整设置见 [安装与配置](docs/INSTALLATION.md)。

## 从源码构建

两个组件各自使用 Gradle Wrapper，**仓库根目录没有统一的构建入口**。安装 JDK 21 后，从仓库根目录执行：

```powershell
# Windows PowerShell
Set-Location Freesia-NeoForge-Velocity
.\gradlew.bat build
Set-Location ..\freesianeoforgeworker-1.21.1
.\gradlew.bat build
Set-Location ..
```

Linux / macOS 在对应目录使用 `./gradlew build`。产物位于各组件的 `build/libs/`；Velocity 应部署包含依赖的 JAR，排除 `*-thin.jar`，Worker 排除 `*-sources.jar`。

Velocity 的 `build` 会执行 `regressionTest`。网络代理、镜像、CI 和发版说明见 [开发与发布](docs/DEVELOPMENT.md)。

## 项目目录

```text
NEOFREESIA-III-NEOFORGE/
├── .github/workflows/build.yml       # 两组件构建、回归检查与 Release
├── docs/                             # 共享文档
│   ├── README.md                     # 文档索引
│   ├── INSTALLATION.md               # 安装、配置与验收
│   ├── ARCHITECTURE.md               # 架构与协议
│   ├── DEVELOPMENT.md                # 构建、测试、CI 与发布
│   ├── TROUBLESHOOTING.md            # 诊断与排障
│   └── CHANGELOG.md                  # 版本历史
├── Freesia-NeoForge-Velocity/         # 独立 Gradle 工程：Velocity 插件
│   ├── src/main/java/                # 插件入口、Worker 会话、路由与包解析
│   ├── src/main/resources/           # velocity-plugin.json
│   ├── src/test/java/                # SwitchRegressionTest
│   ├── gradle/wrapper/               # 此组件的 Gradle Wrapper
│   ├── build.gradle
│   ├── settings.gradle
│   └── README.md
├── freesianeoforgeworker-1.21.1/      # 独立 Gradle 工程：NeoForge Worker Mod
│   ├── src/main/java/                # Mod 入口、Mixin 与压缩编码器
│   ├── src/main/resources/           # Mixin 配置与语言资源
│   ├── src/main/templates/           # neoforge.mods.toml 元数据模板
│   ├── gradle/wrapper/               # 此组件的 Gradle Wrapper
│   ├── gradle-local.properties.example
│   ├── gradle.properties             # Minecraft / NeoForge / Mod 版本
│   ├── build.gradle
│   ├── settings.gradle
│   └── README.md
├── LICENSE
└── README.md
```

`build/`、`.gradle/`、`run/` 等是本机生成目录，已由 Git 忽略，不属于源码目录。

## 已知限制

- C2S 包尚未实现反向实体 ID 重映射，带实体 ID 的动画或表达式请求存在适配边界。
- 可见性依据代理观察到的实体包推断，未实现 Freesia II 的后端 tracker 上报机制。
- 本地回归不覆盖真实 YSM 资源同步、握手生命周期、网络延迟和完整模组环境。
- 当前仅配置单个 Worker 地址，未实现多 Worker 调度。

连接阶段、PacketEvents 注册表异常和模型恢复问题见 [诊断与排障](docs/TROUBLESHOOTING.md)。

## 参与贡献

欢迎通过 [Issues](https://github.com/USTB-SkyCode/NEOFREESIA-III-NEOFORGE/issues) 提交问题或通过 Pull Request 贡献代码。请说明组件版本、复现步骤，并附同一时段的 Velocity / Worker 日志；提交前参考 [贡献与验证要求](docs/DEVELOPMENT.md#参与贡献)。

版本演进见 [版本历史](docs/CHANGELOG.md)。

## 许可与致谢

本仓库根 [LICENSE](LICENSE) 为 MIT License，Copyright (c) 2026 YIYU0321。Worker 的 MDK 模板附带 [模板许可](freesianeoforgeworker-1.21.1/TEMPLATE_LICENSE.txt)。

设计思路源自 [Freesia II](https://github.com/NguyenDevs/FreesiaII) 及其贡献者。本项目是面向 NeoForge 的独立实现，不是 Yes Steve Model 官方项目。现有协议说明来源于 YSM 发行包分析与 OpenYSM 协议参考，仓库不包含 YSM 或 OpenYSM 代码。

感谢 [Velocity](https://github.com/PaperMC/Velocity)、[PacketEvents](https://github.com/retrooper/packetevents)、[MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) 与 [NeoForge](https://github.com/NeoForged/NeoForge) 提供的基础设施。
