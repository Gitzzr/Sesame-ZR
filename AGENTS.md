# AGENTS.md

> AI 进入本项目首先需要知道的信息。人类读者请从 [`docs/project-overview.md`](docs/project-overview.md) 开始。

## 这是什么项目

**Sesame-ZR** —— 一个注入支付宝的 libxposed (API 102) 模块，用 Xposed/LSPosed 框架加载后自动完成蚂蚁森林、小鸡庄园、神奇海洋、蚂蚁新村、会员任务等「蚂蚁系」日常操作。fork 自 [Sesame-TK](https://github.com/Fansirsqi/Sesame-TK)。

- 语言 / 构成：Kotlin 为主（217 个 `.kt`）+ Java 兼容层（96 个 `.java`），另有约 20 个内嵌 Web 页面（`assets/web`）和一套 Python 调试台（`serve-debug/`）
- 包名：`fansirsqi.xposed.sesame`（同时是 namespace 与 applicationId）
- 关于用户：中文项目，**所有注释、提交信息、文档一律中文**

## 开工前必读的 8 条硬规则

1. **主干分支是 `main`，而且只有这一条长期分支**（GitHub Flow）。CI（`.github/workflows/android.yml`）只认它。
   改动一律走「特性分支 → PR → **Squash merge** 进 `main`」，**禁止直接往 `main` 推**。分支模型与命名见 [`docs/development.md`](docs/development.md) §5。
2. **不自动完成、不绕过支付宝的安全验证。** 程序能控制的只有「停止请求、调度、接口兼容」，不要把重启应用当作验证通过。
3. **识别到安全验证响应时，只结束当前这一次调用流程**，禁止写入跨调用、跨当天的持久化暂停标记。参见 [`docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md`](docs/superpowers/specs/2026-08-01-energy-rain-verification-retry-design.md)。
4. **所有 hook 必须经 `ModernXposedRuntime.hook(...)` / `replaceWithConstant(...)`**，不要在别处直接调 libxposed 原始 API。参数与返回值由 `HookInvocation` 统一封装。
5. **可测的判定逻辑必须抽成 `*Policy.kt` / `*Policy` 对象**，任务类只留 RPC 编排。既有测试打的全是策略类；把分支逻辑写死在任务里就等于放弃测试。
6. **协程里捕获异常时必须重新抛出 `CancellationException`**，不要把它当普通业务异常吞掉。
7. **文件一律 UTF-8 无 BOM**，代码注释中文。
8. **不要改** `app/build.gradle.kts` 里被注释标注「不要随意改这个了答应我」的 CMake 版本与「答应我就这样吧」的 `ndkVersion`。

## 环境现状（2026-09-21 在开发机实测，已配通）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 仓库位置 | ⚠️ **必须是纯 ASCII 路径** | 现位于 `C:\Users\Administrator\WorkBuddy\senmayi\Sesame-ZR`。AGP 会直接拒绝含非 ASCII 字符的项目路径（报 `Your project path contains non-ASCII characters`），所以**不要把仓库放回带中文的目录** |
| JDK | ✅ 17.0.7 @ `C:\env\Java\jdk-17_windows-x64` | `JAVA_HOME` 已指向它，`%JAVA_HOME%\bin` 已置于用户 PATH 最前 |
| Android SDK | ✅ `C:\env\android-sdk` | platform **37.0** + build-tools **37.0.0** + platform-tools；`ANDROID_HOME` / `ANDROID_SDK_ROOT` 已设；`local.properties` 已写（该文件在 `.gitignore` 里，不要提交） |
| Gradle | ✅ 9.5.0（wrapper） | 发行包已预置进 wrapper 缓存，**不要用系统 gradle** |
| 已跑通 | `:app:compileDebugKotlin` | 2026-09-21 实测 `BUILD SUCCESSFUL in 4m 9s` |
| GitHub 推送 | ⚠️ **需经 Clash 代理** | 本机直连 github.com 不通（浏览器能上是走了系统代理，git 默认不读）。仓库已配 `http.https://github.com.proxy = http://127.0.0.1:7897`（**只对 github.com 生效**，不影响 cnb.cool/gitee）。报 `Could not connect to server` 就先检查 Clash 是否在跑 |

> **wrapper 缓存是手动预置的。** `services.gradle.org` 的发行包会 302 跳转到 GitHub，本机访问 GitHub 不通。
> 缓存位置 `~/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/`，zip 由腾讯镜像
> `https://mirrors.cloud.tencent.com/gradle/gradle-9.5.0-bin.zip` 取得，sha256 与官方
> `services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256` 一致。缓存若被清掉照此重建，
> **不要改 `gradle-wrapper.properties` 的 `distributionUrl`**。

环境变量改动只对**新起的进程**生效；改完要重开终端（或 `export`/`$env:` 临时覆盖）才看得到。

> 曾记录过一个「JDK 的 Unix 域临时路径导致 Gradle 回环连接失败」的坑，需要在环境里加
> `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`。**本次在 JDK 17 + ASCII 路径下未复现**，
> 保留备查。真要加时**只改环境变量，不要动 `gradle.properties`**。

## ⚠️ 已知阻塞：测试编译目前是坏的

`app/src/test/java/fansirsqi/xposed/sesame/task/antFarm/ChouChouLeSchedulePolicyTest.kt` 引用了 **主源码中并不存在**的 `ChouChouLeScheduleAction` 与 `ChouChouLeSchedulePolicy`（提交 `579aae63` 只加了测试，没加实现）。

后果：`./gradlew :app:testDebugUnitTest` 会**编译失败**，整个测试任务跑不起来。这不是测试用例失败，是编译不过。

**接手时的第一件事应该是二选一**：

- 补上 `ChouChouLeScheduleAction` / `ChouChouLeSchedulePolicy` 的最小实现（`RUN` / `WAIT_FOR_TIME` / `SKIP_COMPLETED` 三态 + `actionFor(completedToday, timeReached)`），或
- 先移除/`@Ignore` 该测试文件。

详见 [`TODO.md`](TODO.md)。

## 常用命令速查

```bash
./gradlew :app:assembleDebug                  # 调试包
./gradlew :app:assembleRelease                # 发布包（CI 走这个）
./gradlew :app:testDebugUnitTest              # 全部单元测试
./gradlew :app:compileDebugKotlin             # 只编译，最快的验证手段
./gradlew :app:lintDebug                      # lint
./gradlew clean

# 跑单个测试类
./gradlew.bat :app:testDebugUnitTest --tests "fansirsqi.xposed.sesame.task.antForest.EnergyRainCoroutineTest" --no-daemon

# Web 端 JS 合同测试（Node 内置 node:test，无 npm 依赖）
node --test app/src/test/js/
```

Windows 下把 `./gradlew` 换成 `./gradlew.bat`。完整说明见 [`docs/development.md`](docs/development.md)。

## 改代码前先定位：代码地图

| 你要改什么 | 去哪 |
| --- | --- |
| 模块入口、注入时机 | `hook/modern/HookEntry.kt` |
| hook 的封装方式、全局运行时 | `hook/modern/ModernXposedRuntime.kt`、`XposedEnv.kt` |
| 模块主流程编排（Token / RPC / 保活 / 定时 / 广播） | `hook/ApplicationHook.kt` ⭐ 脊柱 |
| RPC 请求与拦截 | `hook/RequestManager.kt`、`RpcDispatchGate.kt`、`RpcRecoveryPolicy.kt` |
| RPC 底层桥接 | `hook/rpc/bridge/{Old,New}RpcBridge.java` |
| 本地 HTTP 服务与接口 | `hook/server/ModuleHttpServer*.kt`、`hook/server/handlers/` |
| 任务调度与并发 | `task/CoroutineTaskRunner.kt`（在 `TaskRunner.kt` 内）、`task/ModelTask.kt` |
| 某个业务的自动化逻辑 | `task/<业务域>/`（`antForest`、`antFarm`、`antOcean` …） |
| 纯逻辑/可测策略 | 同目录下的 `*Policy.kt` |
| 设置项的新增与默认值 | `model/BaseModel.kt` 及各业务 `*Model` 类里的 `ModelField` |
| 设置项数据类型 | `model/modelFieldExt/*ModelField.java` |
| 配置读写与落盘 | `data/Config.java`、`data/Status.kt`、`ui/repository/ConfigRepository.kt`、`util/Files.kt` |
| Compose 界面 | `ui/screen/`、`ui/viewmodel/`、`ui/theme/` |
| Web 设置页 | `app/src/main/assets/web/*.html` + `ui/WebSettingsActivity.java`（桥对象名 `HOOK`） |
| 主题与视觉规则 | [`DESIGN.md`](DESIGN.md) |
| 组件与对外 API | [`docs/component-api.md`](docs/component-api.md) |

更完整的架构与数据流见 [`docs/architecture.md`](docs/architecture.md)。

## 文档索引

| 文档 | 内容 | 位置 |
| --- | --- | --- |
| `AGENTS.md` | 本文件。AI 入口、硬规则、环境现状、已知阻塞 | 根目录 |
| `DESIGN.md` | 视觉规则：Compose 主题 + Web 主题、色彩/字体/间距/圆角 | 根目录 |
| `TODO.md` | 当前任务、优先级、开发进度 | 根目录 |
| [`docs/project-overview.md`](docs/project-overview.md) | 项目整体说明：定位、能力、技术栈、目录结构 | docs/ |
| [`docs/architecture.md`](docs/architecture.md) | 架构与数据流：注入链路、RPC 链路、任务调度、配置同步 | docs/ |
| [`docs/user-guide.md`](docs/user-guide.md) | 面向使用者的功能说明与设置项含义 | docs/ |
| [`docs/development.md`](docs/development.md) | 开发方式、命令、回归清单、常见故障 | docs/ |
| [`docs/component-api.md`](docs/component-api.md) | 组件与对外 API：Compose 组件、Web JS 合同、HTTP 接口、ModelField | docs/ |
| [`CODEBUDDY.md`](CODEBUDDY.md) | 给 CodeBuddy Code 的精简版仓库导览 | 根目录 |
| [`CHANGELOG.md`](CHANGELOG.md) | 用户可见改动的日期表 | 根目录 |

## 交付前自查

- [ ] 走 [`docs/development.md`](docs/development.md) 里的回归清单
- [ ] 用户可见的改动，已在 `CHANGELOG.md` 表格追加一行（`| 日期 | 模块 | 功能改动 |`）
- [ ] 非平凡功能，已补 `docs/superpowers/specs/` 设计稿 + `docs/superpowers/plans/` 实施计划，并同步勾选状态
- [ ] 新文件是 UTF-8 无 BOM
- [ ] 若新增需要入库的 `.txt` / `.json`，记得 `git add -f`（`.gitignore` 有宽泛的 `*.txt`、`*.json` 规则）
