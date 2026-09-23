package fansirsqi.xposed.sesame.model

/**
 * 账号档位（大号 / 小号）预设的**纯策略**。
 *
 * 设计约束（来自需求）：
 * - 本项目绝大多数「蚂蚁系」功能都会以**好友**为操作对象。同一台设备上，或互为好友的两个账号同时跑本模块时，
 *   默认配置会互相收取 / 占用对方的能量、饲料、金币、卡片等资源。
 * - 因此这里为每个设置项标注 [FieldScope]：
 *   - [FieldScope.SELF]：只作用于本账号的数据与资源，两个档位都可以自由开关；
 *   - [FieldScope.CROSS_ACCOUNT]：会以其他支付宝账号为操作对象（可能读取、占用、转移或干扰对方资源），
 *     **小号档位必须落到「中性值」**（关闭 / 清空 / 不保护），保证小号不可能窃取或消耗大号资源。
 *
 * 本文件是两套预设的**唯一数据源**：既驱动 [AccountPreset] 的写入，也被 [AccountPresetPolicy.altViolations]
 * 用于自检（单测会断言该列表恒为空）。新增设置项时请同步登记到 [FIELDS]，否则「小号隔离」这项保证就失效了。
 *
 * 本对象不依赖任何 Android API，可在 JVM 单元测试中直接调用。
 */
enum class PresetTier(val code: String, val label: String, val summary: String) {
    /** 主力号：全功能档，开启各模块的收益与进度类能力 */
    MAIN("main", "大号", "主力号全功能档：开启各模块的收益与进度类能力"),

    /** 小号：隔离档，跨账号功能只对「大号名单」开放 */
    ALT("alt", "小号", "小号隔离档：跨账号功能只对「大号名单」开放，不会动其他人");

    companion object {
        fun fromCode(code: String?): PresetTier? = entries.firstOrNull { it.code == code }
    }
}

/** 设置项的资源作用域 */
enum class FieldScope {
    /** 只影响本账号 */
    SELF,

    /** 以其他支付宝账号（好友）为操作对象，可能窃取 / 占用 / 干扰对方资源 */
    CROSS_ACCOUNT,
}

/**
 * 预设表的一行：某个模型下的某个设置项，以及两个档位分别要写入的值。
 *
 * @param mainValue 大号档位的值；[AccountPresetPolicy.KEEP] 表示不覆盖（沿用账号当前值）
 * @param altValue  小号档位的值；[FieldScope.CROSS_ACCOUNT] 的行**不允许**为 [AccountPresetPolicy.KEEP]
 * @param altGuardField 门控字段。部分设置项是「动作选择器」，本身没有「不执行」这个选项
 *   （例如 `HireAnimalType` 只有「选中雇佣 / 选中不雇佣」，而 `CleanOceanType` 的「不清理」是 1 不是 0）。
 *   这类行的安全性由**配套开关**保证：只要开关关闭，选择器取什么值都不会产生实际动作。
 *   填写配套开关的字段 code 后，隔离自检会连带校验该开关在小号档位确实被关闭。
 * @param note 备注（说明取值含义或安全依据）
 */
data class PresetField(
    val modelCode: String,
    val fieldCode: String,
    val label: String,
    val scope: FieldScope,
    val mainValue: Any?,
    val altValue: Any?,
    val altGuardField: String? = null,
    val note: String = "",
) {
    /** 唯一标识：`模型.字段` */
    val id: String get() = "$modelCode.$fieldCode"

    /** 取指定档位要写入的值 */
    fun valueFor(tier: PresetTier): Any? = if (tier == PresetTier.MAIN) mainValue else altValue
}

object AccountPresetPolicy {

    /** 不覆盖：沿用账号当前值 */
    val KEEP: Any? = null

    private const val ON = true
    private const val OFF = false

    /** 通用「关闭 / 未选中」选项值 */
    private const val NONE = 0

    private val EMPTY_SET: Set<String> = emptySet()
    private val EMPTY_MAP: Map<String, Int> = emptyMap()

    // ------------------------------------------------------------------ 模型代码
    private const val BASE = "BaseModel"
    private const val FOREST = "AntForest"
    private const val FARM = "AntFarm"
    private const val OCEAN = "AntOcean"
    private const val FISH_POND = "AntFishPond"
    private const val STALL = "AntStall"
    private const val DODO = "AntDodo"
    private const val COOPERATE = "AntCooperate"
    private const val MEMBER = "AntMember"
    private const val ORCHARD = "AntOrchard"
    private const val SPORTS = "AntSports"
    private const val ANCIENT_TREE = "EcoProtection"
    private const val GREEN_FINANCE = "GreenFinance"
    private const val RESERVE = "Reserve"
    private const val OTHER = "OtherTask"
    private const val ANSWER_AI = "AnswerAI"

    // ------------------------------------------------------------------ 行构造
    private fun self(model: String, code: String, label: String, main: Any?, alt: Any?) =
        PresetField(model, code, label, FieldScope.SELF, main, alt)

    private fun cross(
        model: String,
        code: String,
        label: String,
        main: Any?,
        alt: Any?,
        note: String = "",
    ) = PresetField(model, code, label, FieldScope.CROSS_ACCOUNT, main, alt, null, note)

    /** 动作选择器：安全性由 [guard] 指向的配套开关保证（见 [PresetField.altGuardField]） */
    private fun guardedCross(
        model: String,
        code: String,
        label: String,
        main: Any?,
        alt: Any?,
        guard: String,
        note: String,
    ) = PresetField(model, code, label, FieldScope.CROSS_ACCOUNT, main, alt, guard, note)

    // ==================================================================
    //  模块启用开关（所有档位一律启用；具体功能由下面的行控制）
    // ==================================================================
    private val MODULES = listOf(
        BASE, FOREST, FARM, OCEAN, FISH_POND, STALL, DODO,
        COOPERATE, MEMBER, ORCHARD, SPORTS, ANCIENT_TREE,
        GREEN_FINANCE, RESERVE, OTHER, ANSWER_AI,
    )

    private val MODULE_ENABLE: List<PresetField> = MODULES.map {
        self(it, "enable", "启用模块", ON, ON)
    }

    /**
     * 全量设置项表。
     *
     * 约定：
     * - [FieldScope.CROSS_ACCOUNT] 的**开关类**行，`altValue` 必须是中性值（false / 0 / 空集合 / 空 Map / 空串）；
     * - 名单类字段（SelectModelField / SelectAndCountModelField）在大号档位一律 [KEEP]，因为无法预知用户要给谁浇水、送谁道具；
     * - 调优类参数（间隔、次数、时间点）不参与档位切换，一律 [KEEP]，避免一键切换打乱用户手感。
     */
    val FIELDS: List<PresetField> = buildList {
        addAll(MODULE_ENABLE)

        // ============================================================== 基础设置
        // 调度、日志、时区、通知等基础项与「账号资源」无关，两档都保持用户当前值。
        addAll(
            listOf(
                self(BASE, "stayAwake", "保持唤醒", KEEP, KEEP),
                self(BASE, "manualTriggerAutoSchedule", "手动触发目标应用运行", KEEP, KEEP),
                self(BASE, "checkInterval", "执行间隔(分钟)", KEEP, KEEP),
                self(BASE, "taskExecutionRounds", "任务执行轮数", KEEP, KEEP),
                self(BASE, "modelSleepTime", "模块休眠时间", KEEP, KEEP),
                self(BASE, "execAtTimeList", "定时执行时间点", KEEP, KEEP),
                self(BASE, "wakenAtTimeList", "定时唤醒时间点", KEEP, KEEP),
                self(BASE, "energyTime", "只收能量时间", KEEP, KEEP),
                self(BASE, "timedTaskModel", "定时任务模式", KEEP, KEEP),
                self(BASE, "timeoutRestart", "超时重启", KEEP, KEEP),
                self(BASE, "waitWhenException", "异常等待时间", KEEP, KEEP),
                self(BASE, "errNotify", "开启异常通知", KEEP, KEEP),
                self(BASE, "setMaxErrorCount", "异常次数阈值", KEEP, KEEP),
                self(BASE, "newRpc", "使用新接口", KEEP, KEEP),
                self(BASE, "debugMode", "开启抓包", KEEP, KEEP),
                self(BASE, "batteryPerm", "为目标应用申请后台运行权限", KEEP, KEEP),
                self(BASE, "recordLog", "记录 record 日志", KEEP, KEEP),
                self(BASE, "runtimeLog", "记录 runtime 日志", KEEP, KEEP),
                self(BASE, "showToast", "气泡提示", KEEP, KEEP),
                self(BASE, "toastPerfix", "气泡前缀", KEEP, KEEP),
                self(BASE, "toastOffsetY", "气泡纵向偏移", KEEP, KEEP),
                self(BASE, "languageSimplifiedChinese", "只显示中文并设置时区", KEEP, KEEP),
                self(BASE, "enableOnGoing", "开启状态栏禁删", KEEP, KEEP),
                self(BASE, "sendHookData", "启用 Hook 数据转发", KEEP, KEEP),
                self(BASE, "sendHookDataUrl", "Hook 数据转发地址", KEEP, KEEP),
                // 账号关系名单：取值由用户在切换流程里当场选择，不走静态表，故两档都是「不覆盖」。
                self(BASE, "mainAccountList", "大号名单（供各功能批量启用）", KEEP, KEEP),
                self(BASE, "subAccountList", "小号名单（供各功能批量启用）", KEEP, KEEP),
            )
        )

        // ============================================================== 蚂蚁森林
        addAll(
            listOf(
                // —— 跨账号：收取 / 窃取类，小号一律关闭 ——
                cross(FOREST, "collectEnergy", "收集能量（同时门控好友树林）", ON, OFF, "无法只收自己不收好友，小号必须关闭"),
                cross(FOREST, "batchRobEnergy", "一键收取好友能量", ON, OFF),
                cross(FOREST, "pkEnergy", "Pk 榜收取", ON, OFF),
                cross(FOREST, "energyPvpChallenge", "1V1 能量挑战自动领奖", ON, OFF),
                cross(FOREST, "energyBombCardType", "炸弹卡（炸好友能量）", NONE, NONE),
                cross(FOREST, "robExpandCard", "1.1 倍能量卡（收好友翻倍）", NONE, NONE),
                cross(FOREST, "collectGiftBox", "领取好友礼盒", ON, OFF),
                cross(FOREST, "helpFriendCollectType", "复活能量", NONE, NONE),
                // —— 跨账号：名单类，小号清空 ——
                cross(FOREST, "dontCollectList", "不收能量名单", KEEP, KEEP, "只由二级页勾选「不收取 TA 的能量」写入；静态表不覆盖（小号不偷大号由 collectEnergy=false 兜底）"),
                cross(FOREST, "alternativeAccountList", "小号列表（复活能量保护名单）", KEEP, KEEP, "只由勾选「帮 TA 复活能量」写入；静态表不覆盖"),
                cross(FOREST, "helpFriendCollectList", "复活能量好友列表", KEEP, KEEP, "只由勾选写入；该功能当前版本不生效，默认也不推荐"),
                cross(FOREST, "giveEnergyRainList", "赠送能量雨名单", KEEP, KEEP),
                cross(FOREST, "whoYouWantToGiveTo", "赠送道具对象", KEEP, KEEP),
                cross(FOREST, "giveProp", "赠送道具", OFF, OFF),
                cross(FOREST, "waterFriendList", "浇水好友列表", KEEP, KEEP),
                // 浇水克数：合法值只有 10/18/33/66，0 会让浇水失效 → "具体参数"，不随档位改动
                cross(FOREST, "waterFriendCount", "浇水克数", KEEP, KEEP),
                cross(FOREST, "notifyFriend", "浇水通知好友", OFF, OFF),
                cross(FOREST, "returnWater10", "返水 10g 门槛", KEEP, NONE),
                cross(FOREST, "returnWater18", "返水 18g 门槛", KEEP, NONE),
                cross(FOREST, "returnWater33", "返水 33g 门槛", KEEP, NONE),
                // —— 纯本账号 ——
                self(FOREST, "energyRain", "能量雨", ON, ON),
                self(FOREST, "energyRainTime", "能量雨执行时间", KEEP, KEEP),
                self(FOREST, "energyRainChance", "兑换使用能量雨次卡", OFF, OFF),
                self(FOREST, "whackMoleMode", "6 秒拼手速运行模式", NONE, NONE),
                self(FOREST, "whackMoleGames", "6 秒拼手速激进局数", KEEP, KEEP),
                self(FOREST, "whackMoleMoleCount", "6 秒拼手速兼容击打数", KEEP, KEEP),
                self(FOREST, "whackMoleTime", "6 秒拼手速执行时间", KEEP, KEEP),
                self(FOREST, "CollectSelfEnergyType", "收自己单个能量球方式", NONE, NONE),
                self(FOREST, "CollectSelfEnergyThreshold", "收自己单个能量球阈值", KEEP, KEEP),
                self(FOREST, "robExpandCardLimt", "收取翻倍能量阈值", KEEP, KEEP),
                self(FOREST, "robExpandCardTime", "1.1 倍能量卡使用时间", KEEP, KEEP),
                self(FOREST, "CollectBombEnergyLimit", "单个炸弹能量大于该值收取", KEEP, KEEP),
                self(FOREST, "collectWateringBubble", "收取浇水金球", ON, OFF),
                self(FOREST, "collectProp", "收集道具", ON, OFF),
                self(FOREST, "doubleCard", "双击卡消耗类型", 1, NONE),
                self(FOREST, "doubleCountLimit", "双击卡使用次数", KEEP, KEEP),
                self(FOREST, "doubleCardTime", "双击卡使用时间", KEEP, KEEP),
                self(FOREST, "DoubleCardConstant", "限时双击永动机", ON, OFF),
                self(FOREST, "bubbleBoostCard", "加速器消耗类型", 1, NONE),
                self(FOREST, "bubbleBoostTime", "加速器使用时间", KEEP, KEEP),
                self(FOREST, "shieldCard", "保护罩消耗类型", 1, NONE),
                self(FOREST, "shieldCardConstant", "限时保护永动机", ON, OFF),
                self(FOREST, "stealthCard", "隐身卡消耗类型", 1, NONE),
                self(FOREST, "stealthCardConstant", "限时隐身永动机", ON, OFF),
                self(FOREST, "vitalityExchange", "活力值兑换", ON, ON),
                self(FOREST, "vitalityExchangeList", "活力值兑换列表", KEEP, KEEP),
                self(FOREST, "userPatrol", "保护地巡护", ON, ON),
                self(FOREST, "combineAnimalPiece", "合成动物碎片", ON, ON),
                self(FOREST, "consumeAnimalProp", "派遣动物伙伴", ON, OFF),
                self(FOREST, "receiveForestTaskAward", "森林任务", ON, ON),
                self(FOREST, "forestChouChouLe", "森林寻宝任务", ON, ON),
                self(FOREST, "ecoDailyTask", "森林任务 | 环保打卡", ON, ON),
                self(FOREST, "gift7thSign", "森林 7 天签到（新用户）", OFF, OFF),
                self(FOREST, "medicalHealth", "健康医疗任务", ON, ON),
                self(FOREST, "medicalHealthOption", "健康医疗选项", KEEP, KEEP),
                self(FOREST, "forestMarket", "森林集市", ON, ON),
                self(FOREST, "youthPrivilege", "青春特权 | 森林道具", ON, ON),
                self(FOREST, "studentCheckIn", "青春特权 | 签到红包", ON, ON),
                self(FOREST, "ecoLife", "绿色行动", ON, ON),
                self(FOREST, "ecoLifeTime", "绿色行动执行时间", KEEP, KEEP),
                self(FOREST, "ecoLifeOpen", "绿色任务 | 自动开通", OFF, OFF),
                self(FOREST, "ecoLifeOption", "绿色行动选项", KEEP, KEEP),
                self(FOREST, "queryInterval", "查询间隔", KEEP, KEEP),
                self(FOREST, "collectInterval", "收取间隔", KEEP, KEEP),
                self(FOREST, "doubleCollectInterval", "双击间隔", KEEP, KEEP),
                self(FOREST, "balanceNetworkDelay", "平衡网络延迟", KEEP, KEEP),
                self(FOREST, "advanceTime", "提前时间", KEEP, KEEP),
                self(FOREST, "tryCount", "尝试收取次数", KEEP, KEEP),
                self(FOREST, "retryInterval", "重试间隔", KEEP, KEEP),
                self(FOREST, "cycleinterval", "循环间隔", KEEP, KEEP),
                self(FOREST, "showBagList", "显示背包内容", KEEP, KEEP),
            )
        )

        // ============================================================== 蚂蚁庄园
        addAll(
            listOf(
                // —— 跨账号 ——
                cross(FARM, "feedFriendAnimalList", "帮喂小鸡好友列表", KEEP, KEEP),
                cross(FARM, "rewardFriend", "打赏好友", OFF, OFF),
                cross(FARM, "getFeed", "一起拿饲料", OFF, OFF),
                guardedCross(FARM, "getFeedType", "一起拿饲料动作", NONE, NONE, "getFeed", "GetFeedType：0=选中赠送，1=随机赠送，没有「不执行」选项；安全性由 getFeed 开关保证"),
                cross(FARM, "getFeedlList", "一起拿饲料好友列表", KEEP, KEEP),
                cross(FARM, "acceptGift", "收麦子", OFF, OFF),
                cross(FARM, "visitFriendList", "送麦子好友列表", KEEP, KEEP),
                cross(FARM, "hireAnimal", "雇佣好友小鸡", ON, OFF, "占用好友小鸡产出"),
                guardedCross(FARM, "hireAnimalType", "雇佣小鸡动作", NONE, 1, "hireAnimal", "HireAnimalType：0=选中雇佣，1=选中不雇佣"),
                cross(FARM, "hireAnimalList", "雇佣小鸡好友列表", KEEP, KEEP),
                cross(FARM, "notifyFriend", "通知赶鸡", OFF, OFF),
                guardedCross(FARM, "notifyFriendType", "通知赶鸡动作", 1, 1, "notifyFriend", "NotifyFriendType：0=选中通知，1=选中不通知"),
                cross(FARM, "notifyFriendList", "通知赶鸡好友列表", KEEP, KEEP),
                cross(FARM, "dontSendFriendList", "遣返好友排除名单", KEEP, KEEP),
                cross(FARM, "collectChickenDiary", "小鸡日记 | 给好友点赞", NONE, NONE),
                cross(FARM, "visitAnimal", "到访小鸡送礼", OFF, OFF),
                cross(FARM, "family", "家庭", OFF, OFF),
                cross(FARM, "familyOptions", "家庭选项", KEEP, EMPTY_SET),
                cross(FARM, "notInviteList", "家庭好友分享排除列表", KEEP, KEEP),
                // 注：AntFarm.giftFamilyDrawFragment 的 addField 在主源码里是注释状态（AntFarm.kt:613），
                // 未注册进 ModelConfig，因此不登记到本表 —— 否则每次切换都会多出一条「跳过」噪声，
                // 掩盖真正的字段缺失。同理未登记的还有 AntMember.annualReview（年度回顾已下线）。
                // —— 纯本账号 ——
                self(FARM, "recallAnimalType", "召回小鸡", NONE, NONE),
                self(FARM, "feedAnimal", "自动喂小鸡", ON, ON),
                self(FARM, "doFarmTask", "做饲料任务", ON, ON),
                self(FARM, "doFarmTaskTime", "饲料任务执行时间", KEEP, KEEP),
                self(FARM, "receiveFarmTaskAward", "收取饲料奖励", ON, ON),
                self(FARM, "useBigEaterTool", "加饭卡", ON, OFF),
                self(FARM, "useAccelerateTool", "加速卡", ON, OFF),
                self(FARM, "useAccelerateToolContinue", "加速卡 | 连续使用", ON, OFF),
                self(FARM, "useAccelerateToolWhenMaxEmotion", "加速卡 | 仅满状态使用", ON, OFF),
                self(FARM, "remainingTime", "饲料剩余时间加速阈值", KEEP, KEEP),
                self(FARM, "ignoreAcceLimit", "忽略加速限制", KEEP, KEEP),
                self(FARM, "enableChouchoule", "小鸡抽抽乐", ON, OFF),
                self(FARM, "autoExchange", "抽抽乐自动兑换物品", ON, OFF),
                self(FARM, "enableChouchouleTime", "抽抽乐执行时间", KEEP, KEEP),
                self(FARM, "recordFarmGame", "游戏改分", ON, OFF),
                self(FARM, "gameRewardMax", "游戏改分产出上限", KEEP, KEEP),
                self(FARM, "farmGameTime", "小鸡游戏时间", KEEP, KEEP),
                self(FARM, "enableDdrawGameCenterAward", "开宝箱", ON, ON),
                self(FARM, "sleepTime", "小鸡睡觉时间", KEEP, KEEP),
                self(FARM, "wakeupTime", "小鸡起床时间", KEEP, KEEP),
                self(FARM, "npcAnimalType", "雇佣 NPC 小鸡", NONE, NONE),
                self(FARM, "sendBackAnimal", "遣返", ON, ON),
                self(FARM, "timeSendBack", "投喂后赶鸡间隔", KEEP, KEEP),
                self(FARM, "sendBackAnimalWay", "遣返方式", 1, 1),
                self(FARM, "sendBackAnimalType", "遣返动作", NONE, NONE),
                self(FARM, "donation", "每日捐蛋", ON, OFF),
                self(FARM, "donationCount", "每日捐蛋次数", KEEP, KEEP),
                self(FARM, "useSpecialFood", "使用特殊食品", ON, OFF),
                self(FARM, "useNewEggCard", "使用新蛋卡", ON, OFF),
                self(FARM, "signRegardless", "签到忽略饲料余量", ON, ON),
                self(FARM, "receiveFarmToolReward", "收取道具奖励", ON, ON),
                self(FARM, "harvestProduce", "收获爱心鸡蛋", ON, ON),
                self(FARM, "kitchen", "小鸡厨房", ON, ON),
                self(FARM, "chickenDiary", "小鸡日记", ON, ON),
                self(FARM, "diaryTietze", "小鸡日记 | 贴贴", ON, ON),
                self(FARM, "listOrnaments", "小鸡每日换装", ON, ON),
                self(FARM, "paradiseCoinExchangeBenefit", "小鸡乐园兑换权益", ON, OFF),
                self(FARM, "paradiseCoinExchangeBenefitList", "小鸡乐园权益列表", KEEP, KEEP),
                self(FARM, "useSmartSchedulerManager", "使用智能定时蹲点", KEEP, KEEP),
                self(FARM, "doChouChouLeDonationTask", "抽抽乐捐赠任务（禁止开启）", OFF, OFF),
            )
        )

        // ============================================================== 神奇海洋
        addAll(
            listOf(
                cross(OCEAN, "cleanOcean", "清理好友海洋", ON, OFF),
                guardedCross(OCEAN, "cleanOceanType", "清理动作", NONE, 1, "cleanOcean", "CleanOceanType：0=选中清理，1=选中不清理"),
                cross(OCEAN, "cleanOceanList", "清理好友列表", KEEP, KEEP),
                cross(OCEAN, "userprotectType", "保护类型（面向他人海域）", NONE, NONE),
                cross(OCEAN, "protectOceanList", "保护海域列表", KEEP, EMPTY_MAP),
                self(OCEAN, "dailyOceanTask", "海洋任务", ON, ON),
                self(OCEAN, "aiFish", "AI 摸鱼", ON, ON),
                self(OCEAN, "exchangeProp", "制作万能拼图", ON, OFF),
                self(OCEAN, "usePropByType", "使用万能拼图", ON, OFF),
                self(OCEAN, "PDL_task", "潘多拉任务", ON, ON),
            )
        )

        // ============================================================== 福气鱼池
        addAll(
            listOf(
                self(FISH_POND, "fishPondTask", "鱼池任务 | 签到与领奖", ON, ON),
                self(FISH_POND, "autoFish", "自动钓鱼", ON, OFF),
                self(FISH_POND, "fishDailyLimit", "自动钓鱼每日次数", KEEP, KEEP),
            )
        )

        // ============================================================== 蚂蚁新村
        addAll(
            listOf(
                cross(STALL, "stallAutoOpen", "摆摊（进入好友村子）", ON, OFF),
                guardedCross(STALL, "stallOpenType", "摆摊动作", NONE, 1, "stallAutoOpen", "StallOpenType：0=选中摆摊，1=选中不摆摊"),
                cross(STALL, "stallOpenList", "摆摊好友列表", KEEP, KEEP),
                cross(STALL, "stallAutoTicket", "贴罚单", ON, OFF),
                guardedCross(STALL, "stallTicketType", "贴罚单动作", NONE, 1, "stallAutoTicket", "StallTicketType：0=选中贴罚单，1=选中不贴罚单"),
                cross(STALL, "stallTicketList", "贴罚单好友列表", KEEP, KEEP),
                cross(STALL, "stallThrowManure", "丢肥料", OFF, OFF),
                guardedCross(STALL, "stallThrowManureType", "丢肥料动作", 1, 1, "stallThrowManure", "StallThrowManureType：0=选中丢肥料，1=选中不丢肥料"),
                cross(STALL, "stallThrowManureList", "丢肥料好友列表", KEEP, KEEP),
                cross(STALL, "stallInviteShop", "邀请好友摆摊", OFF, OFF),
                guardedCross(STALL, "stallInviteShopType", "邀请摆摊动作", 1, 1, "stallInviteShop", "StallInviteShopType：0=选中邀请，1=选中不邀请"),
                cross(STALL, "stallInviteShopList", "邀请摆摊好友列表", KEEP, KEEP),
                cross(STALL, "stallAllowOpenReject", "请走小摊", OFF, OFF),
                cross(STALL, "stallWhiteList", "请走小摊白名单", KEEP, KEEP),
                cross(STALL, "stallBlackList", "请走小摊黑名单", KEEP, KEEP),
                cross(STALL, "stallInviteRegister", "邀请好友开通新村", OFF, OFF),
                cross(STALL, "stallInviteRegisterList", "邀请开通好友列表", KEEP, KEEP),
                cross(STALL, "assistFriendList", "助力好友列表", KEEP, KEEP),
                self(STALL, "stallAutoClose", "收摊", ON, OFF),
                self(STALL, "stallSelfOpenTime", "收摊摆摊时长", KEEP, KEEP),
                self(STALL, "stallAllowOpenTime", "请走小摊允许时长", KEEP, KEEP),
                self(STALL, "stallAutoTask", "自动任务", ON, ON),
                self(STALL, "stallReceiveAward", "自动领奖", ON, ON),
                self(STALL, "stallDonate", "自动捐赠", ON, OFF),
                self(STALL, "roadmap", "自动进入下一村", ON, ON),
            )
        )

        // ============================================================== 神奇物种
        addAll(
            listOf(
                cross(DODO, "collectToFriend", "帮好友抽卡", ON, OFF),
                guardedCross(DODO, "collectToFriendType", "帮抽卡动作", NONE, 1, "collectToFriend", "CollectToFriendType：0=选中帮抽卡，1=选中不帮抽卡"),
                cross(DODO, "collectToFriendList", "帮抽卡好友列表", KEEP, KEEP),
                cross(DODO, "sendFriendCard", "送卡片好友列表", KEEP, KEEP),
                self(DODO, "usepropGroup", "使用道具类型", KEEP, KEEP),
                self(DODO, "usePropUNIVERSALCARDType", "万能卡使用方式", KEEP, KEEP),
                self(DODO, "autoGenerateBook", "自动合成图鉴", ON, ON),
            )
        )

        // ============================================================== 蚂蚁森林合种
        addAll(
            listOf(
                cross(COOPERATE, "cooperateWater", "合种浇水", ON, OFF, "浇水消耗自身能量、收益归共享树"),
                cross(COOPERATE, "cooperateWaterList", "合种浇水列表", KEEP, EMPTY_MAP),
                cross(COOPERATE, "cooperateWaterTotalLimitList", "浇水总量限制列表", KEEP, EMPTY_MAP),
                cross(COOPERATE, "cooperateSendCooperateBeckon", "召唤队友浇水（仅队长）", OFF, OFF),
                cross(COOPERATE, "loveCooperateWater", "真爱合种浇水", OFF, OFF),
                cross(COOPERATE, "loveCooperateWaterNum", "真爱合种浇水克数", KEEP, NONE),
                cross(COOPERATE, "teamCooperateWaterNum", "组队合种浇水克数", KEEP, NONE),
            )
        )

        // ============================================================== 会员（全部为本账号权益）
        addAll(
            listOf(
                self(MEMBER, "memberSign", "会员签到", ON, ON),
                self(MEMBER, "memberTask", "会员任务", ON, ON),
                self(MEMBER, "memberPointExchangeBenefit", "会员积分兑换权益", ON, OFF),
                self(MEMBER, "memberPointExchangeBenefitList", "会员积分兑换列表", KEEP, KEEP),
                self(MEMBER, "sesameGrainExchange", "芝麻粒兑换道具", ON, OFF),
                self(MEMBER, "sesameGrainExchangeList", "芝麻粒兑换列表", KEEP, KEEP),
                self(MEMBER, "sesameTask", "芝麻粒信用任务", ON, ON),
                self(MEMBER, "collectSesame", "芝麻粒领取", ON, ON),
                self(MEMBER, "collectSesameWithOneClick", "芝麻粒一键收取", ON, ON),
                self(MEMBER, "sesameAlchemy", "芝麻炼金", ON, ON),
                self(MEMBER, "enableZhimaTree", "芝麻树", ON, ON),
                self(MEMBER, "collectInsuredGold", "蚂蚁保保障金领取", ON, ON),
                self(MEMBER, "enableGoldTicket", "黄金票签到", ON, ON),
                self(MEMBER, "enableGoldTicketConsume", "黄金票提取（兑换黄金）", OFF, OFF),
                self(MEMBER, "enableGameCenter", "游戏中心签到", ON, ON),
                self(MEMBER, "merchantSign", "商家服务签到", ON, ON),
                self(MEMBER, "merchantKmdk", "商家服务开门打卡", ON, ON),
                self(MEMBER, "merchantMoreTask", "商家服务积分任务", ON, ON),
                self(MEMBER, "beanSignIn", "安心豆签到", ON, ON),
                self(MEMBER, "beanExchangeBubbleBoost", "安心豆兑换时光加速器", OFF, OFF),
                self(MEMBER, "CollectStickers", "领取贴纸", ON, ON),
            )
        )

        // ============================================================== 蚂蚁农场
        addAll(
            listOf(
                cross(ORCHARD, "assistFriendList", "助力好友列表", KEEP, KEEP),
                self(ORCHARD, "plantMode", "种植模式", KEEP, KEEP),
                self(ORCHARD, "executeInterval", "执行间隔", KEEP, KEEP),
                self(ORCHARD, "receiveSevenDayGift", "收取七日礼包", ON, ON),
                self(ORCHARD, "receiveOrchardTaskAward", "收取农场任务奖励", ON, ON),
                self(ORCHARD, "orchardSpreadManureCount", "果树每日施肥次数", KEEP, NONE),
                self(ORCHARD, "orchardSpreadManureCountYeb", "摇钱树每日施肥次数", KEEP, NONE),
            )
        )

        // ============================================================== 运动
        addAll(
            listOf(
                cross(SPORTS, "battleForFriends", "抢好友", ON, OFF),
                guardedCross(SPORTS, "battleForFriendType", "抢好友动作", NONE, 1, "battleForFriends", "BattleForFriendType：0=选中抢，1=选中不抢"),
                cross(SPORTS, "originBossIdList", "抢好友列表", KEEP, KEEP),
                cross(SPORTS, "trainFriend", "训练好友", ON, OFF),
                self(SPORTS, "walk", "行走路线", ON, ON),
                self(SPORTS, "walkPathTheme", "行走路线主题", KEEP, KEEP),
                self(SPORTS, "walkCustomPath", "自定义路线", KEEP, KEEP),
                self(SPORTS, "walkCustomPathId", "自定义路线代码", KEEP, KEEP),
                self(SPORTS, "openTreasureBox", "开启宝箱", ON, ON),
                self(SPORTS, "sportsTasks", "运动任务", ON, ON),
                self(SPORTS, "sportsEnergyBubble", "运动球任务（有概率触发滑块验证）", ON, OFF),
                self(SPORTS, "receiveCoinAsset", "收能量气球", ON, ON),
                self(SPORTS, "donateCharityCoin", "捐能量气球", ON, OFF),
                self(SPORTS, "donateCharityCoinType", "捐能量方式", KEEP, KEEP),
                self(SPORTS, "donateCharityCoinAmount", "捐能量数量", KEEP, KEEP),
                self(SPORTS, "neverlandTask", "健康岛任务", ON, ON),
                self(SPORTS, "neverlandGrid", "健康岛自动走路建造", ON, ON),
                self(SPORTS, "neverlandGridStepCount", "健康岛今日走路最大次数", KEEP, KEEP),
                self(SPORTS, "zeroCoinLimit", "训练好友 0 金币上限次数", KEEP, KEEP),
                self(SPORTS, "tiyubiz", "文体中心", ON, ON),
                self(SPORTS, "minExchangeCount", "最小捐步步数", KEEP, KEEP),
                self(SPORTS, "latestExchangeTime", "最晚捐步时间", KEEP, KEEP),
                self(SPORTS, "syncStepCount", "自定义同步步数", KEEP, KEEP),
                self(SPORTS, "coinExchangeDoubleCard", "能量气球兑换双击卡", KEEP, OFF),
            )
        )

        // ============================================================== 保护古树
        addAll(
            listOf(
                self(ANCIENT_TREE, "ancientTreeOnlyWeek", "仅周一三五运行", KEEP, KEEP),
                self(ANCIENT_TREE, "ancientTreeCityCodeList", "古树区划代码列表", KEEP, KEEP),
            )
        )

        // ============================================================== 绿色经营
        addAll(
            listOf(
                cross(GREEN_FINANCE, "greenFinancePointFriend", "收取好友金币", ON, OFF),
                self(GREEN_FINANCE, "greenFinanceLsxd", "打卡 | 绿色行动", ON, ON),
                self(GREEN_FINANCE, "greenFinanceLscg", "打卡 | 绿色采购", ON, ON),
                self(GREEN_FINANCE, "greenFinanceLsbg", "打卡 | 绿色办公", ON, ON),
                self(GREEN_FINANCE, "greenFinanceWdxd", "打卡 | 绿色销售", ON, ON),
                self(GREEN_FINANCE, "greenFinanceLswl", "打卡 | 绿色物流", ON, ON),
                self(GREEN_FINANCE, "greenFinanceDonation", "捐助快过期金币", ON, OFF),
            )
        )

        // ============================================================== 保护地
        addAll(
            listOf(
                self(RESERVE, "reserveList", "保护地列表", KEEP, KEEP),
            )
        )

        // ============================================================== 其他任务
        addAll(
            listOf(
                self(OTHER, "credit2101", "信用 2101", ON, ON),
                self(OTHER, "credit2101Options", "信用 2101 任务选项", KEEP, KEEP),
                self(OTHER, "CreditOptions", "信用 2101 事件类型", KEEP, KEEP),
                self(OTHER, "haojiaWuyou", "好家无忧卡", ON, ON),
            )
        )

        // ============================================================== AI 答题
        // 消耗的是第三方大模型额度，与大号资源无关，两档都保持用户当前值。
        addAll(
            listOf(
                self(ANSWER_AI, "useGeminiAI", "AI 类型", KEEP, KEEP),
                self(ANSWER_AI, "getTongyiAIToken", "通义千问令牌入口", KEEP, KEEP),
                self(ANSWER_AI, "tongYiToken", "通义千问令牌", KEEP, KEEP),
                self(ANSWER_AI, "getGeminiAIToken", "Gemini 令牌入口", KEEP, KEEP),
                self(ANSWER_AI, "GeminiAIToken", "Gemini 令牌", KEEP, KEEP),
                self(ANSWER_AI, "getDeepSeekToken", "DeepSeek 令牌入口", KEEP, KEEP),
                self(ANSWER_AI, "DeepSeekToken", "DeepSeek 令牌", KEEP, KEEP),
                self(ANSWER_AI, "getCustomServiceToken", "自定义服务说明", KEEP, KEEP),
                self(ANSWER_AI, "CustomServiceToken", "自定义服务令牌", KEEP, KEEP),
                self(ANSWER_AI, "CustomServiceBaseUrl", "自定义服务 BaseUrl", KEEP, KEEP),
                self(ANSWER_AI, "CustomServiceModel", "自定义服务模型", KEEP, KEEP),
            )
        )
    }

    /** 全部跨账号设置项 */
    val CROSS_ACCOUNT_FIELDS: List<PresetField> =
        FIELDS.filter { it.scope == FieldScope.CROSS_ACCOUNT }

    /** 取某个档位的完整覆盖表（过滤掉 KEEP 的行） */
    fun overridesFor(tier: PresetTier): List<PresetField> =
        FIELDS.filter { it.valueFor(tier) !== KEEP }

    /**
     * 值是否属于「中性值」——即不会对其他账号产生任何写操作或资源占用的取值。
     */
    fun isNeutralValue(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> !value
        is Int -> value == 0
        is String -> value.isEmpty()
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    /**
     * 纯参数行：数值本身不指定任何账号，但**不是"0 即关闭"型** —— 设 0 会破坏功能
     * （浇水克数的合法值只有 10/18/33/66，0 会让浇水失效）。
     * 这类行两档都必须「不覆盖」，例外在此显式登记，并由单测保证它们确实是 KEEP。
     */
    val PLAIN_PARAM_ROWS: Set<String> = setOf("AntForest.waterFriendCount")

    /** 某个字段 code 是否属于「好友名单」（名单类规则与开关类相反） */
    private fun isFriendList(modelCode: String, fieldCode: String): Boolean =
        AccountFriendListPolicy.byId("$modelCode.$fieldCode") != null

    /**
     * 隔离自检（**开关类**）：返回违反小号隔离约束的跨账号设置项。
     *
     * 合规的开关类跨账号行必须满足其一：
     * 1. `altValue` 是中性值（不可为 [KEEP]——开关是"阻止行为"的最后一层，
     *    保留账号原值就等于没有隔离）；
     * 2. `altValue` 虽是「动作选择器」下标，但有 [PresetField.altGuardField] 指向的配套开关，
     *    且该开关在小号档位确实被关闭。
     *
     * 好友名单类不适用本规则（它们的规则相反，见 [listRowsNotKeep]）；
     * [PLAIN_PARAM_ROWS] 里登记的纯参数也一并豁免。
     * 单测断言本列表恒为空。
     */
    fun altViolations(): List<PresetField> {
        val byKey = FIELDS.associateBy { it.modelCode to it.fieldCode }
        return CROSS_ACCOUNT_FIELDS
            .filterNot { isFriendList(it.modelCode, it.fieldCode) || it.id in PLAIN_PARAM_ROWS }
            .filter { field ->
                val alt = field.altValue
                if (alt === KEEP) {
                    true
                } else if (isNeutralValue(alt)) {
                    false
                } else {
                    val guardCode = field.altGuardField ?: return@filter true
                    val guard = byKey[field.modelCode to guardCode] ?: return@filter true
                    guard.scope != FieldScope.CROSS_ACCOUNT || !isNeutralValue(guard.altValue)
                }
            }
    }

    /**
     * 隔离自检（**名单类**）：好友名单字段在静态表里必须一律 [KEEP]。
     *
     * 名单**只由二级页的勾选驱动**。若静态表也写它，会出现两个后果：
     * 1. 与「没勾就不动」的语义矛盾 —— 未勾选的名单会被静默清空；
     * 2. 与勾选机制打架 —— 用户取消勾选后，静态表写的值仍会留下（取消无效）。
     *
     * 行为层的隔离不依赖名单，而是由开关承担（小号档 `collectEnergy = false`，
     * 即使名单里有人也一颗能量都收不到）。单测断言本列表恒为空。
     */
    fun listRowsNotKeep(): List<String> =
        FRIEND_LIST_ROWS.filter { it.mainValue !== KEEP || it.altValue !== KEEP }.map { it.id }

    /** 静态表里登记为好友名单的那些行 */
    val FRIEND_LIST_ROWS: List<PresetField> =
        FIELDS.filter { isFriendList(it.modelCode, it.fieldCode) }

    /** 隔离自检：`altGuardField` 是否都指向了表中真实存在的行 */
    fun brokenGuards(): List<String> {
        val keys = FIELDS.map { it.modelCode to it.fieldCode }.toSet()
        return CROSS_ACCOUNT_FIELDS
            .mapNotNull { field ->
                field.altGuardField?.let { guard ->
                    val key = field.modelCode to guard
                    if (keys.contains(key)) null else "${field.modelCode}.${field.fieldCode} -> $guard"
                }
            }
    }

    /** 校验表里是否出现重复的 (模型, 字段) */
    fun duplicateEntries(): List<String> =
        FIELDS.groupBy { it.modelCode to it.fieldCode }
            .filterValues { it.size > 1 }
            .keys
            .map { (model, field) -> "$model.$field" }
            .sorted()

    /** 某个档位实际会写入的字段总数（用于 UI 展示） */
    fun overrideCount(tier: PresetTier): Int = overridesFor(tier).size
}
