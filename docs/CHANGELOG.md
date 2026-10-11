# 版本历史

[文档索引](README.md) · [发布流程](DEVELOPMENT.md#版本与发布检查)

本页整理已有 README 记录的功能演进。Velocity 插件与 Worker Mod 独立版本化，Release tag 不会自动修改组件版本。

## Velocity 插件

| 版本 | 主要变化 |
| --- | --- |
| `1.0.3-diagnostic`（当前源码） | 增加 Worker 连接阶段、断开原因与异常堆栈日志；原生实体包观察改为只读缓冲副本前缀 |
| `1.0.2-visibilityfix` | 模型按 UUID 缓存、投递时重写 ID；支持 `SPAWN_ENTITY`；按接收者维护可见表和 tracking revision |
| `1.0.1-switchfix` | 切服模型保持修复 |
| `1.0.0` | 初始骨架 |

`1.0.1` / `1.0.2` 未在本仓库以独立版本源码形式保留，其修复已包含在当前 `1.0.3-diagnostic` 中。本表不表示每一项都存在独立 GitHub Release。

## Worker Mod

当前版本为 `1.0.0`，源码包含世界 tick 精简、出站包过滤、保活处理、假客户端玩家 NBT 隔离保存与玩家无敌机制。已有文档未提供更细的独立版本记录。

## 验证状态

当前发布定位仍为连接诊断，尚未完成真实客户端与服务器端到端验证。Velocity 本地回归测试覆盖 Java 映射、路由与解析逻辑，不能替代真实 YSM 模型同步验收。
