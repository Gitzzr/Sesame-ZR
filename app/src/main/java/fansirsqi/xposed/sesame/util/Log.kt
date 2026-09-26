package fansirsqi.xposed.sesame.util

import android.content.Context
import fansirsqi.xposed.sesame.model.BaseModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 日志工具类，负责初始化和管理各种类型的日志记录器，并提供日志输出方法。
 *
 * 分类体系（三层）：
 * - 业务日志：forest(森林生态) / ocean(海洋垂钓) / farm(庄园) / orchard(果园) / stall(新村) / life(生活权益) / other(兜底)
 * - 系统日志：runtime(调度与运行状态) / error(异常) / capture(抓包) / debug(调试)
 * - 聚合视图：record(全部日志，业务/系统写入统一镜像，受 BaseModel.recordLog 开关控制)
 */
object Log {
    private const val DEFAULT_TAG = ""

    // 异常去重 + 栈帧截断策略（纯逻辑，见 ErrorLogPolicy.kt）
    private val errorLogPolicy = ErrorLogPolicy()

    /**
     * 业务日志分类。
     *
     * 域内子模块用 tag 维度区分，不再为小模块单独开文件，控制文件总量。
     */
    enum class LogCategory(internal val loggerName: String) {
        FOREST("forest"),   // 森林生态：antForest / antCooperate / reserve / EcoProtection / antDodo
        OCEAN("ocean"),     // 海洋垂钓：antOcean / antFishPond
        FARM("farm"),       // 蚂蚁庄园：antFarm / AntFarmFamily / ChouChouLe
        ORCHARD("orchard"), // 果园：antOrchard
        STALL("stall"),     // 蚂蚁新村：antStall / ReadingDada
        LIFE("life"),       // 生活权益：antMember / antSports / GreenFinance / Credit2101 / AnswerAI
        OTHER("other"),     // 兜底：无法归类的零散日志
    }

    // Logger 实例
    private val RECORD_LOGGER: Logger
    private val DEBUG_LOGGER: Logger
    private val FOREST_LOGGER: Logger
    private val FARM_LOGGER: Logger
    private val OTHER_LOGGER: Logger
    private val ERROR_LOGGER: Logger
    private val CAPTURE_LOGGER: Logger
    private val OCEAN_LOGGER: Logger
    private val ORCHARD_LOGGER: Logger
    private val STALL_LOGGER: Logger
    private val LIFE_LOGGER: Logger
    private val RUNTIME_LOGGER: Logger

    init {
        // 🔥 1. 立即初始化 Logcat，确保在任何 Context 到来之前控制台可用
        Logback.initLogcatOnly()

        // 2. 初始化 Logger 实例 (此时它们已经有了 Logcat 能力)
        RECORD_LOGGER = LoggerFactory.getLogger("record")
        DEBUG_LOGGER = LoggerFactory.getLogger("debug")
        FOREST_LOGGER = LoggerFactory.getLogger("forest")
        FARM_LOGGER = LoggerFactory.getLogger("farm")
        OTHER_LOGGER = LoggerFactory.getLogger("other")
        ERROR_LOGGER = LoggerFactory.getLogger("error")
        CAPTURE_LOGGER = LoggerFactory.getLogger("capture")
        OCEAN_LOGGER = LoggerFactory.getLogger("ocean")
        ORCHARD_LOGGER = LoggerFactory.getLogger("orchard")
        STALL_LOGGER = LoggerFactory.getLogger("stall")
        LIFE_LOGGER = LoggerFactory.getLogger("life")
        RUNTIME_LOGGER = LoggerFactory.getLogger("runtime")
    }

    /**
     * 🔥 新增初始化方法
     * 在这里传入 Context，追加文件日志功能
     */
    @JvmStatic
    fun init(context: Context) {
        try {
            Logback.initFileLogging(context)
        } catch (e: Exception) {
            android.util.Log.e("SesameLog", "Log init failed", e)
        }
    }

    // --- 日志方法 ---


    @JvmStatic
    fun record(msg: String) {
        if (BaseModel.recordLog.value == true) {
            RECORD_LOGGER.info("$DEFAULT_TAG{}", msg)
        }
    }

    @JvmStatic
    fun record(tag: String, msg: String) {
        record("[$tag]: $msg")
    }

    @JvmStatic
    fun forest(msg: String) {
        record(msg)
        FOREST_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun forest(tag: String, msg: String) {
        forest("[$tag]: $msg")
    }

    @JvmStatic
    fun farm(msg: String) {
        record(msg)
        FARM_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun other(msg: String) {
        record(msg)
        OTHER_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun other(tag: String, msg: String) {
        other("[$tag]: $msg")
    }

    // --- 新业务分类：统一走 biz() 路由（写分类文件 + 镜像 record） ---

    /**
     * 业务日志统一路由：写入分类文件，并镜像进 record.log（受 recordLog 开关控制）。
     *
     * 新业务模块一律走此入口或对应的分类便捷方法，保证「全部日志」聚合语义完整。
     */
    @JvmStatic
    fun biz(category: LogCategory, msg: String) {
        record(msg)
        categoryLogger(category).debug("{}", msg)
    }

    @JvmStatic
    fun biz(category: LogCategory, tag: String, msg: String) {
        biz(category, "[$tag]: $msg")
    }

    private fun categoryLogger(category: LogCategory): Logger = when (category) {
        LogCategory.FOREST -> FOREST_LOGGER
        LogCategory.OCEAN -> OCEAN_LOGGER
        LogCategory.FARM -> FARM_LOGGER
        LogCategory.ORCHARD -> ORCHARD_LOGGER
        LogCategory.STALL -> STALL_LOGGER
        LogCategory.LIFE -> LIFE_LOGGER
        LogCategory.OTHER -> OTHER_LOGGER
    }

    // 分类便捷方法（渐进迁移：旧模块可继续用 forest/farm/other，新模块优先用这些）

    @JvmStatic
    fun ocean(msg: String) = biz(LogCategory.OCEAN, msg)

    @JvmStatic
    fun ocean(tag: String, msg: String) = biz(LogCategory.OCEAN, tag, msg)

    @JvmStatic
    fun orchard(msg: String) = biz(LogCategory.ORCHARD, msg)

    @JvmStatic
    fun orchard(tag: String, msg: String) = biz(LogCategory.ORCHARD, tag, msg)

    @JvmStatic
    fun stall(msg: String) = biz(LogCategory.STALL, msg)

    @JvmStatic
    fun stall(tag: String, msg: String) = biz(LogCategory.STALL, tag, msg)

    @JvmStatic
    fun life(msg: String) = biz(LogCategory.LIFE, msg)

    @JvmStatic
    fun life(tag: String, msg: String) = biz(LogCategory.LIFE, tag, msg)

    // --- 运行日志：任务调度 / 轮次统计 / RPC 摘要，使用 INFO/WARN 级别 ---

    @JvmStatic
    fun runtime(msg: String) {
        RUNTIME_LOGGER.info("{}", msg)
    }

    @JvmStatic
    fun runtime(tag: String, msg: String) {
        runtime("[$tag]: $msg")
    }

    @JvmStatic
    fun runtimeWarn(msg: String) {
        RUNTIME_LOGGER.warn("{}", msg)
    }

    @JvmStatic
    fun runtimeWarn(tag: String, msg: String) {
        runtimeWarn("[$tag]: $msg")
    }

    @JvmStatic
    fun debug(msg: String) {
        DEBUG_LOGGER.debug("{}", msg)
    }

    @JvmStatic
    fun debug(tag: String, msg: String) {
        debug("[$tag]: $msg")
    }

    @JvmStatic
    fun error(msg: String) {
        ERROR_LOGGER.error("$DEFAULT_TAG{}", msg)
    }

    @JvmStatic
    fun error(tag: String, msg: String) {
        error("[$tag]: $msg")
    }

    @JvmStatic
    fun capture(msg: String) {
        CAPTURE_LOGGER.info("$DEFAULT_TAG{}", msg)
    }

    @JvmStatic
    fun capture(tag: String, msg: String) {
        capture("[$tag]: $msg")
    }

    fun d(tag: String, msg: String) {
        DEBUG_LOGGER.debug("[$tag]: $msg")
    }

    fun i(tag: String, msg: String) {
        RECORD_LOGGER.info("[$tag]: $msg")
    }

    fun w(tag: String, msg: String) {
        RECORD_LOGGER.warn("[$tag]: $msg")
    }

    fun e(tag: String, msg: String, th: Throwable?=null) {
        ERROR_LOGGER.error("[$tag]: $msg ${android.util.Log.getStackTraceString(th)}")
    }


    /**
     * 异常堆栈的统一出口：去重 + 截断。
     *
     * @param prefix 保留原有的输出前缀，避免改变既有日志格式
     * @param msg 为空时按单参数 [error] 输出，否则按 `error(msg, text)` 输出（与改造前一致）
     */
    private fun writeStackTrace(th: Throwable?, prefix: String, msg: String?) {
        if (th == null) return
        val signature = ErrorLogPolicy.signatureOf(th)
        val action = errorLogPolicy.actionFor(signature)
        if (action == ErrorLogAction.SUPPRESS) return

        val text = prefix + StackTraceFormatter.truncateFrames(
            android.util.Log.getStackTraceString(th)
        )
        if (msg == null) {
            error(text)
        } else {
            error(msg, text)
        }
        if (action == ErrorLogAction.PRINT_AND_ANNOUNCE) {
            record("⚠️ 错误【$signature】已出现${errorLogPolicy.countOf(signature)}次，后续将不再打印详细堆栈")
        }
    }

    @JvmStatic

    fun printStackTrace(th: Throwable) {
        writeStackTrace(th, "error: ", null)
    }

    @JvmStatic

    fun printStackTrace(msg: String, th: Throwable) {
        writeStackTrace(th, "Throwable error: ", msg)
    }

    @JvmStatic
    fun printStackTrace(tag: String, msg: String, th: Throwable) {
        writeStackTrace(th, "[$tag] Throwable error: ", msg)
    }

    // 兼容 Exception 参数的重载 (Kotlin 中 Exception 是 Throwable 的子类，其实可以直接用上面的)
    // 但为了保持原有 Java API 的签名习惯，这里保留
    @JvmStatic
    fun printStackTrace(e: Exception) {
        printStackTrace(e as Throwable)
    }

    @JvmStatic
    fun printStackTrace(msg: String, e: Exception) {
        printStackTrace(msg, e as Throwable)
    }

    @JvmStatic
    fun printStackTrace(tag: String, msg: String, e: Exception) {
        printStackTrace(tag, msg, e as Throwable)
    }

    @JvmStatic
    fun printStack(tag: String) {
        val stackTrace = "stack: " + android.util.Log.getStackTraceString(Exception("获取当前堆栈$tag:"))
        record(stackTrace)
    }
}