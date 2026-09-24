package jp.kenshopocket.app.data

import org.junit.Assert.assertThrows
import org.junit.Test

class CampaignRepositoryTest {
    @Test fun acceptsOrdinaryHttpsUrl() {
        CampaignRepository.requireSafeHttpsUrl("https://example.invalid/apply?token=AbC#entry")
    }

    @Test fun rejectsJavascriptUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            CampaignRepository.requireSafeHttpsUrl("javascript:alert(1)")
        }
    }

    @Test fun rejectsHttpAndUserInfo() {
        assertThrows(IllegalArgumentException::class.java) {
            CampaignRepository.requireSafeHttpsUrl("http://example.invalid/apply")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CampaignRepository.requireSafeHttpsUrl("https://user@example.invalid/apply")
        }
    }
}
