package com.musicplayer.ui.screens

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import com.musicplayer.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AboutBuildInfoTest {
    @Suppress("DEPRECATION")
    @Test fun readsInstalledPackageAndSdkValuesInsteadOfHardcodedDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val app = context.applicationInfo.apply {
            minSdkVersion = 27
            targetSdkVersion = 33
            flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = context.packageName
            versionName = "2.3-test"
            versionCode = 123
            applicationInfo = app
        })

        val build = readAppBuildInfo(context)
        assertEquals("2.3-test", build.versionName)
        assertEquals(123L, build.versionCode)
        assertEquals(27, build.minSdk)
        assertEquals(33, build.targetSdk)
        assertFalse(build.isDebug)
        assertEquals("Основная", build.variantLabel)
        assertEquals(BuildConfig.CI_BUILD_NUMBER, build.buildNumber)
        assertEquals(BuildConfig.SOURCE_REVISION, build.revision)
        assertEquals(Build.VERSION.SDK_INT, build.androidSdk)
        assertEquals(Build.SUPPORTED_ABIS.joinToString(", "), build.deviceAbis)
    }

    @Test fun localBuildHasNoInventedCiNumberOrRevision() {
        val build = readAppBuildInfo(RuntimeEnvironment.getApplication()).copy(buildNumber = "", revision = "", isDebug = true)
        assertEquals("Локальная сборка", build.buildLabel)
        assertEquals("Не указана", build.revisionLabel)
        assertEquals("Отладочная", build.variantLabel)
        assertTrue(build.diagnostics().contains("Сборка: Локальная сборка"))
        assertTrue(build.diagnostics().contains("Ревизия: Не указана"))
    }

    @Config(sdk = [26])
    @Test fun diagnosticsWorkOnTheMinimumSupportedAndroid() {
        val context = RuntimeEnvironment.getApplication()
        val build = readAppBuildInfo(context)
        @Suppress("DEPRECATION")
        val expectedCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        assertEquals(expectedCode, build.versionCode)
        assertEquals(26, build.androidSdk)
        assertTrue(build.diagnostics().contains("Пакет: ${context.packageName}"))
    }
}
