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
