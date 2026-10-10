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
