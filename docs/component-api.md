# 组件与对外 API

> 本文件是「可复用的东西」的索引：Compose 组件、View 对话框、Web 端 JS 合同、Native↔JS 桥、内置 HTTP 接口、配置模型 API。
>
> 视觉规范见 [`../DESIGN.md`](../DESIGN.md)；数据流见 [`architecture.md`](architecture.md)。

---

## 0. 总览：本项目有 6 类「组件」

| 类型 | 位置 | 复用方式 |
| --- | --- | --- |
| **A. Compose 组件** | `ui/screen/components/`、`ui/screen/card/`、`ui/compose/` | `@Composable` 函数，直接调用 |
| **B. View / XML 对话框** | `ui/widget/` | 静态 `show(...)` 工厂方法 |
| **C. Web 端纯逻辑 JS** | `assets/web/js/settings-search-contract.js` | UMD 模块，浏览器 + Node 双用 |
| **D. Native ↔ JS 桥** | `ui/WebSettingsActivity.java` → `window.HOOK` / `window.Android` | Web 页面通过 `window.HOOK.*` 调 Kotlin |
| **E. 内置 HTTP 接口** | `hook/server/handlers/` | 外部工具 HTTP 调用，跑在 `0.0.0.0:<port>` |
| **F. 配置模型 API** | `model/ModelField` + `modelFieldExt/` | 声明式设置项，两套 UI 自动渲染 |

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
**测试**：`app/src/test/js/settings-search-contract.test.js`（`node --test app/src/test/js/`）

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

## G. 组件约定与坑

1. **`SettingsComponents.kt` 缺 `package` 行** —— `SettingsSwitchItem` 落在默认包，与同目录其他文件不一致，建议补齐。
2. **`ListDialog` 用静态字段存对话框引用**（`static AlertDialog listDialog`），是单例式实现，**同一时刻只能开一个**。
3. **`window.HOOK.*` 返回的是 JSON 字符串**，忘了 `JSON.parse` 会拿到 `"[object Object]"` 之类的字符串。
4. **`Window.HOOK.getTabs()` 的 `modelIcon` 是文件名**，图标必须存在于 `assets/web/images/icon/model/`，否则前端显示破图；新模块记得放图标。
5. **新增设置项不需要写 UI 代码** —— 别为了「渲染」去改两套 UI，那是 schema 驱动自动完成的。
6. **三个 Web 页面互不共享代码**（`semi_index.html` / `varlet_index.html` / `index.html` 是三个独立文件），改一处不会同步到其他两处。
7. **扩展函数 `Context.openUrl` / `joinQQGroup` 只在 UI 层用**，不要在 hook 或 task 里引入 `android.content.Intent` 副作用。
8. 新增 `*Policy` 对象时，**保持纯函数/无副作用**，否则失去可测性（这是策略层存在的唯一理由）。
