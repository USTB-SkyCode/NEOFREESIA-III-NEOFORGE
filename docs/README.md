# 文档索引

[返回项目首页](../README.md)

| 文档 | 内容 | 适用读者 |
| --- | --- | --- |
| [安装与配置](INSTALLATION.md) | 组件分工、Worker 和代理配置、游戏内验收、升级与回滚 | 服主 / 运维 |
| [架构与协议](ARCHITECTURE.md) | Worker 连接、UUID 缓存、接收者视图、YSM 包处理 | 开发者 |
| [开发与发布](DEVELOPMENT.md) | 本地构建、网络设置、回归测试、CI、Release、贡献要求 | 开发者 / 维护者 |
| [诊断与排障](TROUBLESHOOTING.md) | 日志阶段、连接故障、模型恢复、Issue 信息 | 服主 / 开发者 |
| [版本历史](CHANGELOG.md) | 已有版本的功能演进与当前状态 | 所有读者 |

组件级说明与源码入口：

- [Velocity 插件](../Freesia-NeoForge-Velocity/README.md)
- [NeoForge Worker Mod](../freesianeoforgeworker-1.21.1/README.md)

源码与构建文件保留在各自的独立 Gradle 工程中；共同部署、技术和维护说明集中在本目录。运行配置 `config.properties` 在部署后的 Velocity 数据目录生成，本机网络配置 `gradle-local.properties` 由开发者自行创建。

文档中的版本以当前源码为依据。两组件独立版本化，Git tag 不会自动更新 JAR 版本；真实服务器的联调结果需另行记录。

## 外部资源

- [Velocity 文档](https://docs.papermc.io/velocity/)
- [NeoForge 文档](https://docs.neoforged.net/)
- [Freesia II](https://github.com/NguyenDevs/FreesiaII)
- [YSM 的 Freesia 插件说明](https://ysm.cfpa.team/wiki/freesia-plugin/)：用于背景参考；其上游组件与配置不能直接替代本项目设置。
