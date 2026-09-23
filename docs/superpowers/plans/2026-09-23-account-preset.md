# 账号档位（大号 / 小号）一键切换 —— 实施计划与记录

> 设计稿：[`../specs/2026-09-23-account-preset-design.md`](../specs/2026-09-23-account-preset-design.md)
> 状态：已实施（2026-09-23 ~ 09-24）。K50 至尊版端到端验证通过并已还原现场；小米 17 待验证。
>
> 本文档是这一个功能的**唯一**过程记录 —— 后续需求变化与轮次在本文档追加章节，**不新开文件**。
> 轮次二一度另开过一份文档，已并入本文件（见「六、后续轮次」）。

## 一、任务拆解

| # | 轮次 | 任务 | 产出 |
| --- | --- | --- | --- |
| 1 | 一 | 梳理全量设置项并完成资源风险分级 | 300 行预设表（16 个模块总开关 + 284 个设置项），其中 79 行为跨账号 |
| 2 | 一 | 实现纯策略 | `model/AccountPresetPolicy.kt` |
| 3 | 一 | 实现应用器与落盘 | `model/AccountPreset.kt` |
| 4 | 一 | 接入设置页入口 | `ui/AccountPresetMenu.kt` + `SettingsContent.kt` |
| 5 | 一 | 补单测并跑通 CI 三步 | `AccountPresetPolicyTest.kt` |
| 6 | 二 | 核实"哪些设置项是好友名单"及其门控 | 26 项分类表（含门控与计数语义） |
| 7 | 二 | 实现分类与填充策略 | `model/AccountFriendListPolicy.kt` |
| 8 | 二 | 扩展应用器：白名单制 + 大号侧排除清理 | `model/AccountPreset.apply()` |
| 9 | 二 | 改造交互：四步流程 + 关系名单选择 | `ui/AccountPresetMenu.kt` |
| 10 | 三 | 改为「逐项功能勾选」两级对话框 | `AccountFriendListPolicy` + `AccountPresetMenu` |
| 11 | 四 | 修 R1 / R2 两个逻辑冲突 | `AccountPresetPolicy` + `AccountPreset` |
| 12 | 五 | 方案 A：删掉两个成员名单设置项 | `BaseModel` + 策略表 + 应用器 + UI |
| 13 | 六 | 补预设表规模与两表一致性断言 | `AccountPresetPolicyTest` |

## 二、设置项梳理方法（轮次一）

不靠人工记忆，用脚本从源码里把设置项**抽干**，并做**双向覆盖比对**，避免遗漏：

1. 正则扫 `ModelField("<code>", "<label>", <default>` 覆盖 `model/` 与 `task/` 下的全部 `.kt` / `.java`
   → 得到 297 处声明（含少量噪声，例如同一 code 在枚举 `nickNames` 附近再次出现）。
2. 再做一次「括号配对」扫描，只取每个模型 `getFields()` 函数体内部的声明
   → 得到 246 个**真正注册**到 UI 的字段。
3. 对「先赋给成员变量、`getFields()` 只做 `addField(var)`」的写法
   （`BaseModel`、`AnswerAI`、`AntCooperate`、`AntFishPond`）回落到声明表补齐。
4. 用脚本逐模型比对「注册字段」与「策略表覆盖字段」—— **16 个已注册模型零缺口**。
   首轮比对抓到一处遗漏（`AntForest.robExpandCardTime`，1.1 倍能量卡使用时间），已补。

最终 `AccountPresetPolicy.FIELDS` 共 **300 行** = 16 个模块总开关 + 284 个设置项，其中 **79 行**为跨账号。

> 方案 A 删掉 `mainAccountList` / `subAccountList` 两行后，由 302 行 / 286 项 / 80 行跨账号
> 变为 300 行 / 284 项 / 79 行跨账号（见本文档「六、后续轮次」）。
> 轮次六补了规模断言，改表会直接让单测变红，提醒同步文档口径。

> 唯一未纳入的是 `ManualTaskModel`（手动任务页的 6 个一次性触发开关）：它**不在 `ModelOrder` 中注册**，
> 不进入 `ModelConfigMap`，因此不属于档位配置的范畴。单测 `预设表覆盖的模型与 ModelOrder 注册的模型完全一致`
> 会在「新增模型忘了登记」或「表里写了不存在的模型」时双向失败。

风险分级判据（写进代码注释的规则）：

- `SELF`：只读写本账号数据 —— 各模块自己的签到、任务、领取、游戏、调优参数、日志展示。
- `CROSS_ACCOUNT`：会以其他支付宝账号为操作对象 —— 含「好友列表 / 送 / 帮 / 抢 / 邀请 / 通知 / 打赏 /
  雇佣 / 训练 / 浇水 / 清理 / 助力」这类字段，以及**用途是保护其他账号的名单字段**本身。

## 三、好友名单字段的核实过程（轮次二）

"哪些是好友名单"不能靠关键词猜。用供应商反查：主源码里所有以 `AlipayUser::getList`
为候选集的 `SelectModelField`，共 **26 项**；同时确认了以下**不是**好友列表（容易误判）：

| 字段 | 真实候选集 | 误判风险 |
| --- | --- | --- |
| `AntOcean.protectOceanList` | `AlipayBeach` 海域 | 看着像"好友海域" |
| `AntCooperate.cooperateWaterList` / `cooperateWaterTotalLimitList` | **合种 ID** | 名字像好友列表 |
| `AntForest.vitalityExchangeList` | `VitalityStore` 商品 | — |
| `AntMember.memberPointExchangeBenefitList` / `sesameGrainExchangeList` | `MemberBenefit` | — |
| `AntFarm.paradiseCoinExchangeBenefitList` | `ParadiseCoinBenefit` | — |
| `EcoProtection.ancientTreeCityCodeList` | `AreaCode` 区划 | — |
| `Reserve.reserveList` | `ReserveEntity` 保护地 | — |
| `AntFarm.familyOptions` | `farmFamilyOption()` 选项 | — |
| `AntDodo.usepropGroup` | `listPropGroupOptions()` 道具类型 | — |

**门控核实（两处与直觉不符）**：

- `AntFarm.visitFriendList`（送麦子）**不是"名单非空即生效"** —— 它被包在
  `if (visitAnimal!!.value)`（「到访小鸡送礼」）里面；
- `AntForest.giveEnergyRainList`（赠送能量雨）挂在 `energyRain`「能量雨」开关下。

其余门控：`giveProp`（赠送道具）、`getFeed`（一起拿饲料）、`collectToFriend`（帮抽卡）、
`sendBackAnimal`（遣返）。浇水 / 帮喂小鸡 / 送卡片 / 助力好友 / 不收能量**无门控**。

**计数语义**：「选择 + 计数」型名单（浇水 / 帮喂小鸡 / 送麦子）的值是 `Map<userId, Int>`，
`Int` 是**每日执行次数**，代码里有 `if (count <= 0) continue` 与 `min(count, 3)` 这类保护 ——
所以一键填充必须给正数，默认取 **1**。

## 四、实施记录

### 4.1 改动清单（最终形态）

| 文件 | 类型 | 改动 |
| --- | --- | --- |
| `model/AccountPresetPolicy.kt` | 新增 | 档位枚举 `PresetTier`、作用域枚举 `FieldScope`、行模型 `PresetField`、全量设置项表 `FIELDS`（300 行）、中性值判定 `isNeutralValue`、隔离自检 `altViolations` / `brokenGuards` / `duplicateEntries` / `listRowsNotKeep`、纯参数例外 `PLAIN_PARAM_ROWS`。**无任何 Android 依赖**（遵循硬规则 5：可测判定逻辑抽成 `*Policy`）。 |
| `model/AccountFriendListPolicy.kt` | 新增 | 26 项好友名单分类表（`SERVICE` / `EXPLOIT` / `EXCLUSION` / `PROTECT`）+ 门控开关 `gateSwitches` + 计数默认值 + 推荐规则（`recommendedForMainList` / `recommendedForSubList` / `isSelectable`）+ 两条隔离自检。 |
| `model/AccountPreset.kt` | 新增 | 应用器：`Config.load` → 按档位写静态行 → 按勾选聚合写名单并连带开门控 → 大号档清理排除名单 → `Config.save(uid, force)` → 广播 `com.eg.android.AlipayGphone.sesame.restart` → 写 `account_preset.json`；切换非当前账号时对内存配置做 JSON 快照并在 `finally` 恢复。 |
| `ui/AccountPresetMenu.kt` | 新增 | 交互：选账号 → 选档位 → 一级好友列表 → 二级该账号功能清单 → 确认页（可取消保护名单）→ 应用 → Toast 结果。 |
| `ui/screen/content/SettingsContent.kt` | 修改 | 「扩展&外观」区新增「账号档位（大号 / 小号）」入口（+11 行）。 |
| `model/BaseModel.kt` | 修改（**净变化为 0**） | 轮次二新增 `mainAccountList` / `subAccountList`，轮次五按方案 A 删除 → 相对 `main` 无净变化。 |
| `test/.../AccountPresetPolicyTest.kt` | 新增 | 15 项，见 §4.7。 |
| `test/.../AccountFriendListPolicyTest.kt` | 新增 | 16 项，见 §4.7。 |
| `test/.../AccountPresetGuardTest.kt` | 新增 | 7 项：空注册表守卫、`aggregateSelection(selection)` 反转、大号档 `val remain = current - friends`、记录走 `selection.<tier>`、以及「不再有独立成员名单设置项」的回归守卫。 |
| `docs/superpowers/specs/2026-09-23-account-preset-design.md` | 新增 | 设计稿（轮次二起「26 个好友名单字段」等章节并入同一份）。 |
| `docs/user-guide.md` | 修改 | 新增第 8 章「大号 / 小号档位（一键切换）」。 |
| `CHANGELOG.md` / `TODO.md` | 修改 | 一条用户可见记录；任务勾选状态。 |

未改动任何业务任务（`task/**`）与 `hook/**`、`rpc/**`（三者改动文件数均为 0），
也未改 `Model` / `ModelField` 结构 —— 本功能只做配置写入。

### 4.2 关键设计决策与理由

| 决策 | 理由 |
| --- | --- |
| 小号档位直接关闭 `collectEnergy`（收集能量），而不是只关「一键收取」 | 该开关在当前实现里**同时门控收自己与收好友**（`AntForest.kt` 私有 `collectEnergy(...)` 开头即判断），无法只收自己的。若只关 `batchRobEnergy`，小号仍会逐球偷走大号的能量。 |
| 除关闭开关外，还自动写入「双向保护名单」 | 用的是模块**既有**字段：`dontCollectList`（不收能量名单）、`alternativeAccountList`（周一保护）、`helpFriendCollectList`（复活能量名单）。大小号互为名单，形成第二道保险。 |
| 名单类字段「大号不覆盖、小号清空」→ **后来反转为两档都不由档位驱动** | 轮次四发现"小号清空"与「没勾就不动」矛盾，且会让取消勾选失效；最终名单**只由勾选驱动**。见 §4.3 与「六、后续轮次」。 |
| 大号档位保持「纯干扰项」关闭（丢肥料、请走小摊、通知赶鸡、打赏好友、邀请摆摊/开通） | 判据是「无实际收益、只影响他人」。全开会让大号反手损害小号，与「互不影响」冲突。 |
| 调度 / 日志 / 调优参数一律不覆盖 | 与账号间资源无关，一键切换不该打乱用户手感。 |
| `CROSS_ACCOUNT` 行的 `altValue` 禁止为「不覆盖」（开关类） | 不覆盖 = 小号保留账号原有配置，隔离保证直接落空。这是最容易踩的坑，已由单测锁定。 |
| 引入 `altGuardField`（动作选择器 → 配套开关） | 部分设置项是「动作选择器」，本身没有「不执行」选项，且「不执行」下标不是 0（`DONT_HIRE=1`、`DONT_CLEAN=1`、`DONT_TICKET=1` …）。这类行的安全性由配套开关保证，自检会连带校验该开关在小号档位确实关闭。 |
| 不新增配置读写机制 | 完全复用设置页既有链路（`Model.getModelConfigMap().getModelField(code).setObjectValue(...)` + `Config.save`），不引入第二套状态。 |
| 档位记录只用于展示，不参与运行时判定 | 避免「配置文件」与「档位记录」两套状态不一致；运行时一律读配置字段本身。 |
| 名单**填了还要连带打开门控开关** | 否则名单填了不生效（见「三」的门控表）。这条由 `gateSwitches` 建模，单测覆盖。 |
| 文案只讲「启用哪些功能」，不讲参数 | 这一层不配参数（浇水克数、次数仍在各模块账号配置里），文案若出现「（每日 1 次）」会让人以为在这里配。见「六、7.1」。 |

### 4.3 轮次二与轮次一的关键差异（含对轮次一错误的修正）

| 项 | 轮次一 | 轮次二 |
| --- | --- | --- |
| 排除名单的数据来源 | 两档都写"本机其他已载入账号" | 小号档写**大号名单**；**大号档不写，且主动移除小号** |
| 小号档定位 | 关闭全部跨账号功能 | **白名单制**：服务类只对大号开放（开关打开 + 名单收窄），索取/干扰类保持关闭 |
| 写入的名单数量 | 3 份（其中 2 份无效或非该机制） | 只写真正兑现隔离的 `dontCollectList` + 12 个功能名单（按需） |
| 入口文案 | 大号档 / 小号档 | 大号推荐配置 / 小号推荐配置 |

**轮次一有两处错误，轮次二按需求修正：**

1. **大号档把"小号"写进「不收能量」是错的。** 用户明确要求：大号需要收取小号的能量。
   现在大号档不但不写，还会把选中的账号从排除名单里**移除**。
2. **写了 3 份名单但只有 1 份真正起作用。** 核实后确认：`helpFriendCollectList` 在
   「复活能量」为关闭时零效果（且 `monday` 变量恒为 `true`，是死代码）；
   `alternativeAccountList` 不是"不收能量"机制（它是复活能量保护名单）。现在这两份都不碰。

### 4.4 实施中的修正（轮次一）

**第一次跑单测时隔离断言直接失败**，报出 9 条「跨账号行没有中性小号取值」：
`AntFarm.hireAnimalType`、`AntFarm.notifyFriendType`、`AntOcean.cleanOceanType`、
`AntStall.stallOpenType`、`AntStall.stallTicketType`、`AntStall.stallThrowManureType`、
`AntStall.stallInviteShopType`、`AntDodo.collectToFriendType`、`AntSports.battleForFriendType`。

原因是这些枚举的「不执行」常量值是 `1` 而不是 `0`（例如 `StallTicketType.DONT_TICKET = 1`），
而最初的中性值判定只接受 `0`。这正是把约束写成可执行断言的价值 —— 靠人工 review 极易漏掉。

修正方式：不放松判定，而是**显式建模**：给这类行加 `altGuardField` 指向配套开关（如
`stallTicketType → stallAutoTicket`），自检改为「选择器取值非中性时，其门控开关必须在小号档位被关闭」，
并顺带把大号档位中「开关本来就不开」的选择器值改成对应的「不执行」项
（`notifyFriendType`、`stallThrowManureType`、`stallInviteShopType` 的大号取值由 `0` 改为 `1`），
使大号档位内部自洽。

**编译期两处非致命但必须修的写法**：

- `JsonUtil.parseObject(json, Map::class.java)` 触发 `Cannot infer type for type parameter 'T'`
  → 改用 `JsonUtil.toNode(json)` + `JsonNode.path(...)` 逐字段读取，顺带消掉了强制类型转换。
- `readerForUpdating(...).readValue(snapshot)` 同样无法推断类型 → 显式 `readValue<Any>(snapshot)`。

### 4.5 实施中踩到的坑（轮次二）

**测试里搜源码搜错了文件。** 新测试要反查"主源码里哪些字段的候选集是 `AlipayUser`"，
最初扫描整个 `src/main` —— 但 `model/AccountFriendListPolicy.kt` **本身**就含有这 26 个 code 字面量，
而 walk 顺序里 `model/` 排在 `task/` 之前，于是"在策略文件里找到了 code，附近当然没有
`AlipayUser`"，26 项全部误判失败。修法：扫描范围限定到 `task/`（业务模型所在目录），并在注释里写明原因。

**确认页文案在白名单制下失真。** 原写「关闭全部 79 项以好友为对象的设置」，
但白名单制下小号档会**打开** 4 个开关并填充 12 个名单，这句话不再准确。
改为「索取/干扰类保持关闭，名单一律清空」+ 按是否指定大号分别说明。

### 4.6 验证结果

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 主源码编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（仅有仓库既存的 deprecation 警告） |
| 新增单测 | `./gradlew.bat :app:testDebugUnitTest --tests "...AccountPreset*Test"` | ✅ 全通过 |
| 全量单测 | `./gradlew.bat :app:testDebugUnitTest` | ✅ **216 项 / 0 失败** |
| 打包 | `./gradlew.bat :app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| Web 合同测试 | `node --test app/src/test/js/*.test.js` | ✅ 7 tests / 7 pass / 0 fail |

### 4.7 单测清单

`AccountPresetPolicyTest`（15 项）：

| 用例 | 断言 |
| --- | --- |
| 小号档位不含任何可能动到其他账号资源的设置项 | `altViolations()` 为空 |
| 开关类跨账号项必须显式声明小号取值，名单类必须「不覆盖」 | 分三类逐类断言 |
| 好友名单类行在静态表里一律不覆盖 | `listRowsNotKeep()` 为空 + 26 项 |
| **好友名单在两张策略表之间登记一致**（轮次六补） | `FRIEND_LISTS` 每项都在 `FIELDS` 中且 `scope == CROSS_ACCOUNT` |
| 小号不偷大号由开关兜底而不是靠写名单 | `AntForest.collectEnergy` 的 alt 为 `false` |
| 动作选择器的门控开关都存在且在小号档位被关闭 | `brokenGuards()` 为空 + 门控开关本身中性 |
| 预设表没有重复登记 | `duplicateEntries()` 为空 |
| **预设表规模锁定，改表必须同步文档口径**（轮次六补） | 16 模块开关 / 300 行 / 284 设置项 / 79 跨账号 |
| 预设表登记的所有字段在主源码中都真实存在 | 正则扫描主源码的 `ModelField("code"` 声明，挡住笔误 |
| 预设表覆盖的模型与 `ModelOrder` 注册的模型完全一致 | 双向集合差断言 |
| 核心窃取类设置项都被判定为跨账号 | 16 个关键 code 逐一断言 |
| 小号档关掉收取类开关，但名单交给勾选驱动 | 取值断言（含纯参数例外） |
| 大号档位确实会写入一组配置 | `overrideCount(MAIN) > 50` |
| 档位枚举可以按代码互转 | `fromCode` |
| 中性值判定只接受关闭或空集合 | 正反用例 |

`AccountFriendListPolicyTest`（16 项）：

| 用例 | 断言 |
| --- | --- |
| 分类表共 26 项且无重复 | 计数 + 无重复 |
| 每一项的字段 code 在主源码中都存在 | 挡笔误 |
| 每一项的候选集确实取自 `AlipayUser` | 挡"把商品/海域当好友名单" |
| 主源码里的好友名单字段都已登记 | 反查完整性，新增设置项漏登记会失败 |
| 小号档要填的名单其门控开关都会在档位里被打开 | 挡"填了不生效" |
| 小号档不得把非大号账号写进服务或索取名单 | 约束 1 正反用例 |
| 大号档不得让小号残留在排除名单里 | 约束 2 正反用例 |
| 不收能量名单是小号档唯一且必须的排除落点 | 大号档取值必须是「不覆盖」 |
| 复活能量体系在小号档不参与名单填充 | 独立体系隔离 |
| 索取干扰类名单在小号档一律清空 | 且不得出现在填充列表 |
| 选择计数型名单都给了正的默认次数 | 防"次数 0 导致任务跳过" |
| 推荐集与方向可用性 | 小号方向 12 项、大号方向 1 项、排除类在大号方向不可选 |

> 沿用仓库既有惯例（多份单测是「读源码文本做断言」），因为 `Model` 体系依赖 Android 运行时，
> 在 JVM 单测里直接实例化模型不可行。

## 五、K50 实机验证（轮次二，可回滚，已还原）

测试机：Redmi K50 Ultra（`4d3a3d64`，Android 15，USB）。**小米 17 未做任何改动**（用户自用）。

流程：备份配置目录 → 走完流程 → 核对落盘 → **还原备份并重启支付宝**。

原始 `config_v2.json` md5 `851616af8d13f25c71dfa9ee0985529e`，验证后已精确还原（md5 一致、
`account_preset.json` 已移除）。

### 5.1 操作链路

```
设置 → 账号档位（大号/小号）→ 梓锐 → 小号推荐配置
  → 关系名单对话框「选择大号名单（共 6 位好友）」→ 勾选「提醒-*梓锐」（= 大号）
  → 关闭 → 确认页 → 确认应用
```

### 5.2 模块日志

```
[AccountPreset]: 模型注册表为空，按设置页惯例先执行 Model.initAllModel()
[AccountPreset]: 模型注册表就绪：16 个模型
[AccountPreset]: 已把【小号】档位应用到 梓锐(2088922617455078)；覆盖 232 项设置；套用好友名单 22 处
```

> 第一行再次印证：主界面进程里模型注册表初始为空，`ensureModelRegistry()` 是必需的。

### 5.3 落盘核对（`2088412711917481` = 大号阿锐）

| 检查项 | 结果 |
| --- | --- |
| 「不收能量 \| 配置列表」 | `[]` → `['2088412711917481']` ✅ **小号不会偷大号** |
| 服务类名单 11 处 | 全部指向大号 ✅ |
| ↳ 浇水 / 帮喂小鸡 / 送麦子（计数型） | `{'2088412711917481': 1}` ✅ 次数给的是正数 |
| ↳ 赠送能量雨 / 赠送道具 / 一起拿饲料 / 帮抽卡 / 送卡片 / 助力×2 / 遣返 | `['2088412711917481']` ✅ |
| 门控开关 | `giveProp` `False→True`、`getFeed` `False→True`、`visitAnimal` `False→True`、`collectToFriend` `False→True` ✅ |
| 索取/干扰类名单 10 处 | 全部清空（贴罚单 / 丢肥料 / 抢好友 / 雇佣 / 清理海洋 …）✅ |
| 复活能量体系 | `alternativeAccountList` / `helpFriendCollectList` 未被填充 ✅ |

### 5.4 CI 三步

`assembleDebug` ✅ / `testDebugUnitTest` ✅ / `node --test` ✅ 7/7

## 六、后续轮次（追加章节，不新开文件）

### 6.1 轮次三：修正「服务 TA」的文案歧义（2026-09-24）

用户指出：这一层**只决定是否开启对应功能，不涉及具体配置**，具体配置仍在账号配置里完成；
原文案会让人误解。逐条修正：

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| 设置项显示名 | 大号名单 \| 供各功能**一键套用** | 大号名单 \| 供各功能**批量启用** |
| 小号档摘要 | 关闭全部跨账号功能，只保留纯本账号操作，不会动大号任何资源 | 跨账号功能只对「大号名单」开放，不会动其他人 |
| 一级标题 | 大号名单：共 6 位好友 | **批量启用功能 · 大号名单（共 6 位好友）** |
| 一级说明 | （无） | 新增两行，讲清"只批量启用、参数仍在各模块账号配置里" |
| 二级标题 | 提醒-\*梓锐：选择要启用的功能 | **批量启用功能 · 提醒-\*梓锐** |
| 二级说明 | （无） | 新增三段：勾选做什么 / 不勾会怎样 / 计数型默认次数可在账号配置里改 |
| 功能条目 | 给 TA 浇水**（每日 1 次）** | 给 TA 浇水 ← **删掉参数描述**（这是误解的主要来源） |
| 分组标题 | 服务 TA（消耗自己、利于对方） | 服务 TA —— 消耗自己、利于对方 |
| 确认页 | 大号名单：1 个账号，共启用 12 项功能 / · 给 TA 浇水（1 个账号） | 将对「大号名单」的 1 个账号**批量启用 12 项功能**（只写入下列好友名单并打开对应开关，**不改具体参数**）/ · **浇水 \| 好友列表**（1 个账号） |
| 日志 / Toast | 套用好友名单 12 处 | 批量启用 12 项功能 |

两处顺带发现的问题：
- **`TextView` 不渲染 Markdown** —— 说明文字里写的 `**加粗**` 会原样显示，已去掉。
- **一级说明改放在容器内的首行**（而不是 `setMessage`），避免与固定高度的列表抢空间。

文案类改动不改任何写入行为；K50 上只做只读回显验证（截图确认渲染），配置 md5 未变。

### 6.2 轮次四：修两个逻辑冲突（2026-09-24）

用户复核时指出「很多重复的内容」。逐条核实后确认有 12 处重叠（R1~R12），
本轮先修其中**两条不是冗余、而是行为错误**的：

**R1：`dontCollectList` 双路径 —— 取消勾选被静默忽略。**
`AccountPresetPolicy` 里 `dontCollectList` 的 alt 值是 `WriteMainAccountList` 标记，
apply **第 2 步**会无条件把它写成"大号名单"，而按勾选写入在**第 4 步**。
用户取消勾选「不收取 TA 的能量」后，第 2 步写的值仍然留着 → **取消无效**。
（它本来就是功能清单里的一个普通项，不需要这条特殊路径。）
**修法**：删掉 `WriteMainAccountList` 与 apply 第 2 步的对应分支，`dontCollectList` 只由勾选驱动。

**R2：26 个名单字段被档位无条件改写 —— 与"没勾就不动"矛盾。**
脚本统计：26 项名单字段的 alt = 22×`EMPTY_SET` + 3×`EMPTY_MAP` + 1×`WriteMainAccountList`，
main 全是 `KEEP`。意味着小号档 apply 时**26 个名单字段全部会被写一遍**（25 个清空 + 1 个写名单），
与用户勾选无关；`aggregateSelection` 只是在其后覆盖。
而文案写的是「未勾选的服务类功能保持关闭，**不动其名单**」→ 实际是**清空**。
**修法**：26 个名单字段的 alt 全部改成 `KEEP`，名单**只由勾选驱动**。

**连带发现：8 个参数类字段也被档位改写。** 按字段类型反查发现静态表还在改 8 个数值参数：

| 字段 | 原 alt | 处置 | 理由 |
| --- | --- | --- | --- |
| `AntForest.waterFriendCount` 浇水克数 | 0 | **改 KEEP** | 合法值只有 10/18/33/66，**0 会让浇水失效** |
| `AntForest.returnWater10/18/33` 返水门槛 | 0 | 保留 | 注释明示"关闭:0" → 属关行为 |
| `AntFarm.teamCooperateWaterNum` | 0 | 保留 | 明示"0为关闭" |
| `AntFarm.orchardSpreadManureCount/Yeb` | 0 | 保留 | 0 = 不施肥 |
| `AntCooperate.loveCooperateWaterNum` | 0 | 保留 | 真爱合种开关本身已关闭，参数值无影响 |

`waterFriendCount` 必须改还有一个直接原因：R2 之后名单不再清空，
若用户本来有浇水名单而克数被设成 0，浇水会**带着非法克数去请求**；
这也和文案「浇水克数仍在账号配置里改」自相矛盾。

**规则因此分成三类（小号档的要求是相反的）**：

| 类别 | 小号档要求 | 自检 |
| --- | --- | --- |
| 开关类（布尔 / 动作选择器） | 中性值，**不允许「不覆盖」** | `altViolations()` |
| 名单类（26 个） | **必须「不覆盖」** | `listRowsNotKeep()` |
| 纯参数类（1 个） | **必须「不覆盖」** | `PLAIN_PARAM_ROWS` + 单测 |

另有两条测试因编码了旧行为而失败、已按新语义改写：
「每一条跨账号设置项都显式声明了小号取值」→ 拆成"开关类必须显式、名单类必须不覆盖"；
「小号档位关闭好友能量收取且清空好友名单」→ 改为"关掉收取类开关，但名单交给勾选驱动"。

**K50 验证：用哨兵值证明「不动」是真的。** 往配置里注入**伪造 uid 哨兵**再走一遍流程
（这样"没动"与"被清空"才可区分）：

| 检查项 | 结果 |
| --- | --- |
| 取消勾选「不收取 TA 的能量」后应用 | `dontCollectList` 仍是哨兵 `['7777777777777777']` ✅ **取消勾选真的生效** |
| 未勾选的「贴罚单」名单 | 仍是哨兵 `['9999999999999999']` ✅ 未被清空 |
| 未勾选的「雇佣小鸡」名单 | 仍是哨兵 `['8888888888888888']` ✅ 未被清空 |
| 浇水克数（纯参数） | `33 → 33` ✅ 未被改成 0 |
| 已勾选的 11 项 | 全部指向大号 ✅ |
| 对应开关 | `collectToFriend`/`visitAnimal`/`giveProp`/`getFeed` 打开；`stallAutoTicket`/`hireAnimal` 保持关闭 ✅ |

日志：`覆盖 195 项设置；批量启用 11 项功能` —— 覆盖数从 223 降到 195，
正好少了 26 个名单行 + 2 个参数的无效写入。验证后已**还原现场**（md5 一致）。

> 方法论：「没动」和「被清空」在空字段上看起来一样，必须注入哨兵才可区分。

### 6.3 轮次五：方案 A —— 删掉两个成员名单设置项（2026-09-24）

用户选定方案 A：把「大号名单 / 小号名单」两个设置项**全部删除**。

**删的理由（三条硬事实）**：

| # | 事实 | 依据 |
| --- | --- | --- |
| 1 | **没有任何运行时消费者** | `task/` 与 `hook/` 对 `mainAccountList` / `subAccountList` 零引用；全仓只有声明、常量名与一处读取 |
| 2 | **与档位记录里的勾选完全重复** | `selection.<tier>` 的 **key 就是好友 uid** → 成员集合可直接推导 |
| 3 | **同一份配置里只有一个会被用到** | 档位决定方向，而两个字段同属一份 `config_v2.json` → 另一个恒为死数据 |

**改动清单**：

| 层 | 改动 |
| --- | --- |
| 设置项 | `BaseModel` 删除 `mainAccountList` / `subAccountList` 的声明与注册（字段数 28 → 26） |
| 策略表 | `AccountPresetPolicy.FIELDS` 删掉对应两行（302 → 300） |
| 常量 | `AccountFriendListPolicy` 删除 `MAIN_LIST_MODEL/FIELD`、`SUB_LIST_MODEL/FIELD` |
| 应用器 | `apply()` 参数 **4 → 1**：`selection: Map<好友, Set<功能>>`；好友集合 = `selection.filterValues{非空}.keys` |
| 大号档清理 | 从"按 `subList` 全量移除"改为"移除**本次选中的好友**" |
| 记录 | `PresetRecord` 改为 `selections: Map<tierCode, Map<friendId, Set<featureId>>>`；JSON 从 `featureSelection.{main,sub}` + `protectedAccounts` + `tierLabel` 简化为 `selection.{alt,main}`（R6/R7 一并消失） |
| 辅助方法 | 删除 `readStoredRelationList()`（它就是为了读那两个字段） |
| 界面 | 一级列表**去掉"是否列入名单"勾选框**（名单没有消费者，勾它不产生任何效果）；点整行 → 二级；行尾「已配置 N 项 / 未配置」；标题从「批量启用功能 · 小号名单」改为「批量启用功能 · 小号推荐配置」 |

**实施中抓到的一个回归**：重构后第一次上机，点进二级页发现**默认勾选是空的** ——
因为把"预置推荐项"的两处逻辑（原来的成员初始化 + 行点击时的补齐）都删掉了，
而新的 `checkedIds()` 只 `getOrPut { LinkedHashSet() }`。
修法：把预置逻辑收进 `checkedIds(friend, recommended)` —— **首次打开某个好友时按推荐预置，已配过的沿用上次**。

> 教训：交互重构时，"默认值从哪来"这类隐式行为最容易丢；上线前必须真的走一遍点进点出，不能只看编译通过。

**K50 验证（可回滚，已还原）**：先删掉旧 schema 的 `account_preset.json`（模拟首次使用）再走流程：

| 检查项 | 结果 |
| --- | --- |
| 一级标题 | 「批量启用功能 · 小号推荐配置（共 6 位好友）」—— 不再有"名单"字样 ✅ |
| 一级行 | 无勾选框；6 位好友全部「未配置 ▸」✅ |
| 点进大号 → 二级 | 默认勾选 **12 项** ✅ |
| 确定后一级行 | 变成「**已配置 12 项** ▸」✅ |
| 应用日志 | `覆盖 196 项设置；批量启用 12 项功能` ✅ |
| 配置写入 | 勾选的 12 项全部指向大号 ✅ |
| 未勾选的与参数 | `stallTicketList` / `hireAnimalList` / `waterFriendCount(66)` / `alternativeAccountList` / `notInviteList` **全部未动** ✅ |
| `BaseModel` 字段数 | 26（少了那两项），不含 `mainAccountList` ✅ |
| **新记录结构** | `{tier, appliedAt, appliedCount, selection:{alt:{uid:[12 项]}, main:{}}}` —— 无 `protectedAccounts`、无 `tierLabel`、无 `featureSelection` ✅ |
| **复用** | 重新打开流程，大号那行仍显示「已配置 12 项」→ 勾选从记录恢复，**不再依赖已删掉的设置项** ✅ |

验证后已还原现场（md5 一致、`account_preset.json` 已移除）。CI 三步全绿。

### 6.4 轮次六：补一致性断言（2026-09-24）

复核功能范围时发现两处缺口，各补一条用例（`AccountPresetPolicyTest` 13 → 15 项）：

1. **预设表规模无人看守** —— 设计稿与本文档里写过 `FIELDS` 的规模，
   方案 A 删掉两行后文档没跟着改，却没有任何东西变红（文档写 302/286/80，实际 300/284/79）。
   新增 `预设表规模锁定，改表必须同步文档口径`：断言 16 个模块开关、300 行、284 设置项、79 跨账号。
   **目的不是"证明规模"，而是让改预设表这件事带上摩擦**，逼着同步文档口径。
2. **两张策略表登记一致性（R11）** —— 26 个好友名单同时登记在
   `AccountFriendListPolicy.FRIEND_LISTS`（功能视角）与 `AccountPresetPolicy.FIELDS`（档位视角），
   而 `FRIEND_LIST_ROWS` 是 `FIELDS.filter { isFriendList(...) }`，
   **漏登记不会报错、只会静默少一项**。新增用例断言 `FRIEND_LISTS` 每项都在 `FIELDS` 中、
   且 `scope == CROSS_ACCOUNT`，两表数量相等。

顺带修正了设计稿与本文档里的过期数字（302/286/80 → 300/284/79），并保留一句溯源说明。

## 七、验收清单

- [x] 小号档位通过隔离自检（`altViolations()` 为空）
- [x] 预设表中的字段 code 全部在主源码中存在，模型 code 全部在 `ModelOrder` 中注册
- [x] 26 项好友名单分类完整、无重复，且候选集确为好友列表
- [x] 主源码里的好友名单字段都被登记（反查完整性）
- [x] 小号档只对选中的账号动手；索取/干扰类不写入
- [x] **大号档不写排除名单**，并能移除残留在名单里的选中账号
- [x] 未勾选的功能与纯参数一律不被档位改动（哨兵值验证）
- [x] K50 端到端验证通过（含落盘核对）
- [x] `:app:assembleDebug` / `:app:testDebugUnitTest`（216 项）通过
- [x] `node --test app/src/test/js/*.test.js` 通过
- [x] CI 三步通过；新文件 UTF-8 无 BOM
- [x] 切换后广播生效（配置无需重启支付宝即被重载，K50 日志确认）
- [ ] **实机**：小米 17（大号）切到大号推荐配置，确认收能量、一键收取等能力开启，
      且「豁免项」在大号档确实整组置灰 —— **待用户后续自行进行**
- [ ] 观察一轮实际任务：小号是否真的只对大号浇水 / 送道具，且不再收大号能量

## 八、R 系列进度

用户复核时列出的 12 处重叠（R1~R12）处置情况：

| 编号 | 状态 |
| --- | --- |
| R1 取消勾选被静默忽略 | ✅ 轮次四已修 |
| R2 未勾选的名单被静默清空 | ✅ 轮次四已修 |
| R4 两个名单项与记录重复 | ✅ 轮次五（方案 A） |
| R5 两个名单项在同一配置里互斥 | ✅ 轮次五（方案 A） |
| R6 `protectedAccounts` 冗余 | ✅ 轮次五（其内容就是旧的大号名单） |
| R7 `tierLabel` 可推导 | ✅ 轮次五（顺手去掉） |
| R8 命名与"档位"交叉 | ✅ 轮次五（"名单"概念消失，标题改为「X 推荐配置」） |
| R9 一级页勾选框语义为空 | ✅ 轮次五（勾选框已删） |
| R10 确认页「覆盖 N 项」用的是静态表计数（不准） | ✅ 轮次四（名单类改 `KEEP` 后，计数即实际写入数） |
| R11 两张表重复登记、缺一致性断言 | ✅ 轮次六（已补断言） |
| R12 `isSelectable` 与 `recommendedIds` 在排除类上重合 | ⬜ 待确认（判断为不构成问题） |

> R3 在原记录里没有留下条目描述，无法对应到具体项；R1~R12 之外的编号不复用。

## 九、遗留与后续

1. **小米 17 未验证**。按用户要求本轮只在 K50 测试；大号侧（排除名单移除选中账号）的逻辑
   有单测覆盖，但缺真机确认。
2. **小号不再自动收自己的能量**（隔离的代价）。若要放开，需手动开启「收集能量」并确保大号在
   「不收能量 | 配置列表」内 —— 该组合会削弱隔离保证，因此不作为默认值。
3. **跨设备场景下保护名单为空**。大小号分处两台设备时拿不到对方 userId，
   此时唯一保证是小号档位本身已关闭全部跨账号功能；确认页会明确提示「本机只检测到 1 个账号」。
4. **`RoleUtil` 式的自动识别未做**：档位由用户显式选择，不做「按账号自动判定大小号」的猜测。
5. **不是"平铺"语义了**：逐项勾选后，同一批好友可对不同功能有不同选择；
   但一级列表仍是"好友 → 功能集"两层，做不到"浇水给 A、送道具给 B 各配一个名单"
   （这需要在二级页里再选人，会重新引入复杂度）。
6. **无门控的 4 类名单，取消勾选 ≠ 停用**：浇水 / 帮喂小鸡 / 送卡片 / 助力好友
   没有独立开关（名单非空即生效），"不动"它们意味着**已有名单会继续执行**。
   要停用只能到该功能的账号配置里清空名单。
7. **`AntFarm.visitAnimal` 是送麦子的门控**，名字与"送麦子"不直接对应；已在策略表里注释，
   若上游把门控拆开，需要同步更新。
8. **设置页目前有两套 UI**（Compose 新设置页 / Web 设置页）。本功能的入口只加在 Compose 设置页；
   Web 设置页若要同样入口，属后续独立改动。
9. **`AntForest.alternativeAccountList` 显示名也叫「小号列表」**，但它与"谁是我的小号"毫无关系
   （它是复活能量保护名单）。文案与文档里一律用「TA」指代"当前正在配置的那个账号"，避免混淆。
