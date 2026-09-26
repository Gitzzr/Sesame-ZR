package fansirsqi.xposed.sesame.task.antFarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽抽乐调度策略的契约测试。
 *
 * 历史：本测试在提交 `579aae63` 就写好了，但描述的 `ChouChouLeScheduleAction` /
 * `ChouChouLeSchedulePolicy` **在主源码中并不存在**（只加了测试，没加实现）。
 * 直接引用会让整个 `:app:testDebugUnitTest` 编译失败，而 `@Ignore` 只跳过「执行」、
 * 不跳过「编译」，所以当时只能把断言注释掉保留成契约。
 *
 * 2026-09-26 补上实现并接线到 `AntFarm.handleChouChouLeLogic()`，断言恢复。
 *
 * 契约：
 * | completedToday | timeReached | 期望                | 语义                           |
 * | -------------- | ----------- | ------------------- | ------------------------------ |
 * | false          | true        | RUN                 | 已到执行时间，不等游戏改分，直接跑 |
 * | false          | false       | WAIT_FOR_TIME       | 未到执行时间，等待              |
 * | true           | true        | SKIP_COMPLETED      | 今日已完成，跳过                |
 * | true           | false       | SKIP_COMPLETED      | 今日已完成优先于「未到时间」     |
 */
class ChouChouLeSchedulePolicyTest {

    @Test
    fun `已到执行时间时不等待游戏改分直接执行抽抽乐`() {
        assertEquals(
            ChouChouLeScheduleAction.RUN,
            ChouChouLeSchedulePolicy.actionFor(
                completedToday = false,
                timeReached = true
            )
        )
    }

    @Test
    fun `未到执行时间时等待`() {
        assertEquals(
            ChouChouLeScheduleAction.WAIT_FOR_TIME,
            ChouChouLeSchedulePolicy.actionFor(
                completedToday = false,
                timeReached = false
            )
        )
    }

    @Test
    fun `今日已完成时跳过`() {
        assertEquals(
            ChouChouLeScheduleAction.SKIP_COMPLETED,
            ChouChouLeSchedulePolicy.actionFor(
                completedToday = true,
                timeReached = true
            )
        )
    }

    @Test
    fun `今日已完成优先于未到时间`() {
        // 原契约表未覆盖 (true, false)：此时按「completedToday 优先级更高」应同样跳过，
        // 否则会出现「已完成后又因为没到时间而反复判定」的歧义。
        assertEquals(
            ChouChouLeScheduleAction.SKIP_COMPLETED,
            ChouChouLeSchedulePolicy.actionFor(
                completedToday = true,
                timeReached = false
            )
        )
    }
}
