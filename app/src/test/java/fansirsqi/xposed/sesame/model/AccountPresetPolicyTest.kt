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
    fun `每一条跨账号设置项都显式声明了小号取值`() {
        val missing = AccountPresetPolicy.CROSS_ACCOUNT_FIELDS
            .filter { it.altValue === AccountPresetPolicy.KEEP }
            .map { "${it.modelCode}.${it.fieldCode}" }
        assertTrue("以下跨账号设置项的小号取值是「不覆盖」，小号会保留账号原有配置：$missing", missing.isEmpty())
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
    fun `预设表登记的所有字段在主源码中都真实存在`() {
        val unknown = AccountPresetPolicy.FIELDS
            .map { it.fieldCode }
            .filterNot { declaredFieldCodes.contains(it) }
            .distinct()
        assertTrue("以下字段在主源码中找不到声明（疑似笔误）：$unknown", unknown.isEmpty())
    }

    @Test
    fun `预设表登记的模型代码都在 ModelOrder 中注册`() {
        val unknown = AccountPresetPolicy.FIELDS
            .map { it.modelCode }
            .distinct()
            .filterNot { modelOrderSource.contains("$it::class.java") }
        assertTrue("以下模型代码未在 ModelOrder 中注册：$unknown", unknown.isEmpty())
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
    fun `小号档位关闭好友能量收取且清空好友名单`() {
        fun altValueOf(model: String, field: String): Any? =
            AccountPresetPolicy.FIELDS.first { it.modelCode == model && it.fieldCode == field }.altValue

        assertEquals(false as Any?, altValueOf("AntForest", "collectEnergy"))
        assertEquals(false as Any?, altValueOf("AntForest", "batchRobEnergy"))
        assertEquals(false as Any?, altValueOf("AntForest", "pkEnergy"))
        assertEquals(emptyMap<String, Int>() as Any?, altValueOf("AntForest", "waterFriendList"))
        assertEquals(emptySet<String>() as Any?, altValueOf("AntForest", "helpFriendCollectList"))
        assertEquals(0 as Any?, altValueOf("AntForest", "returnWater10"))
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
        assertTrue(AccountPresetPolicy.isNeutralValue(AutoProtectAccounts))

        assertFalse(AccountPresetPolicy.isNeutralValue(true))
        assertFalse(AccountPresetPolicy.isNeutralValue(1))
        assertFalse(AccountPresetPolicy.isNeutralValue(setOf("uid")))
        assertFalse(AccountPresetPolicy.isNeutralValue(AccountPresetPolicy.KEEP))
    }
}
