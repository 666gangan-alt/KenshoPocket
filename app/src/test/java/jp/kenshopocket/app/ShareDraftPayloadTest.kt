package jp.kenshopocket.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareDraftPayloadTest {
    @Test fun legacyRawSharedDraftRemainsReReadable() {
        assertEquals("懸賞のお知らせ https://example.invalid/apply", decodeLegacySharedText("懸賞のお知らせ https://example.invalid/apply"))
    }

    @Test fun manualCampaignDraftIsNotReinterpretedAsSharedText() {
        val payload = JSONObject().put("kind", "MANUAL_CAMPAIGN").put("title", "手動").toString()
        assertNull(decodeLegacySharedText(payload))
    }
}
