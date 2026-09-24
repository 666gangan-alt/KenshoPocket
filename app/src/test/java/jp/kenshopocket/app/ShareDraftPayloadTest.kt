package jp.kenshopocket.app

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ShareDraftPayloadTest {
    @Test fun legacyRawSharedDraftRemainsReReadable() {
        assertEquals("懸賞のお知らせ https://example.invalid/apply", decodeLegacySharedText("懸賞のお知らせ https://example.invalid/apply"))
    }

    @Test fun manualCampaignDraftIsNotReinterpretedAsSharedText() {
        val payload = "{\"kind\":\"MANUAL_CAMPAIGN\",\"title\":\"手動\"}"
        assertNull(decodeLegacySharedText(payload))
    }
}
