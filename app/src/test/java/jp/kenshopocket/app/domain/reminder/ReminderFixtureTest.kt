package jp.kenshopocket.app.domain.reminder

import android.app.Application
import java.time.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ReminderFixtureTest {
    @Test fun readsAndVerifiesEveryShippedReminderFixture() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("reminder_cases.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val cases = fixture.getJSONArray("cases")
        assertEquals(12, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            when (case.getString("operation")) {
                "planDeadlineReminders" -> {
                    val d = case.getJSONObject("deadline")
                    val offsets = case.getJSONArray("dayOffsets")
                    val minutes = case.getJSONArray("minutesBefore")
                    val result = ReminderLogic.planDeadlineReminders(Instant.parse(case.getString("now")), DeadlineInput(d.getString("precision"), LocalDate.parse(d.getString("date")), if (d.isNull("time")) null else LocalTime.parse(d.getString("time")), ZoneId.of(d.getString("zoneId")), d.getBoolean("confirmed")), (0 until offsets.length()).map { offsets.getLong(it) }, LocalTime.parse(case.getString("localTime")), (0 until minutes.length()).map { minutes.getLong(it) })
                    val expected = case.getJSONArray("expectedPlannedAt")
                    assertEquals(id, (0 until expected.length()).map { expected.getString(it) }, result.plannedAt.map { it.toString() })
                    if (case.has("expectedReason")) assertEquals(id, case.getString("expectedReason"), result.reason)
                    if (case.has("expectedPrecisionWarning")) assertEquals(id, case.getString("expectedPrecisionWarning"), result.warning)
                }
                "nextRepeatingReminder", "calculatePeriod" -> {
                    val r = case.getJSONObject("rule")
                    val rule = RepeatRule(r.getString("mode"), ZoneId.of(r.getString("zoneId")), LocalTime.parse(r.getString("resetTime")), LocalTime.parse(r.optString("remindTime", "09:00")), r.optBoolean("confirmed", true))
                    if (case.getString("operation") == "calculatePeriod") {
                        val result = ReminderLogic.calculatePeriod(Instant.parse(case.getString("at")), rule)
                        assertEquals(id, case.getString("expectedStart"), result.start.toString())
                        assertEquals(id, case.getString("expectedEnd"), result.end.toString())
                    } else {
                        val entries = case.getJSONArray("activeApplications")
                        val result = ReminderLogic.nextRepeatingReminder(Instant.parse(case.getString("now")), rule, (0 until entries.length()).map { Instant.parse(entries.getString(it)) })
                        assertEquals(id, case.getString("expectedNextAt"), result.toString())
                    }
                }
                "evaluateDispatch" -> assertEquals(id, case.getString("expected"), ReminderLogic.evaluateDispatch(Instant.parse(case.getString("now")), Instant.parse(case.getString("validUntil")), case.getString("purpose"), case.optBoolean("appliedInPeriod"), case.optBoolean("received"), case.getBoolean("permissionGranted")))
                "applyQuietHours" -> {
                    val result = ReminderLogic.applyQuietHours(Instant.parse(case.getString("plannedAt")), Instant.parse(case.getString("validUntil")), ZoneId.of(case.getString("zoneId")), LocalTime.parse(case.getString("quietStart")), LocalTime.parse(case.getString("quietEnd")))
                    assertNull(id, result.effectiveAt)
                    assertEquals(id, case.getString("expectedReason"), result.reason)
                }
                else -> fail("Unknown fixture operation: $id")
            }
        }
    }
}
