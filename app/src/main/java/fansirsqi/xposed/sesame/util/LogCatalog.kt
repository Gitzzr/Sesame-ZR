package fansirsqi.xposed.sesame.util

/**
 * logName → 中文标签。
 *
 * 日志文件名用的是英文短名（`record.log` / `error.log`），而界面上一向按中文分类展示
 * （见 `ui/screen/content/LogsContent.kt` 的按钮文案）。查看器要在标题上显示「错误日志 · 09-27」
 * 这类文案，就需要一份集中映射 —— 不再让各处各写一套字符串。
 */
object LogCatalog {

    private val LABELS = mapOf(
        "record" to "全部日志",
        "forest" to "森林日志",
        "ocean" to "海洋日志",
        "farm" to "庄园日志",
        "orchard" to "果园日志",
        "stall" to "新村日志",
        "life" to "生活日志",
        "runtime" to "运行日志",
        "error" to "错误日志",
        "capture" to "抓包日志",
        "other" to "其他日志",
        "debug" to "调试日志",
        "system" to "系统日志",
        "captcha" to "验证码日志"
    )

    /** 取中文标签；未登记的名字退回「<name> 日志」，保证任何文件都能显示 */
    @JvmStatic
    fun labelOf(logName: String): String = LABELS[logName] ?: "$logName 日志"
}
