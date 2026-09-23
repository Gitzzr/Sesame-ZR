# 大号列表配置（好友名单一键套用）—— 实施计划与记录

> 设计稿：[`../specs/2026-09-23-friend-list-preset-design.md`](../specs/2026-09-23-friend-list-preset-design.md)
> 状态：已实施（2026-09-23），已在 **K50** 完成端到端验证并还原现场。
> 前置：[`2026-09-23-account-preset.md`](2026-09-23-account-preset.md)。

## 一、任务拆解

| # | 任务 | 产出 |
| --- | --- | --- |
| 1 | 核实"哪些设置项是好友名单"及其门控 | 26 项分类表（含门控与计数语义） |
| 2 | 实现分类与填充策略 | `model/AccountFriendListPolicy.kt` |
| 3 | 新增两份关系名单设置项 | `BaseModel.mainAccountList` / `subAccountList` |
| 4 | 扩展应用器：白名单制 + 大号侧排除清理 | `model/AccountPreset.apply()` |
| 5 | 改造交互：四步流程 + 关系名单选择 | `ui/AccountPresetMenu.kt` |
| 6 | 补单测与文档 | `AccountFriendListPolicyTest` 等 11 项 |

## 二、核实过程（这一节是本次工作的主要成本）

**"哪些是好友名单"不能靠关键词猜。** 用供应商反查：主源码里所有以 `AlipayUser::getList`
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

## 三、实施记录

### 3.1 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `model/AccountFriendListPolicy.kt` | 新增 | 26 项好友名单分类表（`SERVICE` / `EXPLOIT` / `EXCLUSION` / `PROTECT`）+ 门控 + 计数默认值 + 白名单开关 + 两条隔离自检 |
| `model/BaseModel.kt` | 修改 | 新增 `mainAccountList`「大号名单 \| 供各功能一键套用」、`subAccountList`「小号名单 \| 供各功能一键套用」 |
| `model/AccountPreset.kt` | 修改 | `apply()` 增加 `mainList` / `subList` 参数；小号档白名单制（开开关 + 收窄名单 + 清空索取类）；大号档从排除名单移除小号；新增 `readStoredRelationList()` 供复用默认值 |
| `model/AccountPresetPolicy.kt` | 修改 | `dontCollectList` 的小号取值改为「写入大号名单」、**大号档保持不覆盖**（约束 3）；`alternativeAccountList` / `helpFriendCollectList` 小号档清空且不再写"本机其他账号" |
| `ui/AccountPresetMenu.kt` | 修改 | 四步流程；关系名单选择复用设置页的 `ListDialog`；档位文案改为「大号推荐配置 / 小号推荐配置」 |
| `test/.../AccountFriendListPolicyTest.kt` | 新增 | 11 项 |
| `test/.../AccountPresetGuardTest.kt` | 修改 | 断言白名单制与排除清理的调用顺序 |

### 3.2 与上一版的关键差异（含对上一版错误的修正）

| 项 | 上一版 | 本版 |
| --- | --- | --- |
| 排除名单的数据来源 | 两档都写"本机其他已载入账号" | 小号档写**大号名单**；**大号档不写，且主动移除小号** |
| 小号档定位 | 关闭全部跨账号功能 | **白名单制**：服务类只对大号开放（开关打开 + 名单收窄），索取/干扰类保持关闭 |
| 写入的名单数量 | 3 份（其中 2 份无效或非该机制） | 只写真正兑现隔离的 `dontCollectList` + 12 个功能名单（按需） |
| 入口文案 | 大号档 / 小号档 | 大号推荐配置 / 小号推荐配置 |

**上一版有两处错误，本版按需求修正：**

1. **大号档把"小号"写进「不收能量」是错的。** 用户明确要求：大号需要收取小号的能量。
   现在大号档不但不写，还会把「小号名单」里的账号从排除名单里**移除**。
2. **写了 3 份名单但只有 1 份真正起作用。** 核实后确认：`helpFriendCollectList` 在
   「复活能量」为关闭时零效果；`alternativeAccountList` 不是"不收能量"机制（它是复活能量保护名单，
   且 `monday` 恒为 `true`）。现在这两份都不碰。

### 3.3 实施中踩到的坑

**测试里搜源码搜错了文件。** 新测试要反查"主源码里哪些字段的候选集是 `AlipayUser`"，
最初扫描整个 `src/main` —— 但 `model/AccountFriendListPolicy.kt` **本身**就含有这 26 个 code 字面量，
而 `Model` 类的 walk 顺序里 `model/` 排在 `task/` 之前，于是"在策略文件里找到了 code，附近当然没有
`AlipayUser`"，26 项全部误判失败。修法：扫描范围限定到 `task/`（业务模型所在目录），并在注释里写明原因。

**确认页文案在白名单制下失真。** 原写「关闭全部 79 项以好友为对象的设置」，
但白名单制下小号档会**打开** 4 个开关并填充 12 个名单，这句话不再准确。
改为「索取/干扰类保持关闭，名单一律清空」+ 按是否指定大号分别说明。

## 四、K50 实机验证（可回滚）

测试机：Redmi K50 Ultra（`4d3a3d64`，Android 15，USB）。**小米17 未做任何改动**（用户自用）。

流程：备份配置目录 → 走完四步流程 → 核对落盘 → **还原备份并重启支付宝**。

原始 `config_v2.json` md5 `851616af8d13f25c71dfa9ee0985529e`，验证后已精确还原（md5 一致、
`account_preset.json` 已移除）。

### 4.1 操作链路

```
设置 → 账号档位（大号/小号）→ 梓锐 → 小号推荐配置
  → 关系名单对话框「选择大号名单（共 6 位好友）」→ 勾选「提醒-*梓锐」（= 大号）
  → 关闭 → 确认页 → 确认应用
```

### 4.2 模块日志

```
[AccountPreset]: 模型注册表为空，按设置页惯例先执行 Model.initAllModel()
[AccountPreset]: 模型注册表就绪：16 个模型
[AccountPreset]: 已把【小号】档位应用到 梓锐(2088922617455078)；覆盖 232 项设置；套用好友名单 22 处
```

> 第一行再次印证：主界面进程里模型注册表初始为空，`ensureModelRegistry()` 是必需的。

### 4.3 落盘核对（`2088412711917481` = 大号阿锐）

| 检查项 | 结果 |
| --- | --- |
| 「不收能量 \| 配置列表」 | `[]` → `['2088412711917481']` ✅ **小号不会偷大号** |
| 服务类名单 11 处 | 全部指向大号 ✅ |
| ↳ 浇水 / 帮喂小鸡 / 送麦子（计数型） | `{'2088412711917481': 1}` ✅ 次数给的是正数 |
| ↳ 赠送能量雨 / 赠送道具 / 一起拿饲料 / 帮抽卡 / 送卡片 / 助力×2 / 遣返 | `['2088412711917481']` ✅ |
| 门控开关 | `giveProp` `False→True`、`getFeed` `False→True`、`visitAnimal` `False→True`、`collectToFriend` `False→True` ✅ |
| 索取/干扰类名单 10 处 | 全部清空（贴罚单 / 丢肥料 / 抢好友 / 雇佣 / 清理海洋 …）✅ |
| 关系名单持久化 | `mainAccountList = ['2088412711917481']` ✅ |
| 复活能量体系 | `alternativeAccountList` / `helpFriendCollectList` 未被填充 ✅ |

### 4.4 CI 三步

`assembleDebug` ✅ / `testDebugUnitTest` ✅ 205 项（含新增 11 项）/ `node --test` ✅ 7/7

## 五、验收清单

- [x] 26 项分类完整、无重复，且候选集确为好友列表
- [x] 主源码里的好友名单字段都被登记（反查完整性）
- [x] 小号档只对大号动手；索取/干扰类清空
- [x] **大号档不写排除名单**，并能移除残留小号
- [x] K50 端到端验证通过（含落盘核对）
- [x] CI 三步通过；新文件 UTF-8 无 BOM
- [ ] 小米17（大号）实机验证 —— **待用户后续自行进行**
- [ ] 观察一轮实际任务：小号是否真的只对大号浇水 / 送道具，且不再收大号能量

## 六、遗留与后续

1. **小米17 未验证**。按用户要求本轮只在 K50 测试；大号侧（排除名单移除小号）的逻辑
   有单测覆盖，但缺真机确认。
2. **关系名单是"平铺"语义**：套用后所有服务类名单都是同一份大号名单。
   若要"浇水给 A、送道具给 B"，仍需事后单独改。
3. **`AntFarm.visitAnimal` 是送麦子的门控**，名字与"送麦子"不直接对应；已在策略表里注释，
   若上游把门控拆开，需要同步更新。
4. **未做逐功能勾选**：本版是"全量套用 12 个名单"。若用户反馈某些功能不想套用，
   可加一个功能勾选步骤（会重新引入复杂度，故未默认提供）。

## 七、第二轮：改为「逐项功能勾选」（2026-09-23 增补）

### 7.1 需求变化

第一轮是「选好友 → 全量套用 12 个名单」。用户要求改成：
**在名单里选中某个账号后，完整展示该账号对应的全部功能，供用户逐项勾选，默认勾选全部推荐项**，
两个名单（大号 / 小号）都按此逻辑。

### 7.2 实现方式

| 层 | 改动 |
| --- | --- |
| 数据 | `FriendListRef` 增加 `featureLabel`（方向化文案）、`gateSwitches`（勾选时连带写入的开关）、`effective`（当前实现是否生效）、`id`；删除 `altFillable` 与独立的 `ALT_WHITELIST_SWITCHES` |
| 推荐 | 新增 `recommendedForMainList()`（12 项）/ `recommendedForSubList()`（1 项）/ `recommendedIds(方向)` / `isSelectable(项, 方向)` |
| 应用 | `apply()` 增加 `mainSelection` / `subSelection`；新增 `aggregateSelection()` 把「好友 → 功能」反转为「功能 → 好友」，**勾了就填、填了就开开关**，未勾选保持档位基线 |
| 持久 | `account_preset.json` 增加 `featureSelection.{main,sub}`；`readRecord()` 解析回来供下次复用 |
| UI | 弃用 `ListDialog`（行点击被硬编码为切换勾选，做不了下钻），改自建两级对话框 |

### 7.3 两处必须知道的实现坑

1. **裸 `ListView` 放进 `AlertDialog` 会塌成 0 高度** —— `setView` 给的是 `wrap_content`。
   修法：套一层 `FrameLayout` 并给固定高度（屏高的 55%）。
2. **中立按钮默认会关闭对话框** —— 「全选推荐」「按推荐重置」点一下就关掉，用户没法接着调整。
   修法：改用 `builder.create()` + `setOnShowListener` 里覆写 `BUTTON_NEUTRAL` 的点击。

另外适配器里读计数**不能用会写入的 `getOrPut`**：`getView` 会对每个好友调用一次，
用 `checkedIds()` 会给 273 个好友全部建出空条目 —— 改成只读 `selection[...]?.size ?: 0`。

### 7.4 K50 验证（可回滚，已还原）

备份（md5 `5b696cdf…`）→ 走完两级流程 → 核对落盘 → **还原并重启支付宝**（md5 一致、`account_preset.json` 已移除）。

| 检查项 | 结果 |
| --- | --- |
| 一级列表默认状态 | 大号「提醒-*梓锐」显示「**已启用 12 项**」 ✅ 与推荐集一致 |
| 二级功能清单 | 4 组分节共 26 项；服务类默认勾选、索取类默认不勾、「复活 TA 的能量」标注**当前版本不生效**且不勾、豁免组两项默认勾选 ✅ |
| 确认页 | 「大号名单：1 个账号，共启用 12 项功能」并逐项列出 ✅ |
| 档位记录 | `featureSelection.main[uid]` 落盘 12 个功能 id ✅ |
| 勾选的功能 | 11 个名单字段全部指向大号；「不收能量名单」`[] → ['2088412711917481']` ✅ |
| 未勾选的功能 | 名单保持空；门控开关 `hireAnimal` / `stallAutoTicket` / `cleanOcean` **保持 False**（未被误开）✅ |
| 已勾选功能的门控 | `collectToFriend` `False → True` ✅ |

日志：`覆盖 223 项设置；套用好友名单 12 处`。CI 三步 + 新增单测全通过。

### 7.5 第三轮：修正「服务 TA」的文案歧义（2026-09-24）

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

## 八、第四轮：修两个逻辑冲突（2026-09-24）

用户复核时指出「很多重复的内容」。逐条核实后确认有 12 处重叠（R1~R12），
本轮先修其中**两条不是冗余、而是行为错误**的：

### 8.1 R1：`dontCollectList` 双路径 —— 取消勾选被静默忽略

`AccountPresetPolicy` 里 `dontCollectList` 的 alt 值是 `WriteMainAccountList` 标记，
apply **第 2 步**会无条件把它写成"大号名单"，而按勾选写入在**第 4 步**。
用户取消勾选「不收取 TA 的能量」后，第 2 步写的值仍然留着 → **取消无效**。
（它本来就是功能清单里的一个普通项，不需要这条特殊路径。）

**修法**：删掉 `WriteMainAccountList` 与 apply 第 2 步的对应分支，`dontCollectList` 只由勾选驱动。

### 8.2 R2：26 个名单字段被档位无条件改写 —— 与"没勾就不动"矛盾

脚本统计：26 项名单字段的 alt = 22×`EMPTY_SET` + 3×`EMPTY_MAP` + 1×`WriteMainAccountList`，
main 全是 `KEEP`。意味着小号档 apply 时**26 个名单字段全部会被写一遍**（25 个清空 + 1 个写名单），
与用户勾选无关；`aggregateSelection` 只是在其后覆盖。
而文案写的是「未勾选的服务类功能保持关闭，**不动其名单**」→ 实际是**清空**。

**修法**：26 个名单字段的 alt 全部改成 `KEEP`，名单**只由勾选驱动**。

### 8.3 连带发现：8 个参数类字段也被档位改写

按字段类型反查发现静态表还在改 8 个数值参数，逐一判定：

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

### 8.4 规则因此分成三类（小号档的要求是相反的）

| 类别 | 小号档要求 | 自检 |
| --- | --- | --- |
| 开关类（布尔 / 动作选择器） | 中性值，**不允许「不覆盖」** | `altViolations()` |
| 名单类（26 个） | **必须「不覆盖」** | `listRowsNotKeep()` |
| 纯参数类（1 个） | **必须「不覆盖」** | `PLAIN_PARAM_ROWS` + 单测 |

另有两条测试因编码了旧行为而失败、已按新语义改写：
「每一条跨账号设置项都显式声明了小号取值」→ 拆成"开关类必须显式、名单类必须不覆盖"；
「小号档位关闭好友能量收取且清空好友名单」→ 改为"关掉收取类开关，但名单交给勾选驱动"。

### 8.5 K50 验证：用哨兵值证明「不动」是真的

往配置里注入**伪造 uid 哨兵**再走一遍流程（这样"没动"与"被清空"才可区分）：

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

### 8.6 尚未处理（待继续商量）

R4~R12：两个名单设置项与 `featureSelection` 重复、大小号名单在同一份配置里互斥、
`protectedAccounts` / `tierLabel` 冗余、命名与"档位"交叉、一级页"列入名单"勾选框语义为空、
确认页「覆盖 N 项」用的是静态表计数（不准）、两张表登记缺一致性断言。
用户选择"先列出来逐个商量"，故本轮不动。

## 九、第五轮：方案 A —— 删掉两个成员名单设置项（2026-09-24）

用户选定方案 A：把「大号名单 / 小号名单」两个设置项**全部删除**。

### 9.1 删的理由（三条硬事实）

| # | 事实 | 依据 |
| --- | --- | --- |
| 1 | **没有任何运行时消费者** | `task/` 与 `hook/` 对 `mainAccountList` / `subAccountList` 零引用；全仓只有声明、常量名与我自己写的一处读取 |
| 2 | **与档位记录里的勾选完全重复** | `selection.<tier>` 的 **key 就是好友 uid** → 成员集合可直接推导 |
| 3 | **同一份配置里只有一个会被用到** | 档位决定方向，而两个字段同属一份 `config_v2.json` → 另一个恒为死数据 |

### 9.2 改动清单

| 层 | 改动 |
| --- | --- |
| 设置项 | `BaseModel` 删除 `mainAccountList` / `subAccountList` 的声明与注册（字段数 28 → 26） |
| 策略表 | `AccountPresetPolicy.FIELDS` 删掉对应两行（302 → 300） |
| 常量 | `AccountFriendListPolicy` 删除 `MAIN_LIST_MODEL/FIELD`、`SUB_LIST_MODEL/FIELD` |
| 应用器 | `apply()` 参数 **4 → 1**：`selection: Map<好友, Set<功能>>`；好友集合 = `selection.filterValues{非空}.keys`；`aggregateSelection()` 不再需要额外的名单参数 |
| 大号档清理 | 从"按 `subList` 全量移除"改为"移除**本次选中的好友**" |
| 记录 | `PresetRecord` 改为 `selections: Map<tierCode, Map<friendId, Set<featureId>>>`；JSON 从 `featureSelection.{main,sub}` + `protectedAccounts` + `tierLabel` 简化为 `selection.{alt,main}`（R6/R7 一并消失） |
| 辅助方法 | 删除 `readStoredRelationList()`（它就是为了读那两个字段） |
| 界面 | 一级列表**去掉"是否列入名单"勾选框**（名单没有消费者，勾它不产生任何效果）；点整行 → 二级；行尾「已配置 N 项 / 未配置」；标题从「批量启用功能 · 小号名单」改为「批量启用功能 · 小号推荐配置」 |

### 9.3 实施中抓到的一个回归

重构后第一次上机，点进二级页发现**默认勾选是空的** —— 因为我把"预置推荐项"的两处逻辑（原来的成员初始化 + 行点击时的补齐）都删掉了，
而新的 `checkedIds()` 只 `getOrPut { LinkedHashSet() }`。

修法：把预置逻辑收进 `checkedIds(friend, recommended)` —— **首次打开某个好友时按推荐预置，已配过的沿用上次**。
这既恢复了"默认自动勾选所有推荐功能"，也保证重开时不会被重置。

> 教训：交互重构时，"默认值从哪来"这类隐式行为最容易丢；上线前必须真的走一遍点进点出，不能只看编译通过。

### 9.4 K50 验证（可回滚，已还原）

先删掉旧 schema 的 `account_preset.json`（模拟首次使用）再走流程：

| 检查项 | 结果 |
| --- | --- |
| 一级标题 | 「批量启用功能 · 小号推荐配置（共 6 位好友）」—— 不再有"名单"字样 ✅ |
| 一级行 | 无勾选框；6 位好友全部「未配置 ▸」（K50 只有 1 个账号，无同机预置）✅ |
| 点进大号 → 二级 | 默认勾选 **12 项** ✅ |
| 确定后一级行 | 变成「**已配置 12 项** ▸」✅ |
| 应用日志 | `覆盖 196 项设置；批量启用 12 项功能` ✅ |
| 配置写入 | 勾选的 12 项全部指向大号 ✅ |
| 未勾选的与参数 | `stallTicketList` / `hireAnimalList` / `waterFriendCount(66)` / `alternativeAccountList` / `notInviteList` **全部未动** ✅ |
| `BaseModel` 字段数 | 26（少了那两项），不含 `mainAccountList` ✅ |
| **新记录结构** | `{tier, appliedAt, appliedCount, selection:{alt:{uid:[12 项]}, main:{}}}` —— 无 `protectedAccounts`、无 `tierLabel`、无 `featureSelection` ✅ |
| **复用** | 重新打开流程，大号那行仍显示「已配置 12 项」→ 勾选从记录恢复，**不再依赖已删掉的设置项** ✅ |

验证后已还原现场（md5 一致、`account_preset.json` 已移除）。CI 三步全绿。

### 9.5 R 系列进度

| 编号 | 状态 |
| --- | --- |
| R1 取消勾选被静默忽略 | ✅ 第四轮已修 |
| R2 未勾选的名单被静默清空 | ✅ 第四轮已修 |
| R4 两个名单项与记录重复 | ✅ 本轮（方案 A） |
| R5 两个名单项在同一配置里互斥 | ✅ 本轮（方案 A） |
| R6 `protectedAccounts` 冗余 | ✅ 本轮（其内容就是旧的大号名单） |
| R7 `tierLabel` 可推导 | ✅ 本轮（顺手去掉） |
| R8 命名与"档位"交叉 | ✅ 本轮（"名单"概念消失，标题改为「X 推荐配置」） |
| R9 一级页勾选框语义为空 | ✅ 本轮（勾选框已删） |
| R11 两张表重复登记、缺一致性断言 | ⬜ 待处理 |
| R12 `isSelectable` 与 `recommendedIds` 在排除类上重合 | ⬜ 待处理（判断为不构成问题，待确认） |
