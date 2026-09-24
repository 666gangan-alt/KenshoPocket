package jp.kenshopocket.app.domain.importer

import java.net.URI
import java.text.Normalizer
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class SourceKind { SHARED_TEXT, OCR, MANUAL }

data class ImportCandidate(
    val title: String,
    val titleRequiresReview: Boolean,
    val dateCandidate: LocalDate?,
    val timeCandidate: LocalTime?,
    val deadlineConfirmed: Boolean = false,
    val launchUrlCandidate: String?,
    val relatedUrls: List<String>,
    val urlReviewRequired: Boolean,
    val entryModeCandidate: String = "ONCE",
    val warnings: Set<String> = emptySet(),
    val sourceText: String,
)

class ImportParser(private val clock: Clock = Clock.systemUTC()) {
    fun parse(text: String, sourceKind: SourceKind, zoneId: ZoneId = ZoneId.of("Asia/Tokyo")): List<ImportCandidate> {
        require(text.length <= 200_000) { "原文は20万文字以下にしてください" }
        val normalizedLines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val blocks = splitBlocks(normalizedLines)
        val parsed = blocks.mapNotNull { parseBlock(it, sourceKind, zoneId) }.toMutableList()
        if (parsed.isEmpty() && text.isNotBlank()) parsed += parseBlock(normalizedLines, sourceKind, zoneId) ?: return emptyList()
        val merged = mutableListOf<ImportCandidate>()
        for (candidate in parsed) {
            val duplicateIndex = merged.indexOfFirst { existing ->
                candidate.relatedUrls.isNotEmpty() && existing.relatedUrls.any(candidate.relatedUrls::contains)
            }
            if (duplicateIndex < 0) merged += candidate else {
                val old = merged[duplicateIndex]
                merged[duplicateIndex] = old.copy(
                    relatedUrls = (old.relatedUrls + candidate.relatedUrls).distinct(),
                    warnings = old.warnings + candidate.warnings,
                    sourceText = old.sourceText + "\n\n" + candidate.sourceText,
                )
            }
        }
        return merged.take(100)
    }

    private fun splitBlocks(lines: List<String>): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) { result += current; current = mutableListOf() }
            } else current += line.trim()
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun parseBlock(lines: List<String>, sourceKind: SourceKind, zoneId: ZoneId): ImportCandidate? {
        if (lines.all(String::isBlank)) return null
        val source = lines.joinToString("\n")
        val warnings = linkedSetOf<String>()
        val urls = linkedSetOf<String>()
        var launchUrl: String? = null
        var urlReview = false
        for (line in lines) {
            for (token in urlTokens(line)) {
                val parsed = parseUrl(token, sourceKind)
                warnings += parsed.second
                if (parsed.first != null) {
                    urls += parsed.first!!
                    if (line.contains("応募") || launchUrl == null) launchUrl = parsed.first
                    if (parsed.second.isNotEmpty()) urlReview = true
                }
            }
        }
        if (sourceKind == SourceKind.OCR && urls.isNotEmpty()) { warnings += "OCR_URL_REVIEW"; urlReview = true }
        if (source.contains("終わり") || source.contains("終了")) warnings += "ENDED_MENTION"
        if ((source.contains("ポイント") || source.contains("P豰")) && (source.contains("抽選") || source.contains("くじ"))) warnings += "BENEFIT_TYPE_AMBIGUOUS"
        if (source.contains("24時間ごと")) warnings += "ROLLING_INTERVAL_UNSUPPORTED"
        if (Regex("今日|明日|昨日").containsMatchIn(source)) warnings += "RELATIVE_DATE_UNCERTAIN"

        val analysis = normalizeForAnalysis(source)
        val date = parseDeadlineDate(analysis, zoneId, warnings)
        val time = parseTime(analysis)
        val titleLine = lines.firstOrNull { line -> urlTokens(line).isEmpty() || line.replace(urlRegex, "").isNotBlank() }.orEmpty()
        val title = cleanTitle(titleLine).ifBlank { "タイトル未確認" }
        val titleReview = title == "タイトル未確認" || (lines.size == 1 && urls.isNotEmpty() && cleanTitle(lines[0]).isBlank())
        if (urls.isEmpty() && !source.contains(":") && !source.contains("：") && date == null && warnings.isEmpty()) return null
        return ImportCandidate(
            title = title,
            titleRequiresReview = titleReview,
            dateCandidate = date,
            timeCandidate = time,
            launchUrlCandidate = launchUrl,
            relatedUrls = urls.toList(),
            urlReviewRequired = urlReview,
            entryModeCandidate = if (source.contains("24時間ごと")) "MANUAL" else if (source.contains("毎日")) "DAILY" else "ONCE",
            warnings = warnings,
            sourceText = source,
        )
    }

    private fun parseDeadlineDate(text: String, zoneId: ZoneId, warnings: MutableSet<String>): LocalDate? {
        val reference = clock.instant().atZone(zoneId).toLocalDate()
        val rangeLine = text.lines().firstOrNull { it.contains("応募期間") }
        val target = rangeLine ?: text.lines().filterNot { it.contains("発表") || it.contains("当選日") }.joinToString("\n")
        val fullMatches = fullDateRegex.findAll(target).toList()
        if (fullMatches.isNotEmpty()) {
            val chosen = fullMatches.last()
            return validDate(chosen.groupValues[1].toInt(), chosen.groupValues[2].toInt(), chosen.groupValues[3].toInt(), warnings)
        }
        val short = shortDateRegex.find(target) ?: return null
        val month = short.groupValues[1].toInt()
        val day = short.groupValues[2].toInt()
        warnings += "YEAR_MISSING"
        val candidates = (reference.year - 1..reference.year + 1).mapNotNull { year ->
            try { LocalDate.of(year, month, day) } catch (_: DateTimeException) { null }
        }
        if (candidates.isEmpty()) { warnings += "INVALID_DATE"; return null }
        val selected = candidates.minWith(compareBy<LocalDate> { kotlin.math.abs(ChronoUnit.DAYS.between(reference, it)) }.thenByDescending { it })
        if (selected.isBefore(reference)) warnings += "PAST_DATE_CANDIDATE"
        return selected
    }

    private fun validDate(year: Int, month: Int, day: Int, warnings: MutableSet<String>): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        warnings += "INVALID_DATE"
        null
    }

    private fun parseTime(text: String): LocalTime? {
        val match = timeRegex.find(text) ?: return null
        return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
    }

    private fun cleanTitle(value: String): String = normalizeForAnalysis(value)
        .replace(urlRegex, "")
        .replace(fullDateRegex, "")
        .replace(shortDateRegex, "")
        .replace(timeRegex, "")
        .replace(Regex("[()（）]ポイント.*"), "")
        .replace(Regex("まで$"), "")
        .trim(' ', '　', '《', '》')

    private fun parseUrl(token: String, sourceKind: SourceKind): Pair<String?, Set<String>> {
        val clean = token.trim().trimEnd('。', '、', ')', '）', ']', '》')
        if (Regex("^(javascript|file|content|intent):", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return null to setOf("UNSUPPORTED_URL_SCHEME")
        val withScheme = if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) clean else "https://$clean"
        val warnings = linkedSetOf<String>()
        if (withScheme != clean) warnings += "SCHEME_ADDED"
        val uri = runCatching { URI(withScheme) }.getOrNull()
        if (uri == null || uri.host == null || uri.userInfo != null || (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true))) return null to setOf("UNSUPPORTED_URL_SCHEME")
        if (sourceKind == SourceKind.OCR) warnings += "OCR_URL_REVIEW"
        return withScheme to warnings
    }

    private fun urlTokens(line: String): List<String> = urlRegex.findAll(line).map { it.value }.toList()
    private fun normalizeForAnalysis(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

    companion object {
        private val urlRegex = Regex("(?i)(?:https?://|javascript:|file:|content:|intent:)[^\\s<>「」]+|(?:[A-Za-z0-9-]+\\.)+(?:invalid|com|net|org|jp)(?:/[^\\s<>「」]*)?")
        private val fullDateRegex = Regex("(?<!\\d)(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)")
        private val shortDateRegex = Regex("(?<![\\d/])(\\d{1,2})[/-](\\d{1,2})(?![\\d/])")
        private val timeRegex = Regex("(?<!\\d)(\\d{1,2})(?::|時)(\\d{2})(?:分)?")
    }
}
