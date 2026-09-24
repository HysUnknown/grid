# 分册 01：工程骨架与规范（Task 1-2）

前置：先读 [00-overview.md](00-overview.md) 的"GKD 语法校准结论"与"统一约定"。

---

### Task 1: Gradle 双模块骨架

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`
- Create: `android/engine/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/cn/hys159x/grid/app/MainActivity.kt`
- Create: `android/engine/src/test/kotlin/cn/hys159x/grid/engine/SmokeTest.kt`

- [ ] **Step 1: 准备 Gradle wrapper**

```bash
gradle -v || winget install --id Gradle.Gradle -e
cd D:/dev/app/android
gradle wrapper --gradle-version 8.9
```

Expected: `android/gradlew.bat`、`android/gradle/wrapper/gradle-wrapper.jar` 生成。

- [ ] **Step 2: 写工程配置文件**

`android/settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "grid"
include(":app", ":engine")
```

`android/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

`android/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
lifecycleRuntime = "2.8.7"
serialization = "1.7.3"
okhttp = "4.12.0"
room = "2.6.1"
junit = "5.11.4"
coreKtx = "1.15.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntime" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
junit5 = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: 写模块构建文件**

`android/engine/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
```

`android/app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cn.hys159x.grid.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.hys159x.grid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
```

`android/app/src/main/AndroidManifest.xml`（无障碍服务 Task 10 才注册，先只留主体）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="网格"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/java/cn/hys159x/grid/app/MainActivity.kt`（Task 13 重写为完整 UI）：

```kotlin
package cn.hys159x.grid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("网格") }
    }
}
```

`android/engine/src/test/kotlin/cn/hys159x/grid/engine/SmokeTest.kt`：

```kotlin
package cn.hys159x.grid.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class SmokeTest {
    @Test
    fun `engine module runs jvm tests`() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 4: 构建验证**

```bash
cd D:/dev/app/android
./gradlew.bat :engine:test :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`；产物 `app/build/outputs/apk/debug/app-debug.apk` 存在。

- [ ] **Step 5: Commit**

```bash
cd D:/dev/app
git add android
git commit -m "chore: Android 双模块工程骨架（app+engine）"
```

---

### Task 2: selector-spec.md 规范定稿

**Files:**
- Create: `docs/specs/selector-spec.md`

- [ ] **Step 1: 写规范文档**（内容以 [00-overview.md](00-overview.md) "GKD 语法校准结论"为准展开，结构如下，逐节填入结论表中已确认的内容，禁止留空）

`docs/specs/selector-spec.md` 章节结构：

1. **范围**：本规范定义"网格"选择器语法子集，Kotlin 引擎（Android）与 TS 引擎（HarmonyOS）实现必须一致；用例集为一致性的判定标准。
2. **选择器语法**：属性清单、操作符表（含 null 语义）、关系符表（空格/`>`/`<`/`<<`/`+`/`-`、数字与多值、目标节点规则 `@`/默认最后一个）——逐字采用 00-overview 校准结论。
3. **规则字段**：`matches`(AND)/`anyMatches`(OR)/`excludeMatches`(OR)/`excludeAllMatches`(AND)/`action`(click/clickNode/clickCenter/back/longClick/longClickNode/longClickCenter/none)/`preKeys`/`actionCd`(默认1000)/`actionDelay`/`actionMaximum`/`resetMatch`(activity/match/app)/`matchTime`/`matchRoot`/`order`/`activityIds`/`excludeActivityIds`。
4. **忽略字段清单**与**不支持语法清单**（遇规则含不支持语法→整条标记 skip 并计数；忽略字段→正常执行其余部分）。
5. **动作语义**：`click`=clickable 用节点点击否则坐标中心点击；`clickNode`=节点 ACTION_CLICK（屏幕外也可）；`clickCenter`=计算 bounds 中心发手势（出屏=未匹配）；`back`=GLOBAL_ACTION_BACK。
6. **匹配算法**：目标节点候选枚举（自事件节点向上回溯至根，逐子树深度优先）→ 对每个候选从右向左验证关系链 → 任一候选全链通过即命中，返回首个（深度优先序）。
7. **标准用例集格式**：JSON 数组，每条 `{"name": "...", "selector": "...", "tree": {...}, "expectTargetId": "..." 或 null}`；tree 节点结构 `{"vid": "...", "text": "...", "children": [...]}`（Task 3 定义 DSL 后由 fixture 文件承载）。
8. **版本**：v1.0，2026-09-20，来源 gkd.li 官方文档抓取记录。

- [ ] **Step 2: Commit**

```bash
cd D:/dev/app
git add docs/specs/selector-spec.md
git commit -m "docs: 选择器语法子集规范 selector-spec v1.0"
```
