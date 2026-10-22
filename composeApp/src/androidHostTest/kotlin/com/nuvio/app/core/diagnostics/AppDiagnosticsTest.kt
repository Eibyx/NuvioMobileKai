package com.nuvio.app.core.diagnostics

import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import com.nuvio.app.R
import com.nuvio.app.core.logging.InAppLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDiagnosticsTest {
    @Test
    fun crashReportsAreRedactedCappedAtFiveAndExportedThroughTheShareSheet() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                authority = "${context.packageName}.fileprovider"
                name = FileProvider::class.java.name
                packageName = context.packageName
                applicationInfo = context.applicationInfo
                grantUriPermissions = true
                metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
            },
        )
        AppDiagnostics.initialize(context)
        AppDiagnostics.clear()

        InAppLogger.info("Debrid", "GET https://api.torbox.app/v1/api/user/me?token=tb-secret-123")
        repeat(7) { index ->
            AppDiagnostics.writeCrashReport(
                Thread.currentThread(),
                IllegalStateException("crash $index while signed in as kai@example.com"),
            )
            Thread.sleep(2) // distinct timestamps
        }

        val reports = AppDiagnostics.reportFiles()
        assertEquals(5, reports.size)
        assertTrue(reports.first().readText().contains("crash 6"), "newest report is kept")
        reports.forEach { report ->
            val text = report.readText()
            assertFalse(text.contains("kai@example.com"), text)
            assertFalse(text.contains("tb-secret-123"), text)
            assertTrue(text.contains("at com.nuvio.app.core.diagnostics.AppDiagnosticsTest"), text)
        }

        assertTrue(AppDiagnostics.exportAndShare().isSuccess)
        val chooser = assertNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals("text/plain", send.type)

        AppDiagnostics.clear()
        assertEquals(0, AppDiagnostics.reportFiles().size)
        assertEquals(null, AppDiagnostics.notice.value)
    }

    /** FileProvider caches its roots per authority, and every Robolectric test has its own directories. */
    @Before
    @After
    fun forgetFileProviderRoots() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }
}
