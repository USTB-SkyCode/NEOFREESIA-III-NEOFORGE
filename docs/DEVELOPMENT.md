# 开发与发布

[文档索引](README.md) · [架构与协议](ARCHITECTURE.md) · [版本历史](CHANGELOG.md)

## 工程布局与构建

安装 JDK 21。两个组件各自有 `settings.gradle`、`build.gradle` 和 Gradle Wrapper，仓库根没有聚合工程：

| 组件目录 | Wrapper 版本 | 构建产物 |
| --- | --- | --- |
| `Freesia-NeoForge-Velocity/` | Gradle `8.10.2` | `build/libs/Freesia-NeoForge-Velocity-1.0.3-diagnostic.jar` |
| `freesianeoforgeworker-1.21.1/` | Gradle `9.2.1` | `build/libs/freesianeoforgeworker-1.0.0.jar` |

从仓库根执行：

```powershell
# Windows PowerShell
Set-Location Freesia-NeoForge-Velocity
.\gradlew.bat build
Set-Location ..\freesianeoforgeworker-1.21.1
.\gradlew.bat build
Set-Location ..
```

```bash
# Linux / macOS
(cd Freesia-NeoForge-Velocity && ./gradlew build)
(cd freesianeoforgeworker-1.21.1 && ./gradlew build)
```

两个工程可以分别导入 IDE。Worker 使用 ModDevGradle，首次构建会下载并处理 Minecraft、NeoForge 和 Parchment 映射，需要足够磁盘空间与网络访问。

Velocity 的完整部署包由 `shadowJar` 生成，包含 MCProtocolLib 等依赖；普通 `jar` 使用 `thin` classifier，避免两项任务覆盖同名产物。部署与发布均排除 `*-thin.jar` 和 `*-sources.jar`。

## 本机网络设置

Worker 提供本机配置模板。在其工程目录执行：

```powershell
Copy-Item gradle-local.properties.example gradle-local.properties
```

Linux / macOS 使用 `cp gradle-local.properties.example gradle-local.properties`。按需启用 HTTP 代理或镜像，例如：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
useBmclapiMojangMirror=true
```

端口应替换为本机代理的实际端口；不需要的选项保持注释。`gradle-local.properties` 已被忽略，缺失时 `settings.gradle` 会跳过加载。不要把本机地址写入共享的 `gradle.properties`。

BMCLAPI Mojang 镜像默认关闭，默认保留 ModDevGradle 的官方 Mojang 仓库；也可通过 `-PuseBmclapiMojangMirror=true` 请求开启。本机文件注入的系统属性优先于该项目属性。

本机文件在 settings 插件解析之后加载，无法为更早解析的 Foojay 插件提供代理。如 Gradle 插件门户本身也需要代理，将相应 `systemProp.*` 放入 Gradle 用户目录的 `gradle.properties`（默认 `~/.gradle/gradle.properties`）。

Velocity 当前使用 Maven Central、PaperMC、OpenCollab、CodeMC 等仓库及阿里云镜像，Wrapper 下载地址使用腾讯云 Gradle 镜像；其工程不自动加载 Worker 的本机配置文件，需要代理时使用 Gradle 用户配置。

## 回归测试与验证边界

在 Velocity 工程目录执行：

```powershell
.\gradlew.bat regressionTest
.\gradlew.bat check
```

`check` 依赖 `regressionTest`，`build` 包含 `check`。测试入口是普通 Java `main()` 程序 [SwitchRegressionTest.java](../Freesia-NeoForge-Velocity/src/test/java/SwitchRegressionTest.java)，不使用 JUnit；标准 `test` 任务已禁用。

现有套件包含 45 项断言，主题如下：

| 主题 | 覆盖内容 |
| --- | --- |
| 模型缓存 | 就绪前只缓存、初始补发、动画不覆盖模型、最新状态读取 |
| 身份与路由 | 同服各接收者独立重写 ID、跨服同 ID 隔离、未知实体丢弃 |
| 生命周期 | 切服、配置阶段、重生、离线清理、迟到回调和旧世界失效 |
| 可见性 | 实体销毁、ID 重用、反复进出视野、同 ID 重进的追踪代次 |
| 数据与解析 | 缓存副本、VarInt ID 缩短保留正文、畸形负载处理 |
| 诊断与观察 | 阶段判定、TCP 与登录区别、实体包只读解析 |

测试使用内存中的 Velocity 接口替身驱动真实 `MapperManager`、`YsmPacketProxy` 等代码，**不启动真实客户端或服务器**。Worker 当前没有专门的自动化回归套件。

编译和本地回归不覆盖真实 YSM 资源缓存、完整握手、网络延迟或 PacketEvents 在完整模组环境下的行为。涉及协议或生命周期的修改还需 [游戏内验收](INSTALLATION.md#游戏内验收)，并记录实际环境与结果。

## GitHub Actions

根工作流 [build.yml](../.github/workflows/build.yml) 在分支 push、`v*` tag push、PR 和手动触发时运行：

| Job | 任务 | Artifact |
| --- | --- | --- |
| `velocity-plugin` | JDK 21、`build regressionTest`，校验部署包包含 MCProtocolLib 与插件描述 | `Freesia-NeoForge-Velocity` |
| `worker-mod` | JDK 21、`build`，上传 Worker JAR | `freesianeoforgeworker` |
| `release` | 等待两个构建成功，校验各有一个部署 JAR，创建 Release 或覆盖附件 | 两个 JAR 附件 |

两个构建 job 在 Ubuntu 上并行运行。分支 push、PR 和未填写 tag 的手动构建会跳过 `release`，其结论为 `skipped` 属于正常行为。

## 版本与发布检查

两个组件独立版本化。发布前核对：

| 文件 | 字段 | 当前值 |
| --- | --- | --- |
| [Velocity build.gradle](../Freesia-NeoForge-Velocity/build.gradle) | `version` | `1.0.3-diagnostic` |
| [velocity-plugin.json](../Freesia-NeoForge-Velocity/src/main/resources/velocity-plugin.json) | `version` | `1.0.3-diagnostic` |
| [Worker gradle.properties](../freesianeoforgeworker-1.21.1/gradle.properties) | `mod_version` | `1.0.0` |

Velocity 两处版本必须一致，否则文件名和运行时版本不同。Git tag 不会自动修改组件版本，因此 tag 与 Worker JAR 版本可以不同，发布说明应清楚标注。

Worker 的 `mod_license` 当前仍为模板值 `All Rights Reserved`，但根许可证为 MIT；维护者应在发布前核对元数据与实际授权，文档重整未改变这一字段或授权文件。

维护者确认版本、构建与文档后，可推送未使用的 `v*` tag，例如：

```bash
git tag v1.0.3-diagnostic
git push origin v1.0.3-diagnostic
```

请按实际发布版本选择 tag。两个构建均成功后工作流自动创建 Release；含 `-` 的 tag 自动标记为预发布。新 Release 使用 GitHub 自动生成说明，部署附件排除薄 JAR 和源码包。

### 重跑与补发

已有 tag 的发布失败时，可重跑原 tag 的 Actions 运行。也可使用 **Run workflow** 并填写已存在的 `v*` tag：已有 Release 会通过 `gh release upload --clobber` 覆盖同名附件，没有 Release 时通过 `--verify-tag` 校验远端 tag 后创建。

**手动补发时，构建基于所选运行 ref，输入的 tag 只决定 Release 目标。** 应在 ref 选择器中选择目标 tag，确保构建源码与 Release 对应提交一致；不要把其他分支的构建误挂到旧 tag。

## 参与贡献

提交 Issue 时提供组件版本、复现步骤、预期与实际行为，以及同一时段的 Velocity / Worker 日志。请先按 [排障指南](TROUBLESHOOTING.md) 确认连接停在哪一阶段，日志中的凭据等敏感内容应脱敏。

提交 Pull Request 时：

- 修改路由、缓存或协议解析后运行 Velocity 回归；修改 Worker 后构建 Worker；跨组件修改分别检查两个工程。
- 说明实际运行过的检查，并区分本地逻辑验证与真实游戏内验证。
- 配置、版本、依赖或部署行为变化时，同步更新对应文档。
- 不提交 `build/`、`.gradle/`、运行目录、本机代理配置或第三方 Mod JAR。
- Commit 使用类型前缀与中文说明，例如 `docs: 重整项目说明与部署文档`、`fix: 修复切服后的模型映射`。
- 代码注释保持简洁，仅在需要时用一行中文说明某部分代码功能，避免带日期的大段注释。
