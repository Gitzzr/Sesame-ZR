# 项目整体说明

> Sesame-ZR · 面向使用者的能力概览请见 [`user-guide.md`](user-guide.md) · 架构细节见 [`architecture.md`](architecture.md)

---

## 1. 一句话定位

**Sesame-ZR 是一个注入支付宝的 Xposed 模块，把「蚂蚁系」日常任务自动化。**

它本身不是一个独立 App。安装后由 Xposed/LSPosed 框架加载进支付宝进程，借助支付宝自身的 RPC 通道完成收能量、喂小鸡、做任务等操作，同时提供一个设置界面供用户开关每一项能力。

| 项 | 值 |
| --- | --- |
| 模块类型 | Xposed 模块（**libxposed API 102**，仅支持现代 API 入口） |
| 作用域 | `com.eg.android.AlipayGphone`（**仅支付宝**） |
| 包名 | `fansirsqi.xposed.sesame` |
| 当前版本 | `0.9.9`（`versionName` 硬编码；`versionCode` = git 提交数） |
| 授权 | 见仓库根 `LICENSE` |
| 上游 | fork 自 [Sesame-TK](https://github.com/Fansirsqi/Sesame-TK) |

> ⚠️ **合规前提**：本项目自动化的是用户自己账号下的日常操作。它**不自动完成、不绕过**支付宝的安全验证 —— 触发风控时会停止并提示人工处理。这是硬性设计约束，不是可选项。

---

## 2. 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.3（主体，217 个 `.kt`）+ Java 17（96 个 `.java`，历史模块与兼容垫片） |
| Hook 框架 | libxposed API 102（`app/libs/*.aar`，仓库内自带）+ `hiddenapibypass` 6.1 |
| 构建 | Gradle 9.5.0（wrapper）+ AGP 9.3.0 + Kotlin Compose 插件 + `rikka-tools-refine` |
| 编译目标 | `compileSdk 37` / `minSdk 26`（Android 8.0）/ `targetSdk 36` / build-tools `37.0.0` |
| UI（新版） | Jetpack Compose + Material 3 + `viewBinding`（共存）+ AIDL |
| UI（默认版） | 内嵌 WebView 页面：Vue 3 + Semi UI / Varlet / Vant（`assets/web`） |
| 网络/序列化 | OkHttp 5、Jackson 2.20、kotlinx-serialization、fastjson（残留）、NanoHTTPD（内置 HTTP 服务） |
| 异步 | Kotlin 协程（`CoroutineTaskRunner`，纯协程调度，非传统线程池） |
| 系统能力 | Shizuku（`dev.rikka.shizuku`）、root shell、DexKit（运行时找类找方法） |
| 日志 | SLF4J + Logback-Android |
| 测试 | JUnit 4 + `org.json`（Kotlin/Java 单测）、Node 内置 `node:test`（Web JS 合同测试） |
| 调试台 | Python 3.12+ / FastAPI / SQLAlchemy / Frida / uv（`serve-debug/`） |

---

## 3. 能力范围（业务域）

自动化逻辑按业务域分目录放在 `app/src/main/java/fansirsqi/xposed/sesame/task/`：

| 目录 | 面向的能力 | 设置页分组 |
| --- | --- | --- |
| `antForest/` | 蚂蚁森林：收能量、好友能量、PK 榜、能量雨、道具（双击卡/加速器/保护罩/炸弹卡/隐身卡）、森林寻宝、1V1 能量挑战、绿色行动 | 森林 |
| `antFarm/` | 小鸡庄园：喂食、雇佣、遣返、捐蛋、麦子、抽抽乐、小鸡乐园限时活动、家庭 | 庄园 |
| `antOcean/` | 神奇海洋：日常任务、清理、万能拼图、保护沙滩、AI 摸鱼 | 海洋 |
| `antFishPond/` | 福气鱼池：签到、钓鱼、福利鱼、每日次数限制 | 森林 |
| `antOrchard/` | 蚂蚁农场：任务奖励、施肥 | 农场 |
| `antStall/` | 蚂蚁新村：摆摊收摊、贴罚单、丢肥料、自动任务、下一村 | 新村 |
| `antDodo/` | 神奇物种：帮抽卡、送卡、道具、合成图鉴 | 神奇物种 |
| `antCooperate/` | 合种：浇水、召唤队友、真爱合种 | 合种 |
| `antSports/` | 运动：行走路线、宝箱、运动任务、捐步、健康岛、抢好友、训练好友、文体中心 | 运动 |
| `antMember/` | 会员：签到、任务、积分兑换、芝麻信用、蚂蚁保、黄金票、游戏中心、商家服务、安心豆、年度回顾 | 会员 |
| `reserve/` | 保护地巡护 | 保护地 |
| `greenFinance/` | 绿色经营 | 绿色经营 |
| `EcoProtection/` | 生态保护相关 | — |
| `AnswerAI/` | AI 答题 | AI答题 |
| `customTasks/` | 自定义任务、手动任务流（`ManualTask`） | — |
| `other/` | 杂项（`credit2101`、`haojia` 等） | — |

另外还有：

- `model/BaseModel.kt` —— **全局开关**：执行间隔、定时执行/唤醒时间表、任务轮数、模块休眠时段、超时重启、异常阈值、新接口开关、抓包、Hook 数据转发、后台权限、气泡提示、语言等
- `task/ModelTask.kt` —— 任务基类，**同时是配置单元与执行单元**
- 每个业务域通常有配套的 `<Domain>RpcCall.java` / `<Domain>Protocol.kt`（RPC 协议层）和 `*Policy.kt`（可测策略层）

---

## 4. 目录结构

```
Sesame-ZR/
├── AGENTS.md                  # AI 入口：硬规则、环境现状、已知阻塞
├── DESIGN.md                  # 视觉规则（Compose / XML / Web 三套体系）
├── TODO.md                    # 任务、优先级、进度
├── CODEBUDDY.md               # 给 CodeBuddy Code 的仓库导览
├── CHANGELOG.md               # 用户可见改动（日期 | 模块 | 说明）
├── README.md                  # 面向访客的简介
├── AppIdMap.txt               # 支付宝小程序/业务 ID → 名称映射
│
├── app/                       # 唯一的 Gradle 模块
│   ├── build.gradle.kts       # 构建配置（版本号、ABI 分包、依赖、CMake 条件启用）
│   ├── libs/                  # libxposed API 102 的 aar（api / interface / service）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # 模块元数据、作用域、Activity/Service/Provider
│       │   ├── aidl/                    # ICommandService / ICallback / IStatusListener
│       │   ├── assets/web/              # ★ Web 设置页（HTML/CSS/JS/字体/图标）
│       │   ├── jniLibs/<abi>/libchecker.so  # 预编译原生校验库（无 C++ 源码）
│       │   ├── java/fansirsqi/xposed/sesame/
│       │   │   ├── hook/                # 注入、RPC、保活、HTTP 服务、Token
│       │   │   │   ├── modern/          # ★ 入口 HookEntry + ModernXposedRuntime
│       │   │   │   ├── rpc/bridge/      # 新旧 RPC 桥
│       │   │   │   ├── rpc/intervallimit/  # 接口级限频
│       │   │   │   ├── server/handlers/ # HTTP 处理器
│       │   │   │   ├── keepalive/       # SmartSchedulerManager
│       │   │   │   └── internal/        # 支付宝内部能力适配
│       │   │   ├── task/                # ★ 业务自动化（按域分目录）
│       │   │   ├── model/               # ★ 设置项 schema
│       │   │   ├── ui/                  # ★ 两套 UI + 主题 + 组件 + ViewModel
│       │   │   ├── data/                # Config / Status / General / RuntimeInfo
│       │   │   ├── entity/              # 数据实体
│       │   │   ├── net/                 # 网络层
│       │   │   ├── service/             # CommandService / ShellManager / Shizuku
│       │   │   └── util/                # 日志、文件、时间、地图、权限…
│       │   └── res/                     # XML 资源（日/夜配色、样式、图标）
│       └── test/
│           ├── java/                    # Kotlin/Java 单元测试
│           └── js/                      # Web 端 JS 合同测试
│
├── docs/
│   ├── project-overview.md    # ← 本文件
│   ├── architecture.md        # 架构与数据流
│   ├── user-guide.md          # 面向使用者的功能说明
│   ├── development.md         # 开发方式、命令、回归清单
│   ├── component-api.md       # 组件与对外 API
│   └── superpowers/           # 设计稿 (specs/) 与实施计划 (plans/)
│       ├── specs/2026-08-01-energy-rain-verification-retry-design.md
│       ├── specs/2026-08-03-rob-expand-energy-post-collect-design.md
│       ├── plans/2026-08-01-energy-rain-verification-retry.md
│       ├── plans/2026-08-03-rob-expand-energy-post-collect.md
│       └── plans/2026-09-05-verification-and-log-errors.md
│
├── serve-debug/               # Python 抓包/调试台（uv 管理）
│   ├── main.py                # FastAPI：接收 Hook 转发数据（/hook）
│   ├── webui.py               # 本地跑 Web 设置页，改版式用
│   ├── models.py / schemas.py / config.py
│   └── README.MD              # 使用方法
│
├── gradle/libs.versions.toml  # 版本目录（所有依赖版本集中在此）
└── .github/workflows/         # android.yml（唯一流水线：PR 校验 + main 发布构建）
```

---

## 5. 交付形态

### 5.1 APK

一次构建产出 **5 个 APK**（ABI 分包 + 通用包），文件名格式 `Sesame-ZR-<abi>-<versionName>.apk`：

| 产物 | 适用设备 |
| --- | --- |
| `arm64-v8a` | 64 位 ARM —— **主流新手机，发布只带这个** |
| `armeabi-v7a` | 32 位 ARM（老设备） |
| `x86` / `x86_64` | 模拟器 / 部分平板 |
| `universal` | 通用包，体积最大 |

`versionCode` 取自 `git rev-list --count HEAD` —— 每次提交都会让它自增，因此**构建机必须能跑 git 且是完整克隆**（CI 用 `fetch-depth: 0`）。

### 5.2 发布链路

| 触发 | workflow | 行为 |
| --- | --- | --- |
| PR 到 `main` | `ci.yml` | `assembleDebug` + 单测（Kotlin/JVM + Web JS）—— **合入门禁，不签名** |
| push 到 `main` | `ci.yml` | 同上，验证主干始终可发布 |
| 发布 Release | `android.yml` | `assembleRelease` → 签名 → 上传各 ABI artifact → 同步到 Release |

流水线按职责拆分：校验（`ci.yml`）不含签名，所以能稳定变绿；发版（`android.yml`）依赖 4 个 secrets，只在发 Release 时触发。

签名依赖 4 个 secrets：`ANDROID_SIGNING_KEY`、`ANDROID_KEY_ALIAS`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_PASSWORD`。

> 两个 workflow **都不执行单元测试**，只构建。这是 `TODO.md` P3 里记录的改进点。

---

## 6. 文档地图

| 我想… | 看 |
| --- | --- |
| 让 AI 快速上手 / 知道硬规则 | [`../AGENTS.md`](../AGENTS.md) |
| 了解项目定位与结构 | 本文件 |
| 理解架构与数据流 | [`architecture.md`](architecture.md) |
| 知道每个设置项是干什么的 | [`user-guide.md`](user-guide.md) |
| 搭环境、跑命令、回归 | [`development.md`](development.md) |
| 改界面 / 视觉规范 | [`../DESIGN.md`](../DESIGN.md) |
| 复用组件 / 对接 API | [`component-api.md`](component-api.md) |
| 看当前该做什么 | [`../TODO.md`](../TODO.md) |
| 看某个功能为什么这么设计 | `superpowers/specs/` + `superpowers/plans/` |
