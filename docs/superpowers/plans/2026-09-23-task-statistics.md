# 任务执行统计结构化落盘 —— 实施计划与记录

> 设计稿：[`../specs/2026-09-23-task-statistics-design.md`](../specs/2026-09-23-task-statistics-design.md)
> 状态：已实施（2026-09-23），待实机回归。

**Goal:** 把每轮任务执行的计数与耗时按账号、按自然日结构化落盘到 `statistics.json`。

**Architecture:** 纯策略（`TaskStatisticsPolicy`）负责合并与裁剪，IO 编排（`TaskStatisticsRecorder`）负责读写文件，
`CoroutineTaskRunner` 在 `finally` 里以 `NonCancellable + Dispatchers.IO` 调用，与既有的文本统计打印并列。

**Tech Stack:** Kotlin / kotlinx.coroutines / Jackson（`JsonHelper`）/ JUnit 4。

## Global Constraints

- 文件 UTF-8 无 BOM；注释中文。
- 判定逻辑抽成 `*Policy`（硬规则 5），IO 与逻辑分离，策略层不得引入 Android 依赖。
- 统计是旁路观测：**任何失败都不得影响任务流程**。
- 不改 `TaskRunCounter` / `TaskRunOutcome` 的既有语义与调用点。
- 存储位置必须与既有账号目录约定一致（`config/<userId>/`）。

## 一、任务拆解

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | 定义数据结构与合并/裁剪纯逻辑 | `task/TaskStatisticsPolicy.kt` |
| 2 | 实现 IO 编排 | `task/TaskStatisticsRecorder.kt` |
| 3 | 在任务收尾处接入落盘 | `task/TaskRunner.kt` |
| 4 | 补单测并跑通 CI 三步 | `task/TaskStatisticsPolicyTest.kt` |
| 5 | 补设计稿、实施计划与 CHANGELOG | 本文件、设计稿、`CHANGELOG.md` |

## 二、实施记录

### 2.1 改动清单

| 文件 | 类型 | 改动 |
| --- | --- | --- |
| `task/TaskStatisticsPolicy.kt` | 新增 | `RunStatRecord`（单轮结果）、`DayStatRecord`（当日累计）、`TaskStatisticsRecord`（文件根结构）、`mergeRun()`、`prune()`。`VERSION = 1`、`KEEP_DAYS = 30`。**无 IO、无 Android 依赖**。 |
| `task/TaskStatisticsRecorder.kt` | 新增 | `recordRun()` / `read()` / `serialize()`。落盘文件名 `statistics.json`，与 `status.json` 同目录、按账号隔离；`@Synchronized` 防并发写坏；解析失败按空统计重建。 |
| `task/TaskRunner.kt` | 修改 | `finally` 中在 `printExecutionSummary(...)` 之后调用 `recordStatistics(startTime)`；新增 `recordStatistics()`（`NonCancellable + Dispatchers.IO`）。 |
| `task/TaskStatisticsPolicyTest.kt` | 新增 | 7 项，见 §2.3。 |
| `CHANGELOG.md` | 修改 | 追加一行用户可见改动。 |
| `docs/superpowers/{specs,plans}/2026-09-23-task-statistics*` | 新增 | 本对文档。 |

### 2.2 关键设计决策与理由

| 决策 | 理由 |
| --- | --- |
| 用 `NonCancellable` 包住落盘 | 整轮任务被取消时，统计仍写盘 —— 取消本身是值得记录的事件；否则「经常被取消」这类情况在数据里会完全消失。 |
| 用 `Dispatchers.IO` | `recordRun` 是同步文件 IO，落在调用线程会拖慢任务收尾；`suspend` 函数用 `withContext` 切走。 |
| 调用点放在 `finally` | 与 `printExecutionSummary` 相邻，保证读快照时计数已定稿；异常路径也覆盖。 |
| 合并逻辑做成纯函数并复用一个 `TaskRunSnapshot` | 数据结构直接对应既有计数器快照，不重复定义语义；纯函数可 JVM 单测，符合硬规则 5。 |
| `prune` 用字典序而不是解析日期 | `yyyy-MM-dd` 的字典序等价于时间序，省掉日期解析与时区坑。 |
| 解析失败时**按空统计重建** | 宁可丢历史，也不让一个坏文件永久阻断统计链路；同时记 `printStackTrace` 保留现场。 |
| 保存 `version` 字段 | 后续结构变更（如拆分 `skipped`）需要迁移判断依据。 |
| 不做 UI | 本轮只解决「留不住、不可聚合」；展示属独立改动。 |

### 2.3 单测清单

| 用例 | 断言 |
| --- | --- |
| 首次合并会初始化当日统计 | `runs = 1`、`firstAt == lastAt == now`、各计数与入参一致 |
| 同日多次执行会累加且保留首次时间 | `runs` 累加、`firstAt` 不变、`lastAt` 前移 |
| 不同日期各自独立统计 | 两个日期 key 互不影响 |
| 超出保留窗口时裁剪最旧日期 | 40 条 → 30 条，且被裁掉的是最旧的那批 |
| 未超出保留窗口时不裁剪 | 原样返回 |
| 合并时保留历史其它日期 | 只改当日，其它日期不变 |
| `mergeRun` 原样保留传入的账号 | `userId` 不被 `prev` 覆盖 |

> 沿用仓库既有惯例：读源码文本类断言之外，纯逻辑用普通 JUnit；IO 层依赖外部存储与 Android 运行时，
> 不写单测，靠编译 + 实机覆盖。

## 三、验收清单

- [x] 合并/裁剪逻辑有 7 项单测覆盖
- [x] `:app:assembleDebug` 通过
- [x] `:app:testDebugUnitTest` 通过
- [x] `node --test app/src/test/js/*.test.js` 通过
- [x] 新文件 UTF-8 无 BOM
- [ ] **实机**：跑完一轮任务后，确认 `config/<userId>/statistics.json` 生成且当日 `runs` 递增
- [ ] **实机**：同一天触发第二轮，确认当日计数累加、`firstAt` 不变
- [ ] **实机**：断网或强制停止使任务取消时，确认统计仍被写入（`NonCancellable` 生效）

## 四、遗留与后续

1. **小米17 / K50 实机回归未做** —— 本轮只完成代码与单测。
2. **精度到轮不到任务**：无法回答「某个功能失败了几次」，需要按任务粒度埋点（写盘量会显著增加）。
3. **`skipped` 仍是合并计数**：与 `TaskRunCounter` 既有语义一致，拆分需先改计数器。
4. **无 UI**：`statistics.json` 由用户自行读取；若要做「任务统计」页面，属独立改动。
5. **不做跨设备聚合与清理**：账号删除后的残留 `statistics.json` 由既有账号目录逻辑处理。

## 五、来源说明

本功能最初在 `Worktrees/Sesame-ZR/fix-verification-pause-stuck-8535af9d` 这个 worktree 里以**未提交**状态完成，
2026-09-24 迁出为独立分支 `feat/task-statistics`（基于 `main`）并补齐本对文档与 CHANGELOG。
迁移时校验过：`TaskRunner.kt` 在 `main` / worktree 两侧 blob 一致（`da7f63ba`），因此改动与
「安全验证暂停」那轮工作无关，可独立成 PR。
