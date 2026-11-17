plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.musicplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicplayer"
        minSdk = 26 // Как ты просил изначально
        targetSdk = 36
        versionCode = 15
        versionName = "1.4.3"

        // Embed the identity of this APK, not the latest build available online.
        val revision = providers.environmentVariable("GITHUB_SHA").orNull.orEmpty()
            .takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }.orEmpty()
        val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull.orEmpty()
            .takeIf { it.matches(Regex("[0-9]+")) }.orEmpty()
        buildConfigField("String", "SOURCE_REVISION", "\"$revision\"")
        buildConfigField("String", "CI_BUILD_NUMBER", "\"$buildNumber\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Стабильная подпись: ОДИН ключ на ВСЕ сборки ──────────────────────────
    // Ключ лежит в репозитории (signing/glowpath.keystore) и используется всегда —
    // и в CI, и локально, и для debug, и для release. Разные ключи у «обновлений»
    // были причиной ошибки «приложение конфликтует с другим пакетом»: Android
    // считает APK с другой подписью чужим и отказывается ставить его поверх.
    // Пароли указаны рядом с ключом сознательно: проект личный, а предсказуемая
    // подпись важнее секретности (см. signing/README.txt).
    val stableKeystorePath = System.getenv("GLOWPATH_KEYSTORE_PATH").orEmpty()
        .ifBlank { "signing/glowpath.keystore" }
    val stableKeystorePassword = System.getenv("GLOWPATH_KEYSTORE_PASSWORD").orEmpty()
        .ifBlank { "glowpath-stable-key" }
    val stableKeyAlias = System.getenv("GLOWPATH_KEY_ALIAS").orEmpty()
        .ifBlank { "glowpath-release" }
    val stableKeyPassword = System.getenv("GLOWPATH_KEY_PASSWORD").orEmpty()
        .ifBlank { stableKeystorePassword }

    signingConfigs {
        create("glowpathStable") {
            // rootProject.file: ключ лежит в КОРНЕ репозитория, а file() резолвится
            // относительно каталога модуля app/ — без rootProject сборка не найдёт ключ.
            storeFile = rootProject.file(stableKeystorePath)
            storePassword = stableKeystorePassword
            keyAlias = stableKeyAlias
            keyPassword = stableKeyPassword
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    // В Kotlin 2.0.21 блок composeOptions больше НЕ нужен, 
    // так как используется специальный плагин Compose Compiler.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        release {
            // Release = R8 (инлайнинг, удаление мёртвого кода) + не-debuggable ART.
            // Именно эту сборку надо мерить на FPS: debug-сборка Compose заметно медленнее.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подписываем СТАБИЛЬНЫМ ключом из репозитория — APK ставится поверх
            // предыдущей версии без удаления данных и без конфликта пакетов.
            signingConfig = signingConfigs.getByName("glowpathStable")
        }
        debug {
            isMinifyEnabled = false
            // Debug тоже стабильным ключом: иначе debug-сборка с CI (одноразовый
            // debug-ключ раннера) конфликтовала с любой ранее установленной.
            signingConfig = signingConfigs.getByName("glowpathStable")
        }
    }
}

// ── SDK-джарки Robolectric: скачивает CI-шаг ДО Gradle, Gradle только проверяет ──
// Robolectric без локальных джарок качает android-all с Maven Central ПРЯМО ВО
// ВРЕМЯ запуска тестов. GitHub-раннеры делят общие IP, и Maven Central регулярно
// отвечает им HTTP 429 — тогда ВСЕ тесты падают мгновенно и одинаково, а сборка
// умирает без внятного объяснения. Качать их же через Gradle-зависимости тоже
// ненадёжно: сбой резолюции внутри Copy-задачи ломает сборку молча или с
// невнятным текстом. Поэтому теперь джарки забирает отдельный шаг workflow
// (ci/fetch-robolectric-sdk.sh: 4 зеркала Maven Central, повторы, проверка
// размера и ZIP-подписи) ещё ДО запуска Gradle, а actions/cache сохраняет их
// между прогонами — обычно скачивание происходит один раз на всё время.
// Директория — app/robolectric-sdk-jars. Этот файл лишь:
//   1) если обе джарки уже на месте, выставляет robolectric.dependency.dir —
//      тогда Robolectric берёт их с диска и в сеть не ходит вовсе;
//   2) задача prepareRobolectricSdkJars в CI (env CI/GITHUB_ACTIONS) громко и
//      понятно падает, если джарок нет — вместо 64 одинаковых исключений
//      LocalDependencyResolver в отчёте тестов; локально же (вне CI) только
//      предупреждает, и Robolectric качает джарки сам, как раньше.
// Версии соответствуют маппингу Robolectric 4.14.1 (DefaultSdkProvider,
// PREINSTRUMENTED_VERSION = 7):
//   API 26 -> android-all-instrumented-8.0.0_r4-robolectric-r1-i7.jar
//   API 34 -> android-all-instrumented-14-robolectric-10818077-i7.jar
val robolectricSdkJarNames = listOf(
    "android-all-instrumented-8.0.0_r4-robolectric-r1-i7.jar",
    "android-all-instrumented-14-robolectric-10818077-i7.jar",
)

val robolectricSdkDir: java.io.File = layout.projectDirectory.dir("robolectric-sdk-jars").asFile

fun robolectricSdkJarValid(name: String): Boolean {
    val f = java.io.File(robolectricSdkDir, name)
    return f.isFile && f.length() > 50_000_000L
}

fun robolectricSdkReady(): Boolean =
    robolectricSdkDir.isDirectory && robolectricSdkJarNames.all { robolectricSdkJarValid(it) }

val prepareRobolectricSdkJars = tasks.register("prepareRobolectricSdkJars") {
    description = "Проверяет наличие android-all джарок Robolectric (скачивает ci/fetch-robolectric-sdk.sh)"
    doLast {
        val missing = robolectricSdkJarNames.filterNot { robolectricSdkJarValid(it) }
        if (missing.isNotEmpty()) {
            val hint =
                "Не найдены SDK-джарки Robolectric в ${robolectricSdkDir.absolutePath}: $missing. " +
                    "Сначала выполните bash ci/fetch-robolectric-sdk.sh — он скачивает их " +
                    "с зеркал Maven Central и кладёт в app/robolectric-sdk-jars. Без них " +
                    "тесты падают с IllegalArgumentException из LocalDependencyResolver."
            val isCiRun = (System.getenv("CI") ?: "").equals("true", ignoreCase = true) ||
                (System.getenv("GITHUB_ACTIONS") ?: "").equals("true", ignoreCase = true)
            if (isCiRun) {
                throw org.gradle.api.GradleException(hint)
            }
            logger.warn("ВНИМАНИЕ: $hint — вне CI Robolectric скачает джарки с Maven Central сам.")
        }
    }
}

tasks.configureEach {
    if (this is org.gradle.api.tasks.testing.Test) {
        dependsOn(prepareRobolectricSdkJars)
        // Местная папка с джарками отключает сетевой Maven-резолвер Robolectric
        // (LegacyDependencyResolver проверяет её ДО похода в Maven Central).
        // Свойство выставляем только если джарки уже на месте — так локальный
        // запуск без CI-шага не ломается и качает джарки сам, как раньше.
        if (robolectricSdkReady()) {
            systemProperty("robolectric.dependency.dir", robolectricSdkDir.absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Устанавливает baseline profile (baseline-prof.txt) при первом запуске — AOT-компиляция кода приложения
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Compose BOM — обновлён для совместимости с material3 1.5.x (Expressive)
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    // Иконки и дополнительные функции
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Coil disk cache support (okio)
    implementation("com.squareup.okio:okio:3.9.0")

    // Jsoup — HTML parser for hitmos.me online search
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.navigation:navigation-compose:2.8.7")

    // Материал дизайн (XML поддержка)
    implementation("com.google.android.material:material:1.12.0")

    // Media Session — для уведомления с кнопками управления
    implementation("androidx.media:media:1.7.0")

    // Palette API — извлечение доминирующего цвета из обложки альбома
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Haze — hardware-accelerated glassmorphism blur
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // Lottie — JSON анимации
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // Local HTTP server for Hikka / external now-playing
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
