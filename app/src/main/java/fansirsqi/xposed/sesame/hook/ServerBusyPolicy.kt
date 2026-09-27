package fansirsqi.xposed.sesame.hook

/**
 * 支付宝明确要求稍后再试时，不再把这次响应当成可立刻重试的网络错误。
 *
 * 单独的 `1009` 不在这里停。那个码也出现在普通业务拒绝和安全验证里，
 * 只有「系统繁忙，请稍后再试」这条文案对应日志里连打的限流。
 */
object ServerBusyPolicy {
    const val BUSY_TEXT: String = "系统繁忙，请稍后再试"

    @JvmStatic
    fun isBusy(errorMessage: String?): Boolean {
        return errorMessage?.contains(BUSY_TEXT) == true
    }
}
