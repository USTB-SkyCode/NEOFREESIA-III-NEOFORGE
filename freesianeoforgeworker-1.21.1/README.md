# Freesia NeoForge · Worker Mod

[项目首页](../README.md) · [安装与配置](../docs/INSTALLATION.md) · [架构与协议](../docs/ARCHITECTURE.md) · [开发与发布](../docs/DEVELOPMENT.md)

将独立 NeoForge 服务端精简为 YSM Worker 节点，接收 [Velocity 插件](../Freesia-NeoForge-Velocity/README.md) 创建的假客户端连接。YSM 负责模型同步，本 Mod 负责精简世界运行、保持连接和隔离玩家数据。

**本 Mod 安装在专用 Worker 服务端，不适合安装在玩家实际游玩的业务服或客户端。**

| 项目 | 当前值 |
| --- | --- |
| Mod ID | `freesianeoforgeworker` |
| 版本 | `1.0.0` |
| 部署产物 | `build/libs/freesianeoforgeworker-1.0.0.jar` |
| Minecraft | `1.21.1` |
| NeoForge 构建版本 | `21.1.248` |
| Java | `21` |
| 配套 Mod | NeoForge 1.21.1 版 YSM `2.6.5` 及其发行版要求的前置 |

## 部署

1. 创建独立 NeoForge 1.21.1 服务端，在 `mods/` 放入 Worker Mod、YSM 及其前置。
2. 将 Worker 设为离线模式，使用独立端口，并仅允许代理访问。
3. 在 Velocity 插件配置中填写此 Worker 的地址。
4. 保持 Worker 独立，不把它加入 Velocity 的 `servers` 列表。

完整 `server.properties` 示例及客户端、业务服配置见 [安装指南](../docs/INSTALLATION.md#配置-worker)。

## 运行机制

实现位于 `src/main/java/com/nguyendevs/freesia/neoforgeworker/`：

| 文件 | 行为 |
| --- | --- |
| [FreesiaNeoForgeWorker.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/FreesiaNeoForgeWorker.java) | Mod 入口，保存服务端实例供 Mixin 使用 |
| [mixin/MinecraftServerMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/MinecraftServerMixin.java) | 替换世界 tick，保留连接与玩家列表 tick；跳过常规区块保存 |
| [mixin/ConnectionMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/ConnectionMixin.java) | 过滤出站包；保留登录、配置、自定义负载和保活等包，替换压缩编码器 |
| [mixin/PlayerDataStorageMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/PlayerDataStorageMixin.java) | 保存与恢复假客户端玩家 NBT，首次连接使用初始模板 |
| [mixin/PlayerListMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/PlayerListMixin.java) | 绕过白名单、人数上限与封禁检查 |
| [mixin/ServerCommonPacketListenerImplMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/ServerCommonPacketListenerImplMixin.java) | 简化 keep-alive 响应处理 |
| [mixin/LivingEntityMixin.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/mixin/LivingEntityMixin.java) | 使 Worker 上的玩家免疫伤害 |
| [impl/FakeCompressionEncoder.java](src/main/java/com/nguyendevs/freesia/neoforgeworker/impl/FakeCompressionEncoder.java) | 写入未压缩帧头并复制正文，单包上限为 8 MiB |

连接过滤允许自定义负载包，不只允许 YSM 通道；由代理识别并处理目标 YSM 通道。网络隔离是部署要求，因为 Worker 为离线模式且绕过了常规玩家准入检查。

## 玩家数据

假客户端以真实玩家 UUID 连接 Worker，其 NBT 写入：

```text
<Worker 世界目录>/freesia_playerdata/<uuid>.dat
```

默认世界目录下即 `world/freesia_playerdata/`。保存的是完整玩家 NBT，包括随玩家序列化的 YSM 模型数据；此目录应纳入 Worker 备份。世界 tick 和常规区块保存被禁用，不等于所有数据都不写盘。

目前保存失败会被忽略，恢复失败会回退到初始玩家模板，因此模型持久化还取决于目录权限和 YSM 的实际序列化行为。

## 从源码构建

在本目录执行：

```powershell
.\gradlew.bat build
```

Linux / macOS 使用 `./gradlew build`。构建使用 ModDevGradle `2.0.144`，首次运行需要下载 Minecraft、NeoForge 和映射资源。版本集中于 [gradle.properties](gradle.properties)，Mod 元数据由 [neoforge.mods.toml 模板](src/main/templates/META-INF/neoforge.mods.toml) 生成，Mixin 列表位于 [freesianeoforgeworker.mixins.json](src/main/resources/freesianeoforgeworker.mixins.json)。

如需本机网络代理或 BMCLAPI 镜像，复制 [gradle-local.properties.example](gradle-local.properties.example) 为 `gradle-local.properties` 并按需填写，详见 [本机网络设置](../docs/DEVELOPMENT.md#本机网络设置)。

当前构建脚本保留 MDK 开发运行配置，但 YSM 不会自动下载或加入开发环境。`build` 成功不能证明已完成真实 YSM 联调，验收要求见 [游戏内验收](../docs/INSTALLATION.md#游戏内验收)。

## 许可

仓库根 [LICENSE](../LICENSE) 为 MIT，MDK 模板许可见 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。目前 `gradle.properties` 的 `mod_license` 仍为模板值 `All Rights Reserved`，生成的 Mod 元数据与根许可证存在文字差异，见 [发布检查](../docs/DEVELOPMENT.md#版本与发布检查)。
