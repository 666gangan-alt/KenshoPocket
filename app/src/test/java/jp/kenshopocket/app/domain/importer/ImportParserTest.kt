package jp.kenshopocket.app.domain.importer

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val parser = ImportParser(Clock.fixed(Instant.parse("2026-09-23T02:32:00Z"), zone))
    private fun parse(text: String, kind: SourceKind = SourceKind.SHARED_TEXT) = parser.parse(text, kind, zone)

    @Test fun multipleCampaignsAndMissingYears() {
        val values = parse("""
            《その他抽選》

            お茶A くじは終わり
            （ポイント 5P豰えます）11/4
            9時59分まで
            https://example.invalid/tea-a

            ジュースB 11/30
            https://example.invalid/juice-b

            紅茶C 1/10
            https://example.invalid/tea-c

            麦茶D 9/27
            https://example.invalid/tea-d

            エナジーE 12/27
            https://example.invalid/energy-e

            コラボF 10/11
            https://example.invalid/collab-f
        """.trimIndent())
        assertEquals(6, values.size)
        assertEquals("2026-11-04", values[0].dateCandidate.toString())
        assertEquals("09:59", values[0].timeCandidate.toString())
        assertTrue(values[0].warnings.containsAll(setOf("YEAR_MISSING", "ENDED_MENTION", "BENEFIT_TYPE_AMBIGUOUS")))
        assertEquals("2027-01-10", values[2].dateCandidate.toString())
        assertTrue(values.none { it.deadlineConfirmed })
    }

    @Test fun yearSelectionDoesNotForcePastDateForward() {
        val january = parse("紅茶 1/10\nhttps://example.invalid/january").single()
        assertEquals("2027-01-10", january.dateCandidate.toString())
        val yesterday = parse("昨日締切の可能性 9/22\nhttps://example.invalid/past").single()
        assertEquals("2026-09-22", yesterday.dateCandidate.toString())
        assertTrue("PAST_DATE_CANDIDATE" in yesterday.warnings)
    }

    @Test fun fullDateTimeStillNeedsConfirmation() {
        val value = parse("抽選 2026/11/4 9時59分まで\nhttps://example.invalid/timed").single()
        assertEquals("2026-11-04", value.dateCandidate.toString())
        assertEquals("09:59", value.timeCandidate.toString())
        assertFalse(value.deadlineConfirmed)
    }

    @Test fun invalidDatesAreNotRounded() {
        listOf("2026/9/31", "2026/2/29").forEach { date ->
            val value = parse("抽選 $date\nhttps://example.invalid/invalid").single()
            assertNull(value.dateCandidate)
            assertTrue("INVALID_DATE" in value.warnings)
        }
    }

    @Test fun preservesUrlCaseAndNormalizesOnlyDateCopy() {
        val value = parse("飲料 ２０２６／１１／３０\nhttps://example.invalid/AbC?Token=AbC%2F1#Form").single()
        assertEquals("2026-11-30", value.dateCandidate.toString())
        assertEquals("https://example.invalid/AbC?Token=AbC%2F1#Form", value.launchUrlCandidate)
    }

    @Test fun schemeAdditionAndOcrNeedReview() {
        val scheme = parse("企画 11/30\nexample.invalid/path").single()
        assertEquals("https://example.invalid/path", scheme.launchUrlCandidate)
        assertTrue(scheme.urlReviewRequired)
        assertTrue("SCHEME_ADDED" in scheme.warnings)
        val ocr = parse("お茶 11/30\nhttps://example.invalid/O0Il1", SourceKind.OCR).single()
        assertEquals("https://example.invalid/O0Il1", ocr.launchUrlCandidate)
        assertTrue("OCR_URL_REVIEW" in ocr.warnings)
    }

    @Test fun httpUrlRemainsCandidateButRequiresReview() {
        val value = parse("企画 11/30\nhttp://example.invalid/apply").single()
        assertEquals("http://example.invalid/apply", value.launchUrlCandidate)
        assertTrue(value.urlReviewRequired)
        assertTrue("INSECURE_HTTP_REVIEW" in value.warnings)
    }

    @Test fun relatedUrlsAndDuplicates() {
        val related = parse("企画 2026/11/30\n公式案内 https://example.invalid/info\n応募 https://example.invalid/apply").single()
        assertEquals(2, related.relatedUrls.size)
        assertEquals("https://example.invalid/apply", related.launchUrlCandidate)
        val duplicate = parse("企画 2026/11/30\nhttps://example.invalid/one\n\nリンクプレビュー\nhttps://example.invalid/one").single()
        assertEquals(1, duplicate.relatedUrls.size)
    }

    @Test fun rejectsDangerousScheme() {
        val value = parse("企画\njavascript:alert(1)").single()
        assertNull(value.launchUrlCandidate)
        assertTrue("UNSUPPORTED_URL_SCHEME" in value.warnings)
    }

    @Test fun separatesApplicationEndFromAnnouncement() {
        val value = parse("企画\n応募期間 2026/9/1〜2026/9/30\n当選発表 2026/10/10\nhttps://example.invalid/range").single()
        assertEquals("2026-09-30", value.dateCandidate.toString())
    }

    @Test fun rollingIntervalIsNotDaily() {
        val value = parse("抽選 24時間ごとに応募可能\nhttps://example.invalid/rolling").single()
        assertEquals("MANUAL", value.entryModeCandidate)
        assertTrue("ROLLING_INTERVAL_UNSUPPORTED" in value.warnings)
    }

    @Test fun urlOnlyAndRelativeDateStayUnconfirmed() {
        val urlOnly = parse("https://example.invalid/url-only").single()
        assertTrue(urlOnly.titleRequiresReview)
        assertNull(urlOnly.dateCandidate)
        val relative = parse("今日まで\nhttps://example.invalid/relative").single()
        assertTrue("RELATIVE_DATE_UNCERTAIN" in relative.warnings)
        assertFalse(relative.deadlineConfirmed)
    }
}
