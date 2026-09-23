package fansirsqi.xposed.sesame.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「大号列表配置」的好友名单分类测试。
 *
 * 这里的每一条都对应一个具体风险：
 * - 分类漏项 / 多项 → 一键套用会填错功能，或把非好友列表（商品、海域、区划）当成好友名单；
 * - 方向搞反（把正向名单当排除名单）→ 得到完全相反的效果；
 * - 门控开关没跟着打开 → 名单填了却不生效，用户以为配置成功；
 * - 大号档把「小号」写进排除名单 → **违背用户明确要求**：大号要收小号的能量。
 */
class AccountFriendListPolicyTest {

    private val mainRoot = File("src/main/java/fansirsqi/xposed/sesame")

    /**
     * 业务模型源码。**只取 `task/` 目录** —— `model/` 下的策略文件里也含有这些字段 code 字面量，
     * 若一并扫描，"谁先被遍历到"会决定结果，测试会随机错位。
     */
    private val mainSources: Map<String, String> by lazy {
        mainRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.path.replace('\\', '/').contains("/task/") }
            .associate { it.path.replace('\\', '/') to it.readText() }
    }

    @Test
    fun `分类表共 26 项且无重复`() {
        val lists = AccountFriendListPolicy.FRIEND_LISTS
        assertEquals("好友名单总数与预期不符", 26, lists.size)
        assertEquals("存在重复登记", emptyList<String>(), lists.map { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys.sorted())
        assertEquals(11, AccountFriendListPolicy.ofKind(FriendListKind.SERVICE).size)
        assertEquals(10, AccountFriendListPolicy.ofKind(FriendListKind.EXPLOIT).size)
        assertEquals(4, AccountFriendListPolicy.ofKind(FriendListKind.EXCLUSION).size)
        assertEquals(1, AccountFriendListPolicy.ofKind(FriendListKind.PROTECT).size)
    }

    @Test
    fun `每一项的字段 code 在主源码中都存在`() {
        val missing = AccountFriendListPolicy.FRIEND_LISTS
            .filter { ref -> mainSources.values.none { it.contains("\"${ref.fieldCode}\"") } }
            .map { it.key }
        assertTrue("以下字段在主源码中找不到声明：$missing", missing.isEmpty())
    }

    @Test
    fun `每一项的候选集确实取自好友列表 AlipayUser`() {
        // 正向校验：字段声明之后不远处必须出现 AlipayUser 作为候选集，
        // 否则说明把商品 / 海域 / 区划等非好友列表错当成好友名单了。
        val wrong = AccountFriendListPolicy.FRIEND_LISTS.filter { ref ->
            val hit = mainSources.values.firstOrNull { it.contains("\"${ref.fieldCode}\"") } ?: return@filter true
            val idx = hit.indexOf("\"${ref.fieldCode}\"")
            val window = hit.substring(idx, minOf(hit.length, idx + 600))
            !window.contains("AlipayUser")
        }.map { it.key }
        assertTrue("以下字段的候选集不是好友列表（AlipayUser）：$wrong", wrong.isEmpty())
    }

    @Test
    fun `主源码里的好友名单字段都已登记`() {
        // 反查完整性：凡是「候选集 = AlipayUser」的 SelectModelField，其 code 必须出现在分类表里。
        // 用"候选集前最近的英文标识符字面量"定位 code，中文标签天然被排除。
        val supplierRegex = Regex("""AlipayUser\s*(::|\.)getList""")
        val codeRegex = Regex(""""([A-Za-z][A-Za-z0-9_]{3,40})"\s*,""")
        val discovered = mutableSetOf<String>()

        for ((path, text) in mainSources) {
            for (m in supplierRegex.findAll(text)) {
                val head = text.substring(maxOf(0, m.range.first - 900), m.range.first)
                val codes = codeRegex.findAll(head).map { it.groupValues[1] }.toList()
                if (codes.isNotEmpty()) discovered += codes.last()
            }
        }

        val known = AccountFriendListPolicy.FRIEND_LISTS.map { it.fieldCode }.toSet()
        // AntFarmFamily.kt 里的 getList() 是函数内取全部好友，不属于任何设置项字段
        val allowlist = setOf<String>()
        val unknown = (discovered - known - allowlist).sorted()
        assertTrue(
            "以下好友名单字段未登记到 AccountFriendListPolicy（新增设置项时请同步登记）：$unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `小号档要填的名单其门控开关都会在档位里被打开`() {
        val whitelistKeys = AccountFriendListPolicy.ALT_WHITELIST_SWITCHES
            .filter { AccountPresetPolicy.isNeutralValue(it.value).not() }
            .map { it.key }
            .toSet()

        val inert = AccountFriendListPolicy.altListsFilledWithMain()
            .filter { it.gateField != null }
            .filter { ref ->
                val gate = "${ref.modelCode}.${ref.gateField}"
                if (gate in whitelistKeys) {
                    false
                } else {
                    // 或者该开关在小号档的静态取值本身就是「开启」
                    val staticAlt = AccountPresetPolicy.FIELDS
                        .firstOrNull { it.modelCode == ref.modelCode && it.fieldCode == ref.gateField }
                        ?.altValue
                    AccountPresetPolicy.isNeutralValue(staticAlt)
                }
            }
            .map { "${it.key} 的门控 ${it.gateField} 在小号档不会开启" }

        assertTrue("以下名单填了也不会生效：$inert", inert.isEmpty())
    }

    @Test
    fun `小号档不得把非大号账号写进服务或索取名单`() {
        val ref = AccountFriendListPolicy.FRIEND_LISTS.first { it.fieldCode == "waterFriendList" }
        val mainList = setOf("uid-main")

        // 合规：只填大号
        assertTrue(
            AccountFriendListPolicy
                .altWhitelistViolations(mapOf(ref to mainList), mainList)
                .isEmpty()
        )
        // 违规：混入了第三方
        assertFalse(
            AccountFriendListPolicy
                .altWhitelistViolations(mapOf(ref to setOf("uid-main", "uid-other")), mainList)
                .isEmpty()
        )
    }

    @Test
    fun `大号档不得让小号残留在排除名单里`() {
        val subList = setOf("uid-sub")

        assertTrue(
            AccountFriendListPolicy.mainExclusionViolations(listOf("uid-friend"), subList).isEmpty()
        )
        assertFalse(
            "小号残留在排除名单里必须被判定为违规",
            AccountFriendListPolicy.mainExclusionViolations(listOf("uid-friend", "uid-sub"), subList).isEmpty()
        )
    }

    @Test
    fun `不收能量名单是小号档唯一且必须的排除落点`() {
        val row = AccountPresetPolicy.FIELDS.first {
            it.modelCode == "AntForest" && it.fieldCode == "dontCollectList"
        }
        assertEquals("大号档绝不能写「不收能量名单」", AccountPresetPolicy.KEEP, row.mainValue)
        assertTrue(
            "小号档必须把它写成大号名单",
            row.altValue === WriteMainAccountList,
        )
        assertTrue(
            "小号档的取值必须被判定为中性（安全）",
            AccountPresetPolicy.isNeutralValue(row.altValue),
        )
        assertEquals(
            "该项必须被归类为排除/豁免类",
            FriendListKind.EXCLUSION,
            AccountFriendListPolicy.FRIEND_LISTS.first { it.fieldCode == "dontCollectList" }.kind,
        )
    }

    @Test
    fun `复活能量体系在小号档不参与名单填充`() {
        val untouched = AccountFriendListPolicy.altListsUntouched().map { it.key }
        assertTrue(
            "复活能量相关名单属于独立体系，小号档不应填充：$untouched",
            untouched.contains("AntForest.helpFriendCollectList") &&
                untouched.contains("AntForest.alternativeAccountList"),
        )
        val filled = AccountFriendListPolicy.altListsFilledWithMain().map { it.key }
        assertFalse(
            "复活能量名单不得出现在填充列表里",
            filled.any { it.contains("helpFriendCollect") || it.contains("alternativeAccount") },
        )
    }

    @Test
    fun `索取干扰类名单在小号档一律清空`() {
        val cleared = AccountFriendListPolicy.altListsCleared().map { it.key }
        assertTrue(cleared.contains("AntStall.stallTicketList"))
        assertTrue(cleared.contains("AntStall.stallThrowManureList"))
        assertTrue(cleared.contains("AntSports.originBossIdList"))
        assertTrue(cleared.contains("AntFarm.hireAnimalList"))
        // 索取类不得出现在填充列表里（哪怕是填大号）
        val filled = AccountFriendListPolicy.altListsFilledWithMain().map { it.key }.toSet()
        assertTrue("索取类名单不能出现在填充列表：${cleared.filter { it in filled }}", cleared.none { it in filled })
    }

    @Test
    fun `选择计数型名单都给了正的默认次数`() {
        AccountFriendListPolicy.FRIEND_LISTS.filter { it.isCounted }.forEach { ref ->
            val count = ref.countDefault ?: 0
            assertTrue("${ref.key} 的默认次数必须为正数，否则任务会直接跳过", count > 0)
        }
        // 已知的三个选择计数型名单
        val counted = AccountFriendListPolicy.FRIEND_LISTS.filter { it.isCounted }.map { it.key }.toSet()
        assertEquals(
            setOf(
                "AntForest.waterFriendList",
                "AntFarm.feedFriendAnimalList",
                "AntFarm.visitFriendList",
            ),
            counted,
        )
    }
}
