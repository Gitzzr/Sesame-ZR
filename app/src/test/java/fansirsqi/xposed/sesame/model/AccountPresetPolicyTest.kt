package fansirsqi.xposed.sesame.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 账号档位预设的策略级测试。
 *
 * 这是「小号不得窃取或消耗大号资源」这条硬约束的守门人：只要有人往预设表里
 * 添了一条跨账号设置项却没给出中性的小号取值，[AccountPresetPolicy.altViolations] 就会非空，
 * 下面的第一条用例即失败。
 */
class AccountPresetPolicyTest {

    private val mainSourceRoot = File("src/main/java/fansirsqi/xposed/sesame")

    /** 主源码中声明过的全部设置项 code（含未登记的，只要声明存在即可） */
    private val declaredFieldCodes: Set<String> by lazy {
        val regex = Regex("""ModelField\s*\(\s*"([^"]+)"""")
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file -> regex.findAll(file.readText()).map { it.groupValues[1] }.asSequence() }
            .toSet()
    }

    private val modelOrderSource: String by lazy {
        File("src/main/java/fansirsqi/xposed/sesame/model/ModelOrder.kt").readText()
    }

    @Test
    fun `小号档位不含任何可能动到其他账号资源的设置项`() {
        val violations = AccountPresetPolicy.altViolations()
        assertTrue(
            "以下跨账号设置项没有给出中性的小号取值：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `开关类跨账号项必须显式声明小号取值，名单类必须「不覆盖」`() {
        val listIds = AccountPresetPolicy.FRIEND_LIST_ROWS.map { it.id }.toSet()

        // 开关类：不允许「不覆盖」——开关是阻止行为的最后一层
        val switchesMissing = AccountPresetPolicy.CROSS_ACCOUNT_FIELDS
            .filterNot { it.id in listIds || it.id in AccountPresetPolicy.PLAIN_PARAM_ROWS }
            .filter { it.altValue === AccountPresetPolicy.KEEP }
            .map { it.id }
        assertTrue(
            "以下开关类跨账号项的小号取值是「不覆盖」，小号会保留账号原有配置：$switchesMissing",
            switchesMissing.isEmpty(),
        )

        // 名单类：必须「不覆盖」——名单只由勾选驱动，档位不得改写
        val listNotKeep = AccountPresetPolicy.FRIEND_LIST_ROWS
            .filter { it.altValue !== AccountPresetPolicy.KEEP }
            .map { it.id }
        assertTrue(
            "以下名单类项的小号取值不是「不覆盖」，会静默清空或覆盖用户已配的名单：$listNotKeep",
            listNotKeep.isEmpty(),
        )

        // 纯参数例外必须真的「不覆盖」，否则等于参数被档位改坏
        val paramNotKeep = AccountPresetPolicy.PLAIN_PARAM_ROWS.filter { id ->
            AccountPresetPolicy.FIELDS.firstOrNull { it.id == id }?.altValue !== AccountPresetPolicy.KEEP
        }
        assertTrue("以下纯参数行不是「不覆盖」：$paramNotKeep", paramNotKeep.isEmpty())
    }

    @Test
    fun `好友名单类行在静态表里一律不覆盖`() {
        // 名单只由二级页的勾选驱动。若静态表也写它，会造成两个后果：
        //   1. 与「没勾就不动」矛盾 —— 未勾选的名单被静默清空；
        //   2. 与勾选机制打架 —— 用户取消勾选后静态表写的值仍留下（取消无效）。
        val bad = AccountPresetPolicy.listRowsNotKeep()
        assertTrue("以下好友名单行在静态表里不是「不覆盖」：$bad", bad.isEmpty())
        assertEquals(
            "静态表应当登记全部 26 个好友名单字段",
            26,
            AccountPresetPolicy.FRIEND_LIST_ROWS.size,
        )
    }

    @Test
    fun `好友名单在两张策略表之间登记一致`() {
        // R11：好友名单同时登记在 AccountFriendListPolicy.FRIEND_LISTS（功能视角）
        // 与 AccountPresetPolicy.FIELDS（档位视角），两处必须对得上。

        val missing = AccountFriendListPolicy.FRIEND_LISTS
            .filter { ref -> AccountPresetPolicy.FIELDS.none { it.id == ref.id } }
            .map { it.id }
        assertTrue("以下好友名单在预设表 FIELDS 里没有对应行：$missing", missing.isEmpty())

        val notCross = AccountFriendListPolicy.FRIEND_LISTS
            .filter { ref ->
                AccountPresetPolicy.FIELDS
                    .firstOrNull { it.id == ref.id }?.scope != FieldScope.CROSS_ACCOUNT
            }
            .map { it.id }
        assertTrue(
            "以下好友名单未登记为跨账号，小号隔离保证会失效：$notCross",
            notCross.isEmpty(),
        )

        assertEquals(
            "两张表的名单数量应当一致",
            AccountFriendListPolicy.FRIEND_LISTS.size,
            AccountPresetPolicy.FRIEND_LIST_ROWS.size,
        )
    }

    @Test
    fun `小号不偷大号由开关兜底而不是靠写名单`() {
        // 名单不再由档位自动写，所以「小号不偷大号」必须由开关层保证
        assertEquals(
            "小号档必须关闭「收集能量」，这是不收任何人能量的兜底",
            false,
            AccountPresetPolicy.FIELDS
                .first { it.modelCode == "AntForest" && it.fieldCode == "collectEnergy" }
                .altValue,
        )
    }

    @Test
    fun `动作选择器的门控开关都存在且在小号档位被关闭`() {
        val broken = AccountPresetPolicy.brokenGuards()
        assertTrue("以下门控字段不存在：$broken", broken.isEmpty())

        val unsafe = AccountPresetPolicy.CROSS_ACCOUNT_FIELDS
            .filter { it.altGuardField != null }
            .filter { row ->
                val guard = AccountPresetPolicy.FIELDS
                    .first { it.modelCode == row.modelCode && it.fieldCode == row.altGuardField }
                !AccountPresetPolicy.isNeutralValue(guard.altValue)
            }
            .map { "${it.modelCode}.${it.fieldCode}" }
        assertTrue("以下动作选择器的门控开关在小号档位没有关闭：$unsafe", unsafe.isEmpty())
    }

    @Test
    fun `预设表没有重复登记`() {
        assertEquals("重复登记：${AccountPresetPolicy.duplicateEntries()}", emptyList<String>(), AccountPresetPolicy.duplicateEntries())
    }

    @Test
    fun `预设表规模锁定，改表必须同步文档口径`() {
        // 这条不是为了"证明"规模，而是为了让**改表这件事有摩擦**：
        // docs/superpowers/{specs,plans} 里写过 FIELDS 的规模口径，
        // 曾经因为删掉两行没回头改文档而失真（302/286/80 → 300/284/79）。
        // 增删预设表行时这条会红，提醒同步文档。
        assertEquals(
            "模块开关数应为 ModelOrder 注册的 16 个模型",
            16,
            AccountPresetPolicy.FIELDS.count { it.fieldCode == "enable" },
        )
        assertEquals(
            "FIELDS 规模变化了 —— 请同步 docs/superpowers/{specs,plans} 里的规模口径",
            300,
            AccountPresetPolicy.FIELDS.size,
        )
        assertEquals(
            "设置项数（除模块开关）变化了 —— 同上",
            284,
            AccountPresetPolicy.FIELDS.count { it.fieldCode != "enable" },
        )
        assertEquals(
            "跨账号行数变化了 —— 同上",
            79,
            AccountPresetPolicy.CROSS_ACCOUNT_FIELDS.size,
        )
    }

    @Test
    fun `预设表登记的所有字段在主源码中都真实存在`() {
        val unknown = AccountPresetPolicy.FIELDS
            .map { it.fieldCode }
            .filterNot { declaredFieldCodes.contains(it) }
            .distinct()
        assertTrue("以下字段在主源码中找不到声明（疑似笔误）：$unknown", unknown.isEmpty())
    }

    @Test
    fun `预设表覆盖的模型与 ModelOrder 注册的模型完全一致`() {
        val registered = Regex("""(\w+)::class\.java""")
            .findAll(modelOrderSource)
            .map { it.groupValues[1] }
            .toSet()
        val covered = AccountPresetPolicy.FIELDS.map { it.modelCode }.toSet()

        assertEquals(
            "预设表未覆盖以下已注册模型：${registered - covered}",
            emptySet<String>(),
            registered - covered,
        )
        assertEquals(
            "预设表登记了未注册的模型：${covered - registered}",
            emptySet<String>(),
            covered - registered,
        )
    }

    @Test
    fun `核心窃取类设置项都被判定为跨账号`() {
        val mustBeCross = listOf(
            "AntForest.collectEnergy",
            "AntForest.batchRobEnergy",
            "AntForest.pkEnergy",
            "AntForest.dontCollectList",
            "AntForest.waterFriendList",
            "AntFarm.hireAnimal",
            "AntFarm.rewardFriend",
            "AntDodo.collectToFriend",
            "AntCooperate.cooperateWater",
            "AntSports.battleForFriends",
            "AntSports.trainFriend",
            "AntStall.stallAutoTicket",
            "AntStall.stallThrowManure",
            "AntOcean.cleanOcean",
            "GreenFinance.greenFinancePointFriend",
            "AntOrchard.assistFriendList",
        )
        val wrong = mustBeCross.filterNot { key ->
            val (model, field) = key.split(".")
            val row = AccountPresetPolicy.FIELDS.firstOrNull { it.modelCode == model && it.fieldCode == field }
            row != null && row.scope == FieldScope.CROSS_ACCOUNT
        }
        assertTrue("以下设置项应被判定为跨账号：$wrong", wrong.isEmpty())
    }

    @Test
    fun `小号档关掉收取类开关，但名单交给勾选驱动`() {
        fun altValueOf(model: String, field: String): Any? =
            AccountPresetPolicy.FIELDS.first { it.modelCode == model && it.fieldCode == field }.altValue

        // 开关层：必须真的关掉（这是"不偷任何人"的兜底）
        assertEquals(false as Any?, altValueOf("AntForest", "collectEnergy"))
        assertEquals(false as Any?, altValueOf("AntForest", "batchRobEnergy"))
        assertEquals(false as Any?, altValueOf("AntForest", "pkEnergy"))

        // 名单层：不再由档位写 —— 由二级页勾选决定；没勾就保持账号现状
        assertEquals(AccountPresetPolicy.KEEP, altValueOf("AntForest", "waterFriendList"))
        assertEquals(AccountPresetPolicy.KEEP, altValueOf("AntForest", "helpFriendCollectList"))
        assertEquals(AccountPresetPolicy.KEEP, altValueOf("AntForest", "dontCollectList"))

        // "0 = 关闭"型的数值参数仍随档位关闭
        assertEquals(0 as Any?, altValueOf("AntForest", "returnWater10"))
        // 而"0 会破坏功能"的纯参数不动
        assertEquals(AccountPresetPolicy.KEEP, altValueOf("AntForest", "waterFriendCount"))
    }

    @Test
    fun `大号档位确实会写入一组配置`() {
        assertTrue(AccountPresetPolicy.overrideCount(PresetTier.MAIN) > 50)
        assertNotNull(
            AccountPresetPolicy.FIELDS.firstOrNull {
                it.modelCode == "AntForest" && it.fieldCode == "collectEnergy"
            }?.mainValue
        )
    }

    @Test
    fun `档位枚举可以按代码互转`() {
        assertEquals(PresetTier.MAIN, PresetTier.fromCode("main"))
        assertEquals(PresetTier.ALT, PresetTier.fromCode("alt"))
        assertEquals(null, PresetTier.fromCode("unknown"))
    }

    @Test
    fun `中性值判定只接受关闭或空集合`() {
        assertTrue(AccountPresetPolicy.isNeutralValue(false))
        assertTrue(AccountPresetPolicy.isNeutralValue(0))
        assertTrue(AccountPresetPolicy.isNeutralValue(emptySet<String>()))
        assertTrue(AccountPresetPolicy.isNeutralValue(emptyMap<String, Int>()))
        assertTrue(AccountPresetPolicy.isNeutralValue(""))

        assertFalse(AccountPresetPolicy.isNeutralValue(true))
        assertFalse(AccountPresetPolicy.isNeutralValue(1))
        assertFalse(AccountPresetPolicy.isNeutralValue(setOf("uid")))
        assertFalse(AccountPresetPolicy.isNeutralValue(AccountPresetPolicy.KEEP))
    }
}
