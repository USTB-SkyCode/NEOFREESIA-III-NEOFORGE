# FREESIA-III-NEOFORGE

**面向 NeoForge 1.21.1 + Yes Steve Model (YSM) 2.6.5 的 Velocity 模型转发方案。**

把 YSM 的模型同步流量从业务后端改道到独立的 **Worker 节点**，并在代理两侧做实体 ID 重映射。

---

## 组件

| 目录 | 产物 | 版本 | 运行位置 |
|---|---|---|---|
| [`Freesia-NeoForge-Velocity/`](Freesia-NeoForge-Velocity/README.md) | `Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` | `1.0.3-diagnostic` | Velocity 代理 `plugins/` |
| `freesianeoforgeworker-1.21.1/` | `freesianeoforgeworker-1.0.0.jar` | `1.0.0` | NeoForge 1.21.1 Worker 服务端 `mods/` |

插件侧的完整文档见 **[Freesia-NeoForge-Velocity/README.md](Freesia-NeoForge-Velocity/README.md)**
（架构、协议、诊断日志、构建、部署、验收、已知限制）。

---

## 为什么需要它

YSM 的模型同步不是普通插件消息往返，它依赖**服务端直连的 Netty 连接**：

- 服务端用 `connection.channel().unsafe().outboundBuffer()` 做模型流控与限速；
- 对连接处理器类名做严格校验。

因此「Velocity + 多后端子服」组网里，只做插件消息透传无法让模型正常工作。
本方案复刻 **Freesia II** 思路：**在代理处终结协议 → 改道到专门计算节点 → 回灌真实客户端**。

```
真实客户端 (YSM 2.6.5)
      │
      ▼
 Velocity + freesianeo 插件 ──MCPRotocolLib 假客户端──▶ Worker (NeoForge+YSM, 只算模型)
      │
      └──正常 MC 协议──▶ 业务后端子服 (玩家实际游玩, 不参与 YSM)
```

---

## 快速开始

```shell
# 1) Velocity 插件
cd Freesia-NeoForge-Velocity
gradlew.bat build            # build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar
gradlew.bat regressionTest   # 45 项本地回归检查

# 2) Worker mod
cd ../freesianeoforgeworker-1.21.1
gradlew.bat build            # build/libs/freesianeoforgeworker-1.0.0.jar
```

部署要点（详见插件 README）：

1. Velocity 需安装 **PacketEvents 2.13.0**（硬依赖）；
2. Worker 端装 `freesianeoforgeworker` + `ysm-2.6.5-neoforge+mc1.21.1-release.jar`，
   **不要**加进 Velocity 的 `servers` 列表；
3. 业务后端子服卸掉 YSM，避免与 Worker 双重计算；
4. 在 `plugins/freesianeo/config.properties` **显式**填写 `worker_address`。

---

## 在线构建（GitHub Actions）

工作流位于 **[`.github/workflows/build.yml`](.github/workflows/build.yml)**，
在 push / pull_request / 手动触发时于 `ubuntu-latest` 上构建两个组件。

两个组件是**彼此独立的 Gradle 工程**（没有聚合 `settings.gradle`），
所以这里是两个并行 job，而不是一次 `./gradlew build`：

| Job | 工作目录 | 做什么 | 产物 |
|---|---|---|---|
| `velocity-plugin` | `Freesia-NeoForge-Velocity` | `build` + `regressionTest`，并断言产物确实是 shaded jar | `Freesia-NeoForge-Velocity` |
| `worker-mod` | `freesianeoforgeworker-1.21.1` | `build`（ModDevGradle 会下载并反编译 Minecraft，较慢） | `freesianeoforgeworker` |

构建产物在该次 Actions 运行页面的 **Artifacts** 区域下载。

### 为此做的三处调整

官方 runner 上没有你本机的环境，所以顺手清掉了几个会让 CI 直接失败或产出错包的问题：

1. **代理不再写死在入库文件里。**
   `freesianeoforgeworker-1.21.1/gradle.properties` 原先硬编码 `127.0.0.1:7897`。
   runner 上没有这个代理，会导致**一个依赖都下载不到**。
   现已移到被 `.gitignore` 忽略的 `gradle-local.properties`，由 `settings.gradle` 自动加载：
   本机行为不变，CI 与别人克隆后也能直接构建（文件不存在时静默跳过）。

2. **BMCLAPI 镜像改为可选。**
   原先无条件把官方 Mojang 仓库替换成 `bmclapi2.bangbang93.com`。
   它只是国内网络下的镜像，不应成为所有人构建的必经环节。
   现在默认使用官方仓库，本机需要在 `gradle-local.properties` 里设
   `useBmclapiMojangMirror=true`（或用 `-PuseBmclapiMojangMirror=true`）。

3. **修掉 jar 档名冲突。**
   `shadowJar` 的 `archiveClassifier` 是空串，普通 `jar` 也是空串，
   两者会写出同一个档名，谁后执行谁覆盖 —— 也就是说**薄 jar 覆盖 shaded jar** 是可能发生的。
   已给薄 jar 加 `thin` classifier；CI 另外断言交付物里含 `mcprotocollib`
   与 `velocity-plugin.json`，避免把一个部署即 `NoClassDefFoundError` 的包发出去。

同时收尾了几项仓库卫生问题：此前 `build/`（含**过期的 1.0.0 jar**）和 `.gradle/`
是被 git 跟踪的，CI 会把它当构建产物上传，因此已取消跟踪并补齐根目录 `.gitignore`；
`gradlew` 的执行位也补成了 `100755`。
另外删除了 `freesianeoforgeworker-1.21.1/.github/workflows/build.yml` ——
GitHub 只读取**仓库根**的 `.github/workflows/`，那个嵌套的工作流从来不会被执行。

### 本机构建

```shell
cd freesianeoforgeworker-1.21.1
copy gradle-local.properties.example gradle-local.properties   # 按需填写代理
```

### 验证边界

工作流只证明**代码能编译、打包，且 45 项回归断言通过**。
`SwitchRegressionTest` 是普通 `main()` 程序，用内存中的 Velocity 接口桩驱动真实
`MapperManager` / `YsmPacketProxy`，**不启动真实的 Minecraft 客户端或服务端**，
因此不能替代连接真实服务器的联调（见下方「已知限制」）。

---

## 版本历史

| 版本 | 要点 |
|---|---|
| `1.0.0` | 原始骨架 |
| `1.0.1-switchfix` | 切服模型保持修复 |
| `1.0.2-visibilityfix` | 模型按 UUID 缓存、投递时重写 ID；`SPAWN_ENTITY` 支持；per-view 可见表；tracking revision |
| **`1.0.3-diagnostic`** | **当前版本**：连接阶段/断开原因/异常堆栈日志；原生实体包改为只读副本前缀 |

> `1.0.1` / `1.0.2` 未以源码形式保留在本仓库；其修复内容已包含在 `1.0.3` 中。

---

## 已知限制

- C2S 未做反向实体 ID 重映射；
- 不含跨服 tracker 可见性同步；
- **未在真实服务器上验证**：本地回归测试直接驱动 Java 逻辑，不覆盖 YSM 资源缓存/握手生命周期与网络延迟；
- `Unknown component type named farmersdelight:backstabbing` 属 **PacketEvents 自身**的
  注册表解码问题，可能阻断玩家连接，与本插件 Worker 连接是两个独立问题。

---

## 许可

MIT License，Copyright (c) 2026 YIYU0321（见 [`LICENSE`](LICENSE)）。

设计思路源自 [Freesia II](https://github.com/NguyenDevs/FreesiaII)。
YSM 协议事实来自对 YSM 发行 jar 的反编译与 MIT 许可的 OpenYSM 源码；本仓库不含 YSM 或 OpenYSM 代码。
