# DESIGN.md

> 本项目的视觉规则。改任何界面（Compose 页 / XML 页 / Web 设置页）之前先读这份。
>
> 配套：[`docs/component-api.md`](docs/component-api.md)（组件与 API）、[`docs/architecture.md`](docs/architecture.md)（DI 与数据流）。

---

## 0. 前提：本项目有两套并存的 UI 体系

这是理解一切视觉问题的起点。同一个模块里同时存在：

| 体系 | 载体 | 主题来源 | 用户怎么进入 |
| --- | --- | --- | --- |
| **A. Compose 版**（`UiMode.New`） | `ui/MainActivity.kt` → `ui/screen/MainScreen.kt` | `ui/theme/`（Material 3 + 自定义色板） | 用户在设置里选「新版原生界面」 |
| **B. Web 版**（`UiMode.Web`，**默认**） | `ui/WebSettingsActivity.java` → `assets/web/*.html` | `assets/web/css/index.css` + 第三方库皮肤 | 默认入口 |

外加一层历史遗留的 **XML/View 资源**（`res/values/colors.xml`、`styles.xml`），它服务于 `SettingActivity`、`ExtendActivity`、`LogViewerActivity` 这类非 Compose Activity 和对话框。

⚠️ **三套配色的强调色并不一致**：

- Compose 主题：青色系，`primary = #1FA0CC`
- XML 资源：蓝色，`colorPrimary = #2489FD`
- Web 设置页：橙红，`#E64000`

这是历史演进的结果，**不是 bug，但也不要「顺手统一」** —— 统一属于产品级决策，需要单独立项。改动时请在你所在的那套体系内保持一致。

---

## A. Compose 主题（`ui/theme/`）

### A1. 色彩体系

`Color.kt` 定义了 **6 套完整色板**，`theme.kt` 把它们装配成 `ColorScheme`：

| 色板 | 变量前缀 | 在 `theme.kt` 中的 scheme |
| --- | --- | --- |
| 亮色 | `*Light` | `lightScheme` |
| 暗色 | `*Dark` | `darkScheme` |
| 亮色-中对比 | `*LightMediumContrast` | `mediumContrastLightColorScheme` |
| 亮色-高对比 | `*LightHighContrast` | `highContrastLightColorScheme` |
| 暗色-中对比 | `*DarkMediumContrast` | `mediumContrastDarkColorScheme` |
| 暗色-高对比 | `*DarkHighContrast` | `highContrastDarkColorScheme` |

每个色板都覆盖完整的 Material 3 role 集（`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` / `secondary…` / `tertiary…` / `error…` / `background` / `surface` / `surfaceVariant` / `outline` / `outlineVariant` / `scrim` / `inverse*` / `surfaceDim` / `surfaceBright` / `surfaceContainerLowest…Highest`）。

**基准色锚点**（用于判断新颜色是否「属于这套体系」）：

| Role | 亮色 | 暗色 |
| --- | --- | --- |
| `primary` | `#1FA0CC` | `#89D0EE` |
| `primaryContainer` | `#BBEAFF` | `#004D62` |
| `secondary` | `#4C616B` | `#B4CAD5` |
| `tertiary` | `#5C5B7E` | `#C5C3EA` |
| `error` | `#BA1A1A` | `#FFB4AB` |
| `background` / `surface` | `#F5FAFD` | `#0F1417` |
| `onSurface` | `#171C1F` | `#DEE3E6` |

新增颜色时：**优先复用已有 role**。确实需要新色值时，必须把 6 套色板全部补齐，不能只改亮色。

### A2. 动态取色

- 开关状态存在 `ThemeManager`（`SharedPreferences` key = `dynamic_color`，**默认 `true`**）。
- `AppTheme(dynamicColor = ...)` 的形参默认值是 `false`，实际值由 `MainActivity` 从 `ThemeManager.isDynamicColor` 传入 —— 别被形参默认值误导。
- 生效条件：`dynamicColor == true` **且** `Build.VERSION.SDK_INT >= S`（Android 12）。否则回落到 `darkScheme` / `lightScheme`。
- 中/高对比色板**当前未被 `AppTheme` 使用**，是预留能力。要接入需扩展 `AppTheme` 的参数分支。

### A3. 字体

`Type.kt` 用 **Google Fonts 可下载字体**，通过 `com.google.android.gms.fonts` provider 拉取：

| 用途 | 字族 |
| --- | --- |
| `display*` / `headline*` / `title*` | **ABeeZee**（`displayFontFamily`） |
| `body*` / `label*` | **Andada Pro**（`bodyFontFamily`） |

证书来自 `R.array.com_google_android_gms_fonts_certs`（`res/values-v23/font_certs.xml`）。字号/行高全部沿用 Material 3 `Typography()` 的 baseline，**只换字族，不改尺寸**。

需要注意：这是**网络字体**，设备离线或设备无 GMS 时会回落系统字体。所以
**不要依赖具体字形宽度做布局**。

### A4. 形状与尺寸

| 元素 | 规格 | 出处 |
| --- | --- | --- |
| 设置卡片 / 列表条目 | `RoundedCornerShape(12.dp)` | `SettingsItem`、`SettingsSwitchItem`、`UserItemCard` |
| 功能按钮 | `RoundedCornerShape(16.dp)`，高 `72.dp` | `MenuButton` |
| 按钮内图标 | `28.dp` | `MenuButton` |
| 条目内图标 | 默认 `24.dp`；头像 `40.dp` | `SettingsItem` / `UserItemCard` |
| 条目内边距 | `16.dp` | 全部设置条目 |
| 图标与文字间距 | `8.dp`（设置条目）/ `16.dp`（头像） | — |
| 竖向元素间隙 | `2.dp` | `MenuButton` |

### A5. 语义色使用约定（读现有组件归纳）

| 场景 | 用什么 |
| --- | --- |
| 图标默认色、次要文字 | `onSurfaceVariant` |
| 更弱的提示文字、副标题 | `outline` |
| 强调、可交互、链接 | `primary` |
| 危险操作（`isDanger = true`） | `error` |
| 普通卡片底 | `surfaceContainerLow` |
| 需要「浮起来」的卡片 | `surfaceContainerHigh`（+ `tonalElevation`） |
| 菜单按钮底 / 内容 | `surfaceVariant` / `primary` |
| 对话框底 / 文字 | `surfaceContainerHigh` / `onSurface` |

### A6. 沉浸式与系统栏

`AppTheme` 里统一处理，业务页面**不要再自己设置系统栏**：

- `statusBarColor` / `navigationBarColor` 设为透明
- `WindowCompat.setDecorFitsSystemWindows(window, false)`（内容延伸到状态栏后面）
- `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` = `!darkTheme`（保证图标与背景对比）

深色模式跟随系统（`isSystemInDarkTheme()`）。

### A7. 主题色/图标彩蛋

`IconManager` 会在**圣诞节**期间把启动图标切到 `MainActivityAliasChristmas`（`android:enabled` 在 `MainActivityAlias` / `...Christmas` 两个 `activity-alias` 之间切换），并随机弹一句带 emoji 的祝福。做图标相关改动时注意这条链路存在。

---

## B. XML / View 资源

服务对象：`SettingActivity`、`ExtendActivity`、`WebSettingsActivity`、`LogViewerActivity` 以及各 `ui/widget` 对话框（`ListDialog`、`StringDialog`、`ChoiceDialog`）。

### B1. 主题

`res/values/styles.xml`：

```xml
<style name="AppTheme" parent="Theme.Material3.DayNight.NoActionBar">
```

- `colorPrimary` = `@color/colorPrimary`，`titleTextColor` / `subtitleTextColor` = `@color/active_text`
- 状态栏与导航栏均透明，`windowNoTitle = true`
- `actionOverflowMenuStyle` 指向 `MenuTheme`（自定义溢出菜单背景 `@drawable/menu_background`）

`res/values-v33/styles.xml` 补了 `Widget.App.Button.MD3` 的 `backdropColor`（Material You 相关，仅 Android 13+）。

### B2. 色值表

分日夜两套：`res/values/colors.xml` + `res/values-night/colors.xml`。

| 名称 | 亮色 | 暗色 | 用途 |
| --- | --- | --- | --- |
| `background` | `#F4F4F4` | `#1A1A1A` | 页面底 |
| `base_color` / `colorPrimary` | `#2489FD` | `#4D4D4D` | 主题强调色 |
| `textColorPrimary` | `#000000` | `#FFFFFF` | 正文 |
| `selection_color` | `#2489FD` | `#F87640` | 选中态 |
| `main_button` | `#BCE1E9` | `#3A3A3A` | 按钮底 |
| `main_button_pressed` | `#2489FD` | `#FFEBCD` | 按钮波纹 |
| `orange` | `#E64000` | `#F87640` | 强调/链接 |
| `not_active_text` | `#e10000` | `#F4D022` | 未激活/警示 |
| `active_text` | `#DCDCDC` | `#DEDBDB` | Toolbar 文字 |
| `load_active_text` | `#999999` | `#C8B9B9` | 弱化文字 |
| `toast_text_color` | `#090909` | `#FBFBFB` | Toast 文字 |
| `switch_off` | `#DFDFDF` | `#DFDFDF` | 关闭态 |

### B3. 按钮规格

`Widget.App.Button.Main`（主功能按钮，`MenuButton` 的 XML 对应物）：

| 属性 | 值 |
| --- | --- |
| 圆角 | `12dp` |
| 内边距 | `12dp` |
| 图标尺寸 / 间距 | `48dp` / `2dp` |
| 图标位置 | `top` |
| 文字 | `12sp`，`@color/textColorPrimary` |
| 底 / 波纹 | `@color/main_button` / `@color/main_button_pressed` |
| 宽 / 高 | `113dp` / `wrap_content` |

### B4. 尺寸与手绘资源

- `res/values/dimen.xml`：`setting_item_padding = 16dp`、`status_bar_height = 24dp`
- `res/drawable/`（8 个手写 XML shape）：`dialog_list_button`、`menu_background`、`shape_diary_toast`、`switch_track`、`tab_selected_background`、`title_logo`、`toast_ic` + `ic_launcher_christmas.png`

---

## C. Web 设置页（`assets/web/`）

### C1. 三套页面

| 文件 | UI 库 | 说明 |
| --- | --- | --- |
| `semi_index.html` | Vue 3 + **Semi UI**（`js/semi-ui.min.js` + `css/semi.min.css`） | **第二版，当前主推**，带全局搜索 |
| `varlet_index.html` | Vue 3 + **Varlet** | 备选皮肤 |
| `index.html` | Vue 3 + **Vant**（`js/vant.js` + `css/vant.css`） | 最早的一版，用 `.theme1` / `.theme2` 两个 class 切皮肤 |
| `demo.html` | — | 组件演示沙盒，不参与运行时 |

改版式时**优先改 `semi_index.html`**；`settings-search-contract.js` 的合同测试也锚定它。

### C2. 自定义样式只在 `index.css`

`semi.min.css`（514 KB）和 `vant.css`（225 KB）是第三方库产物，**不要改**。项目自己的规则全在 `css/index.css`。

主题锚点：

| 用途 | 值 |
| --- | --- |
| 强调色 / 链接 / 选中 | `#E64000` |
| 页面底色 | `#f6f6f6` |
| 内容盒底色 | `#ffffff` |
| 描述文字 | `#666666`，`12px` |
| 禁用文字 | `#323233` |
| 分隔线 | `#f0efef` / `#f6f6f6` |
| 输入框描边 | `#e5dbdb` |
| 圆角 | `4px`（输入框） |
| 主内容盒宽度 | `calc(100vw - 40px)`，外边距 `10px`，内边距 `10px` |

### C3. 结构与工具类

| 类名 | 含义 |
| --- | --- |
| `.content-box` | 内容容器（白底、可滚动、固定宽度） |
| `.title-box` | 标题区（`.theme1` 左对齐 / `.theme2` 居中竖排） |
| `.list-icon` | 列表图标（`20px` / `.theme2` 下 `16px`），色 `#E64000` |
| `.list-label` | 列表文字 |
| `.dialog-list` + `-item` / `-container` / `-content` | 好友选择等长列表对话框 |
| `.theme1` / `.theme2` | 老页面的两套皮肤，底色均为 `#f6f6f6` |

间距工具类：`.mt5` `.mt10` `.mt15` `.mt20` `.mr15` `.pdTB10` `.pdT6` `.w50` `.w100` `.w120`；
布局工具类：`.flex` `.flex-center` `.flex-space-between` `.borderTop` `.borderLeft`。

> 注意 `.mt*` 实际作用于 **`margin-bottom`**（命名与行为不符，沿用即可，别改）。

### C4. 两个必须知道的全局约束

1. **`index.css` 顶部把几乎所有标签的 `font-size` 强制设为 `16px !important`**。要改字号必须用 `!important` 覆盖，且要预期影响面很大。
2. **`[v-cloak] { display: none }`** —— 页面用 `v-cloak` 防止模板闪烁，新增根节点时记得带上。

### C5. Web 端主题联动

- 夜间模式由桥接口 `HOOK.isNightMode()` 提供（读 Android `Configuration.UI_MODE_NIGHT_MASK`），**不依赖** `prefers-color-scheme`。
- 版本号由 `HOOK.getBuildInfo()` 提供（返回 `applicationId:versionName`）。

---

## D. 图标资源

| 类型 | 位置 | 命名规则 |
| --- | --- | --- |
| 业务模块图标 | `assets/web/images/icon/model/` | 文件名 = `Model.getIcon()` 的返回值，如 `AntForest.png`、`AnswerAI.svg`；兜底 `Default.png`；另有 `selected/` 子目录与 `Theme.png` |
| 应用启动图标 | `res/mipmap-*` | 标准密度分级 + `mipmap-anydpi-v26` 自适应图标 |
| 圣诞图标 | `res/drawable/ic_launcher_christmas.png` | 由 `activity-alias` 切换 |
| Compose 界面图标 | `androidx.compose.material:material-icons-extended` | 直接用 `Icons.Rounded.*` / `Icons.AutoMirrored.Rounded.*` |

⚠️ `assets/web/web` 目录属于**运行时资源**，改动会在下次构建才生效；本地热调试走 `serve-debug/webui.py`（需先把 `assets/web` 拷到 `serve-debug/web`，见 `serve-debug/README.MD`）。

---

## E. 改视觉时的自查清单

- [ ] 改的是哪套体系？在**该体系内部**保持一致，不跨体系借用色值
- [ ] Compose：颜色取自 `MaterialTheme.colorScheme.*`，没有硬编码 `Color(0xFF...)`
- [ ] Compose：若确实新增色值 → 6 套色板全部补齐（亮/暗/中对比×2/高对比×2）
- [ ] Compose：圆角/间距复用了 A4 表里的既有档位（12dp / 16dp / 8dp）
- [ ] Compose：没有在业务页面里重复设置系统栏（`AppTheme` 已统一处理）
- [ ] Compose：暗色模式下确认过对比度（`onSurfaceVariant` vs `outline` 别混用）
- [ ] XML：日/夜两套 `colors.xml` 都改了
- [ ] Web：只改 `index.css` 或 HTML，没有碰 `*.min.css` / `vendor js`
- [ ] Web：字号覆盖带了 `!important`；新增根节点带了 `v-cloak`
- [ ] Web：改动涉及 `semi_index.html` 时，跑一遍 `node --test app/src/test/js/`
- [ ] 三个页面对应的功能是否都要同步？（`semi` / `varlet` / `index` 三份是独立文件，**不会自动同步**）
