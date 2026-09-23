package fansirsqi.xposed.sesame.model

/**
 * 好友名单（候选集 = 支付宝好友）的分类、门控与「逐项勾选」所需的元数据。
 *
 * 一个好友名单字段 = 一项**可对该好友启用的功能**（1:1），共 26 项。
 * 每项登记四类信息：
 *
 * 1. [FriendListRef.kind] —— 填充语义（服务 / 索取干扰 / 排除豁免 / 保护改写）。
 *    填错方向会得到相反效果（把大号填进「不收能量」会让大号再也收不到能量）。
 * 2. [FriendListRef.featureLabel] —— 面向用户的方向化文案（"TA" = 名单里选中的那个账号）。
 * 3. [FriendListRef.gateSwitches] —— **勾了功能就必须连带打开的开关**。
 *    名单填了不代表生效（赠送道具要开「赠送道具」、送麦子要开「到访小鸡送礼」…）。
 * 4. [FriendListRef.effective] —— 当前实现下是否真的生效（`false` 的项不参与推荐）。
 *
 * 分类与门控都是**枚举白名单**、不做关键词猜测：只有候选集确为 `AlipayUser::getList`
 * 的字段才在表内；候选集是海域 / 合种 ID / 商品 / 区划 / 保护地 / 家庭选项的列表一律排除。
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

/** 需要在勾选功能时一并写入的开关 */
data class GateSwitch(
    val modelCode: String,
    val fieldCode: String,
    val label: String,
    val value: Any,
) {
    val key: String get() = "$modelCode.$fieldCode"
}

/**
 * 一项可对好友启用的功能。
 *
 * @param featureLabel 方向化文案，"TA" 指列表里选中的那个账号
 * @param gateField    主门控开关的字段名（仅用于文档与一致性校验；实际写入看 [gateSwitches]）
 * @param gateSwitches 勾选该功能时要一并写入的开关（含动作选择器）
 * @param countDefault 「选择 + 计数」型名单的默认**每日次数**（null = 普通集合型）。
 *                     计数 ≤0 会被任务直接跳过，所以必须给正数。
 * @param effective    当前实现下是否真的生效。`false` 的项不进入推荐集，UI 会标注说明。
 */
data class FriendListRef(
    val modelCode: String,
    val fieldCode: String,
    val label: String,
    val kind: FriendListKind,
    val featureLabel: String,
    val gateField: String? = null,
    val gateSwitches: List<GateSwitch> = emptyList(),
    val countDefault: Int? = null,
    val effective: Boolean = true,
) {
    val id: String get() = "$modelCode.$fieldCode"

    /** 是否「选择 + 计数」型（值类型是 Map<String, Int>） */
    val isCounted: Boolean get() = countDefault != null
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

    private fun gate(model: String, field: String, label: String, value: Any) =
        GateSwitch(model, field, label, value)

    /** 动作选择器的「执行」下标：0 = 对选中的人执行 */
    private const val CHOICE_DO = 0

    /**
     * 全量功能表（26 项，与好友名单字段 1:1）。
     *
     * 完整性由单测反查：主源码里所有以 `AlipayUser::getList` 为候选集的 `SelectModelField`
     * 都必须出现在本表中；反之表内每一行的 code 也必须在主源码里存在。
     */
    val FRIEND_LISTS: List<FriendListRef> = listOf(
        // ============================================================ 服务 TA（11）
        FriendListRef(
            FOREST, "waterFriendList", "浇水 | 好友列表", FriendListKind.SERVICE,
            "给 TA 浇水", countDefault = 1,
        ),
        FriendListRef(
            FOREST, "giveEnergyRainList", "赠送能量雨 | 配置列表", FriendListKind.SERVICE,
            "赠送 TA 能量雨",
        ),
        FriendListRef(
            FOREST, "whoYouWantToGiveTo", "赠送 | 道具", FriendListKind.SERVICE,
            "赠送 TA 道具", gateField = "giveProp",
            gateSwitches = listOf(gate(FOREST, "giveProp", "赠送道具", true)),
        ),
        FriendListRef(
            FOREST, "helpFriendCollectList", "复活能量 | 好友列表", FriendListKind.SERVICE,
            "复活 TA 的能量", gateField = "helpFriendCollectType", effective = false,
            gateSwitches = listOf(gate(FOREST, "helpFriendCollectType", "复活能量选项", 1)),
        ),
        FriendListRef(
            FARM, "feedFriendAnimalList", "帮喂小鸡 | 好友列表", FriendListKind.SERVICE,
            "帮 TA 喂小鸡", countDefault = 1,
        ),
        FriendListRef(
            FARM, "getFeedlList", "一起拿饲料 | 好友列表", FriendListKind.SERVICE,
            "和 TA 一起拿饲料", gateField = "getFeed",
            gateSwitches = listOf(
                gate(FARM, "getFeed", "一起拿饲料", true),
                gate(FARM, "getFeedType", "一起拿饲料动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            FARM, "visitFriendList", "送麦子好友列表", FriendListKind.SERVICE,
            "送 TA 麦子", gateField = "visitAnimal",
            gateSwitches = listOf(gate(FARM, "visitAnimal", "到访小鸡送礼", true)),
            countDefault = 1,
        ),
        FriendListRef(
            DODO, "collectToFriendList", "帮抽卡 | 好友列表", FriendListKind.SERVICE,
            "帮 TA 抽卡", gateField = "collectToFriend",
            gateSwitches = listOf(
                gate(DODO, "collectToFriend", "帮好友抽卡", true),
                gate(DODO, "collectToFriendType", "帮抽卡动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            DODO, "sendFriendCard", "送卡片好友列表", FriendListKind.SERVICE,
            "送 TA 卡片",
        ),
        FriendListRef(
            ORCHARD, "assistFriendList", "助力好友列表", FriendListKind.SERVICE,
            "助力 TA（农场）",
        ),
        FriendListRef(
            STALL, "assistFriendList", "助力好友列表", FriendListKind.SERVICE,
            "助力 TA（新村）",
        ),

        // ============================================================ 对 TA 索取 / 干扰（10）
        FriendListRef(
            FARM, "hireAnimalList", "雇佣小鸡 | 好友列表", FriendListKind.EXPLOIT,
            "雇佣 TA 的小鸡", gateField = "hireAnimal",
            gateSwitches = listOf(
                gate(FARM, "hireAnimal", "雇佣小鸡", true),
                gate(FARM, "hireAnimalType", "雇佣小鸡动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            FARM, "notifyFriendList", "通知赶鸡 | 好友列表", FriendListKind.EXPLOIT,
            "通知 TA 赶鸡", gateField = "notifyFriend",
            gateSwitches = listOf(
                gate(FARM, "notifyFriend", "通知赶鸡", true),
                gate(FARM, "notifyFriendType", "通知赶鸡动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            SPORTS, "originBossIdList", "抢好友 | 好友列表", FriendListKind.EXPLOIT,
            "抢 TA 的好友", gateField = "battleForFriends",
            gateSwitches = listOf(
                gate(SPORTS, "battleForFriends", "抢好友", true),
                gate(SPORTS, "battleForFriendType", "抢好友动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            STALL, "stallOpenList", "摆摊 | 好友列表", FriendListKind.EXPLOIT,
            "到 TA 的村子摆摊", gateField = "stallAutoOpen",
            gateSwitches = listOf(
                gate(STALL, "stallAutoOpen", "摆摊", true),
                gate(STALL, "stallOpenType", "摆摊动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            STALL, "stallTicketList", "贴罚单 | 好友列表", FriendListKind.EXPLOIT,
            "贴 TA 的罚单", gateField = "stallAutoTicket",
            gateSwitches = listOf(
                gate(STALL, "stallAutoTicket", "贴罚单", true),
                gate(STALL, "stallTicketType", "贴罚单动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            STALL, "stallThrowManureList", "丢肥料 | 好友列表", FriendListKind.EXPLOIT,
            "丢 TA 的肥料", gateField = "stallThrowManure",
            gateSwitches = listOf(
                gate(STALL, "stallThrowManure", "丢肥料", true),
                gate(STALL, "stallThrowManureType", "丢肥料动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            STALL, "stallInviteShopList", "邀请摆摊 | 好友列表", FriendListKind.EXPLOIT,
            "邀请 TA 摆摊", gateField = "stallInviteShop",
            gateSwitches = listOf(
                gate(STALL, "stallInviteShop", "邀请摆摊", true),
                gate(STALL, "stallInviteShopType", "邀请摆摊动作", CHOICE_DO),
            ),
        ),
        FriendListRef(
            STALL, "stallInviteRegisterList", "邀请 | 好友列表", FriendListKind.EXPLOIT,
            "邀请 TA 开通新村", gateField = "stallInviteRegister",
            gateSwitches = listOf(gate(STALL, "stallInviteRegister", "邀请开通新村", true)),
        ),
        FriendListRef(
            STALL, "stallBlackList", "请走小摊 | 黑名单", FriendListKind.EXPLOIT,
            "赶走 TA 的摊位（黑名单）", gateField = "stallAllowOpenReject",
            gateSwitches = listOf(gate(STALL, "stallAllowOpenReject", "请走小摊", true)),
        ),
        FriendListRef(
            OCEAN, "cleanOceanList", "清理 | 好友列表", FriendListKind.EXPLOIT,
            "清理 TA 的海洋", gateField = "cleanOcean",
            gateSwitches = listOf(
                gate(OCEAN, "cleanOcean", "清理好友海洋", true),
                gate(OCEAN, "cleanOceanType", "清理动作", CHOICE_DO),
            ),
        ),

        // ============================================================ TA 的豁免项（4）
        FriendListRef(
            FOREST, "dontCollectList", "不收能量 | 配置列表", FriendListKind.EXCLUSION,
            "不收取 TA 的能量",
        ),
        FriendListRef(
            FARM, "dontSendFriendList", "遣返 | 好友列表", FriendListKind.EXCLUSION,
            "不遣返 TA 的小鸡", gateField = "sendBackAnimal",
            gateSwitches = listOf(gate(FARM, "sendBackAnimal", "遣返", true)),
        ),
        FriendListRef(
            FARM, "notInviteList", "家庭 | 好友分享排除列表", FriendListKind.EXCLUSION,
            "不邀请 TA 进家庭", gateField = "family",
            gateSwitches = listOf(gate(FARM, "family", "家庭", true)),
        ),
        FriendListRef(
            STALL, "stallWhiteList", "请走小摊 | 白名单", FriendListKind.EXCLUSION,
            "不赶走 TA 的摊位（白名单）", gateField = "stallAllowOpenReject",
            gateSwitches = listOf(gate(STALL, "stallAllowOpenReject", "请走小摊", true)),
        ),

        // ============================================================ 独立体系（1）
        FriendListRef(
            FOREST, "alternativeAccountList", "小号列表（复活能量保护名单）", FriendListKind.PROTECT,
            "帮 TA 复活能量（保护能量球）", gateField = "helpFriendCollectType",
            gateSwitches = listOf(gate(FOREST, "helpFriendCollectType", "复活能量选项", 1)),
        ),
    )

    /** 失效项：当前实现下不生效，不进入推荐集 */
    val INEFFECTIVE: List<FriendListRef> = FRIEND_LISTS.filterNot { it.effective }

    // ------------------------------------------------------------------ 查询

    fun ofKind(kind: FriendListKind): List<FriendListRef> = FRIEND_LISTS.filter { it.kind == kind }

    fun byId(id: String): FriendListRef? = FRIEND_LISTS.firstOrNull { it.id == id }

    /** 大号档需要**移除**小号的排除类名单：大号要收小号的能量 */
    fun exclusionLists(): List<FriendListRef> = ofKind(FriendListKind.EXCLUSION)

    // ------------------------------------------------------------------ 推荐规则

    /**
     * **大号名单**（配置小号时，方向 = 小号 → 大号）的推荐功能。
     *
     * = 服务 TA 的 10 项（剔除当前不生效的「复活 TA 的能量」）
     *   + 豁免项里的「不收取 TA 的能量」「不遣返 TA 的小鸡」
     * = **12 项**，与既有白名单制的填充集完全一致（向后兼容）。
     *
     * 刻意不推荐：对 TA 索取/干扰（不该对大号做损人动作）、独立体系（复活能量保护名单）、
     * 「不邀请进家庭」「不赶走摊位」（其门控在小号档是关的，勾了也不生效，但仍可手动勾）。
     */
    fun recommendedForMainList(): Set<String> = buildSet {
        ofKind(FriendListKind.SERVICE).filter { it.effective }.forEach { add(it.id) }
        listOf("dontCollectList", "dontSendFriendList").forEach { code ->
            FRIEND_LISTS.firstOrNull { it.fieldCode == code }?.let { add(it.id) }
        }
    }

    /**
     * **小号名单**（配置大号时，方向 = 大号 → 小号）的推荐功能。
     *
     * 只推荐「帮 TA 复活能量」（`alternativeAccountList`）—— 唯一对大号无损、对小号有益的一项。
     * 刻意不推荐任何排除项（约束：大号要收小号的能量），也不推荐服务类（会消耗大号资源）、
     * 索取类（会动小号的东西）—— 这些都留给用户显式勾选。
     */
    fun recommendedForSubList(): Set<String> =
        setOf(FOREST + ".alternativeAccountList")

    fun recommendedIds(forMainList: Boolean): Set<String> =
        if (forMainList) recommendedForMainList() else recommendedForSubList()

    /** 按方向把推荐集转成「功能 id → 该功能要写入的名单字段」 */
    fun recommendedLists(forMainList: Boolean): List<FriendListRef> =
        recommendedIds(forMainList).mapNotNull { byId(it) }

    /**
     * 某个方向下该项是否可勾选。
     *
     * **大号档禁用排除类** —— 用户明确要求「大号需要收取小号的能量」，
     * 所以「不收取 TA 的能量」这类豁免项对小号根本没有意义，UI 置灰并说明原因。
     */
    fun isSelectable(ref: FriendListRef, forMainList: Boolean): Boolean =
        forMainList || ref.kind != FriendListKind.EXCLUSION

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
        .map { (ref, ids) -> "${ref.id} 含非大号账号：${ids - mainList}" }

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
