package fansirsqi.xposed.sesame.task.antFarm

/**
 * 抽抽乐调度决策。
 */
internal enum class ChouChouLeScheduleAction {
    /** 执行条件已满足：直接执行抽抽乐，不等游戏改分。 */
    RUN,

    /** 执行条件尚未满足：本轮等待，下一轮再看。 */
    WAIT_FOR_TIME,

    /** 今日已完成：跳过。 */
    SKIP_COMPLETED
}

/**
 * 抽抽乐调度策略（纯判定，无 Android 依赖，便于单测）。
 *
 * 背景：`ChouChouLeSchedulePolicyTest` 在提交 `579aae63` 就写好了，
 * 但**实现从未落地** —— 测试引用不存在的类会让整个测试编译失败，
 * 当时只能用「临时隔离测试文件」绕过，后来又加 `@Ignore`（它也挡不住编译，只是显式标记）。
 * 这里补上实现，让那份契约测试真正跑起来（对应 `TODO.md` P0-1）。
 *
 * 语义表（与契约测试一致）：
 *
 * | completedToday | timeReached | 结果 |
 * | --- | --- | --- |
 * | `false` | `true` | [ChouChouLeScheduleAction.RUN] |
 * | `false` | `false` | [ChouChouLeScheduleAction.WAIT_FOR_TIME] |
 * | `true` | 任意 | [ChouChouLeScheduleAction.SKIP_COMPLETED] |
 *
 * **`completedToday` 优先级更高**：今日已完成时，无论是否到时间都跳过。
 *
 * 接线处的两个入参（见 `AntFarm.handleChouChouLeLogic`）：
 * - `completedToday` = `Status.hasFlagToday("farm::chouChouLeFinished")`
 * - `timeReached` = 执行条件是否已满足：**按时模式**下取
 *   `TaskTimeChecker.isTimeReached(enableChouchouleTime, "0900")`（默认 9:00 后）；
 *   **等改分模式**下取 `Status.hasFlagToday("farm::farmGameFinished")`。
 *   两种模式共用同一张真值表，因此本策略只暴露一个「条件是否满足」的布尔量。
 */
internal object ChouChouLeSchedulePolicy {

    /**
     * @param completedToday 今日是否已完成抽抽乐
     * @param timeReached    执行条件是否已满足（到时间 / 游戏改分已完成）
     */
    fun actionFor(completedToday: Boolean, timeReached: Boolean): ChouChouLeScheduleAction {
        if (completedToday) {
            return ChouChouLeScheduleAction.SKIP_COMPLETED
        }
        return if (timeReached) {
            ChouChouLeScheduleAction.RUN
        } else {
            ChouChouLeScheduleAction.WAIT_FOR_TIME
        }
    }
}
