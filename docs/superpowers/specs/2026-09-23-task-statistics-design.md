# 任务执行统计结构化落盘设计

> 状态：已实施（2026-09-23）。实施记录见 [`../plans/2026-09-23-task-statistics.md`](../plans/2026-09-23-task-statistics.md)。

## 背景

`CoroutineTaskRunner` 每轮任务结束时已经会统计各任务的去向，但**只以文本形式打印**：

```
📈 === 执行统计 (并发模式) ===
✅ 完成: 12    ⏳ 超时: 0    ❌ 失败: 1 ...
```

这套数字对排查「某个功能今天到底跑没跑」「失败率是不是在涨」很有用，但文本日志有三个问题：

1. **不可聚合** —— 想知道「最近 7 天失败了几次」只能人工翻日志；
2. **会被滚掉** —— 日志文件按大小/日期轮转，历史数据留不住；
3. **与账号无关** —— 多账号共用一份日志流，分不清哪条属于哪个账号。

计数本身已经在内存里（`TaskRunCounter` / `TaskRunSnapshot`），缺的只是「把它写下来」。

## 目标

- 每轮任务执行结束时，把本次结果的计数与耗时**结构化落盘**，按账号隔离。
- 按**自然日**累积：同一天多轮执行累加，而不是覆盖。
- 只保留**最近 30 天**，避免文件无限增长。
- 落盘**不阻塞**任务流程，且**任务被取消时也要写**（取消也要留痕）。
- 合并逻辑必须是**纯函数**、可 JVM 单测，不依赖 Android。

## 非目标

- 不改 `TaskRunCounter` / `TaskRunOutcome` 的既有语义与调用点。
- 不做 UI 展示（统计文件由用户自行读取；后续若要做页面，属独立改动）。
- 不上报服务端，不做跨设备聚合。
- 不记录每个任务的明细（只有每轮的汇总计数）。

## 数据结构

落盘位置：`.../sesame-TK/config/<userId>/statistics.json`（与 `status.json` 同目录，按账号隔离）。

```json
{
  "version": 1,
  "userId": "2088…",
  "updatedAt": 1758600000000,
  "days": {
    "2026-09-23": {
      "runs": 4, "totalTimeMs": 512340,
      "completed": 61, "startedBackground": 3,
      "timedOut": 0, "skipped": 7, "failed": 1,
      "firstAt": 1758580000000, "lastAt": 1758600000000
    }
  }
}
```

| 字段 | 含义 |
| --- | --- |
| `version` | 结构版本，便于后续迁移 |
| `userId` | 账号，与所在目录一致（冗余存一份便于文件被单独拷贝时自解释） |
| `updatedAt` | 最近一次写入时间 |
| `days` | 日期（`yyyy-MM-dd`）→ 当日累计 |
| `days[].runs` | 当日执行轮次 |
| `days[].totalTimeMs` | 当日累计耗时（毫秒） |
| `days[].firstAt` / `lastAt` | 当日首轮 / 末轮的完成时间戳 |
| 其余计数 | 与 `TaskRunSnapshot` 一一对应 |

`skipped` 在 `TaskRunCounter` 里本就合并了三种去向（离线 / 被过滤 / 仍在运行），这里保持一致，不拆分。

## 实现设计

沿用仓库硬规则 5：**可测的判定逻辑抽成 `*Policy`，IO 单独一层。**

| 文件 | 层次 | 职责 |
| --- | --- | --- |
| `task/TaskStatisticsPolicy.kt` | 纯策略 | 数据结构（`RunStatRecord` / `DayStatRecord` / `TaskStatisticsRecord`）+ `mergeRun()` 合并 + `prune()` 裁剪。**无 IO、无 Android 依赖**。 |
| `task/TaskStatisticsRecorder.kt` | IO 编排 | 「读文件 → 反序列化 → 交给 Policy 合并 → 序列化写回」。`@Synchronized` 防并发写坏文件。 |
| `task/TaskRunner.kt` | 调用点 | `finally` 里 `recordStatistics(startTime)`，与既有的 `printExecutionSummary` 并列。 |

### 合并逻辑（`mergeRun`）

```
mergeRun(prev, userId, dayKey, run, now):
  base = prev ?: 空结构
  old  = base.days[dayKey] ?: 空当日结构
  day  = 逐字段累加；firstAt 取「当日还没有轮次时」的 now，否则保留原值；lastAt = now
  return { version, userId, updatedAt = now, days = prune(base.days + dayKey→day, 30) }
```

`prune` 利用「`yyyy-MM-dd` 的字典序等价于时间序」，按 key 降序取前 `keep` 条即可，无需解析日期。

### 落盘时机与线程

```kotlin
private suspend fun recordStatistics(startTime: Long) {
    ...
    withContext(NonCancellable + Dispatchers.IO) {   // 取消也要写；不阻塞调用线程
        TaskStatisticsRecorder.recordRun(userId, dayKey, run, now)
    }
}
```

- `NonCancellable`：整轮任务被取消时，统计依然写盘 —— 任务被取消本身就是值得记录的事件。
- `Dispatchers.IO`：`recordRun` 是同步文件 IO，不能落在调用线程上。
- 调用点在 `finally`，与 `printExecutionSummary` 相邻，保证「计数已定稿」之后再读快照。

## 错误处理

| 场景 | 处理 |
| --- | --- |
| 当前账号为空 | 记录一行日志后跳过（不写文件） |
| 文件句柄拿不到 | `Files.getTargetFileofUser` 返回 null → 记 error 日志、跳过 |
| 已有文件解析失败 | 返回 null，**按空统计重建**并继续写入（宁可丢历史，也不让统计链路阻断任务） |
| 写盘被拒 | 记 error 日志；不抛出，不影响任务流程 |
| 任意异常 | 捕获后 `Log.printStackTrace`；`CancellationException` 原样抛出（不吞取消信号） |

**原则：统计是旁路观测，任何失败都不得影响任务本身。**

## 测试

`app/src/test/java/fansirsqi/xposed/sesame/task/TaskStatisticsPolicyTest.kt`（7 项）：

| 用例 | 断言 |
| --- | --- |
| 首次合并会初始化当日统计 | `runs = 1`、`firstAt == lastAt == now`、计数与入参一致 |
| 同日多次执行会累加且保留首次时间 | `runs` 累加、`firstAt` 不变、`lastAt` 前移 |
| 不同日期各自独立统计 | 两个 key 各自独立 |
| 超出保留窗口时裁剪最旧日期 | 40 → 30 条，且被裁掉的是最旧的 |
| 未超出保留窗口时不裁剪 | 原样返回 |
| 合并时保留历史其它日期 | 只改当日，其它日期不变 |
| `mergeRun` 原样保留传入的账号 | `userId` 不被 `prev` 覆盖 |

> IO 层（`TaskStatisticsRecorder`）不做单测：它依赖 `Files` 的外部存储路径与 Android 运行时，
> 与仓库既有惯例一致（同类 IO 均不单测，靠编译 + 实机）。

## 边界与已知取舍

1. **精度到「轮」不到「任务」。** 只记每轮的汇总计数，无法回答「森林蹲点失败了几次」——
   那需要按任务粒度埋点，会显著增加写盘量，暂不做。
2. **`skipped` 不区分三种去向。** 与 `TaskRunCounter` 既有语义保持一致；若要拆分需先改计数器。
3. **时区跟随 `TimeUtil.getDateStr2()`**（即设备本地时区），跨时区旅行时「自然日」会跳变。
4. **不清理账号删除后的残留文件** —— 账号目录本身由既有逻辑管理，本功能不额外处理。
