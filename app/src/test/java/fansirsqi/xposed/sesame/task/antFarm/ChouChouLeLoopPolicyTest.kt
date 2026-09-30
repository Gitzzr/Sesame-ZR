package fansirsqi.xposed.sesame.task.antFarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽抽乐循环退出判定的契约测试。
 *
 * 背景（2026-09-30 小米17 实测）：原循环 `do { ... } while (doubleCheck)` 里
 * `doubleCheck` 只代表「这一轮有 RPC 返回成功」，不代表任务状态推进 ——
 * 于是任务「游戏充值得10次机会」让循环以 1 次/秒空转 8.4 小时（19,120 行日志、≥3.8 万次 RPC）。
 *
 * 契约：
 * | round | previousSignature | currentSignature | hadSuccessfulAction | 期望            |
 * | ----- | ----------------- | ---------------- | ------------------- | --------------- |
 * | 任意  | 任意              | 任意             | false               | STOP_NO_ACTION  |
 * | 2     | "A"               | "A"              | true                | STOP_NO_PROGRESS|
 * | 1     | "A"               | "B"              | true                | CONTINUE        |
 * | 1     | null              | "A"              | true                | CONTINUE        |
 * | MAX   | "A"               | "B"              | true                | STOP_MAX_ROUNDS |
 */
class ChouChouLeLoopPolicyTest {

    @Test
    fun `本轮没有任何成功调用时停止`() {
        assertEquals(
            ChouChouLeLoopAction.STOP_NO_ACTION,
            ChouChouLeLoopPolicy.actionFor(
                round = 1,
                previousSignature = null,
                currentSignature = "TASK:TODO:3",
                hadSuccessfulAction = false
            )
        )
    }

    @Test
    fun `成功调用但任务状态与剩余次数都没变时判定无进展退出`() {
        assertEquals(
            ChouChouLeLoopAction.STOP_NO_PROGRESS,
            ChouChouLeLoopPolicy.actionFor(
                round = 2,
                previousSignature = "TASK:TODO:3",
                currentSignature = "TASK:TODO:3",
                hadSuccessfulAction = true
            )
        )
    }

    @Test
    fun `任务状态有推进时继续下一轮`() {
        assertEquals(
            ChouChouLeLoopAction.CONTINUE,
            ChouChouLeLoopPolicy.actionFor(
                round = 1,
                previousSignature = "TASK:TODO:3",
                currentSignature = "TASK:FINISHED:3",
                hadSuccessfulAction = true
            )
        )
    }

    @Test
    fun `第一轮没有上一轮快照时不判定无进展`() {
        // 第一轮必然"没有可比较的对象"，不能因为 previousSignature 为空就误判
        assertEquals(
            ChouChouLeLoopAction.CONTINUE,
            ChouChouLeLoopPolicy.actionFor(
                round = 1,
                previousSignature = null,
                currentSignature = "TASK:TODO:3",
                hadSuccessfulAction = true
            )
        )
    }

    @Test
    fun `达到轮次上限时兜底停止`() {
        assertEquals(
            ChouChouLeLoopAction.STOP_MAX_ROUNDS,
            ChouChouLeLoopPolicy.actionFor(
                round = ChouChouLeLoopPolicy.MAX_ROUNDS,
                previousSignature = "TASK:TODO:3",
                currentSignature = "TASK:TODO:2",
                hadSuccessfulAction = true
            )
        )
    }

    @Test
    fun `快照签名与任务顺序无关`() {
        val one = ChouChouLeLoopPolicy.signatureOf(listOf("B:TODO:1", "A:TODO:2"))
        val two = ChouChouLeLoopPolicy.signatureOf(listOf("A:TODO:2", "B:TODO:1"))

        assertEquals(one, two)
        assertEquals("A:TODO:2|B:TODO:1", one)
    }
}
