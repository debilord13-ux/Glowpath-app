package com.musicplayer.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.ui.graphics.vector.ImageVector
import com.musicplayer.BuildConfig

internal const val GLOWPATH_REPOSITORY = "https://github.com/qrrrv/Glowpath-app"

internal data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val isDebug: Boolean,
    val buildNumber: String,
    val revision: String,
    val packageName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val androidVersion: String,
    val androidSdk: Int,
    val device: String,
    val deviceAbis: String,
) {
    val variantLabel get() = if (isDebug) "Отладочная" else "Основная"
    val buildLabel get() = if (buildNumber.isBlank()) "Локальная сборка" else "№ $buildNumber"
    val revisionLabel get() = revision.take(7).ifBlank { "Не указана" }

    fun diagnostics(): String = """
        Glowpath $versionName
        Код версии: $versionCode
        Вариант: $variantLabel
        Сборка: $buildLabel
        Ревизия: ${revision.ifBlank { "Не указана" }}
        Пакет: $packageName
        Минимальный уровень Android API: $minSdk
        Целевой уровень Android API: $targetSdk
        Android: $androidVersion (API $androidSdk)
        Устройство: $device
        Архитектуры устройства: $deviceAbis
    """.trimIndent()
}

@Suppress("DEPRECATION")
internal fun readAppBuildInfo(context: Context): AppBuildInfo {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val app = context.applicationInfo
    return AppBuildInfo(
        versionName = info.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME,
        versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
        isDebug = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        buildNumber = BuildConfig.CI_BUILD_NUMBER,
        revision = BuildConfig.SOURCE_REVISION,
        packageName = context.packageName,
        minSdk = app.minSdkVersion,
        targetSdk = app.targetSdkVersion,
        androidVersion = Build.VERSION.RELEASE,
        androidSdk = Build.VERSION.SDK_INT,
        device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
        deviceAbis = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "Неизвестны" },
    )
}

internal data class AboutAction(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val url: String,
)

internal val aboutBuildsAction = AboutAction(
    Icons.Rounded.Download, "Сборки приложения", "Готовые APK в GitHub Actions",
    "$GLOWPATH_REPOSITORY/actions/workflows/build.yml",
)

internal val aboutActions = listOf(
    AboutAction(Icons.Rounded.Code, "Исходный код", "Проект Glowpath на GitHub", GLOWPATH_REPOSITORY),
    AboutAction(Icons.Rounded.BugReport, "Сообщить об ошибке", "Или предложить улучшение", "$GLOWPATH_REPOSITORY/issues"),
    AboutAction(Icons.Rounded.Extension, "Библиотеки", "Зависимости и их версии", "$GLOWPATH_REPOSITORY/blob/main/app/build.gradle.kts"),
)

// Credits belong to the original Rhythm project, not to the Glowpath team.
internal val rhythmCredits = listOf(
    "Anjishnu Nandi (cromaguy) — автор Rhythm",
    "Izzy, linsui, Licaon_Kter — сопровождение пакетов Rhythm",
    "theovilardo, Nick (nift4), vivi, Alex (Paxsenix0) — помощь и участие в разработке Rhythm",
    "itzKane (soykane) — концепция интерфейса Rhythm",
)
