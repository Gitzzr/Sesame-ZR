# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## 先读这两处

本仓库已经建立了完整的文档体系，**不要在开发过程中从零重新探索**：

1. **[`AGENTS.md`](AGENTS.md)** —— AI 进入本项目首先需要知道的信息：8 条硬规则、环境现状、**已知阻塞（测试当前编译失败）**、常用命令、代码地图。
2. **[`TODO.md`](TODO.md)** —— 当前任务与优先级。P0 是必须先解决的两件事。

## 文档体系

| 文档 | 内容 |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | AI 入口：硬规则、环境、已知阻塞、代码地图 |
| [`TODO.md`](TODO.md) | 任务、优先级、进度（P0 阻塞 / P1 待验 / P2 缺陷） |
| [`DESIGN.md`](DESIGN.md) | 视觉规则：Compose 主题 / XML 资源 / Web 页面三套体系 |
| [`docs/project-overview.md`](docs/project-overview.md) | 项目定位、技术栈、能力范围、目录结构、发布形态 |
| [`docs/architecture.md`](docs/architecture.md) | 架构与数据流：注入链路、RPC 链路、任务调度、配置同步 |
| [`docs/user-guide.md`](docs/user-guide.md) | 面向使用者的功能与设置项说明 |
| [`docs/development.md`](docs/development.md) | 环境搭建、命令、**回归清单**、故障排查 |
| [`docs/component-api.md`](docs/component-api.md) | 组件与对外 API：Compose 组件、Web JS 合同、Native↔JS 桥、HTTP 接口、ModelField |
| [`CHANGELOG.md`](CHANGELOG.md) | 用户可见改动的日期表 |
| `docs/superpowers/{specs,plans}/` | 逐个功能的设计稿与实施计划（含真实实施记录） |

## 三条最容易踩的

1. **目标分支是 `codex/dev`**，不是 main/master；CI 只认它（`debug.yml` 另监听 `yang`）。
2. **单元测试目前编译不过** —— `ChouChouLeSchedulePolicyTest.kt` 引用了不存在的 `ChouChouLeScheduleAction` / `ChouChouLeSchedulePolicy`。见 `TODO.md` P0-1。
3. **很多单测是「读源码文本做断言」**（`File("src/main/...")`）—— 必须经 Gradle 跑（工作目录是 `app/`），且**重命名标识符会打挂它们**。

其余一切（构建命令、环境要求、架构、约定）以 `AGENTS.md` 与 `docs/` 为准。
