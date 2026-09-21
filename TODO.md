# TODO.md

> 当前任务、优先级与开发进度。
>
> **状态来源说明**：本文件的进度不是凭印象写的，是从仓库内的三份实施计划（`docs/superpowers/plans/`）逐条提取的勾选状态。其中 `2026-09-05-verification-and-log-errors.md` 的结构特殊 —— 文件上半部「实施记录」是真实结果，下半部「第一阶段 / 第二阶段」是**原始计划清单且未回填勾选**（文件第 34 行明确写了「以下为原计划及验收清单，实施结果以本节为准」）。本文件一律以「实施记录」为准。
>
> 最近更新：2026-09-21

---

## 🔴 P0 · 阻塞项（不解决就没法正常开发）

### P0-1 单元测试当前编译失败

**现象**：`./gradlew :app:testDebugUnitTest` 直接编译失败，整个测试任务跑不起来。

**根因**：`app/src/test/java/fansirsqi/xposed/sesame/task/antFarm/ChouChouLeSchedulePolicyTest.kt` 引用了主源码中不存在的两个类型：

- `ChouChouLeScheduleAction`（需要 `RUN` / `WAIT_FOR_TIME` / `SKIP_COMPLETED`）
- `ChouChouLeSchedulePolicy`（需要 `actionFor(completedToday: Boolean, timeReached: Boolean)`）

提交 `579aae63`（*✅test: 添加抽抽乐调度策略单元测试*）**只提交了测试，没提交实现**，导致 `codex/dev` 从此处于「测试编译不过」的状态。三处测试用例（已到时间→`RUN`、未到时间→`WAIT_FOR_TIME`、今日已完成→`SKIP_COMPLETED`）已经把契约写得很清楚了。

**修复方案（二选一）**：

1. **补实现**（推荐）：在 `task/antFarm/` 下新增 `ChouChouLeSchedulePolicy.kt`，按三态语义实现 `actionFor`，并让 `ChouChouLe.kt` 的调度分支实际调用它 —— 否则策略类只是「为测试而测试」。
2. **临时移除**：删除或 `@Ignore` 该测试文件，先恢复测试可跑，再另开任务补实现。

**验收**：`./gradlew :app:testDebugUnitTest` 能编译并执行；此前的 109 项测试基线重新全绿。

> 背景：`2026-09-05` 计划的「分支更正」一节已经记录过这个问题（*「开发分支原有 `ChouChouLeSchedulePolicyTest.kt` 引用不存在的类，完整测试编译失败」*），当时用「临时隔离该文件」的方式绕过并跑通 164 项测试，但**问题本身没有被修**。所以现在仍然是坏的。

### P0-2 开发环境缺 SDK 与 JDK 17 —— ✅ 已完成（2026-09-21）

**原现象**：本机 `ANDROID_HOME` 未设置、无 `local.properties`、`java -version` 是 **1.8**。项目要求 JDK 17+ / SDK 37 / build-tools 37.0.0。任何 Gradle 任务都跑不了。

**已完成**：

- [x] JDK 17：复用本机已有的 `C:\env\Java\jdk-17_windows-x64`（17.0.7 LTS），用户级 `JAVA_HOME` 已指向它
- [x] `%JAVA_HOME%\bin` 置于用户 PATH 最前（原 PATH 里的 JDK 1.8 条目保留但不再优先）
- [x] Android SDK 装到 `C:\env\android-sdk`：platform **37.0** + build-tools **37.0.0** + platform-tools；许可协议已接受
- [x] 用户级 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 已指向 SDK
- [x] `local.properties` 已写 `sdk.dir=C\:\\env\\android-sdk`（该文件已被 gitignore，不要提交）
- [x] 预置 Gradle 9.5.0 发行包进 wrapper 缓存（官方源 302 跳 GitHub 不通，走腾讯镜像；sha256 已与官方值比对一致）
- [x] **验证通过**：
  - `:app:compileDebugKotlin` → `BUILD SUCCESSFUL in 4m 9s`
  - `:app:assembleDebug` → `BUILD SUCCESSFUL in 1m 38s`，产出 **5 个 APK**（armeabi-v7a / arm64-v8a / x86 / x86_64 / universal，版本 0.9.9-debug）
  - `:app:testDebugUnitTest` → 仍失败，但**只剩 P0-1**（见上一节），环境因素已全部排除

**过程中发现的新阻塞（已解决）**：AGP 拒绝含非 ASCII 字符的项目路径。

```
StopExecutionException: Your project path contains non-ASCII characters.
```

因此仓库从 `...\WorkBuddy\蚂蚁森林辅助\Sesame-VN` 迁到 **`C:\Users\Administrator\WorkBuddy\senmayi\Sesame-VN`**。
本地构建不含原生代码（`app/src/main/cpp` 不存在），也可以用 `android.overridePathCheck=true` 绕过，
但推荐直接放 ASCII 路径。详见 `docs/development.md` §1.2。

**遗留**：用户 PATH 里仍保留 `C:\env\Java\jdk-8u341-windows-x64\bin`（排在 `%JAVA_HOME%\bin` 之后），
不影响 Gradle，但命令行 `java` 走的仍是 17（因为 `%JAVA_HOME%\bin` 在最前）。旧项目若需要 JDK 8 需手动切。

> 曾记录的「JDK Unix 域临时路径导致 Gradle 回环连接失败」本次**未复现**（未设 `JAVA_TOOL_OPTIONS` 即构建成功），
> 保留备查。

---

## 🟠 P1 · 待实机验证

### P1-1 安全验证与调度修复的实机回归

`2026-09-05` 计划的「实施记录」里，**唯一没有勾选**的条目：

> - [ ] 实机检查安全验证暂停及人工恢复、重启去重、跨日清理、实际会员奖励和调度时长。

代码侧 10 项已全部完成（`[x]`），构建侧也已验证（109 项测试、0 失败、0 错误，各 ABI APK 产出成功）。**剩下的全部是实机侧**：

- [ ] 安全验证触发后的暂停行为，以及通知 / 支付宝主页确认框的**人工恢复**入口
- [ ] 恢复后一次性令牌校验、动作串行执行是否符合预期
- [ ] 进程重启后「验证暂停状态」是否按账号正确保留
- [ ] 会员游戏「按账号 + 当天 + 任务标识」的尝试记录：重启是否去重、跨日是否清理
- [ ] 实际会员奖励是否正常到账
- [ ] 森林 / 庄园 / 运动主流程的实际调度时长（超时已从原值改为 10 分钟）

**实机步骤要求**（来自计划的「验证与交付」）：先暂停自动任务、人工完成验证、确认普通使用状态，随后只启用必要模块逐项恢复，并记录启动与验证时间。核对时要看**原始 1009 数量、本地阻断计数、阻断后新发请求数、保护罩实际请求数、业务成功状态** —— 不能只看调度器的「成功」计数。

### P1-2 自营项目捐蛋（缺样本，已挂起）

`2026-09-05` 计划的第 6 项，明确记录了取舍：

> 当前 ONE/ALL 只决定捐赠活动数量，没有标的选项。自营 SOLDBY 明确跳过并继续普通活动，未猜测 `targetId`。代价是自营自动捐赠暂不可用，需明确标的配置后再接入已知协议。

**要推进必须先拿到样本**（禁止猜字段名）：

- [ ] 抓 `com.alipay.antfarm.listActivityInfo` 完整请求/响应
- [ ] 抓同一自营项目在官方页面正常操作产生的详情查询
- [ ] 抓 `com.alipay.antfarm.donation` 请求/响应
- [ ] 由样本确定标的物字段与项目关联规则，再实现

**约束**：只有用户本来就打算捐赠时才抓实际捐赠，**不要为排查额外消耗鸡蛋**。普通项目参数必须保持兼容。

---

## 🟡 P2 · 已知缺陷 / 待补证据

### P2-1 青春特权 `3000` 错误

`alipay.membertangram.biz.rpc.student.queryCheckInModel` 出现 `3000`，抓包中没有该接口样本，因此**未猜协议修复**，保留现有处理。

- [ ] 补抓该接口请求/响应，并记录同时间官方页面能否正常打开
- [ ] 若服务端同样失败 → 停止当轮重复尝试；仅模块失败 → 再对比参数与上下文

### P2-2 `CAMP_TRIGGER_ERROR` 与蹲点空收取

- [ ] `CAMP_TRIGGER_ERROR` 且 `retryable=false` 时保持停止（现状已如此，无需改）
- [ ] 蹲点空收取、超远未来的成熟时间记录：**暂作为诊断数据保留**，无新增证据不要改成熟时间、不要强制重试

### P2-3 验证暂停的固有竞争窗口

已记录的**已知限制，非缺陷**：验证暂停不会自动重启恢复；已发出的请求无法撤回；限流等待与反射调用之间存在极短竞争窗口，因此**不能宣称「绝对零在途请求」**。取消后同步浏览等待可能尚未结束，恢复会等待旧业务退出，实机需观察耗时。

### P2-4 内置 HTTP 接口鉴权不统一

`hook/server/ModuleHttpServer.kt` 的 NanoHTTPD 监听 `0.0.0.0`，但三条路由的保护级别不同：

| 路由 | 是否继承 `BaseHandler` | 是否校验 token |
| --- | --- | --- |
| `POST /debugHandler` | ✅ | ✅ 校验 |
| `GET /getAlipayMiniMark` | ❌ 直接实现 `HttpHandler` | ❌ **不校验** |
| `GET /getAuthCode` | ❌ 直接实现 `HttpHandler` | ❌ **不校验** |

`/getAuthCode` 会返回 OAuth2 授权码。若设备处于不可信网络，这两条 GET 接口可被任意调用。

- [ ] 确认这两条接口是否只面向本机调试；若是，至少改为绑定 `127.0.0.1` 或统一继承 `BaseHandler`
- [ ] 注意 `BaseHandler` 在 `secretToken` 为空时**直接放行** —— 别在生产配置里留空

### P2-5 `SettingsComponents.kt` 缺 `package` 声明

`app/src/main/java/fansirsqi/xposed/sesame/ui/screen/components/SettingsComponents.kt` 文件首行直接是 `import`，没有 `package` 行，导致 `SettingsSwitchItem` 落在 Kotlin 默认包，与同目录其他文件不一致。

- [ ] 补上 `package fansirsqi.xposed.sesame.ui.screen.components`

### P2-6 `index.css` 的 `white-space: warp`

`assets/web/css/index.css` 中 `.title` 规则写了 `white-space: warp;`，正确值是 `wrap`。浏览器会忽略这条非法声明，所以目前表现为标题不换行。低风险，改的时候顺手修掉即可。

---

## 🟢 P3 · 文档与协作

- [x] 建立文档体系：`AGENTS.md`、`DESIGN.md`、`TODO.md`、`docs/{project-overview,architecture,user-guide,development,component-api}.md`（2026-09-21）
- [x] `CODEBUDDY.md` 仓库导览（2026-09-21）
- [ ] 把 P0-1 的修复结论回填到 `docs/superpowers/plans/2026-09-05-verification-and-log-errors.md` 的「分支更正」一节
- [ ] 建立 CI 测试环节：目前 `.github/workflows/android.yml` / `debug.yml` **都只构建、不跑测试**，P0-1 这类问题才会长期潜伏在 `codex/dev` 上
- [ ] 评估三套 UI 体系（Compose 青 / XML 蓝 / Web 橙）的配色统一 —— 属于产品决策，需单独立项，**不要顺手改**

---

## ✅ 已完成（来自实施计划）

| 计划 | 内容 | 状态 |
| --- | --- | --- |
| `2026-08-01-energy-rain-verification-retry` | 能量雨安全验证只结束当前调用、不再写当天暂停标记，保留 30 秒冷却 | 6/6 步全绿 |
| `2026-08-03-rob-expand-energy-post-collect` | 倍率卡能量：好友收取完成后复用 `updateSelfHomePage()` 复查一次阈值，达标即领取 | 7/7 步全绿（2 个 Task） |
| `2026-09-05-verification-and-log-errors` | 验证阻断与任务停止、捐蛋响应解析兜底、保护罩去重、调度并发、会员游戏入口兼容 | 代码 10/10，实机待验 0/1 |

`2026-09-05` 已完成的代码侧条目（逐条摘自实施记录）：

- [x] 新旧 Bridge 限流前后检查离线；普通成功响应不再清除验证状态；取消任务保留待退出 Job，恢复先等待旧任务退出
- [x] 验证状态按账号保存在宿主私有存储，重启保留；通知与主页确认框提供手动恢复入口，恢复动作校验账户及一次性令牌并串行执行
- [x] 森林 / 庄园 / 运动主流程占用调度槽直至结束，超时改为 10 分钟；独立长期子任务不纳入主 Job 等待；离线不启动下一轮及下次定时流程
- [x] 捐蛋及收蛋响应缺失 memo 时正常处理失败；验证时退出庄园流程；不误写成功状态
- [x] 保护罩按轮次与道具 ID 去重；保留用户阈值；获取成功后刷新背包
- [x] 兼容抓包证实的会员 `externalGameCenter` 入口及首页参数，保留旧入口
- [x] 会员游戏按账号 + 当天 + 任务标识在执行前持久化尝试记录；成功与尝试分开；跨日清理
- [x] 最终 Gradle `:app:testDebugUnitTest :app:assembleDebug` 通过，109 项测试、0 失败、0 错误
- [x] 校验 23 个文件 UTF-8 无 BOM

---

## 任务看板速览

| 优先级 | 任务 | 阻塞于 | 预计影响面 |
| --- | --- | --- | --- |
| 🔴 P0-1 | 修 `ChouChouLeSchedulePolicy` 测试编译失败 | 无（可立即做） | 1 新增文件 + `ChouChouLe.kt` 调度分支 |
| ~~🔴 P0-2~~ ✅ | ~~装 JDK 17 + Android SDK 37~~ 已完成 2026-09-21 | — | 环境，无代码变更 |
| 🟠 P1-1 | 安全验证与调度修复的实机回归 | 真机 | 无代码变更，纯验证 |
| 🟠 P1-2 | 自营项目捐蛋 | **需要用户提供抓包样本** | `AntFarm.kt` + 协议测试 |
| 🟡 P2-1 | 青春特权 3000 | **需要抓包样本** | 会员模块 |
| 🟡 P2-4 | HTTP 接口鉴权统一 | 需确认使用场景 | `hook/server/` |
| 🟡 P2-5 | 补 `SettingsComponents.kt` 的 `package` | 无 | 1 行 |
| 🟡 P2-6 | `white-space: warp` 笔误 | 无 | `index.css` 一行 |
| 🟢 P3 | CI 增加测试环节 | P0-1 | workflow 改动 |
