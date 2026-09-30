# 组件与对外 API

> 本文件是「可复用的东西」的索引：Compose 组件、View 对话框、Web 端 JS 合同、Native↔JS 桥、内置 HTTP 接口、配置模型 API。
>
> 视觉规范见 [`../DESIGN.md`](../DESIGN.md)；数据流见 [`architecture.md`](architecture.md)。

---

## 0. 总览：本项目有 7 类「组件」

| 类型 | 位置 | 复用方式 |
| --- | --- | --- |
| **A. Compose 组件** | `ui/screen/components/`、`ui/screen/card/`、`ui/compose/` | `@Composable` 函数，直接调用 |
| **B. View / XML 对话框** | `ui/widget/` | 静态 `show(...)` 工厂方法 |
| **C. Web 端纯逻辑 JS** | `assets/web/js/settings-search-contract.js` | UMD 模块，浏览器 + Node 双用 |
| **D. Native ↔ JS 桥** | `ui/WebSettingsActivity.java` → `window.HOOK` / `window.Android` | Web 页面通过 `window.HOOK.*` 调 Kotlin |
| **E. 内置 HTTP 接口** | `hook/server/handlers/` | 外部工具 HTTP 调用，跑在 `0.0.0.0:<port>` |
| **F. 配置模型 API** | `model/ModelField` + `modelFieldExt/` | 声明式设置项，两套 UI 自动渲染 |
| **G. 日志读取工具** | `util/Log*.kt` | 纯逻辑 + `java.io`，可直接 JVM 单测 |

---

## A. Compose 组件

### A1. 设置类组件

#### `SettingsItem`

```kotlin
@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    isDanger: Boolean = false,
    onClick: () -> Unit
)
```

通用可点击设置条目。圆角 `12.dp`，内边距 `16.dp`，底色 `surfaceContainerLow`。
`isDanger = true` 时图标变 `error` 色（用于「清除数据」这类破坏性操作）。

图标与文字间距 **`8.dp`**。

#### `SettingsSwitchItem`

```kotlin
@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
)
```

带开关的设置条目。**整个条目可点击且会切换开关**（`onClick = { onCheckedChange(!checked) }`），
同时内部 `Switch` 也绑定 `onCheckedChange` —— 注意这两者会各触发一次回调，调用方应保证幂等。

图标与文字间距 **`16.dp`**（与 `SettingsItem` 的 `8.dp` 不一致，属于历史差异，改动前先确认设计意图）。

> ⚠️ **`SettingsComponents.kt` 缺少 `package` 声明**（文件首行直接是 `import`），因此 `SettingsSwitchItem` 落在 Kotlin 的**默认包**而不是 `fansirsqi.xposed.sesame.ui.screen.components`。虽然 Kotlin 允许根包声明被无 import 引用，所以能编过，但这是明显的笔误 —— 顺手补 `package` 行即可（已记入 `TODO.md`）。

### A2. 通用组件

#### `MenuButton`

```kotlin
@Composable
fun MenuButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
)
```

首页功能入口按钮。`FilledTonalButton`，高 `72.dp`，圆角 `16.dp`，图标 `28.dp`，
底色 `surfaceVariant`、内容色 `primary`、海拔 `2.dp`，图标在上文字在下。

#### `HtmlText`

```kotlin
@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier)
```

用 `AndroidView` 包 `TextView` + `HtmlCompat.fromHtml` 渲染 HTML，链接可点击且颜色取 `primary`。
**用于展示带 `<a>`、`<font color>` 的说明文本。** 只支持行内标签，不支持块级布局。

#### `CommonAlertDialog`

```kotlin
@Composable
fun CommonAlertDialog(
    showDialog: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String,                                  // 允许 HTML
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    confirmText: String = "确认",
    dismissText: String = "取消",
    confirmButtonColor: Color = MaterialTheme.colorScheme.primary,
    showCancelButton: Boolean = true
)
```

标准对话框。`text` 会经 `parseHtml()` 转成 `AnnotatedString`，因此可以传 `<font color="red">…</font>`。
`showCancelButton = false` 时 `dismissButton` 传 `null`（不是空 lambda），这样才真正不渲染取消按钮。

#### `UserItemCard`

```kotlin
@Composable
fun UserItemCard(user: UserEntity, onClick: () -> Unit)
```

账号列表条目：左侧头像图标（`40.dp`，`primary` 色）→ 中间 `showName` + `account` → 右侧箭头。
`showName` 为空时显示「未知用户」。

#### `UserSelectionDialog`

```kotlin
@Composable
fun UserSelectionDialog(
    userList: List<UserEntity>,
    onDismissRequest: () -> Unit,
    onUserSelected: (UserEntity) -> Unit
)
```

账号选择对话框（标题「账号设置」）。**`userList` 为空时直接 `return`，不渲染** —— 调用方无需自己判断。

### A3. 卡片（`ui/screen/card/`）

| 组件 | 签名要点 | 语义 |
| --- | --- | --- |
| `ModuleStatusCard` | `(status: MainViewModel.ModuleStatus, expanded: Boolean, onClick)` | 模块激活状态。容器色按状态映射：`Activated → primaryContainer`、`NotActivated → errorContainer`、`Loading → surfaceVariant` |
| `OneWordCard` | `(oneWord, isLoading, onClick, onLongClick = null)` | 每日一句。最小高 `112.dp`，圆角 `12.dp`，`isLoading` 时禁用点击 |
| `RpcItemCard` | `(item: RpcDebugEntity, onRun, onEdit, onDelete, onCopy)` | RPC 调试条目，描述可展开 |

### A4. 其他界面级组件

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| `RpcDialogHandler` | `components/RpcDialogHandler.kt` | RPC 调试的参数编辑对话框，支持从剪贴板解析 JSON 字段（`viewModel.parseJsonFields`）、JSON 格式化（`viewModel.tryFormatJson`） |
| `ManualTaskItem` | `components/ManualTaskItem.kt` | 手动任务条目，带「拼手速模式 / 局数 / 特殊食品数量」等内联参数，全部通过回调外抛 |
| `DeviceInfoCard` | `screen/InfoCard.kt` | 设备信息展示，入参 `Map<String, String>` |
| `ExtendItemCard` | `screen/ExtendScreen.kt` | 扩展页条目 |
| `LogLineItem` | `screen/LogViewerScreen.kt` | 单行日志，带搜索高亮（`searchQuery` + 字号 + 文字色） |
| `DraggableScrollbar` | `screen/LogViewerScreen.kt` | 日志长列表的可拖拽滚动条 |
| `BottomNavItem` | `navigation/BottomNavItem.kt` | **sealed class**，三个成员：`Home("home","主页")`、`Logs("logs","日志")`、`Settings("settings","设置")`。新增底部页签从这里加 |

### A5. 扩展函数（`ui/extension/`）

```kotlin
// UiExtensions.kt
fun Context.openUrl(url: String)                          // 打开浏览器，无浏览器时 Toast 提示
fun joinQQGroup(context: Context)                         // 唤起 QQ 群，失败回落网页
fun Context.performNavigationToSettings(user: UserEntity) // 按当前 UiMode 跳到对应设置页
val UiMode.targetActivity: Class<*>                       // Web → WebSettingsActivity；New → SettingActivity

// HtmlConverter.kt
fun String.parseHtml(): AnnotatedString                   // HTML → Compose 富文本
                                                          // 支持 <font color> / <b> / <i> / <u>

// ComposeBridge.kt
object NativeComposeBridge {
    @JvmStatic fun showAlertDialog(...)         // 当前为空实现（有意屏蔽原生启动免责弹窗）
    @JvmStatic fun showAlertAfterDelay(...)     // 同上
}
```

`NativeComposeBridge` 是给 **C++/原生侧调用**的静态入口，目前**故意留空**（用于压掉启动免责声明弹窗与延迟退出）。不要「顺手补上实现」。

---

## B. View / XML 对话框（`ui/widget/`）

供非 Compose 的 Activity 使用。全部是静态工厂方法，不返回实例。

### B1. `ListDialog` —— 好友/列表选择器

```java
public enum ListType { ... }

// 基于 ModelField 的 6 个重载
static void show(Context, CharSequence title, SelectOneModelField, ListType)
static void show(Context, CharSequence title, SelectAndCountOneModelField, ListType)
static void show(Context, CharSequence title, SelectModelField) throws JSONException
static void show(Context, CharSequence title, SelectAndCountModelField)
static void show(Context, CharSequence title, SelectModelField, ListType) throws JSONException
static void show(Context, CharSequence title, SelectAndCountModelField, ListType)

// 基于原始实体列表的重载
static void show(Context, CharSequence title, List<? extends MapperEntity>,
                 SelectModelFieldFunc, Boolean hasCount)
static void show(Context, CharSequence title, List<? extends MapperEntity>,
                 SelectModelFieldFunc, Boolean hasCount, ListType)
```

内置能力：搜索框、上一个/下一个匹配、全选、反选、批量处理区、带计数（`hasCount`）。

### B2. `StringDialog` —— 文本输入/展示

```java
static void showEditDialog(Context, CharSequence title, ModelField<?> modelField)
static void showReadDialog(Context, CharSequence title, ModelField<?> modelField)
static void showReadDialog(Context, CharSequence title, ModelField<?> modelField, String msg)
static void showAlertDialog(Context c, String title, String msg, String positiveButton)
static AlertDialog showSelectionDialog(Context c, String title, CharSequence[] items, ...)
```

### B3. `ChoiceDialog` —— 单选

```java
static void show(Context context, CharSequence title, ChoiceModelField choiceModelField)
```

配合 `modelFieldExt/ChoiceModelField` 使用（对应 Web 端的 `CHOICE` 类型与 `expandKey` 选项数组）。

### B4. Adapter（`ui/adapter/`）

| 类 | 用途 |
| --- | --- |
| `ContentPagerAdapter.java` | 设置页的分组内容页 |
| `TabAdapter.java` | 顶部分组页签 |
| `ListAdapter.java` | `ListDialog` 的列表 |
| `OptionsAdapter.java` | 选项列表 |
| `RpcDebugAdapter.kt` | RPC 调试列表（Compose 侧） |

---

## C. Web 端纯逻辑 JS：`SettingsSearchContract`

**文件**：`app/src/main/assets/web/js/settings-search-contract.js`
**测试**：`app/src/test/js/settings-search-contract.test.js`（`node --test app/src/test/js/*.test.js`）

UMD 模块，同时支持 `module.exports`（Node）与 `window.SettingsSearchContract`（浏览器）。

```js
// 按关键词过滤"模块页签 + 设置项"，纯函数、不修改入参
filterSettingsModels(
    tabs: Array<{ modelCode: string, modelName: string }>,
    modelsMap: Record<string, Array<{ code, name, desc }>>,
    query: string
): Array<{ tab, fields }>

// 搜索态下决定哪个模块仍保持展开；无匹配返回 undefined
visibleSettingsActiveKey(filteredModels: Array<{ tab, fields }>, activeKey: string): string | undefined
```

### 匹配规则（测试已固化，改之前先看测试）

| 输入 | 行为 |
| --- | --- |
| 空串 / 纯空白 | 返回**全部**模块及其**全部**字段（等价于未搜索状态） |
| 命中模块名或模块 code | 返回该模块的**全部**字段 |
| 命中字段的 `name` / `code` / `desc` | **只返回命中的字段**，保留其所属模块 |
| 无命中 | 返回 `[]` |
| 大小写与首尾空白 | 一律忽略（`trim()` + `toLocaleLowerCase()`） |

**不修改入参**（测试会断言入参 JSON 前后一致），且对非数组 / 非对象入参做了安全兜底。

### 页面接入合同

`settings-search-contract.test.js` 还会断言 `semi_index.html` 满足：

- 引用了 `settings-search-contract.js`
- 调用了 `filterSettingsModels(`
- 存在 `placeholder="搜索模块或设置项"`
- 存在空态文案 `未找到匹配设置`

改搜索框文案或引用方式会直接打挂这个测试。

---

## D. Native ↔ JS 桥（`WebSettingsActivity`）

桥通过 `webView.addJavascriptInterface(new WebViewCallback(), "HOOK")` 注册，
因此 Web 页面里通过 **`window.HOOK.*`** 调用。返回值统一是 **JSON 字符串**（不是对象），页面侧需要自己 `JSON.parse`。

### D1. `window.HOOK` 接口一览

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `getTabs()` | JSON 数组 | 模块页签列表（`ModelGroupDto` / `ModelDto` 序列化） |
| `getGroup()` | JSON 数组 | 分组列表 |
| `getModelByGroup(groupCode)` | JSON 数组 | 取某分组下的模型（`ModelDto`） |
| `setModelByGroup(groupCode, modelsValue)` | JSON 字符串 | 批量保存某分组的模型 |
| `getModel(modelCode)` | JSON | 取单个模型的字段值 |
| `setModel(modelCode, fieldsValue)` | JSON 字符串 | 保存单个模型 |
| `getField(modelCode, fieldCode)` | JSON 字符串 | 读单个字段 |
| `setField(modelCode, fieldCode, fieldValue)` | JSON 字符串 | 写单个字段 |
| `isNightMode()` | `boolean` | 是否夜间模式（读 `Configuration.UI_MODE_NIGHT_MASK`） |
| `getBuildInfo()` | `String` | `applicationId:versionName`，如 `fansirsqi.xposed.sesame:0.9.9` |
| `saveOnExit()` | `boolean` | 退出前落盘（内部切主线程执行） |
| `Log(log)` | `void` | 把前端日志写进模块日志（前缀「设置：」） |

### D2. `window.Android` 接口

| 方法 | 说明 |
| --- | --- |
| `onBackPressed()` | 触发 WebView 返回（`canGoBack()` 时后退） |
| `onExit()` | 关闭设置页 |

> `onBackPressed` / `onExit` 只在 `index.html`（旧版）里被调用；`semi_index.html` 只用了 `window.HOOK.*` + `window.Android.onExit()`。新增页面时按需接入。

### D3. 调用示例

```js
const tabs = JSON.parse(window.HOOK.getTabs());
const model = JSON.parse(window.HOOK.getModel('AntForest'));
window.HOOK.setField('AntForest', 'collectEnergy', 'true');
if (window.HOOK.isNightMode()) { /* ... */ }
```

---

## E. 内置 HTTP 接口

服务由 `ModuleHttpServer`（NanoHTTPD）提供，**绑定 `0.0.0.0`**，路由在 `ModuleHttpServer.init` 中注册。

### E1. 扩展点：`HttpHandler` / `BaseHandler`

```kotlin
interface HttpHandler {
    fun handle(session: IHTTPSession, body: String? = null): Response
}
```

```kotlin
abstract class BaseHandler(private val secretToken: String) : HttpHandler {
    // 模板方法：禁止 override
    final override fun handle(session, body): Response
    //   ├─ verifyToken(session)   —— 失败返回 401
    //   ├─ GET  → onGet(session)      （默认 405）
    //   ├─ POST → onPost(session,body)（默认 405）
    //   └─ 异常 → 500 JSON

    open fun onGet(session): Response
    open fun onPost(session, body: String?): Response

    // 响应工具
    protected fun json(status: Response.Status, data: Any): Response
    protected fun ok(data: Any): Response
    protected fun badRequest(message: String): Response
    protected fun unauthorized(): Response
    protected fun methodNotAllowed(): Response
    protected fun notFound(): Response
}
```

**鉴权规则**：`secretToken` 为空白 → **直接放行**（等于不鉴权）；否则要求请求头
`Authorization: Bearer <token>` **或** 直接 `Authorization: <token>`，精确比较。

注册方式：

```kotlin
// hook/server/ModuleHttpServer.kt 的 init 块
register("/yourPath", YourHandler(secretToken), "描述")
```

### E2. 内置接口

#### `POST /debugHandler` —— 转发一次 RPC

```jsonc
// 请求体（RpcRequest）
{ "methodName": "com.alipay.xxx.facade.yyy", "requestData": { /* 参数 */ } }
```

| 情形 | 响应 |
| --- | --- |
| 空 body | `400 {"status":"error","message":"Empty body"}` |
| JSON 非法 | `400 {"status":"error","message":"Invalid JSON: ..."}` |
| `methodName` 或参数为空 | `400 {"status":"error","message":"Fields cannot be empty"}` |
| 下游返回空 | `200 {"status":"empty"}` |
| 成功 | `200` + **下游 RPC 原始响应体**（不是包一层 JSON） |
| RPC 抛异常 | `400 {"status":"error","message":"RPC Error: ..."}` |

> 这个 handler 继承 `BaseHandler`，**会校验 token**。

#### `GET /getAlipayMiniMark?appid=<id>&version=<ver>`

获取支付宝小程序标记。参数缺失返回 `400 {"error":"参数缺失，请提供appid和version参数"}`；
成功返回 `{"success":true,"miniMark":"..."}`（实现：`AlipayMiniMarkHelper.getAlipayMiniMark`）。

#### `GET /getAuthCode?appId=<id>`

获取 OAuth2 授权码。参数缺失返回 `400 {"error":"参数缺失，请提供appId参数"}`；
成功返回 `{"success":true,"authCode":"..."}`（实现：`AuthCodeHelper.getAuthCode`）。

> ⚠️ **鉴权不一致（安全注意）**：`AlipayMiniMarkHandler` 与 `AuthCodeHandler` **直接实现 `HttpHandler`，不继承 `BaseHandler`**，因此**完全不做 token 校验**，同时服务监听 `0.0.0.0`。只有 `/debugHandler` 受 token 保护。若这三条链路都要暴露到不可信网络，需要统一到 `BaseHandler`（已记入 `TODO.md`）。

### E3. 反向链路：模块主动上报

`BaseModel.sendHookData`（默认 `false`）+ `sendHookDataUrl`（默认 `http://127.0.0.1:9527/hook`）会把抓到的数据 POST 给外部服务（`serve-debug/main.py` 的 `/hook`）。

---

## F. 配置模型 API

### F1. `ModelField` 类型表

设置项的唯一来源。在 Model 类里加一个静态字段即可，**落盘与两套 UI 会自动跟上**。

| 实现类 | 语义 | Web DTO `type` |
| --- | --- | --- |
| `BooleanModelField` | 开关 | `BOOLEAN` |
| `IntegerModelField` | 整数 | `INTEGER` |
| `StringModelField` | 单行字符串 | `STRING` |
| `TextModelField` | 多行文本 | `TEXT` |
| `ChoiceModelField` | 单选，配 `expandKey` 选项名数组 | `CHOICE` |
| `SelectModelField` | 多选列表 | `SELECT` |
| `SelectOneModelField` | 单选列表 | `SELECT_ONE` |
| `SelectAndCountModelField` | 选择 + 计数 | `SELECT_AND_COUNT` |
| `SelectAndCountOneModelField` | 选择 + 计数（单选） | `SELECT_AND_COUNT_ONE` |
| `ListModelField` | 时间/范围列表 | `LIST` |
| `EmptyModelField` | 占位（纯展示） | `EMPTY` |

### F2. Web 端看到的字段对象

```json
{
  "code": "doubleCard",
  "configValue": "0",
  "name": "双击卡开关 | 消耗类型",
  "desc": "可选描述",
  "type": "CHOICE",
  "expandKey": ["关闭", "所有道具", "限时道具"]
}
```

`name` 里的 `|` 是**前端约定**：前半段是主标题、后半段是副标题（如「双击卡开关 | 消耗类型」）。

### F3. DTO（`ui/dto/`）

| 类 | 用途 |
| --- | --- |
| `ModelGroupDto` | 分组（页签） |
| `ModelDto` | 一个模型（模块）及其字段 |
| `ModelFieldShowDto` | 给前端展示的字段（含 `type` / `expandKey` / `desc`） |
| `ModelFieldInfoDto` | 字段信息 |

全部用 Jackson 序列化后交给 Web 端。

### F4. `ConfigRepository`

```kotlin
object ConfigRepository {
    val uiMode: StateFlow<UiMode>          // 当前 UI 模式（默认 UiMode.Web）
    fun init(context: Context, prefKey: String)
    fun setUiMode(mode: UiMode)            // 写入 SharedPreferences(key = "ui_option")
}
```

### F5. `UiMode`

```kotlin
enum class UiMode(val value: String) {
    Web("web"),   // 默认
    New("new");

    companion object {
        fun fromValue(value: String?): UiMode   // null / "" / 未知 一律回落 Web
    }
}
```

**不要改这个回落行为** —— `UiModeDefaultTest` 明确断言了「缺失空白或未知配置默认使用第二版界面」。

---

## G. 日志读取工具（`util/Log*`）

日志查看器用的一组工具。**全部不依赖 Android API**，所以能在 JVM 单测里直接跑
（这也是 `LogSource` 用 `LinkedHashMap` 自己实现 LRU、而不是用 `android.util.LruCache` 的原因）。

### G1. `LogFileHistory` —— 按天枚举历史分片

```kotlin
data class Partition(val file: File, val shardIndex: Int)   // 活动文件 shardIndex = -1
data class ParsedShard(val logName: String, val date: String, val index: Int)

fun parseShardName(fileName: String, expectLogName: String? = null): ParsedShard?
fun dayKey(now: Long = System.currentTimeMillis()): String                   // 设备默认时区
fun isToday(date: String, now: Long = System.currentTimeMillis()): Boolean
fun availableDates(logDir: File, logName: String, now: Long = ...): List<String>   // 倒序
fun partitionsOf(logDir: File, logName: String, date: String, now: Long): List<Partition>
fun resolveReadableLogDir(candidates: List<File>, logName: String): File?
```

- 分片命名：`<logName>-<yyyy-MM-dd>.<i>.log`（`i` 是同一天内的**分片序号**）
- `partitionsOf` 按序号**数值**升序；`date` 为今天时把活动文件 `<logName>.log` 追加在最后
- 日期只能从**文件名**反推 —— 日志行里的时间戳没有年份

### G2. `LogSource` —— 多文件 = 单一偏移空间

```kotlin
class LogSource(partitions: List<Partition>, maxOpen: Int = 4) : Closeable {
    fun readLineAt(packed: Long): String?
    fun files(): List<File>
    fun base(seq: Int): Long
    companion object {
        fun baseOf(seq: Int): Long   // seq shl 40
        fun seqOf(packed: Long): Int
        fun offOf(packed: Long): Long
    }
}
```

把 `(分片序号, 文件内偏移)` 打包成一个 `Long`，使上层的偏移表 / 行缓存 / tag 索引 /
错误行集合全都保持 `<Long>`；打包值天然按 (序号, 偏移) 升序。句柄用访问序 LRU 限量。

### G3. `LogDayIndexer` —— 多分片索引

```kotlin
data class IndexResult(offsets, tags, errorOffsets, truncated)
fun readTail(partitions, tailBytes): IndexResult
fun scan(partitions, bufferBytes, maxLines, isActive): IndexResult
```

`scan` **从最新分片往前扫**，凑够 `maxLines` 即停（更旧的反正会被丢掉），
`truncated` 告知上层「有内容没保留」，由界面如实提示。

### G4. `LogIndexBuilder` —— 单文件流式索引

```kotlin
class LogIndexBuilder(startOffset: Long = 0L) {
    fun feed(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun finish()
    fun offsets(): List<Long>; fun tags(): Map<Long, String>; fun errorOffsets(): Set<Long>
}
```

一遍过产出「行偏移 + `[tag]` + 错误行偏移」，**零随机读**（旧实现逐行 `seek+readLine`）。

### G5. 约定

- 顺序扫描**必须用独立流**，不要复用 `LogSource` 的句柄 —— 文件指针会被并发 seek 抢走。
- 新增 logName 时记得在 `LogCatalog.LABELS` 补中文名，否则标题会显示「<name> 日志」。

---

## H. 任务健康状态机（`task/TaskHealth*`）

回答「任务此刻到底在不在正常跑」。**宿主进程写事件、读的一方现算状态**，
所以 `task_health.json` 里存的是**时间戳与计数**，不是结论。

### H1. `TaskHealthPolicy` —— 纯判定（可单测）

```kotlin
TaskHealthPolicy.evaluate(snapshot, now, stallTimeoutMs): TaskHealthState
TaskHealthPolicy.labelOf(state): String          // 界面/日志共用的中文短标签
TaskHealthPolicy.describe(snapshot, state, now): String   // 一行说明
```

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `DEFAULT_STALL_TIMEOUT_MINUTES` | 5 | 无进展多久算卡住 |
| `MIN/MAX_STALL_TIMEOUT_MINUTES` | 1 / 120 | 夹取边界，`normalizeTimeoutMinutes` |

判定优先级：**被暂停 > 卡住 > 记录的状态**。被暂停时不能报卡住 —— 前者等自愈、后者要人工验证。
「卡住」只看**无进展**（`max(lastProgressAt, lastStartAt)` 距今超阈值），不看有没有失败。

⚠️ **进度信号要用两级逼近"整轮耗时"**：`forest.main` 一轮可能跑 30+ 分钟
（查道具 / 能量雨 / 收能量 / 浇水 / 赠道具等相位；**蹲点是独立后台协程，主任务不等它**），
所以除了整轮结束的 `onSuccess`，还有两类进展信号：

1. **相位级**：`runSuspend` 里的局部函数 `phase(name)` —— 每个相位结束时同时做耗时统计与
   `onProgress(ID_FOREST_MAIN, name)`（原 `tc.countDebug(...)` 全部改走它）；
2. **按好友级**：收能量回调里除了 `ID_COLLECT`，也刷一次 `ID_FOREST_MAIN`。

只留"整轮结束"一个点会让面板在 5 分钟后误报「卡住」（2026-10-01 实测）。
注意这只保证"相位之间"有信号：**若某个单相位自身超过阈值，仍会短暂显示卡住**（已知边界）。

⚠️ **等待态（蹲点）不看"多久没进展"，看"预计时刻"**：等待期间本来就没有任何上报
（协程在 `delay` 到能量成熟时刻），按无进展判会让"等得越久越像卡住"（实测误报 23 分钟）。
所以快照带 `expectedAt`（预计动作时刻，由 `EnergyWaitingManager` 上报**最早到期**的那个任务）：

- `now < expectedAt` → 等待中（正常）；
- `expectedAt ≤ now ≤ expectedAt + 阈值` → 等待中（宽限内，正在收）；
- `now > expectedAt + 阈值` → **卡住**（该收没收，真异常）；
- `expectedAt == 0`（历史记录/其它来源）→ 退回"按无进展判定"。

面板文案：等待中显示「预计 HH:MM 收取」，卡住且超预计时间时显示「已超预计收取时间 N 分钟」。

⚠️ **暂停原因有自愈**：`blockedReason` 是全局值、会写进每一项并落盘，**只靠"解除时清一次"会留残影**
（进程在暂停期间被杀、或接续路径变化都会留下）——判定为「已暂停」而任务其实在跑（2026-10-01 实测）。
现在 `ordered()` **始终**以全局值覆盖每一项（为空就写空），并加了不变量 **在线 ⇒ 没有任何暂停原因**
（`TaskHealthPolicy.shouldClearStaleBlock`，进度事件与写盘时都会就地纠正）。

### H2. `TaskHealthMonitor` —— 宿主侧入口

```kotlin
onStart(id, detail)      // 进入进行中
onProgress(id, detail)   // 有进展（收能量时逐好友、主任务还按相位边界调用；落盘节流 3s）
onWaiting(id, detail, expectedAt)  // 等能量成熟（expectedAt=预计动作时刻，判定以它为基准）
onSuccess(id, detail)    // 成功，清零 consecutiveFailures
onFailure(id, detail)    // 失败，累加 consecutiveFailures
onBlocked(reason)        // 全局：被离线/安全验证挡住
onBlockedCleared()
readAll(userId, stallTimeoutMinutes, now): List<TaskHealthSnapshot>  // 界面用
```

| ID | 显示名 | 埋点位置 |
| --- | --- | --- |
| `forest.main` | 森林主任务 | `AntForest` 一轮的 `onStart` / `onSuccess` / `onFailure`，**外加每个相位边界与每次收好友的 `onProgress`** |
| `forest.collect` | 收能量 | `AntForest.collectEnergy` |
| `forest.waiting` | 蹲点收取 | `EnergyWaitingManager` |

落盘：`config/<uid>/task_health.json`，展示顺序由 `ORDER` 固定，界面不用再排。

### H3. 两个易踩的坑

1. **模块 App 进程里 `UserMap.currentUid` 是空的**（没有支付宝登录态），直接按 uid 拼路径会读到空文件、
   页面永远显示「还没有记录」—— `readAll` 因此有 `resolveHealthFile` 兜底：扫 `config/` 下的账号目录，
   取**最近改过**的那份。
2. **`Status.hasFlagToday` 不能用来做跨天核对**：它的 `flagList` 跨天会自己清，状态机的文件不会。
   另有一层：**宿主进程重启后内存是空的**，直接落盘会把已跑完的状态抹成「未开始」——
   所以 `syncDailyState(now)` 在每次写盘前先把磁盘上属于**今天**（按记录里的最新时间戳判定）的记录接进内存，
   接进来的接着用、不是今天的丢弃。同一个自然日内只读一次盘。

> 只观测不干预：这里不做重试 / 取消 / 重启。判定错了最多显示不准，不会把正常任务打断。

---

## I. 组件约定与坑

1. **`SettingsComponents.kt` 缺 `package` 行** —— `SettingsSwitchItem` 落在默认包，与同目录其他文件不一致，建议补齐。
2. **`ListDialog` 用静态字段存对话框引用**（`static AlertDialog listDialog`），是单例式实现，**同一时刻只能开一个**。
3. **`window.HOOK.*` 返回的是 JSON 字符串**，忘了 `JSON.parse` 会拿到 `"[object Object]"` 之类的字符串。
4. **`Window.HOOK.getTabs()` 的 `modelIcon` 是文件名**，图标必须存在于 `assets/web/images/icon/model/`，否则前端显示破图；新模块记得放图标。
5. **新增设置项不需要写 UI 代码** —— 别为了「渲染」去改两套 UI，那是 schema 驱动自动完成的。
6. **三个 Web 页面互不共享代码**（`semi_index.html` / `varlet_index.html` / `index.html` 是三个独立文件），改一处不会同步到其他两处。
7. **扩展函数 `Context.openUrl` / `joinQQGroup` 只在 UI 层用**，不要在 hook 或 task 里引入 `android.content.Intent` 副作用。
8. 新增 `*Policy` 对象时，**保持纯函数/无副作用**，否则失去可测性（这是策略层存在的唯一理由）。

---

## J. 重复失败放弃（`model/RepeatFailurePolicy` + `task/RepeatFailureGuard`）

同一目标**当天失败到上限（5 次）就停止尝试**，用于收敛「每轮都失败、当天从未成功」的调用；
完整限制清单见 [`docs/failure-give-up.md`](failure-give-up.md)。

```kotlin
// 纯判定（可单测，无 Android 依赖）
RepeatFailurePolicy.DAILY_FAILURE_LIMIT                  // 5
RepeatFailurePolicy.kindOf(vararg hints: String?): RepeatFailureKind   // DETERMINISTIC / TRANSIENT / UNKNOWN
RepeatFailurePolicy.isCountable(vararg hints: String?): Boolean        // 安全验证 / 空响应 → false（不计入）
RepeatFailurePolicy.shouldGiveUp(failureCountToday: Int): Boolean

// 接线（读写 Status 当天计数 + 播报 + 台账）；放在 task 包，避免 util → task 的分层倒置
RepeatFailureGuard.shouldSkipToday(key: String): Boolean
RepeatFailureGuard.recordFailure(key: String, displayName: String, vararg hints: String?): Boolean
```

计数落在 `config/<uid>/status.json` 的 `failureCountTodayList`（跨天随 `unload()` 清零）。
**⚠️ 类别只影响措辞不影响阈值**：不可用类同样是"当天放弃"而非退避。
**⚠️ 安全验证（`RPC_VERIFICATION_REQUIRED`）与空响应（`EMPTY_RPC_RESPONSE`）一律不计入** ——
否则会写成"该功能今天不再尝试"，违反项目硬规则 3。完整限制见该文档 §3。

---
