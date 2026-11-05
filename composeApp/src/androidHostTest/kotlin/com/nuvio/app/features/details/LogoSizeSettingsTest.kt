package com.nuvio.app.features.details

import android.app.Application
import android.content.Context
import com.nuvio.app.core.ui.ArtworkLogoScale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogoSizeSettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_meta_screen_settings", Context.MODE_PRIVATE).edit().clear().commit()
        MetaScreenSettingsStorage.initialize(context)
        MetaScreenSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        MetaScreenSettingsRepository.clearLocalState()
    }

    @Test
    fun theDetailLogoSizeIsKeptWithinItsBoundsAndSurvivesAReload() {
        assertEquals(100, MetaScreenSettingsRepository.uiState.value.logoScalePercent)

        MetaScreenSettingsRepository.setLogoScalePercent(150)
        MetaScreenSettingsRepository.onProfileChanged()
        assertEquals(150, MetaScreenSettingsRepository.uiState.value.logoScalePercent)

        MetaScreenSettingsRepository.setLogoScalePercent(999)
        assertEquals(ArtworkLogoScale.MAX_PERCENT, MetaScreenSettingsRepository.uiState.value.logoScalePercent)
        MetaScreenSettingsRepository.setLogoScalePercent(1)
        assertEquals(ArtworkLogoScale.MIN_PERCENT, MetaScreenSettingsRepository.uiState.value.logoScalePercent)

        MetaScreenSettingsRepository.resetToDefaults()
        assertEquals(ArtworkLogoScale.DEFAULT_PERCENT, MetaScreenSettingsRepository.uiState.value.logoScalePercent)
    }

    @Test
    fun anOlderPayloadWithoutTheSizeKeepsTheUsualSize() {
        MetaScreenSettingsStorage.savePayload("""{"blur_unwatched_episodes":true}""")
        MetaScreenSettingsRepository.onProfileChanged()
        assertEquals(ArtworkLogoScale.DEFAULT_PERCENT, MetaScreenSettingsRepository.uiState.value.logoScalePercent)
    }
}
