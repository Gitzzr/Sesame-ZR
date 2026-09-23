# 账号档位（大号 / 小号）一键切换 —— 实施计划与记录

> 设计稿：[`../specs/2026-09-23-account-preset-design.md`](../specs/2026-09-23-account-preset-design.md)
> 状态：已实施（2026-09-23），待实机回归。

## 一、任务拆解

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | 梳理全量设置项并完成资源风险分级 | 300 行预设表（16 个模块总开关 + 284 个设置项），其中 79 行为跨账号 |
| 2 | 实现纯策略 | `model/AccountPresetPolicy.kt` |
| 3 | 实现应用器与落盘 | `model/AccountPreset.kt` |
| 4 | 接入设置页入口 | `ui/AccountPresetMenu.kt` + `SettingsContent.kt` |
| 5 | 补单测并跑通 CI 三步 | `AccountPresetPolicyTest.kt` |
| 6 | 补文档与 CHANGELOG | 本文件、设计稿、`CHANGELOG.md`、`docs/user-guide.md` |

## 二、设置项梳理方法

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
> 变为 300 行 / 284 项 / 79 行跨账号（见 `2026-09-23-friend-list-preset.md` 的实施记录）。

> 唯一未纳入的是 `ManualTaskModel`（手动任务页的 6 个一次性触发开关）：它**不在 `ModelOrder` 中注册**，
> 不进入 `ModelConfigMap`，因此不属于档位配置的范畴。单测 `预设表覆盖的模型与 ModelOrder 注册的模型完全一致`
> 会在「新增模型忘了登记」或「表里写了不存在的模型」时双向失败。

风险分级判据（写进代码注释的规则）：

- `SELF`：只读写本账号数据 —— 各模块自己的签到、任务、领取、游戏、调优参数、日志展示。
- `CROSS_ACCOUNT`：会以其他支付宝账号为操作对象 —— 含「好友列表 / 送 / 帮 / 抢 / 邀请 / 通知 / 打赏 /
  雇佣 / 训练 / 浇水 / 清理 / 助力」这类字段，以及**用途是保护其他账号的名单字段**本身。

## 三、实施记录

### 3.1 改动清单

| 文件 | 类型 | 改动 |
| --- | --- | --- |
| `app/src/main/java/fansirsqi/xposed/sesame/model/AccountPresetPolicy.kt` | 新增 | 档位枚举 `PresetTier`、作用域枚举 `FieldScope`、行模型 `PresetField`、全量设置项表 `FIELDS`、中性值判定 `isNeutralValue`、隔离自检 `altViolations` / `brokenGuards` / `duplicateEntries`。**无任何 Android 依赖**（遵循硬规则 5：可测判定逻辑抽成 `*Policy`）。 |
| `app/src/main/java/fansirsqi/xposed/sesame/model/AccountPreset.kt` | 新增 | 应用器：写入 `ModelConfig` 字段 → `Config.save(uid, force)` → 广播 `com.eg.android.AlipayGphone.sesame.restart` → 写 `account_preset.json`；切换非当前账号时对内存配置做 JSON 快照并在 `finally` 恢复。 |
| `app/src/main/java/fansirsqi/xposed/sesame/ui/AccountPresetMenu.kt` | 新增 | 三步交互：选账号 → 选档位 → 确认（含「保护其他账号」勾选）。确认页明确列出将关闭哪些跨账号能力。 |
| `app/src/main/java/fansirsqi/xposed/sesame/ui/screen/content/SettingsContent.kt` | 修改 | 「扩展&外观」区新增「账号档位（大号 / 小号）」入口。 |
| `app/src/test/java/fansirsqi/xposed/sesame/model/AccountPresetPolicyTest.kt` | 新增 | 11 项单测，见下。 |
| `docs/superpowers/specs/2026-09-23-account-preset-design.md` | 新增 | 设计稿。 |
| `docs/user-guide.md` | 修改 | 新增「账号档位」一节。 |
| `CHANGELOG.md` | 修改 | 追加一行用户可见改动。 |

未改动任何业务任务（`task/**`）与 `Model` / `ModelField` 结构 —— 本功能只做配置写入。

### 3.2 关键设计决策与理由

| 决策 | 理由 |
| --- | --- |
| 小号档位直接关闭 `collectEnergy`（收集能量），而不是只关「一键收取」 | 该开关在当前实现里**同时门控收自己与收好友**（`AntForest.kt` 私有 `collectEnergy(...)` 开头即判断），无法只收自己的。若只关 `batchRobEnergy`，小号仍会逐球偷走大号的能量。 |
| 除关闭开关外，还自动写入「双向保护名单」 | 用的是模块**既有**字段：`dontCollectList`（不收能量名单）、`alternativeAccountList`（周一保护）、`helpFriendCollectList`（复活能量名单）。大小号互为名单，形成第二道保险。 |
| 名单类字段「大号不覆盖、小号清空」 | 大号无法预知用户要给谁浇水/送道具，覆写只会破坏用户已有配置；小号则必须清空，否则历史名单会继续生效。 |
| 大号档位保持「纯干扰项」关闭（丢肥料、请走小摊、通知赶鸡、打赏好友、邀请摆摊/开通） | 判据是「无实际收益、只影响他人」。全开会让大号反手损害小号，与「互不影响」冲突。 |
| 调度 / 日志 / 调优参数一律不覆盖 | 与账号间资源无关，一键切换不该打乱用户手感。 |
| `CROSS_ACCOUNT` 行的 `altValue` 禁止为「不覆盖」 | 不覆盖 = 小号保留账号原有配置，隔离保证直接落空。这是最容易踩的坑，已由单测锁定。 |
| 引入 `altGuardField`（动作选择器 → 配套开关） | 部分设置项是「动作选择器」，本身没有「不执行」选项，且「不执行」下标不是 0（`DONT_HIRE=1`、`DONT_CLEAN=1`、`DONT_TICKET=1` …）。这类行的安全性由配套开关保证，自检会连带校验该开关在小号档位确实关闭。 |
| 不新增配置读写机制 | 完全复用设置页既有链路（`Model.getModelConfigMap().getModelField(code).setObjectValue(...)` + `Config.save`），不引入第二套状态。 |
| 档位记录只用于展示，不参与运行时判定 | 避免「配置文件」与「档位记录」两套状态不一致；运行时一律读配置字段本身。 |

### 3.3 实施中的修正

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

### 3.4 验证结果

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 主源码编译 | `./gradlew.bat :app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（仅有仓库既存的 deprecation 警告） |
| 新增单测 | `./gradlew.bat :app:testDebugUnitTest --tests "...AccountPresetPolicyTest"` | ✅ 11 项全通过 |
| 全量单测 | `./gradlew.bat :app:testDebugUnitTest` | ✅ 通过 |
| 打包 | `./gradlew.bat :app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| Web 合同测试 | `node --test app/src/test/js/*.test.js` | ✅ 7 tests / 7 pass / 0 fail |

### 3.5 单测清单

| 用例 | 断言 |
| --- | --- |
| 小号档位不含任何可能动到其他账号资源的设置项 | `altViolations()` 为空 |
| 每一条跨账号设置项都显式声明了小号取值 | 不存在 `altValue` 为「不覆盖」 |
| 动作选择器的门控开关都存在且在小号档位被关闭 | `brokenGuards()` 为空 + 门控开关本身中性 |
| 预设表没有重复登记 | `duplicateEntries()` 为空 |
| 预设表登记的所有字段在主源码中都真实存在 | 正则扫描主源码的 `ModelField("code"` 声明，挡住笔误 |
| 预设表登记的模型代码都在 `ModelOrder` 中注册 | 源码文本断言，挡住模型 code 写错 |
| 核心窃取类设置项都被判定为跨账号 | 16 个关键 code 逐一断言 |
| 小号档位关闭好友能量收取且清空好友名单 | 取值断言 |
| 大号档位确实会写入一组配置 | `overrideCount(MAIN) > 50` |
| 档位枚举可以按代码互转 | `fromCode` |
| 中性值判定只接受关闭或空集合 | 正反用例 |

> 沿用仓库既有惯例（多份单测是「读源码文本做断言」），因为 `Model` 体系依赖 Android 运行时，
> 在 JVM 单测里直接实例化模型不可行。

## 四、验收清单

- [x] 小号档位通过隔离自检（`altViolations()` 为空）
- [x] 预设表中的字段 code 全部在主源码中存在，模型 code 全部在 `ModelOrder` 中注册
- [x] `:app:assembleDebug` / `:app:testDebugUnitTest` 通过
- [x] `node --test app/src/test/js/*.test.js` 通过
- [x] 新文件 UTF-8 无 BOM
- [ ] **实机**：小米 17（大号）切到大号档，确认收能量、一键收取等能力开启
- [ ] **实机**：K50 至尊版（小号）切到小号档，确认不再收取好友能量；对照大号能量流水
- [ ] **实机**：同一台设备上两个账号互为好友时，确认「不收能量名单」写入了对方账号
- [ ] **实机**：切换后广播生效（配置无需重启支付宝即被重载）

## 五、遗留与后续

1. **小号不再自动收自己的能量**（隔离的代价）。若要放开，需手动开启「收集能量」并确保大号在
   「不收能量 | 配置列表」内 —— 该组合会削弱隔离保证，因此不作为默认值。
2. **跨设备场景下保护名单为空**。大小号分处两台设备时拿不到大号 userId，此时唯一保证是小号档位
   本身已关闭全部跨账号功能；确认页会明确提示「本机只检测到 1 个账号」。
3. **`RoleUtil` 式的自动识别未做**：档位由用户显式选择，不做「按账号自动判定大小号」的猜测。
4. 设置页目前有两套 UI（Compose 新设置页 / Web 设置页）。本功能的入口只加在 Compose 设置页；
   Web 设置页若要同样入口，属后续独立改动。
