import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.rikka.tools.refine)
}
var isCIBuild: Boolean = System.getenv("CI").toBoolean()

//isCIBuild = true // 没有c++源码时开启CI构建, push前关闭

/** 2020-01-01T00:00:00Z 对应的 Unix 分钟数（1577836800 / 60），用于生成单调递增的 versionCode。 */
val EPOCH_2020_01_01_MINUTES_UTC: Long = 26_297_280L

android {
    namespace = "fansirsqi.xposed.sesame"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        splits {
            abi {
                isEnable = true
                reset()
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = true
            }
        }

    }
    // versionCode 必须**单调递增**，否则 Android 会拒绝覆盖安装
    // （`INSTALL_FAILED_VERSION_DOWNGRADE`），用户只能卸载重装、数据全丢。
    //
    // 旧实现取自 `git rev-list --count HEAD`，它对 **rebase / squash / reset 不单调**：
    // 2026-09-26 实测因此从 3711 回退到 3706（提交被丢弃后计数变小）。
    // 而本仓库用的是 squash-merge 工作流，分支计数本来就会来回变，所以这个方案不成立。
    //
    // 现改为「自 2020-01-01 起的分钟数」：天然单调、无需人工维护、也不依赖提交历史。
    // 当前约 354 万，远高于旧方案的历史峰值 3711，因此不会与已装版本冲突。
    // 同一分钟内的多次构建会得到相同 versionCode —— 用 `adb install -r` 覆盖安装没问题
    // （只有**降级**才被拒）；构建间的可区分性由 BuildConfig.BUILD_DATE / BUILD_TIME 提供。
    //
    // 注：不要再叠加「基线 × 10^k + 低位」这类写法 —— Int 上界只有 21 亿，
    // 加大倍数会溢出，减小倍数则低位会周期性回绕，回绕本身就是一次版本号回退。
    val minutesSince2020Utc: Int = (
            System.currentTimeMillis() / 60_000L - EPOCH_2020_01_01_MINUTES_UTC
            ).toInt()
    defaultConfig {
        vectorDrawables.useSupportLibrary = true
        applicationId = "fansirsqi.xposed.sesame"
        minSdk = 26
        targetSdk = 36

        val buildDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }.format(Date())

        val buildTime = SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }.format(Date())

        versionCode = minutesSince2020Utc
        versionName = "0.9.9"

        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        if (isCIBuild) {
            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
            }
        }

        testOptions {
            unitTests.all {
                it.enabled = true
            }
        }
    }



    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
        aidl = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = false//关闭脱糖
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        getByName("debug") {
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            versionNameSuffix = "-debug"
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
    val cmakeFile = file("src/main/cpp/CMakeLists.txt")
    if (!isCIBuild && cmakeFile.exists()) {
        externalNativeBuild {
            cmake {
                path = cmakeFile
//                version = "4.1.2"  //不要随意改这个了答应我
                ndkVersion = "29.0.14206865" //这个也是 答应我就这样吧
            }
        }
    }

}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: "universal"
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "Sesame-ZR-$abiName-$versionName.apk"
                }
            )
        }
    }
}

dependencies {
    // Shizuku 相关依赖 - 用于获取系统级权限
    implementation(libs.rikka.shizuku.api)        // Shizuku API
    implementation(libs.rikka.shizuku.provider)   // Shizuku 提供者
    implementation(libs.rikka.refine)             // Rikka 反射工具
//    implementation(libs.rikka.hidden.stub)
    // implementation(libs.ui.tooling.preview.android)
    implementation(libs.cmd.android)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.material3) // 用于通过 Shizuku 执行命令

    // Compose 相关依赖 - 现代化 UI 框架
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")  // Compose BOM 版本管理
    implementation(composeBom)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3)                // Material 3 设计组件
    implementation(libs.androidx.ui.tooling.preview)              // UI 工具预览
    debugImplementation(libs.androidx.ui.tooling)                 // 调试时的 UI 工具
    implementation(libs.androidx.material.icons.extended)         // Material 3 图标

    // 生命周期和数据绑定
    implementation(libs.androidx.lifecycle.viewmodel.compose) // Compose ViewModel 支持

    // JSON 序列化
    implementation(libs.kotlinx.serialization.json) // Kotlin JSON 序列化库

    // Kotlin 协程依赖 - 异步编程（纯协程调度）
    implementation(libs.kotlinx.coroutines.core)     // 协程核心库
    implementation(libs.kotlinx.coroutines.android)  // Android 协程支持

    // 数据观察和 HTTP 服务
    implementation(libs.androidx.lifecycle.livedata.ktx)  // LiveData KTX 扩展
    implementation(libs.androidx.runtime.livedata)        // Compose LiveData 运行时
    implementation(libs.nanohttpd)                   // 轻量级 HTTP 服务器

    // UI 布局和组件
    implementation(libs.androidx.constraintlayout)  // 约束布局

    implementation(libs.activity.compose)           // Compose Activity 支持

    // Android 核心库
    implementation(libs.core.ktx)                   // Android KTX 核心扩展
    implementation(libs.kotlin.stdlib)              // Kotlin 标准库
    implementation(libs.slf4j.api)                  // SLF4J 日志 API
    implementation(libs.logback.android)            // Logback Android 日志实现
    implementation(libs.appcompat)                  // AppCompat 兼容库
    implementation(libs.recyclerview)               // RecyclerView 列表组件
    implementation(libs.viewpager2)                 // ViewPager2 页面滑动
    implementation(libs.material)                   // Material Design 组件
    implementation(libs.webkit)                     // WebView 组件

    // libxposed 102 现代模块接口与服务
    compileOnly(files("libs/api-102.0.0.aar"))
    implementation(files("libs/interface-102.0.0.aar"))
    implementation(files("libs/service-102.0.0.aar"))

    // 代码生成和工具库
    compileOnly(libs.lombok)                       // Lombok 注解处理器（编译时）
    annotationProcessor(libs.lombok)               // Lombok 注解处理
    implementation(libs.okhttp)                    // OkHttp 网络请求库
    implementation(libs.dexkit)                    // DEX 文件分析工具
    implementation(libs.jackson.kotlin)            // Jackson Kotlin 支持

    // 核心库脱糖和系统 API 访问
//    coreLibraryDesugaring(libs.desugar)            // Java 8+ API 脱糖支持

    implementation(libs.hiddenapibypass)           // 隐藏 API 访问绕过

    // Jackson JSON 处理库
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)
}
