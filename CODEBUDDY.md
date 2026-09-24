# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## 先读这两处

本仓库已经建立了完整的文档体系，**不要在开发过程中从零重新探索**：

1. **[`AGENTS.md`](AGENTS.md)** —— AI 进入本项目首先需要知道的信息：8 条硬规则、环境现状、**未完成功能（抽抽乐调度策略）**、常用命令、代码地图。
2. **[`TODO.md`](TODO.md)** —— 当前任务与优先级。P0 两条都已有结论：P0-2 环境搭建已完成、P0-1 编译阻塞已解但功能仍未实现。

## 文档体系

| 文档 | 内容 |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | AI 入口：硬规则、环境、未完成功能、代码地图 |
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

1. **主干是 `main`，且只有这一条长期分支**（GitHub Flow）：特性分支 → PR → Squash merge。CI 只认 `main`，禁止直推。
2. **抽抽乐调度策略是个半成品，不是「测试坏了」** —— `app/src/test/java/fansirsqi/xposed/sesame/task/antFarm/ChouChouLeSchedulePolicyTest.kt` 引用的 `ChouChouLeScheduleAction` / `ChouChouLeSchedulePolicy` **在主源码中不存在**，该测试已用 `@Ignore` 跳过、断言注释保留成契约。**`./gradlew :app:testDebugUnitTest` 是可编译、可执行的**（该用例显示为 skipped），别看到 `@Ignore` 就以为测试任务挂了。要补实现得先定调度语义，见 `TODO.md` P0-1。
3. **很多单测是「读源码文本做断言」**（`File("src/main/...")`）—— 必须经 Gradle 跑（工作目录是 `app/`），且**重命名标识符会打挂它们**。

其余一切（构建命令、环境要求、架构、约定）以 `AGENTS.md` 与 `docs/` 为准。
