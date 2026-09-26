package fansirsqi.xposed.sesame.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同一个异常应该怎么处理。
 */
internal enum class ErrorLogAction {
    /** 打印完整堆栈。 */
    PRINT,

    /** 打印完整堆栈，并额外记录一条「后续不再打印」的汇总提示。 */
    PRINT_AND_ANNOUNCE,

    /** 完全抑制，不再输出。 */
    SUPPRESS
}

/**
 * 异常日志去重策略（纯逻辑，无 Android 依赖，便于单测）。
 *
 * 背景 —— 这里曾存在一个**方向反转**的缺陷：原 `Log.shouldPrintError()` 在
 * `count <= MAX_DUPLICATE_ERRORS` 时返回 `true`，而调用方写的是
 * `if (shouldPrintError(th)) return` —— 即「返回 true 就跳过打印」。
 * 于是实际行为变成：**前 2 次被抑制、第 3 次只打一行汇总、第 4 次起每次都打完整堆栈**，
 * 与「后续将不再打印详细堆栈」的提示完全相反。
 *
 * 后果：2026-09-24 的 `NullPointerException` 风暴中，2 小时内 57 次抛出几乎每次都倾泻完整堆栈，
 * 单次栈深平均 5360 帧（递归导致），单个 error 文件 7MB 只覆盖 23 秒，
 * 一晚写出约 29MB / 30.6 万行异常日志。
 *
 * 现在改为直觉语义：
 * - 第 `1 .. maxDuplicates-1` 次 → [ErrorLogAction.PRINT]
 * - 第 `maxDuplicates` 次        → [ErrorLogAction.PRINT_AND_ANNOUNCE]（打印堆栈 + 「后续不再打印」提示）
 * - 第 `maxDuplicates` 次之后    → [ErrorLogAction.SUPPRESS]
 */
internal class ErrorLogPolicy(private val maxDuplicates: Int = MAX_DUPLICATES) {

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 记一次出现并给出处理动作。
     *
     * @param signature 由 [signatureOf] 生成的异常特征
     */
    fun actionFor(signature: String): ErrorLogAction {
        val count = counts.computeIfAbsent(signature) { AtomicInteger(0) }.incrementAndGet()
        return when {
            count < maxDuplicates -> ErrorLogAction.PRINT
            count == maxDuplicates -> ErrorLogAction.PRINT_AND_ANNOUNCE
            else -> ErrorLogAction.SUPPRESS
        }
    }

    /** 某特征已出现的次数。 */
    fun countOf(signature: String): Int = counts[signature]?.get() ?: 0

    /** 清空统计（切换用户 / 手动重置时调用）。 */
    fun reset() {
        counts.clear()
    }

    companion object {
        /** 同一特征最多打印完整堆栈的次数。 */
        const val MAX_DUPLICATES = 3

        /**
         * 提取异常特征：类名 + 消息前 50 字。
         *
         * 空响应类 JSON 异常统一归并为一个特征，避免不同报文长度造成特征爆炸。
         */
        fun signatureOf(th: Throwable): String {
            if (th.message?.contains("End of input at character 0") == true) {
                return "JSONException:EmptyResponse"
            }
            return th.javaClass.simpleName + ":" + (th.message?.take(50) ?: "null")
        }
    }
}

/**
 * 堆栈文本格式化（纯逻辑，便于单测）。
 *
 * 递归型异常可能产生数千帧堆栈，直接落盘会把日志文件瞬间打满。
 * 这里只保留**栈顶**若干帧（最有诊断价值），其余截断并附上总帧数。
 */
internal object StackTraceFormatter {

    /** 最多保留的堆栈帧数。 */
    const val MAX_FRAMES = 60

    /**
     * 截断过深的堆栈。
     *
     * @param raw [android.util.Log.getStackTraceString] 产出的多行文本
     * @param maxFrames 保留的最大帧数
     */
    fun truncateFrames(raw: String, maxFrames: Int = MAX_FRAMES): String {
        if (maxFrames <= 0) return raw
        val lines = raw.split('\n')
        val totalFrames = lines.count { it.trimStart().startsWith("at ") }
        if (totalFrames <= maxFrames) return raw

        val kept = ArrayList<String>(maxFrames + 2)
        var seen = 0
        for (line in lines) {
            if (line.trimStart().startsWith("at ")) {
                if (seen == maxFrames) {
                    kept.add("\t...(已截断，共 $totalFrames 帧)")
                    break
                }
                seen++
            }
            kept.add(line)
        }
        return kept.joinToString("\n")
    }
}
