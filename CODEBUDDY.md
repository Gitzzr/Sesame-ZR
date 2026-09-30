# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## 先读这两处

本仓库已经建立了完整的文档体系，**不要在开发过程中从零重新探索**：

1. **[`AGENTS.md`](AGENTS.md)** —— AI 进入本项目首先需要知道的信息：8 条硬规则、环境现状、常用命令、代码地图。
2. **[`TODO.md`](TODO.md)** —— 当前任务与优先级。P0 两条都已有结论：P0-2 环境搭建已完成、P0-1 抽抽乐调度策略已于 2026-09-26 实现并接线。

## 文档体系

| 文档 | 内容 |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | AI 入口：硬规则、环境、未完成功能、代码地图 |
| [`TODO.md`](TODO.md) | 任务、优先级、进度（P0 已解除 / P1 待验 / P2 缺陷） |
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
2. **「循环一定要有无进展退出条件」是本项目踩过的坑** —— 抽抽乐与会员商家任务都曾出现
   「服务端一直回成功、但状态不推进 → 循环以 1 次/秒空转数小时」：
   抽抽乐 2026-09-30 实测刷了 19,120 行日志（≥3.8 万次 RPC），商家任务递归重试 905 次。
   判定逻辑一律抽成 `*Policy`（如 `ChouChouLeLoopPolicy`）并配契约测试；
   **不要把「RPC 返回成功」当作「任务有进展」。**
   （历史：`ChouChouLeSchedulePolicy` 曾在 `579aae63` 里只有测试没有实现，
   2026-09-26 已补实现并接线到 `AntFarm.handleChouChouLeLogic()`，该测试现已正常执行。）
3. **很多单测是「读源码文本做断言」**（`File("src/main/...")`）—— 必须经 Gradle 跑（工作目录是 `app/`），且**重命名标识符会打挂它们**。

其余一切（构建命令、环境要求、架构、约定）以 `AGENTS.md` 与 `docs/` 为准。
