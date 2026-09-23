package fansirsqi.xposed.sesame.model

/**
 * 好友名单（候选集 = 支付宝好友）的分类与档位填充规则。
 *
 * 「大号列表配置」的核心问题不是"把名单填上"，而是**填对方向**：
 * 全仓共 26 个好友名单字段，语义并不一致 ——
 * 有的填进去表示「对这些人做某事」，有的表示「**不**对这些人做某事」，
 * 还有一个是把对方从"被收取对象"改成"被保护对象"。
 * 填错方向会得到完全相反的效果（例如把大号填进「不收能量」会让大号再也收不到能量）。
 *
 * 其次，「名单填了 ≠ 生效」：部分名单被一个**独立的开关**门控，开关关着时名单完全不起作用。
 * 因此每条都登记 [FriendListRef.gateField]；白名单制要「开开关 + 收窄名单」成对做。
 *
 * 本对象不依赖任何 Android API，可在 JVM 单元测试中直接调用。
 */
enum class FriendListKind {
    /** 服务类：对名单内的人做某件事，**消耗自己、利于对方**（浇水、赠送、帮喂…） */
    SERVICE,

    /** 索取 / 干扰类：对名单内的人做某件事，**损人利己**（贴罚单、丢肥料、抢好友…） */
    EXPLOIT,

    /** 排除 / 豁免类：名单内的人**不做**该动作 —— 填人得到的是相反效果 */
    EXCLUSION,

    /** 保护改写类：把对方从「被收取对象」改成「被保护对象」，既不"做"也不"不做" */
    PROTECT,
}

/**
 * 一个好友名单字段。
 *
 * @param gateField    该名单生效所依赖的开关字段（null = 名单非空即生效）
 * @param altFillable  小号档是否把「大号名单」填进该名单。默认 true；
 *                     为 false 的条目属于"复活能量"等独立体系，小号档保持现状。
 * @param countDefault 「选择 + 计数」型名单的默认每日次数（null = 该名单是普通的集合型）。
 *                     计数语义是**每日执行次数**，取值 ≤0 会被任务直接跳过，
 *                     所以一键填充必须给一个正数，这里取最保守的 1。
 */
data class FriendListRef(
    val modelCode: String,
    val fieldCode: String,
    val label: String,
    val kind: FriendListKind,
    val gateField: String? = null,
    val altFillable: Boolean = true,
    val countDefault: Int? = null,
) {
    val key: String get() = "$modelCode.$fieldCode"

    /** 是否「选择 + 计数」型（值类型是 Map<String, Int>） */
    val isCounted: Boolean get() = countDefault != null
}

/** 小号档「白名单制」需要额外打开的开关（"开"的那一半） */
data class WhitelistSwitch(
    val modelCode: String,
    val fieldCode: String,
    val label: String,
    val value: Any,
) {
    val key: String get() = "$modelCode.$fieldCode"
}

object AccountFriendListPolicy {

    private const val BASE = "BaseModel"
    private const val FOREST = "AntForest"
    private const val FARM = "AntFarm"
    private const val OCEAN = "AntOcean"
    private const val STALL = "AntStall"
    private const val DODO = "AntDodo"
    private const val ORCHARD = "AntOrchard"
    private const val SPORTS = "AntSports"

    /** 存放关系名单的模块与字段（基础模块） */
    const val MAIN_LIST_MODEL = BASE
    const val MAIN_LIST_FIELD = "mainAccountList"
    const val SUB_LIST_MODEL = BASE
    const val SUB_LIST_FIELD = "subAccountList"

    /**
     * 全量好友名单字段表（26 项）。
     *
     * 完整性由单测反查：主源码里所有以 `AlipayUser::getList` 为候选集的 `SelectModelField`
     * 都必须出现在本表中；反之表内每一行的 code 也必须在主源码里存在。
     */
    val FRIEND_LISTS: List<FriendListRef> = listOf(
        // ---------------------------------------------------------- 服务类（11）
        FriendListRef(FOREST, "waterFriendList", "浇水 | 好友列表", FriendListKind.SERVICE, countDefault = 1),
        FriendListRef(FOREST, "giveEnergyRainList", "赠送能量雨 | 配置列表", FriendListKind.SERVICE, "energyRain"),
        FriendListRef(FOREST, "whoYouWantToGiveTo", "赠送 | 道具", FriendListKind.SERVICE, "giveProp"),
        FriendListRef(
            FOREST, "helpFriendCollectList", "复活能量 | 好友列表", FriendListKind.SERVICE,
            gateField = "helpFriendCollectType", altFillable = false,
        ),
        FriendListRef(FARM, "feedFriendAnimalList", "帮喂小鸡 | 好友列表", FriendListKind.SERVICE, countDefault = 1),
        FriendListRef(FARM, "getFeedlList", "一起拿饲料 | 好友列表", FriendListKind.SERVICE, "getFeed"),
        FriendListRef(FARM, "visitFriendList", "送麦子好友列表", FriendListKind.SERVICE, "visitAnimal", countDefault = 1),
        FriendListRef(DODO, "collectToFriendList", "帮抽卡 | 好友列表", FriendListKind.SERVICE, "collectToFriend"),
        FriendListRef(DODO, "sendFriendCard", "送卡片好友列表", FriendListKind.SERVICE),
        FriendListRef(ORCHARD, "assistFriendList", "助力好友列表", FriendListKind.SERVICE),
        FriendListRef(STALL, "assistFriendList", "助力好友列表", FriendListKind.SERVICE),

        // ---------------------------------------------------------- 索取 / 干扰类（10）
        FriendListRef(FARM, "hireAnimalList", "雇佣小鸡 | 好友列表", FriendListKind.EXPLOIT, "hireAnimal"),
        FriendListRef(FARM, "notifyFriendList", "通知赶鸡 | 好友列表", FriendListKind.EXPLOIT, "notifyFriend"),
        FriendListRef(SPORTS, "originBossIdList", "抢好友 | 好友列表", FriendListKind.EXPLOIT, "battleForFriends"),
        FriendListRef(STALL, "stallOpenList", "摆摊 | 好友列表", FriendListKind.EXPLOIT, "stallAutoOpen"),
        FriendListRef(STALL, "stallTicketList", "贴罚单 | 好友列表", FriendListKind.EXPLOIT, "stallAutoTicket"),
        FriendListRef(STALL, "stallThrowManureList", "丢肥料 | 好友列表", FriendListKind.EXPLOIT, "stallThrowManure"),
        FriendListRef(STALL, "stallInviteShopList", "邀请摆摊 | 好友列表", FriendListKind.EXPLOIT, "stallInviteShop"),
        FriendListRef(STALL, "stallInviteRegisterList", "邀请 | 好友列表", FriendListKind.EXPLOIT, "stallInviteRegister"),
        FriendListRef(STALL, "stallBlackList", "请走小摊 | 黑名单", FriendListKind.EXPLOIT, "stallAllowOpenReject"),
        FriendListRef(OCEAN, "cleanOceanList", "清理 | 好友列表", FriendListKind.EXPLOIT, "cleanOcean"),

        // ---------------------------------------------------------- 排除 / 豁免类（4）
        // dontCollectList 是「小号不偷大号」的唯一落点，也是约束 3 的另一面。
        FriendListRef(FOREST, "dontCollectList", "不收能量 | 配置列表", FriendListKind.EXCLUSION),
        FriendListRef(FARM, "dontSendFriendList", "遣返 | 好友列表", FriendListKind.EXCLUSION, "sendBackAnimal"),
        // 下面两条的门控开关（家庭 / 请走小摊）在小号档整体关闭，填了也不生效，故不参与。
        FriendListRef(
            FARM, "notInviteList", "家庭 | 好友分享排除列表", FriendListKind.EXCLUSION,
            gateField = "family", altFillable = false,
        ),
        FriendListRef(
            STALL, "stallWhiteList", "请走小摊 | 白名单", FriendListKind.EXCLUSION,
            gateField = "stallAllowOpenReject", altFillable = false,
        ),

        // ---------------------------------------------------------- 保护改写类（1）
        FriendListRef(
            FOREST, "alternativeAccountList", "小号列表（复活能量保护名单）", FriendListKind.PROTECT,
            gateField = "helpFriendCollectType", altFillable = false,
        ),
    )

    /**
     * 小号档「白名单制」需要额外打开的开关。
     *
     * 只在**已指定大号名单**时应用；没指定大号就保持全关（保守）。
     * 与 [altListsFilledWithMain] 成对：开开关 + 把名单收窄到大号，缺一不可。
     */
    val ALT_WHITELIST_SWITCHES: List<WhitelistSwitch> = listOf(
        WhitelistSwitch(FOREST, "giveProp", "赠送道具", true),
        WhitelistSwitch(FARM, "getFeed", "一起拿饲料", true),
        // 动作选择器：0 = "选中赠送"
        WhitelistSwitch(FARM, "getFeedType", "一起拿饲料动作", 0),
        WhitelistSwitch(FARM, "visitAnimal", "到访小鸡送礼（送麦子的门控）", true),
        WhitelistSwitch(DODO, "collectToFriend", "帮好友抽卡", true),
        // 动作选择器：0 = "选中帮抽卡"
        WhitelistSwitch(DODO, "collectToFriendType", "帮抽卡动作", 0),
    )

    // ------------------------------------------------------------------ 查询

    fun ofKind(kind: FriendListKind): List<FriendListRef> = FRIEND_LISTS.filter { it.kind == kind }

    /**
     * 小号档要写入「大号名单」的名单 = 服务类 + 排除豁免类（且 [FriendListRef.altFillable]）。
     *
     * 排除豁免类也要填：`不收能量`、`不遣返`、`家庭不邀请`、`请走小摊白名单` ——
     * 把大号填进去等于"对这个账号豁免"，正是隔离与保护所要的。
     */
    fun altListsFilledWithMain(): List<FriendListRef> =
        FRIEND_LISTS.filter {
            it.altFillable &&
                (it.kind == FriendListKind.SERVICE || it.kind == FriendListKind.EXCLUSION)
        }

    /**
     * 小号档要清空的名单 = 索取 / 干扰类。
     * 这类动作即便是对**大号**做也是净损失（贴罚单、丢肥料、抢好友…），所以一律清空。
     */
    fun altListsCleared(): List<FriendListRef> = ofKind(FriendListKind.EXPLOIT)

    /** 小号档完全不碰的名单（保护改写类等独立体系） */
    fun altListsUntouched(): List<FriendListRef> = FRIEND_LISTS.filterNot { it.altFillable }

    /** 大号档需要**移除**小号的排除类名单：大号要收小号的能量 */
    fun mainExclusionListsToPurge(): List<FriendListRef> = ofKind(FriendListKind.EXCLUSION)

    // ------------------------------------------------------------------ 隔离自检

    /**
     * 约束 1（小号侧）：小号档不得把**大号名单以外**的账号写进任何服务 / 索取类名单。
     * 即"小号只对大号动手"，不波及第三方好友。
     */
    fun altWhitelistViolations(
        targets: Map<FriendListRef, Set<String>>,
        mainList: Set<String>,
    ): List<String> = targets.entries
        .filter { (ref, _) ->
            ref.kind == FriendListKind.SERVICE || ref.kind == FriendListKind.EXPLOIT
        }
        .filter { (_, ids) -> !mainList.containsAll(ids) }
        .map { (ref, ids) -> "${ref.key} 含非大号账号：${ids - mainList}" }

    /**
     * 约束 2（大号侧，用户明确要求）：**大号需要收取小号的能量**，
     * 因此排除类名单（尤其「不收能量」）里绝不能残留小号。
     *
     * @param exclusionResult 应用后排除名单的实际内容
     */
    fun mainExclusionViolations(
        exclusionResult: Collection<String>,
        subList: Set<String>,
    ): List<String> {
        val leaked = exclusionResult.filter { it in subList }
        return if (leaked.isEmpty()) emptyList() else listOf("排除名单里残留了小号：$leaked")
    }
}
