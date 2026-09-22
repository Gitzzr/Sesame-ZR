package fansirsqi.xposed.sesame.hook

/**
 * 安全验证暂停的纯判定逻辑（无 Android 依赖，便于单测）。
 *
 * 背景：旧实现把通用业务错误码 `1009` 也当作「需要人工安全验证」，
 * 于是「非好友 / 已领取 / 参数非法」这类普通业务拒绝会触发暂停，
 * 但支付宝根本不会弹出验证页，用户无从操作，只能卸载支付宝清掉暂停标志。
 * 这里把判定收敛为「只认风控文案与专用码」。
 *
 * 2026-09-21 实机日志核对结论：真实风控响应同时携带 `error=1009` 与
 * `errorMessage="为了保障您的操作安全，请进行验证后继续。"`，而普通系统错误是
 * `error=3000, errorMessage="系统出错，正在排查"`。因此「文案判定」既能完整命中真实风控，
 * 又能排除普通错误，不需要保留 1009 单码判定。
 */
object VerificationPausePolicy {

    /** 服务端真正要求人工安全验证时携带的文案。 */
    private val VERIFICATION_TEXTS = listOf(
        "请进行验证后继续",
        "为保障您的正常访问",
        "为了保障您的操作安全"
    )

    /** 明确代表风控验证的错误码。 */
    const val VERIFICATION_ERROR_CODE: String = "RPC_VERIFICATION_REQUIRED"

    /**
     * 通用业务拒绝码。
     * 蚂蚁森林「好友浇水」处对该码的注释即为「访问被拒绝」，不代表需要人工验证。
     */
    const val BUSINESS_REJECT_CODE: String = "1009"

    /** 暂停标志最长保留时间，超过后自动解除，避免误判把用户永久卡在暂停态。 */
    const val VERIFICATION_TTL_MS: Long = 30 * 60 * 1000L

    /**
     * RPC 方法名片段 → 应让用户打开的应用入口。
     *
     * 支付宝的安全验证页是**场景绑定**的：风控在哪个业务场景触发，用户就必须进入那个场景
     * 才能看到验证页。2026-09-21 日志中 `com.alipay.antfarm.doFarmTask` 触发风控，
     * 而用户在支付宝首页 / 蚂蚁森林都看不到任何验证入口 —— 这正是「支付宝没有弹出安全验证」的原因。
     */
    private val ENTRY_HINTS: List<Pair<String, String>> = listOf(
        "antfarm" to "蚂蚁庄园",
        "antforest" to "蚂蚁森林",
        "ecolife" to "蚂蚁森林",
        "antocean" to "神奇海洋",
        "antaifish" to "神奇海洋",
        "antdodo" to "蚂蚁新村",
        "antstall" to "蚂蚁新村",
        "antsports" to "运动",
        "antmember" to "会员中心",
        "alipaymember" to "会员中心",
        "membertangram" to "会员中心",
        "memberasset" to "会员中心",
        "amic." to "会员中心"
    )

    /**
     * 该响应是否要求人工完成安全验证。
     *
     * 只有两种情况成立：文案命中风控话术，或错误码是 [VERIFICATION_ERROR_CODE]。
     */
    @JvmStatic
    fun requiresVerification(errorCode: String?, errorMessage: String?): Boolean {
        val message = errorMessage.orEmpty()
        if (VERIFICATION_TEXTS.any { message.contains(it) }) return true
        return errorCode == VERIFICATION_ERROR_CODE
    }

    /**
     * 由触发风控的 RPC 方法名推断用户应当打开的应用入口。
     * 无法判断时返回 null，不猜测。
     */
    @JvmStatic
    fun entryHintFor(method: String?): String? {
        val name = method.orEmpty()
        if (name.isEmpty()) return null
        return ENTRY_HINTS.firstOrNull { name.contains(it.first) }?.second
    }

    /**
     * 暂停标志是否已过期。
     *
     * [markedAt] 小于等于 0 表示旧版本写入的、没有时间戳的标志，一律视为过期，
     * 这样升级上来的用户会自动解绑，而不是继续被卡住。
     */
    @JvmStatic
    fun isMarkExpired(markedAt: Long, now: Long): Boolean {
        if (markedAt <= 0L) return true
        return now - markedAt > VERIFICATION_TTL_MS
    }
}
