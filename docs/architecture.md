# 架构与数据流

> 配套：[`project-overview.md`](project-overview.md)（定位与结构）、[`component-api.md`](component-api.md)（组件与 API）、[`../AGENTS.md`](../AGENTS.md)（硬规则）

---

## 1. 全局视图

```mermaid
flowchart TB
    subgraph Framework["Xposed / LSPosed 框架"]
        F1[加载模块 aar]
    end

    subgraph Alipay["支付宝进程 (com.eg.android.AlipayGphone)"]
        subgraph Entry["注入入口 hook/modern"]
            HE["HookEntry<br/>(libxposed XposedModule)"]
            MXR["ModernXposedRuntime<br/>(XposedInterface 单例)"]
            ENV["XposedEnv<br/>classLoader / appInfo / processName"]
        end

        subgraph Orchestrator["运行时编排 hook/"]
            AH["ApplicationHook ⭐ 脊柱"]
            RM["RequestManager<br/>RPC 统一入口 + 验证拦截"]
            GATE["RpcDispatchGate<br/>发送前门禁"]
            REC["RpcRecoveryPolicy<br/>熔断/恢复状态机"]
            LIM["RpcIntervalLimit<br/>接口级限频"]
            BR["RpcBridge<br/>Old / New"]
            TOK["TokenHooker + *TokenParser"]
            HTTP["ModuleHttpServer<br/>(NanoHTTPD)"]
            KEEP["SmartSchedulerManager<br/>保活与定时唤醒"]
        end

        subgraph Tasks["任务层 task/"]
            CTR["CoroutineTaskRunner<br/>并发调度"]
            MT["ModelTask 子类<br/>antForest / antFarm / ..."]
            POL["*Policy.kt<br/>纯逻辑，可单测"]
        end

        subgraph Model["配置层 model/"]
            BM["BaseModel<br/>全局开关"]
            MF["ModelField / modelFieldExt<br/>设置项 schema"]
            CFG["Config / Status<br/>落盘"]
        end

        subgraph UI["界面层 ui/"]
            WEB["WebSettingsActivity<br/>+ assets/web (默认)"]
            CMP["MainActivity + Compose<br/>(新版)"]
            VM["ViewModel / ConfigRepository"]
        end

        subgraph Native["系统能力"]
            CMD["CommandService (AIDL)<br/>root / Shizuku"]
            DK["DexKit<br/>运行时定位"]
            SO["libchecker.so"]
        end
    end

    F1 --> HE
    HE --> MXR
    HE --> ENV
    HE --> AH
    AH --> TOK
    AH --> BR
    AH --> HTTP
    AH --> KEEP
    AH --> CTR
    CTR --> MT
    MT --> POL
    MT --> RM
    RM --> GATE
    RM --> REC
    RM --> LIM
    RM --> BR
    BR -.hook.-> AlipayRPC["支付宝内部 RPC 方法"]
    MXR -.intercept.-> AlipayRPC
    MT --> BM
    BM --> MF
    MF --> CFG
    WEB --> CFG
    CMP --> VM
    VM --> CFG
    MT --> CMD
    MT --> DK
    AH --> SO
```

**一句话理解**：`ApplicationHook` 是唯一的编排者。业务任务不直接碰网络，而是通过 `RequestManager` 走统一 RPC 通道；任务的可开关与参数全部由 `ModelField` 描述，两套 UI 只是这份 schema 的不同渲染器。

---

## 2. 注入链路

```mermaid
sequenceDiagram
    participant FW as Xposed 框架
    participant HE as HookEntry
    participant MXR as ModernXposedRuntime
    participant ENV as XposedEnv
    participant AH as ApplicationHook

    FW->>HE: onModuleLoaded(param)
    HE->>MXR: initialize(this)
    Note over MXR: 保存 XposedInterface 单例<br/>此后所有 hook 唯一入口
    HE->>HE: 记录 processName
    HE->>AH: 注入 xposedInterface

    FW->>HE: onPackageReady(param)
    HE->>HE: 判断 packageName == General.PACKAGE_NAME
    HE->>ENV: 写入 classLoader / appInfo / packageName / processName
    HE->>AH: loadPackage(param)
    AH->>AH: 初始化 Token / RPC Bridge / 保活 / HTTP 服务 / 任务调度
```

### 关键文件

| 文件 | 职责 |
| --- | --- |
| `app/src/main/resources/META-INF/xposed/java_init.list` | 声明入口类（**唯一一行**：`fansirsqi.xposed.sesame.hook.modern.HookEntry`） |
| `META-INF/xposed/module.prop` | `minApiVersion=101`、`targetApiVersion=102`、`staticScope=true` |
| `META-INF/xposed/scope.list` + `res/values/arrays.xml` | 作用域：只有 `com.eg.android.AlipayGphone` |
| `hook/modern/HookEntry.kt` | libxposed `XposedModule` 实现，两个回调：`onModuleLoaded` / `onPackageReady` |
| `hook/modern/ModernXposedRuntime.kt` | `XposedInterface` 单例持有者；对外只暴露 `hook` / `replaceWithConstant` / `deoptimize` / `log` |
| `hook/modern/HookInvocation.kt` | 一次 hook 调用的上下文（`executable` / `thisObject` / `initialArgs` / `result` / `proceedCall`） |
| `hook/XposedEnv.kt` | 跨进程静态持有 classLoader、appInfo、进程名 |
| `hook/modern/ReflectionHelper.kt` | 反射工具（找类、找方法、找字段） |

### 设计约束

- **hook 必须走 `ModernXposedRuntime`。** 它统一设置了 `ExceptionMode.PROTECTIVE`（hook 抛错不影响宿主）并把调用封装成 `HookInvocation`。绕过它直接调 libxposed 会丢掉这两层保护。
- **只支持 libxposed 102 现代入口。** 旧版 API 82/100 的兼容层与 `xposed_init` 已被移除（见 `CHANGELOG.md` 2026-07-28 条目）。仓库内有 `Libxposed102MigrationTest` 守着这条约定 —— 它会断言 `src/main/assets/xposed_init` 不存在、`module.prop` 的版本号正确。
- `ApplicationHook` 只处理 `General.PACKAGE_NAME` 的包，其他进程直接 `return`。

---

## 3. RPC 数据流 ⭐ 最核心

所有业务请求最终都汇聚到 `hook/RequestManager.kt`。这是理解本项目**最关键**的一条链路。

```mermaid
flowchart TB
    A["业务任务<br/>AntForest / AntFarm / ..."] --> B["RequestManager.requestString(...)"]
    B --> C{"executeRpc(methodLog, block)"}

    C --> D{"离线?"}
    D -- 是 --> D1["handleOfflineRecovery()<br/>走 RpcRecoveryPolicy"]
    D -- 否 --> E{"recoveryPolicy.blockReason<br/>== VERIFICATION ?"}
    E -- 是 --> E1["blockedResponse()<br/>不发请求，直接返回占位响应"]
    E -- "验证暂停中" --> E2["触发一次提示<br/>notifyVerificationPause()"]

    E -- 否 --> F["RpcDispatchGate.awaitPermission()<br/>发送前门禁"]
    F --> G["RpcIntervalLimit<br/>接口级限频等待"]
    G --> H["RpcBridge<br/>Old / New"]
    H --> I["支付宝内部 RPC 方法<br/>(已 hook)"]
    I --> J["响应字符串"]

    J --> K{"isEmptyRpcResponse ?"}
    K -- 是 --> K1["RpcRecoveryPolicy<br/>onRequestCompletedWithoutResponse()"]
    K -- 否 --> L{"isVerificationRequired ?<br/>1009 / RPC_VERIFICATION_REQUIRED"}

    L -- 是 --> M["handleVerificationRequired(method)<br/>① 置 VERIFICATION 阻断<br/>② 按账号持久化到宿主私有存储<br/>③ 弹提示 + 通知（一次性）"]
    L -- 否 --> N["RpcRecoveryPolicy.onSuccess()<br/>清除网络失败计数"]

    M --> O["返回 VERIFICATION_REQUIRED_RESPONSE 占位串"]
    N --> P["返回真实响应"]
```

### 3.1 为什么要有 `RequestManager` 这一层

它是**唯一的 RPC 出口**。正因如此，才能在单点做四件事：

| 能力 | 实现 |
| --- | --- |
| 统一拦截安全验证 | `isVerificationRequired()` 识别 `1009` / `RPC_VERIFICATION_REQUIRED` |
| 熔断与恢复 | `RpcRecoveryPolicy` 状态机（网络失败重试 / 离线 / 验证阻断三种原因，返回 `RecoveryDecision`） |
| 发送前门禁 | `RpcDispatchGate.awaitPermission()` —— 在**真正发送之前**再检查一次阻断状态 |
| 接口级限频 | `RpcIntervalLimit` + `DefaultIntervalLimit` / `FixedOrRangeIntervalLimit` |

### 3.2 验证暂停（Verification Pause）的完整语义

这是本项目最容易改错的逻辑，**改之前必读**：

| 维度 | 规则 |
| --- | --- |
| 触发 | 响应里出现 `1009` 或 `RPC_VERIFICATION_REQUIRED` |
| 作用域 | **按账号**，持久化在支付宝的宿主私有存储（`VERIFICATION_PREFS = "sesame_rpc_verification"`），进程重启后仍然生效 |
| 行为 | 阻断后续请求（返回占位响应 `VERIFICATION_REQUIRED_RESPONSE`），**不自动重启恢复** |
| 提示 | 单次阻断只提示一次，另有汇总计数；日志要区分「服务端原始验证」与「本地拦截」 |
| 恢复 | 只能人工：`showVerificationResumeDialog(activity)` / `resumeAfterManualVerification(intent)`，校验账户 + 一次性令牌，串行执行 |
| 重新进入 | 「恢复」会先等待旧业务 Job 退出，再重新开始 |
| 已知局限 | 已发出的请求无法撤回；限流等待与反射调用之间存在极短竞争窗口 —— **不得宣称「绝对零在途请求」** |

> 相关回归测试：`RequestManagerTest`、`RpcRecoveryPolicyTest`、`RpcDispatchGateTest`、`SlideCaptchaRemovalTest`。
> 设计依据：`docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md`（「验证只结束当前调用，不写当天暂停标记」）。

### 3.3 新旧 RPC 桥

```mermaid
flowchart LR
    RM["RequestManager"] --> RB["RpcBridge (interface)"]
    RB --> OLD["OldRpcBridge<br/>老版支付宝"]
    RB --> NEW["NewRpcBridge<br/>新版支付宝"]
    OLD --> V["RpcVersion 判定"]
    NEW --> V
```

- `hook/rpc/bridge/RpcBridge.java` 是接口，`requestString` / `requestObject` 有一整套默认重载（默认 `tryCount=3`，`retryInterval` 视重载而定）
- `RpcVersion` + `VersionHook` 根据支付宝版本决定用哪个桥
- `newRpc` 是用户可见开关（`BaseModel.newRpc`，说明文字是「使用新接口(最低支持 v10.3.96.8100)」）
- `hook/rpc/debug/DebugRpc.java` 用于 RPC 调试页（`RpcDebugActivity`）

### 3.4 Token 抓取

```mermaid
flowchart LR
    AL["支付宝 RPC 调用"] -.hook.-> TP["ReferTokenParser /<br/>FishPondTokenParser"]
    TP --> TK["TokenHooker"]
    TK --> UM["util/maps/UserMap<br/>按账号隔离保存"]
```

- `ReferTokenParser` 处理通用 `positionRequest` 位于 `requestData[0]` 的场景（`CHANGELOG` 2026-07-28 记录过该修复）
- `FishPondTokenParser` 处理福气鱼池的钓鱼风控令牌
- 每个账号独立保存，重启后仍可用

---

## 4. 任务调度数据流

```mermaid
flowchart TB
    subgraph Trigger["触发来源"]
        T1["定时：execAtTimeList / checkInterval"]
        T2["唤醒：wakenAtTimeList + WakeLockManager"]
        T3["广播：com.eg.android.AlipayGphone.sesame.restart / manual_task"]
        T4["手动任务流：ManualTask"]
    end

    T1 --> KEEP["SmartSchedulerManager"]
    T2 --> KEEP
    KEEP --> AH["ApplicationHook"]
    T3 --> AH
    AH --> MAIN["MainTask<br/>(ModelTask 的轻量壳)"]

    MAIN --> CTR["CoroutineTaskRunner.run(isFirst, rounds)"]

    CTR --> MU{"ManualTask.isManualRunning ?"}
    MU -- 是 --> SKIP["跳过本次自动调度"]
    MU -- 否 --> R1["isFirst → ApplicationHook.updateDay()<br/>resetCounters()"]

    R1 --> LOOP["repeat(rounds)<br/>rounds = BaseModel.taskExecutionRounds"]
    LOOP --> OFF{"ApplicationHook.offline ?"}
    OFF -- 是 --> END["结束（不排下一轮）"]
    OFF -- 否 --> FILTER["过滤：isEnable<br/>且不在 onceDaily 黑名单"]

    FILTER --> SEM["Semaphore(MAX_CONCURRENCY = 3)<br/>+ async/awaitAll"]
    SEM --> RUN["ModelTask.runSuspend()"]
    RUN --> SUS["业务逻辑（内部调 RequestManager）"]
    SUS --> STAT["统计耗时 / 成功失败"]
    STAT --> LOOP

    LOOP --> DONE{"onlyOnceDaily ?"}
    DONE -- 是 --> FLAG["Status.setFlagToday('OnceDaily::Finished')<br/>(休眠时段不写标记)"]
    DONE -- 否 --> SUM
    FLAG --> SUM["printExecutionSummary()"]
    SUM --> NEXT{"TaskRunnerPolicy.shouldScheduleNext()"}
    NEXT -- 是 --> SCH["scheduleNext()"]
    NEXT -- 否 --> STOP["结束"]
```

### 4.1 关键参数与约束

| 项 | 值 / 来源 |
| --- | --- |
| 最大并发 | `MAX_CONCURRENCY = 3`（**硬编码**在 `CoroutineTaskRunner`，防请求过频触发风控；注释里标注了「可以做成配置项」） |
| 执行轮数 | `BaseModel.taskExecutionRounds`（默认 **1**，范围 1–99；源码注释：*「1轮就好，没必要2轮」*） |
| 每轮执行间隔 | `BaseModel.checkInterval`（默认 50 分钟） |
| 模块休眠时段 | `BaseModel.modelSleepTime`（默认 `0200-0201`）；休眠时**不写** `OnceDaily::Finished` |
| 超时 | `BaseModel.timeoutRestart` 开关；主流程超时已从原值调整为 **10 分钟** |
| 异常阈值 | `BaseModel.setMaxErrorCount`（默认 8） |
| 异常等待 | `BaseModel.waitWhenException`（默认 60 分钟） |
| 取消语义 | 配置重载 / 超时会**取消并等待实际业务 Job 退出**，避免旧任务与新一轮重叠 |

### 4.2 三类任务的边界（易错点）

用户手动任务、自动主流程、长期蹲点子任务三者共享调度槽，边界已明确划分：

- 手动任务流运行中 → **跳过整轮自动调度**（`ManualTask.isManualRunning`）
- 森林 / 庄园 / 运动主流程 → **占满调度槽直到结束**（否则会出现「启动即释放槽位」导致实际并发超限）
- 独立长期子任务（如蹲点）→ **不纳入主 Job 等待**，避免阻塞整轮结束
- 已运行任务 → 第二轮**不重复启动**

> 回归测试：`TaskRunnerPolicyTest`。并发核对结论见 `docs/superpowers/plans/2026-09-05-verification-and-log-errors.md`。

### 4.3 策略层（为什么到处是 `*Policy`）

任务类里混着 RPC 编排、状态判断和网络副作用，几乎不可测。项目采用的拆分是：

```
ModelTask 子类   ← 只管「什么时候调哪个 RPC、怎么解析响应」
   ↓ 调用
*Policy 对象     ← 纯函数/纯状态机，无副作用，可单测
```

现有策略类举例：`TaskRunnerPolicy`、`RpcRecoveryPolicy`、`FishPondPolicy`、`AntForestResponsePolicy`、`AntForestShieldRetryPolicy`、`EnergyWaitingPolicy`、`EnergyPvpChallengePolicy`、`ChouChouLeSchedulePolicy`(待补)、`MemberTaskProtocol`。

**新增带分支的业务逻辑时，判定部分必须落到策略层** —— 否则它永远不会被测试覆盖。

---

## 5. 配置数据流

本项目最有价值的架构特性：**设置项只定义一次，两套 UI 自动同步。**

```mermaid
flowchart TB
    subgraph Define["① 定义（唯一来源）"]
        MF["ModelField 静态字段<br/>model/modelFieldExt/*.java"]
        MC["ModelConfig<br/>持有 fields 集合"]
        M["Model / ModelTask 子类"]
        M --> MF
        M --> MC
    end

    subgraph Persist["② 持久化"]
        CJ["config_v2.json<br/>/sdcard/Android/media/com.eg.android.AlipayGphone/<br/>sesame-TK/config/&lt;userId&gt;/config_v2.json"]
        CS["customset.json<br/>（自定义设置）"]
        ST["Status / StatusFlags<br/>（运行态标记，如 OnceDaily::Finished）"]
        MC --> CJ
        M --> ST
    end

    subgraph Render["③ 渲染（三种消费者）"]
        DTO["ui/dto/<br/>ModelDto / ModelGroupDto<br/>ModelFieldInfoDto / ModelFieldShowDto"]
        CJ --> DTO
        DTO --> WEBUI["Web UI<br/>HOOK.getTabs() / getModel() / setModel()"]
        DTO --> COMPOSE["Compose UI<br/>SettingsContent + SettingsViewModel"]
    end

    WEBUI -->|"setModel / setField"| MC
    COMPOSE -->|"ModelField 修改"| MC
```

### 5.1 设置项的类型体系

`model/modelFieldExt/` 下的每个类对应一种 UI 控件与一种序列化形式：

| ModelField 类型 | 语义 | Web DTO 的 `type` |
| --- | --- | --- |
| `BooleanModelField` | 开关 | `BOOLEAN` |
| `IntegerModelField` | 整数 | `INTEGER` |
| `StringModelField` | 字符串 | `STRING` |
| `ChoiceModelField` | 单选（配 `expandKey` 选项名数组） | `CHOICE` |
| `SelectModelField` | 多选（好友列表等） | `SELECT` |
| `SelectOneModelField` | 单选（列表型） | `SELECT_ONE` |
| `SelectAndCountModelField` | 选择 + 计数（如「浇水好友 + 次数」） | `SELECT_AND_COUNT` |
| `SelectAndCountOneModelField` | 同上，单选变体 | `SELECT_AND_COUNT_ONE` |
| `ListModelField` | 时间/范围列表（如 `0700,0730`） | `LIST` |
| `TextModelField` | 多行文本 | `TEXT` |
| `EmptyModelField` | 占位（纯展示） | `EMPTY` |

以「切换卡类型」为例，用户在 Web 里看到的就是这么一个对象：

```json
{ "code": "doubleCard", "configValue": "0", "name": "双击卡开关 | 消耗类型", "type": "CHOICE", "expandKey": ["关闭", "所有道具", "限时道具"] }
```

### 5.2 存储布局与迁移

```
/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/
├── config/
│   ├── config_v2.json                  # 默认/全局配置
│   └── <userId>/                       # 按账号隔离
│       ├── config_v2.json
│       ├── customset.json
│       └── friend.json                 # 好友列表快照
└── log/                                # 日志目录
```

- 路径定义在 `util/Files.kt`（`MAIN_DIR` / `CONFIG_DIR` / `LOG_DIR`）
- **自动迁移**：老格式 `config/config_v2-<userId>.json` 在首次读取时会被搬到 `config/<userId>/config_v2.json`，成功后删除旧文件
- 目录监听：`MainViewModel.startConfigDirectoryObserver()` + `util/DirectoryWatcher.kt` 让 UI 感知账号目录变化

### 5.3 UI 模式的选择

配置项 `ui_option`（存 `SharedPreferences`，key 前缀 `sesame-tk`）→ `ConfigRepository` → `UiMode`：

```mermaid
flowchart LR
    P["SharedPreferences<br/>ui_option"] --> CR["ConfigRepository.uiMode<br/>(StateFlow)"]
    CR --> UM{"UiMode"}
    UM -->|"Web (默认)"| A["WebSettingsActivity"]
    UM -->|"New"| B["SettingActivity (Compose)"]
    CR --> TA["UiMode.targetActivity<br/>扩展属性"]
```

`UiMode.fromValue()` 对 `null` / 空串 / 未知值**一律回落到 `Web`** —— 这条由 `UiModeDefaultTest` 保护，不要改。

---

## 6. 内置 HTTP 服务

模块在宿主进程里跑了一个 NanoHTTPD 服务，用于与外部工具（如 `serve-debug/`）通信。

```mermaid
flowchart LR
    EXT["外部工具<br/>serve-debug/main.py"] --> MS["ModuleHttpServer<br/>NanoHTTPD, 0.0.0.0:&lt;port&gt;"]
    MS --> R{"按 uri 路由"}
    R --> H1["/debugHandler<br/>DebugHandler"]
    R --> H2["/getAlipayMiniMark<br/>AlipayMiniMarkHandler"]
    R --> H3["/getAuthCode<br/>AuthCodeHandler"]
    H1 --> BH["BaseHandler<br/>模板方法"]
    H2 --> BH
    H3 --> BH
    BH --> AUTH["verifyToken()<br/>Bearer 或裸 token"]
    BH --> GET["onGet()"]
    BH --> POST["onPost()<br/>body 按 content-length 精确读满 + UTF-8"]
```

- 路由注册在 `ModuleHttpServer.init`，`register(path, handler, description)`
- 启动由 `ModuleHttpServerManager.startIfNeeded()` 负责，端口经 `util/PortUtil.java` 选取
- `BaseHandler` 是模板方法模式：**统一鉴权 + 统一异常兜底 + 统一 JSON 响应**（`ok` / `badRequest` / `unauthorized` / `methodNotAllowed` / `notFound`）。新增接口继承它即可
- Token 为空字符串时**默认放行**（如果不设 token 就等于不鉴权，注意别在生产里留空）
- 另一条反向链路：`BaseModel.sendHookData` + `sendHookDataUrl`（默认 `http://127.0.0.1:9527/hook`）把抓到的数据**主动转发**给 `serve-debug/main.py`

---

## 7. 系统能力边界

| 能力 | 实现 | 说明 |
| --- | --- | --- |
| root / Shizuku 命令 | `service/CommandService.kt` + `aidl/ICommandService.aidl` | 跑在独立 `:command` 进程，`exported=true`；`ShellManager` 封装执行 |
| Shizuku 连接 | `service/LsposedServiceManager.kt` + 手写 `ShizukuProvider` 声明 | Manifest 里注释了原因：解决 `provider is null` |
| 运行时定位 | DexKit（`org.luckypray.dexkit`） | 混淆后找不到类/方法时使用，产物落在 `AssetUtil.dexkitDestFile` |
| 原生校验 | `jniLibs/<abi>/libchecker.so` | **预编译**，无 C++ 源码（`src/main/cpp/` 被 gitignore）；`Detector.loadLibrary("checker")` 加载；加载失败会提示「缺少必要依赖」 |
| 保活 | `hook/keepalive/SmartSchedulerManager` + `util/WakeLockManager.kt` | 定时唤醒、`SCHEDULE_EXACT_ALARM` |
| 隐藏图标 | `IconManager` + Manifest 里的 `activity-alias` | 可隐藏图标；圣诞节自动切到 `MainActivityAliasChristmas` |

---

## 8. 扩展点：改这里通常要动哪几个文件

### 8.1 新增一个设置项（最常见）

```
① model/<你的 Model 类>.kt        加一个 ModelField 静态字段（含默认值、名称、类型）
② （可选）model/modelFieldExt/     只有需要新控件类型时才动
```

就这两步。落盘、两套 UI 渲染、Web DTO 都会自动跟上。若该设置影响任务行为，再回到任务类里读它。

### 8.2 新增一个业务任务

```
① task/<domain>/<Xxx>Task.kt      继承 ModelTask，声明 getFields()（配置项）、check()（是否可跑）、runSuspend()（逻辑）
② task/<domain>/<Xxx>Policy.kt    把分支判定抽出来，纯逻辑
③ task/<domain>/<Xxx>RpcCall.java 该业务的 RPC 方法名与参数封装
④ app/src/test/.../<Xxx>PolicyTest.kt  给策略层写测试
⑤ assets/web/images/icon/model/   放一张与 getIcon() 返回值同名的图标
⑥ CHANGELOG.md                    追加一行
```

RPC 必须经 `RequestManager`，不要自己起网络请求。

### 8.3 新增一个 hook

```
① 用 ReflectionHelper / DexKit 找到目标 Executable
② ModernXposedRuntime.hook(executable, before = { ... }, after = { ... })
③ 验证 XposedEnv.classLoader 已就绪（依赖注入时机，未就绪会 ClassNotFound）
```

### 8.4 新增一个 HTTP 接口

```
① hook/server/handlers/<Xxx>Handler.kt   继承 BaseHandler，实现 onGet / onPost
② ModuleHttpServer.init 里 register("/yourPath", XxxHandler(secretToken), "描述")
```

### 8.5 新增一个 Web 端纯逻辑 JS

```
① assets/web/js/<name>.js        写成 UMD（同时支持 module.exports 与 window 挂载）
② app/src/test/js/<name>.test.js 用 node:test 写合同测试
③ 页面里引用，并在测试里断言 HTML 确实引用了它（见 settings-search-contract 的做法）
```

`settings-search-contract.js` 是这个模式的范本：UMD 导出 + 纯函数 + 被 `node --test` 覆盖 + 测试还校验 `semi_index.html` 真的挂了这个脚本。

---

## 9. 日志与可观测性

| 关注点 | 位置 |
| --- | --- |
| 日志实现 | `util/Log.kt` + `util/Logback.kt`（SLF4J + Logback-Android） |
| 日志文件 | `/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/log/` |
| 记录开关 | `BaseModel.recordLog`（record 镜像总开关） |
| 查看器 | `ui/LogViewerActivity` + `ui/viewmodel/LogViewerViewModel.kt`（Compose，搜索/tag 过滤/仅看错误） |
| 抓包 | `BaseModel.debugMode`（说明：基于新接口）；转发地址 `sendHookDataUrl` |
| 状态栏通知 | `util/Notify.kt`、`BaseModel.enableOnGoing`（开启状态栏禁删） |
| 气泡提示 | `BaseModel.showToast` / `toastOffsetY` |
| 异常通知 | `BaseModel.errNotify` + `setMaxErrorCount` |

### 9.1 日志分类体系（三层）

日志唯一入口是 `Log` object，SLF4J 按 logger 名分文件落盘。写入分类文件时**统一镜像进 record.log**（受 `BaseModel.recordLog` 开关控制），保证「全部日志」聚合语义完整。

**第 1 层 · 业务日志（按功能模块域）**

| 分类 | API | 文件 | 收纳模块 |
| --- | --- | --- | --- |
| 森林 | `Log.forest()` | forest.log | antForest、antCooperate（合种）、reserve（保护地）、EcoProtection（古树）、antDodo（神奇物种） |
| 海洋 | `Log.ocean()` | ocean.log | antOcean（神奇海洋）、antFishPond（福气鱼池） |
| 庄园 | `Log.farm()` | farm.log | antFarm、AntFarmFamily、ChouChouLe（抽抽乐） |
| 果园 | `Log.orchard()` | orchard.log | antOrchard（芭芭农场果树） |
| 新村 | `Log.stall()` | stall.log | antStall（摆摊）、ReadingDada |
| 生活 | `Log.life()` | life.log | antMember、antSports、GreenFinance、Credit2101、AnswerAI、庄园捐步 |
| 其他 | `Log.other()` | other.log | 兜底（TokenHooker、无法归类的零散来源），目标是趋近于空 |

**第 2 层 · 系统日志**

| 分类 | API | 文件 | 定位 |
| --- | --- | --- | --- |
| 运行 | `Log.runtime()` / `Log.runtimeWarn()` | runtime.log | TaskRunner 调度/轮次/执行统计、ModelTask 任务开始结束、NewRpcBridge 状态摘要、SmartSchedulerManager、预唤醒。INFO/WARN 级别 |
| 错误 | `Log.error()` / `Log.printStackTrace()` | error.log | 异常统一出口：ErrorLogPolicy 签名去重 + 60 帧截断 |
| 抓包 | `Log.capture()` | capture.log | 仅 HookUtil 调用 |
| 调试 | `Log.debug()` / `Log.d()` | debug.log | logcat 可见，落盘需主动写 |

**第 3 层 · 聚合视图**：record.log（「全部日志」）= 所有业务/系统写入的镜像流。

新增业务模块一律走 `Log.biz(LogCategory.XXX, tag, msg)` 或对应分类便捷方法；域内子模块用 tag 维度区分（`[tag]: msg` 前缀），不再为小模块单独开文件。

### 9.2 容量策略

| 分类 | 单文件上限 | 总量上限 | 保留 |
| --- | --- | --- | --- |
| ocean / orchard / stall / life / runtime（业务小配额） | 3MB | 16MB | 7 天 |
| record / error / capture / forest / farm / other / debug（核心） | 7MB | 64MB | 7 天 |

`system` / `captcha` 文件名为 LOG_NAMES 预留占位，暂无写入方。

**日志使用约定**（来自 `2026-09-05` 计划）：日志需要区分「服务端原始验证」与「本地拦截」；单次阻断只提示一次并保留汇总计数 —— 避免刷屏，也避免误判。

---

### 9.3 日志查看器的读取架构

查看器面对的是**单文件最大 7MB、且正在被追加写**的日志，所以读取分两阶段：

| 阶段 | 做法 | 目的 |
| --- | --- | --- |
| ① 首屏 | 只读**尾部 256KB**（按行对齐后从第一个换行开始） | 打开即出内容，不必等整份索引 |
| ② 完整索引 | 顺序扫一遍全文件，一遍产出「行偏移 + `[tag]` + 错误行偏移」 | 搜索、tag 过滤、仅看错误 |

实现要点（`util/LogIndexBuilder.kt` + `ui/viewmodel/LogViewerViewModel.kt`）：

- **流式一遍过、零随机读**：旧实现建 tag 索引与数错误行都要「逐行 `seek + readLine`」，
  20 万行就是 40 万次随机读，在 `/sdcard`（FUSE）上要几十秒；
- **顺序扫描必须用独立的流**：与界面逐行读取共用同一个 `RandomAccessFile` 时，
  文件指针会被并发 seek 抢走 → 扫描提前撞 EOF。
  实测 1.2MB / 3436 行的 `error.log` 只索引出 1229~1403 行，且每次结果不同；
- 「仅看错误」直接用索引里的错误行偏移，不再逐行回读；
- 断行缓冲上限 8KB（超长行不整行进内存），行偏移按**实际消费字节数**推进，截断也不会漂。

#### 9.3.1 按天读历史（分片枚举 + 偏移打包）

查看器还能翻看历史日志，靠的是 Logback 已经写在盘上的滚动分片（保留 7 天）：

| 文件 | 含义 |
| --- | --- |
| `log/<name>.log` | 当天活动文件（正在写） |
| `log/bak/<name>-<yyyy-MM-dd>.<i>.log` | 历史分片；`i` 是**同一天内**的分片序号 |

三个必须守住的点：

1. **一天通常是多个分片**（实测 `error-2026-09-24` 有 4 个分片共 30.5 万行），
   所以查看器内部不再按单文件处理，而是「一天 = 一组有序分片」；
2. **分片必须按序号数值排序**（字典序下 `.9` 会排到 `.60` 之后）；
3. **行内没有年份**（encoder 是 `%d{dd日 HH:mm:ss.SS}`），日期只能从**文件名**反推，
   所以 `LogFileHistory.dayKey` 用设备默认时区（与 Logback 写文件名同源）。

多文件如何复用既有单文件模型：把 `(分片序号, 文件内偏移)` **打包成一个 `Long`**
（`packed = (seq shl 40) | offset`，见 `util/LogSource.kt`），于是偏移表、行缓存、
tag 索引、错误行集合全部保持 `<Long>` 不变；打包值天然按 (序号, 偏移) 全局升序，
`takeLast(maxLines)` 也就自动等价于「从最旧的分片开始丢」。

| 关注点 | 位置 | 说明 |
| --- | --- | --- |
| 分片枚举/解析 | `util/LogFileHistory.kt` | 纯逻辑：可用日期倒序、某天分片数值升序、可读目录回退 |
| 多文件随机读 | `util/LogSource.kt` | 偏移打包 + **访问序 LRU 句柄**（默认 4 个，62 个分片不会占满 FD） |
| 多分片索引 | `util/LogDayIndexer.kt` | 尾部优先 + 全量扫描；**从最新分片往前扫，凑够 `maxLines` 即停** |
| 日志目录回退 | `LogViewerViewModel.candidateLogDirs` | 与 `Logback.resolveLogDir` 同序，避免日志落到回退目录时历史为空 |

**为什么必须用独立句柄扫描**：顺序扫描与逐行读取若共用同一个 `RandomAccessFile`，
文件指针会被并发 seek 抢走（实测 3436 行的文件只索引出 1229~1403 行）。

### 9.4 噪音基线：什么该进 record.log

`record.log` 是「全部日志」的镜像流，体积很容易被**每轮必打、值几乎不变**的例行输出吃掉。
约定如下（2026-09-28 按当天 3.7MB / 3.5 万行的构成逐项降级）：

| 该进 record.log | 该进 debug.log（`Log.debug`） |
| --- | --- |
| 业务事件：收取、打卡、领取、任务完成、失败原因 | 每轮例行检查的状态快照（`道具使用检查`、`无需续写`） |
| 调度与轮次摘要（`Log.runtime`） | 逐用户/逐任务的内部指标（`更新用户模式`） |
| 模块耗时行（`TimeCounter.stop`，已去掉装饰分隔线） | 重复命中判断（`已存在且时间相同，跳过添加`、`已在黑名单中`） |
| 状态变化（进出离线、验证暂停、每日一次锁定） | 每轮重读的配置值（`获取 XX 配置`） |

---

## 10. 测试的架构含义

本项目的测试分两层，且**第二层会约束你的重构自由度**：

| 层 | 位置 | 手段 |
| --- | --- | --- |
| 策略单测 | `app/src/test/java/.../*PolicyTest.kt` | JUnit 4 + `org.json`，测纯逻辑 |
| **源码契约测试** | 同上目录下的一批文件 | **直接读主源码文本做断言** |

源码契约测试用 `File("src/main/java/...").readText()`，然后 `assertTrue/assertFalse(text.contains("..."))`。典型代表：

- `Libxposed102MigrationTest` —— 断言 `module.prop` 版本、`java_init.list` 内容、`xposed_init` 不存在
- `EnergyRainCoroutineTest` —— 断言源码里**没有** `ENERGY_RAIN_VERIFICATION_FLAG`
- `RequestManagerTest` / `SlideCaptchaRemovalTest` / `ForestChouChouLeTest` / `TaskRunnerPolicyTest` / `AntMemberTest` / `AntFarmFamilyTest`

**两个直接后果**：

1. 这类测试的工作目录是 `app/` 模块目录，必须经 Gradle（`:app:testDebugUnitTest`）运行；
2. **重命名标识符、删除字符串字面量会直接打挂测试** —— 重构前先搜一下有没有对应断言。

所以架构上它们是「防腐层」：把若干设计决策（只支持 102、验证不写持久标记、滑块服务已移除）固化成了可执行的约束。

---

## 11. 离线 / 暂停状态机与自愈

`ApplicationHook.offline` 一置位，**所有 RPC 都会被本地门控拦下**（`RequestManager.executeRpc` 首行），
所以它既是「保护」也是「停摆」的来源。

### 11.1 什么时候会进入离线

| # | 触发条件 | 落点 | 备注 |
| --- | --- | --- | --- |
| 1 | 网络错误累计超阈值（默认 8，`setMaxErrorCount`） | `NewRpcBridge`（自带计数器） | 错误码/文案命中 `errorMark` / `errorStringMark`，含 `46/48 当前网络不可用` |
| 2 | 收到安全验证（`RPC_VERIFICATION_REQUIRED` 或风控文案） | `RequestManager.handleVerificationRequired` | 同时登记暂停标志、停任务、发通知 |
| 3 | `RequestManager` 侧连续失败达阈值 | `RequestManager.handleFailure` | 熔断兜底 |
| 4 | 登录超时 | `OldRpcBridge.handleLoginTimeout` | 通知文案「登录超时」 |
| 5 | 进程启动时发现上次暂停标志未过期 | `RequestManager.restorePauseState` | 等待人工确认 |

解除只有三条路：**人工「跳过并恢复」**（校验令牌）、**验证暂停满 30 分钟 TTL**（仅在存在验证标志时成立）、
**宿主重新初始化**。`reOpenApp()` 在重开支付宝前也会置离线。

### 11.2 自愈：`OfflineRecoveryPolicy` + 探测看门狗

旧实现的问题是**离线期间没有任何东西再去探测**：主任务见 `offline` 直接返回、蹲点被门控拦下、
`handleOfflineRecovery` 只调度一次 `reOpenApp`。实测 2026-09-28 因此静默了 **52 分钟**
（19:08 → 19:59），期间一个能量球都没收，且蹲点按「顺延 60 秒」刷出约 1700 行日志。

现在：

| 机制 | 行为 |
| --- | --- |
| 探测节奏 | 2 → 5 → 15 → 30 分钟，封顶后固定 30 分钟（`OfflineRecoveryPolicy.probeDelayMs`） |
| 探测内容 | ① 先试 30 分钟 TTL（验证暂停就地解除）；② 否则**绕过门控**发一次轻量主页查询验证链路 |
| 打扰上限 | 重开支付宝最多 1 次；探测 5 次后不再逐次记日志（探测继续） |
| 显式日志 | 进入打 `⛔ 已进入离线`，解除打 `✅ 离线已解除（持续 N 分钟）` |
| 蹲点 | 离线期间**不再定时重试**：任务留在等待列表、只提示一次；离线解除后 `EnergyWaitingManager.onOfflineCleared()` 就地恢复 |

`setOffline` 是所有置位/解除点的**唯一入口**（原先两处直接赋值也改走它），保证日志与看门狗不会被绕过。

### 11.3 与「每日一次」的关系

被限制的任务与离线都有同一个风险：**任务没做成却不再尝试**。所以「每日一次」只在**服务端返回成功**时锁定，
并把首次成功写进台账供第二天核对 —— 详见 [`daily-once-tasks.md`](daily-once-tasks.md)。

## 12. 任务健康状态机（观测用）

前面所有可观测手段都是**事后计数**：`TaskRunCounter` 记逐次结果、`TaskStatisticsRecorder` 记当日汇总、
`DailyTaskLogRecorder` 记完成台账。它们能回答「今天做了几次」，却回答不了
**「此刻到底在不在正常跑」** —— 而这正是「明明有能量却不动」这类问题唯一需要的答案。

### 12.1 为什么状态要「现算」而不是「落定」

任务跑在**支付宝进程**、界面在**模块 App 进程**，内存态跨不过去。如果落盘时把 `RUNNING` 写死，
那么宿主进程一旦被杀，文件就永远停在 `RUNNING`，界面看不出区别。

所以约定：**宿主只写时间戳与计数，状态由读的一方现算**。

```text
宿主进程（任务里）                界面进程（主页卡片）
  onStart / onProgress              读 task_health.json
  onSuccess / onFailure        →    按 TaskHealthPolicy.evaluate 现算
  onWaiting / onBlocked             「已 N 分钟无进展」
        ↓ 节流落盘
   config/<uid>/task_health.json
```

这样一来，「宿主被杀、文件停在 RUNNING」这种最典型的卡死，界面照样能显示成**卡住**。

### 12.2 七个状态与判定优先级

| 状态 | 含义 | 是否需关注 |
| --- | --- | --- |
| `IDLE` 未开始 | 今天还没跑过 | 否 |
| `RUNNING` 进行中 | 正在跑且有进展 | 否 |
| `WAITING` 等待中 | 等能量成熟 / 等蹲点时间，属正常 | 否 |
| `OK` 正常 | 最近一次成功 | 否 |
| `FAILED` 失败 | 最近一次失败 | **是** |
| `BLOCKED` 已暂停 | 被离线 / 安全验证挡住（§11） | **是** |
| `STALLED` 卡住 | 进行中但超过阈值**没有任何进展** | **是** |

`evaluate` 的优先级是 **被暂停 > 卡住 > 记录的状态**。被暂停时**不能**报卡住 ——
那是离线/验证造成的，处置方式完全不同（前者等自愈、后者要人工验证），混在一起会误导排查。

「卡住」的口径刻意**只看无进展、不看有没有失败**：失败会照常记 `FAILED`，
而卡住的特征恰恰是「既没失败也没进展」—— 这类沉默停摆最难发现（§11.2 那次静默 52 分钟）。

### 12.3 监测范围与埋点

| ID | 显示名 | 埋点位置 |
| --- | --- | --- |
| `forest.main` | 森林主任务 | `AntForest` 一轮的 `onStart` / `onSuccess` / `onFailure`，**外加每个相位边界（`runSuspend` 的 `phase()`）与每次收好友的 `onProgress`** |
| `forest.collect` | 收能量 | `AntForest.collectEnergy` 的 `onStart` / `onProgress`（按好友） / `onSuccess` |
| `forest.waiting` | 蹲点收取 | `EnergyWaitingManager` 的 `onWaiting`（带上**预计收取时刻** `expectedAt`）/ 完成 / 终止 / 重试 |

只挑了这三个「主链路」任务，不做全量 —— 卡片要一眼看完，铺满几十项反而失去意义。
`blockedReason` 是**全局**的（离线拦的是所有 RPC），所以离线时三项都会显示「已暂停」。

### 12.4 五条硬约束

1. **只观测，不干预**。不做任何重试、取消、重启 —— 判定错了最多显示不准，不会把正常任务打断。
   自动处置（类似 `StallActionPolicy`）留给以后，且必须有用户开关。
2. **状态变化才写日志，进展事件节流落盘**（`MIN_WRITE_INTERVAL_MS = 3s`）。收能量是按好友逐个
   `onProgress` 的，不节流就会按人写一次文件、按人打一行日志。
   ⚠️ 反过来也要注意：**进展信号要用两级逼近"整轮耗时"** —— `forest.main` 一轮可能跑 30+ 分钟，
   只在整轮结束时打一个点，会让面板在 5 分钟后误报「卡住」（2026-10-01 实测），
   所以它同时接收相位级（`phase()`）与按好友（`collectEnergy` 回调）两类进展；
   单相位自身超过阈值时仍会短暂显示卡住（已知边界）。
3. **等预计时刻的任务：`expectedAt` 要参与"取最新基准"，不能单独成立一条优先规则**
   （`TaskHealthPolicy.aliveBaseOf`）。
   蹲点收取在等待期间**本来就不上报**（协程在 delay 到能量成熟时刻），
   按"多久没进展"判会让它等得越久越像卡住（2026-10-01 实测误报 23 分钟），所以必须把
   **预计动作时刻**也当作一条"还活着的证据"混进基准里。
   ⚠️ 但这里踩过一次坑：第一版写成"等待态只要有 `expectedAt` 就只看它"，
   于是 `expectedAt` 一落到过去，**之后所有上报全被吞掉** ——
   蹲点收取失败后每 5 秒一次的重试、`已终止` 的上报都不顶用，
   任务明明在跑却稳定显示「卡住」，比改之前更糟。
   `expectedAt` 只在**等待态**参与取基准（其它状态一律按 0 处理），
   否则一条残留着未来预计时刻的记录会让正在跑的任务永远判不出卡住。
   四种情形都对：预计时刻在未来 → 判不出卡住（达到目的）；
   已过且无后续动作 → 按"预计时刻 + 阈值"判卡住；
   已过但期间有上报 → 基准被更近的上报顶掉，退回旧口径。
   ⚠️ 已知边界：宿主在**等待期间**被杀时，最长会被「等待中」屏蔽到 `expectedAt + 阈值`
   （`EnergyWaitingPolicy.validate` 已挡掉跨天与 >8h 的任务，跨零点由 `belongsToToday` 兜底），
   这与"单相位超阈值仍会短暂显示卡住"是同一类取舍，不额外兜底。
4. **`blockedReason` 必须跟着落盘，并且要能清干净**。它是**全局**的（离线拦的是所有 RPC），
   但只活在内存里；不给每一项带上再写出去，界面就只看到「卡住」而看不到「已暂停」——
   这两者处置方式完全不同（等自愈 vs 人工过验证），显示错会把排查往错误方向带。
   ⚠️ 反面同样成立：写入之后**必须始终以全局值覆盖**（空就写空），
   否则解除后盘上会留残影、界面永远显示「已暂停」（2026-10-01 实测）。
   兜底不变量：**在线 ⇒ 没有任何暂停原因**（`shouldClearStaleBlock` + `healStaleBlock`，
   进度事件与写盘时都会就地纠正）。

   ⚠️ **这条兜底只在宿主进程生效**：`healStaleBlock` 读的是 `ApplicationHook.offline`，
   而界面侧的 `readAll` 跑在模块 App 进程，既拿不到这个标志、也不应该有改写宿主状态的能力。
   所以若宿主进程在「离线已落盘、解除未执行」之间被杀，且当天不再产生任何任务事件，
   界面仍会读到盘上的「离线中」。这是刻意保留的取舍：**界面以盘上最后已知状态为准**，
   宁可显示上一次的已知状态，也不要让展示层去猜宿主此刻是否在线。
   排查时留意：状态陈旧 + 日志末尾没有该 uid 的新事件 ⇒ 大概率是进程已死，而非仍在暂停。
   ⚠️ 另一条配套约束：「暂停原因」有且只有一个写入来源（`TaskHealthMonitor.onBlocked`），
   且必须在 `setOffline(true)` **之后**调用。若将来新增不经过 `setOffline` 的暂停种类，
   它会在上报后立刻被上述兜底判成残影清掉，务必同步改 `shouldClearStaleBlock` 的判据
   （正确做法是给原因带来源标记、按同一来源判定，而不是继续用"全局离线"一个布尔量代指所有暂停）。
5. **以磁盘为准，跨进程接续 + 跨天清痕**（`syncDailyState`）。
   宿主进程一重启内存就是空的，直接落盘会把刚才跑出来的状态冲掉 —— 而「重启支付宝看看」
   正是最常用的排查动作，抹掉历史等于自断线索。所以每次写盘前先把磁盘上属于**今天**的记录接进内存；
   归属按记录里的最新时间戳判定（`Status.hasFlagToday` 那套 `flagList` 跨天会自己清，这里的文件不会）。

### 12.5 界面读不到文件的一个坑

模块 App 进程里**没有支付宝登录态**，`UserMap.currentUid` 是空的，直接按 uid 拼路径会读到空文件、
页面永远显示「还没有记录」。所以 `readAll` 在 uid 缺失时退回扫 `config/` 下的账号目录，
取**最近改过**的那份（`resolveHealthFile`）。主页优先传 `DataStore` 里的当前账号，兜底才走扫描。
