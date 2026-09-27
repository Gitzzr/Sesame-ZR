package fansirsqi.xposed.sesame.hook

/**
 * 服务端明确拒绝「今天」的响应。
 *
 * 与 [ServerBusyPolicy]（系统繁忙，稍后再试，隔一会儿还有机会）不同：
 * 这类响应说的是一整天都没戏，`retriable` 也是 false。
 * 2026-09-27 实测蚂蚁森林「赠送道具」每一轮都被这样拒一次
 * （`{"resultCode":"ROBOT_PUNISHMENT","resultDesc":"您近期操作存在异常，请明天再试","retriable":false}`），
 * 从 02:24 一直重复到深夜 —— 一轮一次换不来结果，只是继续堆风控计数。
 */
object DayBlockedPolicy {

    /** 只认这个专用码与这句「明天再试」，不复用笼统的「异常」二字。 */
    const val DAY_BLOCKED_CODE: String = "ROBOT_PUNISHMENT"

    const val DAY_BLOCKED_TEXT: String = "请明天再试"

    @JvmStatic
    fun isDayBlocked(resultCode: String?, resultDesc: String?): Boolean {
        if (resultCode == DAY_BLOCKED_CODE) return true
        return resultDesc?.contains(DAY_BLOCKED_TEXT) == true
    }
}
