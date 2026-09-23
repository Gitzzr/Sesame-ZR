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
        assertEquals("存在重复登记", emptyList<String>(), lists.map { it.id }.groupBy { it }.filterValues { it.size > 1 }.keys.sorted())
        assertEquals(11, AccountFriendListPolicy.ofKind(FriendListKind.SERVICE).size)
        assertEquals(10, AccountFriendListPolicy.ofKind(FriendListKind.EXPLOIT).size)
        assertEquals(4, AccountFriendListPolicy.ofKind(FriendListKind.EXCLUSION).size)
        assertEquals(1, AccountFriendListPolicy.ofKind(FriendListKind.PROTECT).size)
    }

    @Test
    fun `每一项的字段 code 在主源码中都存在`() {
        val missing = AccountFriendListPolicy.FRIEND_LISTS
            .filter { ref -> mainSources.values.none { it.contains("\"${ref.fieldCode}\"") } }
            .map { it.id }
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
        }.map { it.id }
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
    fun `选择计数型名单都给了正的默认次数`() {
        AccountFriendListPolicy.FRIEND_LISTS.filter { it.isCounted }.forEach { ref ->
            val count = ref.countDefault ?: 0
            assertTrue("${ref.id} 的默认次数必须为正数，否则任务会直接跳过", count > 0)
        }
        // 已知的三个选择计数型名单
        val counted = AccountFriendListPolicy.FRIEND_LISTS.filter { it.isCounted }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "AntForest.waterFriendList",
                "AntFarm.feedFriendAnimalList",
                "AntFarm.visitFriendList",
            ),
            counted,
        )
    }

    // ================================================================ 功能清单与推荐规则

    @Test
    fun `每项功能都有面向用户的方向化文案`() {
        val bad = AccountFriendListPolicy.FRIEND_LISTS
            .filter { it.featureLabel.isBlank() || it.featureLabel == it.label }
            .map { it.id }
        assertTrue("以下功能缺少方向化文案：$bad", bad.isEmpty())
        // 排除类必须读作「不……」，否则用户会以为勾上就是"去做"
        val wrong = AccountFriendListPolicy.ofKind(FriendListKind.EXCLUSION)
            .filterNot { it.featureLabel.startsWith("不") }
            .map { it.id }
        assertTrue("以下豁免项文案没有表达出「不做」的语义：$wrong", wrong.isEmpty())
    }

    @Test
    fun `每项功能都登记了门控开关与主门控字段一致`() {
        val bad = AccountFriendListPolicy.FRIEND_LISTS
            .filter { it.gateField != null }
            .filter { ref -> ref.gateSwitches.none { it.fieldCode == ref.gateField } }
            .map { it.id }
        assertTrue("以下功能的主门控字段没有出现在 gateSwitches 里：$bad", bad.isEmpty())
    }

    @Test
    fun `大号名单的推荐项与既有白名单填充集一致`() {
        // 向后兼容：默认推荐 = 原先白名单制实际填充的那 12 项
        val expected = buildSet {
            AccountFriendListPolicy.ofKind(FriendListKind.SERVICE)
                .filter { it.effective }
                .forEach { add(it.id) }
            add("AntForest.dontCollectList")
            add("AntFarm.dontSendFriendList")
        }
        assertEquals(expected, AccountFriendListPolicy.recommendedForMainList())
        assertEquals(12, AccountFriendListPolicy.recommendedForMainList().size)
    }

    @Test
    fun `大号名单不推荐索取干扰类与独立体系`() {
        val recommended = AccountFriendListPolicy.recommendedForMainList()
        val bad = AccountFriendListPolicy.FRIEND_LISTS
            .filter { it.kind == FriendListKind.EXPLOIT || it.kind == FriendListKind.PROTECT }
            .filter { it.id in recommended }
            .map { it.id }
        assertTrue("索取/干扰类与独立体系不应默认勾选：$bad", bad.isEmpty())
    }

    @Test
    fun `小号名单只推荐帮 TA 复活能量`() {
        assertEquals(
            setOf("AntForest.alternativeAccountList"),
            AccountFriendListPolicy.recommendedForSubList(),
        )
    }

    @Test
    fun `失效项不进入任何推荐集`() {
        val ineffective = AccountFriendListPolicy.INEFFECTIVE.map { it.id }.toSet()
        assertTrue("当前版本至少有一项失效（复活能量｜好友列表）", ineffective.isNotEmpty())
        assertTrue(
            "失效项不得出现在推荐集里",
            AccountFriendListPolicy.recommendedForMainList().none { it in ineffective } &&
                AccountFriendListPolicy.recommendedForSubList().none { it in ineffective },
        )
    }

    @Test
    fun `大号档禁用排除类功能`() {
        // 大号要收小号的能量 → 「不收取 TA 的能量」这类豁免项对小号没有意义
        val exclusion = AccountFriendListPolicy.ofKind(FriendListKind.EXCLUSION)
        assertTrue(
            "排除类在大号档必须不可选",
            exclusion.none { AccountFriendListPolicy.isSelectable(it, forMainList = false) },
        )
        assertTrue(
            "排除类在小号档可选",
            exclusion.all { AccountFriendListPolicy.isSelectable(it, forMainList = true) },
        )
        assertTrue(
            "服务类在大号档可选",
            AccountFriendListPolicy.ofKind(FriendListKind.SERVICE)
                .all { AccountFriendListPolicy.isSelectable(it, forMainList = false) },
        )
    }

    @Test
    fun `按 id 能取回功能`() {
        AccountFriendListPolicy.FRIEND_LISTS.forEach { ref ->
            assertEquals(ref, AccountFriendListPolicy.byId(ref.id))
        }
        assertEquals(null, AccountFriendListPolicy.byId("NotExist.field"))
    }
}
