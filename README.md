# Sesame-ZR

[![PR Check](https://github.com/Gitzzr/Sesame-ZR/actions/workflows/ci.yml/badge.svg)](https://github.com/Gitzzr/Sesame-ZR/actions/workflows/ci.yml)

一个 **Android 端的 libxposed 模块**：被框架加载进宿主 App 进程后，研究并复用宿主自身的 RPC 通道，
把一批重复性的日常操作编排成可配置的任务。

仓库把「支付宝」及其内置的蚂蚁森林 / 小鸡庄园 / 神奇海洋 / 新村 / 会员等**作为练习对象**——
它们恰好提供了一个足够复杂的真实环境：私有 RPC 协议、多版本接口的兼容、Token 生命周期、
限速与熔断、跨进程状态和 UI 配置同步，这些都是教科书之外的题目。

> 本项目用于**移动端逆向与框架技术的学习、研究与交流**，代码以 GPL-3.0 开放。
> 详细的职责边界见文末 [行为边界](#行为边界)。

---

## 目录

- [技术形态](#技术形态)
- [分层结构](#分层结构)
- [几个值得一说的实现点](#几个值得一说的实现点)
- [仓库结构](#仓库结构)
- [构建](#构建)
- [质量保障](#质量保障)
- [行为边界](#行为边界)
- [文档](#文档)
- [许可与上游](#许可与上游)

---

## 技术形态

模块本身不是一个独立运行的 App。它的运行时形态是：**作为一段代码被注入目标进程**，
因此它天然要面对两件事——**没有自己的独立进程**，以及 **只能复用宿主已有的能力**。

| 项 | 值 |
| --- | --- |
| 框架接口 | libxposed API 102（`minApiVersion=101` / `targetApiVersion=102` / `staticScope=true`） |
| 支持范围 | 仅现代 API 入口；旧版 API 82/100 的兼容层与 `xposed_init` 已移除 |
| 作用域 | 只有 `com.eg.android.AlipayGphone` |
| 包名 | `fansirsqi.xposed.sesame`（同时是 namespace 与 applicationId） |
| 语言 | Kotlin 为主（275 个 `.kt`）+ Java 兼容层（96 个 `.java`） |
| 编译目标 | `compileSdk 37` / `minSdk 26`（Android 8.0）/ `targetSdk 36` |
| 版本 | `versionName = 0.9.9`；`versionCode` 取自「自 2020-01-01 起的分钟数」，保证单调递增 |
| 许可 | GPL-3.0（见 [`LICENSE`](LICENSE)） |

运行时形态如下：

```text
【宿主进程：com.eg.android.AlipayGphone】

  Xposed / LSPosed 框架
        │  onModuleLoaded / onPackageReady
        ▼
  HookEntry ─────► ModernXposedRuntime（保存 XposedInterface 单例）
        │
        ▼
  ApplicationHook                    ← 唯一的编排者
        │
        ├── RequestManager ──► RpcDispatchGate ──► RpcBridge ──► 宿主内部 RPC
        │        ├── RpcRecoveryPolicy（有界熔断与恢复）
        │        └── RpcIntervalLimit（接口级限频）
        │
        ├── CoroutineTaskRunner ──► ModelTask 子类（各业务域）
        │                              └── *Policy.kt（纯逻辑，可单测）
        │
        ├── ModuleHttpServer（NanoHTTPD）
        ├── SmartSchedulerManager（定时与进程存活）
        │
        └── Config / Status / Log ──► 外存 sdcard
                                        ▲
【模块自身进程】                        │
                                        │
  设置 UI（Web / Compose）──────────────┘   ← 只读写同一份外存
```

**关键点：宿主进程与模块 App 进程之间没有内存共享。** 任何需要跨进程可见的东西都必须落盘，
这直接决定了配置、`Status` 标记、任务健康快照等一律以文件为交换介质。

---

## 分层结构

| 层 | 目录 | 职责 |
| --- | --- | --- |
| 注入与运行时 | `hook/modern/` | `HookEntry`、`ModernXposedRuntime`（`XposedInterface` 单例）、`XposedEnv` |
| 编排 | `hook/` | `ApplicationHook`（Token / RPC / 保活 / 定时 / HTTP 服务） |
| RPC | `hook/RequestManager.kt`、`hook/rpc/bridge/` | 统一入口、发送前门禁、熔断恢复、接口级限频、新旧桥接 |
| 任务调度 | `task/TaskRunner.kt`（内含 `CoroutineTaskRunner`）、`task/ModelTask.kt` | 纯协程的并发调度；任务基类同时是配置单元与执行单元 |
| 业务逻辑 | `task/<业务域>/` | 16 个域；每个域通常配 `<Domain>RpcCall.java` + `*Policy.kt` |
| 配置 schema | `model/`、`model/modelFieldExt/` | `ModelField` 描述设置项类型与默认值 |
| 界面 | `ui/`、`app/src/main/assets/web/` | Jetpack Compose（新版）与内嵌 WebView（默认） |
| 可观测性 | `util/Log*.kt`、`task/TaskHealth*` | 分层日志、按天翻看、任务健康状态机 |

---

## 几个值得一说的实现点

### 1. hook 只走一个出口

所有 hook 统一经 `ModernXposedRuntime.hook(...)` / `replaceWithConstant(...)`，
由它设置 `ExceptionMode.PROTECTIVE`（hook 自身的异常不至于拖垮宿主），
并把调用压成 `HookInvocation`（`executable` / `thisObject` / `initialArgs` / `result` / `proceedCall`）。
绕过这层直接调 libxposed，就会丢掉这两层保护——仓库里有 `Libxposed102MigrationTest` 守着这条约定。

### 2. RPC 不是一个函数，是一条带状态的链路

`RequestManager.executeRpc` 前后串了三件事：

- `RpcDispatchGate` —— 发送前统一门禁（离线、暂停、验证态都在这里拦下）
- `RpcRecoveryPolicy` —— 有限的本地熔断与**有界**恢复，而不是无限重试
- `RpcIntervalLimit` —— 接口级限频

对外屏蔽了新旧两套桥（`OldRpcBridge` / `NewRpcBridge`）的差异，业务任务只见 protocol 层。

### 3. 判定逻辑一律抽成 `*Policy.kt`

主源码里有 **24 个 `*Policy` 对象**，它们是纯函数/无副作用的状态判定：
`EnergyWaitingResultPolicy`、`OfflineRecoveryPolicy`、`TaskHealthPolicy`、`ServerBusyPolicy`……

这么做只有一个理由：**可测**。58 个 Kotlin 单测文件打的几乎全是策略类。
把分支写死在任务编排里，等于主动放弃测试能力。

### 4. 状态「现算」而不是「落定」

这是本仓库反复出现的一个模式。以最近加的任务健康状态机为例：
宿主进程只把**时间戳与计数**写到 `config/<uid>/task_health.json`，
状态由读的一方按 `evaluate(snapshot, now, timeout)` 现算。

好处很直接：宿主进程被杀时，文件会停在 `RUNNING`——如果状态当初就写死在文件里，
界面永远看不出区别；现算则能如实显示成「卡住 · 已 N 分钟无进展」。
同样的思路也用在「以磁盘为准」的写盘策略上：进程重启后内存是空的，
写盘前先按记录时间戳把属于今天的数据接回来，避免把已跑完的状态抹成「未开始」。

### 5. 配置 schema 驱动两套 UI

设置项不是手写的：它由 `ModelField`（含 `IntegerModelField` / `TextModelField` 等子类型）
声明类型、默认值与范围，Compose 界面与 Web 界面只是这份 schema 的两个渲染器。
新增一个设置项通常不需要写任何 UI 代码。

### 6. 日志与可观测性

日志走 SLF4J + Logback-Android，按「业务 / 系统 / 汇总」三层分类，
每个文件独立滚动（`SizeAndTimeBasedRollingPolicy`，保留 7 天）。
查看器采用**尾部优先**（先读尾 256KB 立刻出内容）+ 后台全量索引，打开几十万行的大文件不再转圈；
历史日志按天合并，可在最近 7 天之间翻页。

此外还有一个只读的状态报告层：
- `TaskStatisticsRecorder` —— 当日/按账号的结构化累计
- `DailyOnceAudit` —— 「一天只应成功一次」的动作台账，供次日核对
- `TaskHealthMonitor` —— 前文的任务健康状态机

三者都**只观测不干预**，不做自动重启或自动恢复。

### 7. 协程取消不是业务异常

任务跑在 `CoroutineTaskRunner`（见 `task/TaskRunner.kt`）里。所有 `catch (Throwable)` 处都会显式重抛 `CancellationException`——
把它当普通异常吞掉，会让取消失效、协程泄漏、任务停不下来。这是仓库里的硬规则之一。

---

## 仓库结构

```
Sesame-ZR/
├── app/                          # 唯一的 Gradle 模块
│   ├── libs/                     # libxposed API 102 的 aar
│   └── src/
│       ├── main/java/fansirsqi/xposed/sesame/
│       │   ├── hook/             # 注入、RPC 链路、保活、HTTP 服务、Token
│       │   ├── task/             # 业务编排（按域分目录）
│       │   ├── model/            # 设置项 schema
│       │   ├── ui/               # 两套 UI + 主题 + ViewModel
│       │   ├── data/ entity/ net/ service/ util/
│       │   └── resources/META-INF/xposed/   # 入口声明与作用域
│       ├── main/assets/web/      # 内嵌 Web 设置页
│       └── test/                 # java（Kotlin/JUnit 4）、js（node:test）
├── docs/                         # 说明与设计文档
│   └── superpowers/              # specs（当前设计）/ plans（实施过程）
├── serve-debug/                  # Python 抓包 / 调试台（FastAPI）
├── changelog.d/                  # CHANGELOG 待汇总片段
└── scripts/changelog_fragments.py
```

---

## 构建

| 依赖 | 版本 |
| --- | --- |
| JDK | 17+ |
| Gradle | 9.5.0（**用 wrapper**，不要系统 gradle） |
| AGP | 9.3.0 |
| Android SDK | platform 37.0 + build-tools 37.0.0 |

```bash
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 发布包
./gradlew :app:testDebugUnitTest    # 全部单元测试
./gradlew :app:compileDebugKotlin   # 只编译，最快
```

Windows 下把 `./gradlew` 换成 `./gradlew.bat`。

> 项目路径必须是**纯 ASCII**。AGP 会直接拒绝含非 ASCII 字符的路径
> （`Your project path contains non-ASCII characters`），配置阶段就中止。

产出按 ABI 分包（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`）外加一个 `universal` 包。

完整命令、环境排障与回归清单见 [`docs/development.md`](docs/development.md)。

---

## 质量保障

两条流水线按职责拆开：**校验**（`ci.yml`）只做构建与测试、不签名，所以能稳定变绿；
**发版**（`android.yml`）才处理签名与分发。

PR 门禁包含：

- `assembleDebug` + Kotlin/Java 单测 + Web JS 合同测试（`node --test`，无 npm 依赖）
- `changelog.d/` 片段格式校验（用户可见的改动需附片段，内部重构不需要）
- 未附片段时给出提醒，但不判失败

几个贯穿仓库的约定：

- 主干只有 `main`，改动一律「特性分支 → PR → Squash merge」，禁止直推
- `CHANGELOG.md` 不直接编辑，由 `scripts/changelog_fragments.py --write` 统一汇总
- 文件 UTF-8 无 BOM，注释与提交信息一律中文
- 非平凡功能在 `docs/superpowers/specs/` 与 `plans/` 留一对文档，一个功能一对，后续轮次追加章节而不新开文件

---

## 行为边界

这部分不是免责套话，而是写在 `AGENTS.md` 里、有测试守护的实现约束：

1. **不自动完成、不代替用户通过宿主返回的安全验证。**
   收到验证类响应时只停止当前链路并提示人工处理；恢复必须有用户可见的解除入口，且受 TTL 上界约束（≤ 30 分钟），
   到期必须重新发起请求，不缓存「已验证」结论。**不存在「用户无从解除、只能卸载宿主应用」的状态。**
2. **作用域仅限目标 App 的一个包名**，其它进程直接返回；不做多 App 适配，不收集任何与功能无关的数据。
3. 保留接口级限频与有界的熔断退避，避免异常时对宿主侧造成非预期的请求压力。
4. 所有自动化能力**默认关闭**——数十个业务域的开关几乎全部默认是关的，由使用者逐个显式开启。

使用时请注意：

- 本项目仅用于**自有账号**下的操作与技术研究。使用时请遵守所在地区法律法规、以及目标平台的用户协议与服务条款。
- 项目与支付宝、蚂蚁集团及其各业务线**没有任何隶属、合作或授权关系**；相关名称、商标归各自权利人所有。
- 仓库不提供现成的二进制分发、群组或任何形式的推广；不鼓励、也不引导任何违反平台规则的使用方式。
- 因使用、修改或二次分发本仓库代码所产生的后果，由使用者自行承担。

---

## 文档

| 我想… | 看 |
| --- | --- |
| 了解项目定位与结构 | [`docs/project-overview.md`](docs/project-overview.md) |
| 理解注入链路、RPC 数据流、调度与状态机 | [`docs/architecture.md`](docs/architecture.md) |
| 知道每个设置项是什么意思 | [`docs/user-guide.md`](docs/user-guide.md) |
| 搭环境、跑命令、回归清单 | [`docs/development.md`](docs/development.md) |
| 复用组件 / 对接 API | [`docs/component-api.md`](docs/component-api.md) |
| 看视觉规范 | [`DESIGN.md`](DESIGN.md) |
| 看某个功能为什么这么设计 | `docs/superpowers/specs/` + `plans/` |
| 给 AI 的仓库入口（硬规则、代码地图） | [`AGENTS.md`](AGENTS.md) |
| 看用户可见改动 | [`CHANGELOG.md`](CHANGELOG.md) |

---

## 许可与上游

代码以 **GNU GPL-3.0** 授权，见 [`LICENSE`](LICENSE)。

本项目 fork 自 [Sesame-TK](https://github.com/Fansirsqi/Sesame-TK)（Fansirsqi），
在此之上做了结构重整与若干行为加固：统一 hook 出口、把判定逻辑抽成可测的策略层、
重做日志与可观测性、加固 RPC 链路的暂停/恢复语义。感谢上游的工作。

二次分发请保留 GPL-3.0 许可与署名。
