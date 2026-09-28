# 日志查看器：按天翻看历史日志 —— 实施计划与记录

> 状态：已实施（2026-09-29），真机已验证。
> 设计依据：仓库内调研（Logback 滚动策略 + 查看器链路）+ 设备实测（小米 17）。

**Goal:** 让日志查看器能翻看最近 7 天的历史日志：标题栏切日期 / 前后翻页，
一天的多个滚动分片合并成一条连续日志（搜索、仅看错误覆盖整天），历史只读。

**Architecture:** 三层新增纯逻辑/IO 组件 —— `LogFileHistory`（分片枚举与解析）、
`LogSource`（多文件随机读：偏移打包 + 句柄 LRU）、`LogDayIndexer`（多分片索引）；
`LogViewerViewModel` 与 `LogViewerScreen` 只做状态与交互接线。

**Tech Stack:** Kotlin / java.io / Compose Material3 / JUnit 4。

## Global Constraints

- 文件 UTF-8 无 BOM；注释中文。
- 判定逻辑抽成纯函数并配单测（硬规则 5），新代码不引入 Android 依赖（`LogSource` 用
  `LinkedHashMap` 实现 LRU 而非 `android.util.LruCache`，否则无法单测）。
- **不改 Logback 的保留策略与容量上限**；历史文件已存在，只做「找出来 + 读」。
- 历史分片是滚动归档，**只读**：不提供清空。
- 现有 3 个调用方（`MainActivity` / `ManualTaskActivity` / `RpcDebugViewModel`）只传文件路径，
  `loadLogs(path)` 签名与语义（打开今天）必须保持兼容。

## 一、任务拆解

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | 分片枚举与解析 | `util/LogFileHistory.kt` + `LogFileHistoryTest` |
| 2 | 多文件随机读 | `util/LogSource.kt` + `LogSourceTest` |
| 3 | 多分片索引 | `util/LogDayIndexer.kt` + `LogDayIndexerTest` |
| 4 | logName → 中文标签 | `util/LogCatalog.kt` |
| 5 | ViewModel 接线 | `ui/viewmodel/LogViewerViewModel.kt` |
| 6 | 日期切换 UI | `ui/screen/LogViewerScreen.kt` |
| 7 | 合并导出 | `util/Files.kt#exportMerged` |
| 8 | 文档回填 | 本文件 + `architecture.md` §9.3.1 + `user-guide.md` + `changelog.d/` |

## 二、实施记录

- [x] **1** `parseShardName`（正则 + 日期合法性）、`availableDates`（倒序、只列真有数据的日期）、
      `partitionsOf`（分片按序号**数值**升序、今天把活动文件排最后）、`resolveReadableLogDir`（目录回退）
- [x] **2** 偏移打包 `(seq shl 40) or offset`（`SHARD_SHIFT=40`）+ 访问序 LRU（默认 4 个句柄）
- [x] **3** `readTail`（最后一个分区的尾部）+ `scan`（**从最新分片往前扫，凑够 maxLines 即停**）
- [x] **4** `LogCatalog.labelOf`，标题显示「错误日志」而不是 `error.log`
- [x] **5** `loadLogs(path)` 转调 `openDay(logName, today)`；新增 `openDay/selectDate/goToOlderDay/goToNewerDay`；
      `LogUiState` 加 10 个日期相关字段；仅「今天」起 `FileObserver` 与防抖追加；
      历史禁用清空；导出改合并；**顺带修**：`FileObserver` 改为观察父目录（原先 Q+ 观察 inode，
      日志滚动后收不到事件）、滚动时重新枚举当天分片
- [x] **6** 标题栏：分类名 + 日期按钮（下拉列 7 天）+ ←/→（边界自动置灰）+ 错误徽标；
      第二行尾随「N 个分片」「已截断」「该日期没有可读日志」；历史态隐藏「清空」；
      导出前大体积确认弹框（>50MB 或 >20 分片）
- [x] **7** `Files.exportMerged(files, baseName, hasTime)`：按顺序追加写成一个文件

### 实机验证（小米 17，versionCode 3546335）

真值取自设备：`error-2026-09-27` 1 个分片 2380 行；`error-2026-09-24` 4 个分片 305706 行。

| 操作 | 界面显示 | 结论 |
| --- | --- | --- |
| 打开「错误日志」 | `错误日志 · 今天 · 143 lines` | 与改造前一致（今天只有活动文件） |
| 点日期按钮 | 列出 `今天 · 09-29 / 09-28 / 09-27 / 09-26 / 09-25 / 09-24` | 只列真有数据的日期 |
| 选 09-27 | `2380 lines` | **与真值完全一致**（分片合并正确） |
| ← 前一天 | `09-26 · 2478 lines` | 翻页正确 |
| → 后一天 | `09-27 · 2380 lines` | 往返正确 |
| 选 09-28 / 今天 | `2821 lines` / `143 lines` | 今天仍按单文件语义 |
| 选 09-24 | `200000 lines · 4 个分片 · 已截断，仅保留最新内容，搜索不覆盖更早的行` | 30.5 万行触发上限，提示如实 |
| 09-24 打开菜单 | 只有「导出本日日志」，**没有「清空日志」** | 历史只读生效 |
| 在 09-24 按 ← | 无变化（该日已是最旧） | 边界置灰生效 |

## 三、验收清单

- [x] 全量单测通过（新增 `LogFileHistoryTest` 10 项、`LogSourceTest` 5 项、`LogDayIndexerTest` 7 项）
- [x] 日期切换 / 前后翻页 / 整天合并行数 = 真值
- [x] 超上限时如实提示截断
- [x] 历史态无「清空」入口
- [ ] 搜索与「仅看错误」在跨分片时的输出（逻辑已由单测覆盖，尚未逐条对视）
- [ ] 导出整天（合并成单文件）尚未在真机点过
- [ ] 长时间停留「今天」时触发 rollover（日志滚动）后能否自动接上新分片

## 四、遗留与后续

- 跨分片搜索的**语义边界**：`maxLines` 截断后搜索只覆盖保留部分，UI 已提示；
  若以后要「整天必搜」，需要改成流式搜索而不是靠索引。
- 历史保留仍由 Logback 的 `maxHistory = 7` 决定；若用户希望更长，属另一件事（存储与清理策略）。
- `docs/component-api.md` 已补三个新 util API 的条目。

## 五、来源说明

- 设备实测：小米 17（`192.168.1.54:34589`）真机 UI 驱动 + `ls/wc -l` 取真值。
- 调研结论均带文件行号记录在实现过程中：`Logback.kt:136/141/145-146`、
  `Files.kt:29/209-245/258-326`、`LogViewerViewModel.kt`（索引与读取路径）。
