package com.os4.musiccover

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/**
 * Whatever a lyric source handed us, turned into lines the renderer can draw.
 *
 * The formats are not ours to choose - the AMLL database alone ships TTML, LRC, YRC, QRC and
 * Lyricify for the same song - so AutoParser sniffs the format and this is the only place that
 * knows there was ever more than one. Kotlin only because AutoParser's constructor is all default
 * arguments, which Java cannot call.
 */
object LyricParse {

    /** Whether romanisation is allowed to be merged into the text shown below a lyric line. */
    @Volatile
    private var displayRoma: Boolean = false

    @JvmStatic
    fun setDisplayRoma(enabled: Boolean) {
        displayRoma = enabled
    }

    /**
     * The same thing for a source that ships its translation separately.
     *
     * NetEase does: the timed lyric comes back under "yrc" (or "lrc") and the translated lines
     * under "tlyric", as a second plain LRC with no link to the first beyond its timestamps. So
     * the two are joined here by time rather than by index - the counts do not match, because
     * the translation has no entries for the instrumental breaks or the credits, and pairing
     * them off in order would slide every translation one line up partway through the song.
     */
    @JvmStatic
    fun parse(body: String, translation: String?): List<LyricLine> {
        val lines = parse(body)
        if (translation.isNullOrBlank() || lines.isEmpty()) return lines
        val tr = lrc(translation)
        if (tr.isEmpty()) return lines
        val texts = assign(lines, tr)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val text = texts[i]
            out.add(if (text == null) line else withTranslation(line, text))
        }
        return out
    }

    /**
     * The same again with a romanisation beside the translation - QQ Music, NetEase and Kuwo
     * ship one for Japanese and Korean songs, as a third timed text joined by time like the
     * translation. It is shown in the translation's place, above the translation when there is
     * both, and goes with the translation switch.
     */
    @JvmStatic
    fun parse(body: String, translation: String?, roma: String?): List<LyricLine> {
        val lines = parse(body, translation)
        if (!displayRoma || roma.isNullOrBlank() || lines.isEmpty()) return lines
        val ro = lrc(roma)
        if (ro.isEmpty()) return lines
        val best = assign(lines, ro)
        val out = ArrayList<LyricLine>(lines.size)
        for ((i, line) in lines.withIndex()) {
            val r = best[i]
            val merged = if (r == null) null else withRoma(r, line.text, line.translation)
            out.add(if (merged == null || merged == line.translation) line
                else withTranslation(line, merged))
        }
        return out
    }

    /** Whether an online copy is worth asking for after a local lyric was found. */
    @JvmStatic
    fun needsSupplement(lines: List<LyricLine>): Boolean {
        for (line in lines) {
            val t = line.translation?.trim()
            if (t.isNullOrBlank()) return true
            if (displayRoma && !t.contains('\n')) return true
        }
        return false
    }

    /**
     * Supplements an authoritative local/file lyric with translation or romanisation from an
     * online copy without replacing the local original or its word timings. This is deliberately
     * a merge, not a second source choice: Salt's file remains the displayed source, while a
     * catalogue is only allowed to fill something the file does not have.
     */
    @JvmStatic
    fun mergeSupplement(base: List<LyricLine>, online: List<LyricLine>): List<LyricLine> {
        if (base.isEmpty() || online.isEmpty()) return base
        val candidates = online.filter { !it.translation.isNullOrBlank() }
        if (candidates.isEmpty()) return base

        val starts = IntArray(candidates.size) { candidates[it].start }
        val out = ArrayList<LyricLine>(base.size)
        var changed = false
        for (line in base) {
            val current = line.translation?.trim()
            val candidate = nearestTranslation(candidates, starts, line.start)
            if (candidate.isNullOrBlank()) {
                out.add(line)
                continue
            }

            val merged = when {
                current.isNullOrBlank() -> candidate
                displayRoma && candidate.contains('\n') && !current.contains('\n') -> candidate
                displayRoma && !current.contains('\n') && looksRomanisation(candidate, line.text) ->
                    candidate + "\n" + current
                else -> current
            }
            if (merged == current) out.add(line)
            else {
                out.add(withTranslation(line, merged))
                changed = true
            }
        }
        return if (changed) out else base
    }

    /** A small per-line nearest match; network copies routinely disagree by a few hundred ms. */
    private fun nearestTranslation(
        lines: List<LyricLine>,
        starts: IntArray,
        at: Int,
    ): String? {
        if (lines.isEmpty()) return null
        var i = starts.binarySearch(at)
        if (i < 0) {
            val ins = -i - 1
            i = when {
                ins == 0 -> 0
                ins >= starts.size -> starts.size - 1
                at - starts[ins - 1] <= starts[ins] - at -> ins - 1
                else -> ins
            }
        }
        return if (kotlin.math.abs(starts[i] - at) <= TRANSLATION_WINDOW_MS) {
            lines[i].translation?.trim()
        } else null
    }

    /**
     * A candidate with Latin letters but no CJK/Japanese syllabary is a safe enough romanisation
     * signal for this supplement. The main line must contain non-Latin text, so an English lyric
     * cannot turn into a duplicated second line by accident.
     */
    private fun looksRomanisation(candidate: String, main: String): Boolean {
        if (!candidate.any { it.isLetter() && it.code < 0x200 }) return false
        if (!main.any { it.isLetter() && it.code >= 0x200 }) return false
        if (candidate.any {
                (it in '\u3040'..'\u30ff') ||
                (it in '\u3400'..'\u4dbf') ||
                (it in '\u4e00'..'\u9fff')
            }) return false
        return true
    }

    /**
     * Each timed entry of a translation or romanisation, given to the ONE line nearest it - not
     * each line taking the nearest entry. A catalogue leaves its credit lines untranslated, and
     * asked the other way round the credits 500ms before the first verse took the verse's
     * translation as their own (QQ's Lemon, 2026-09-24). One entry, one line; the closer of two
     * claimants keeps it. See TRANSLATION_WINDOW_MS for the window.
     */
    private fun assign(lines: List<LyricLine>, entries: List<Pair<Int, String>>): Array<String?> {
        val starts = IntArray(lines.size) { lines[it].start }
        val best = arrayOfNulls<String>(lines.size)
        val gaps = IntArray(lines.size) { Int.MAX_VALUE }
        for ((at, text) in entries) {
            var i = starts.binarySearch(at)
            if (i < 0) {
                val ins = -i - 1
                i = when {
                    ins == 0 -> 0
                    ins >= starts.size -> starts.size - 1
                    at - starts[ins - 1] <= starts[ins] - at -> ins - 1
                    else -> ins
                }
            }
            val gap = kotlin.math.abs(starts[i] - at)
            if (gap <= TRANSLATION_WINDOW_MS && gap < gaps[i]) {
                gaps[i] = gap
                best[i] = text
            }
        }
        return best
    }

    /**
     * The romanisation over the translation, as the one text the renderer draws under a line.
     * Left out when it only repeats the line - a catalogue "romanises" an English song into
     * itself.
     */
    internal fun withRoma(roma: String?, text: String, translation: String?): String? {
        val r = roma?.trim()?.replace(Regex("\\s+"), " ")
        if (r.isNullOrEmpty() || letters(r) == letters(text)) return translation
        return if (translation.isNullOrBlank()) r else r + "\n" + translation.trim()
    }

    private fun letters(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * How far a translated line's time may sit from its line's and still be its translation.
     *
     * A window rather than an equality test: yrc and tlyric are timed independently, and the
     * same line measured here starts at 4390ms in the word-timed copy and 4705ms in the LRC -
     * close enough to be obviously the same line, far enough apart that matching exactly would
     * find nothing at all. The window is wide enough for that drift and narrower than the gap
     * between two sung lines, so the nearest line inside it is the right one.
     */
    private const val TRANSLATION_WINDOW_MS = 1500

    /** Timestamped lines of a plain LRC, in time order, with the empty ones left out. */
    private fun lrc(body: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (raw in body.split('\n')) {
            val m = LRC_TIME.find(raw) ?: continue
            val text = raw.substring(m.range.last + 1)
                .replace(LRC_TIME_TAG, " ")
                .trim()
            if (text.isEmpty()) continue
            val min = m.groupValues[1].toIntOrNull() ?: continue
            val sec = m.groupValues[2].toIntOrNull() ?: continue
            val frac = m.groupValues[3]
            // One digit is tenths, two are hundredths, three are milliseconds.
            val ms = when (frac.length) {
                0 -> 0
                1 -> (frac.toIntOrNull() ?: 0) * 100
                3 -> frac.toIntOrNull() ?: 0
                else -> (frac.toIntOrNull() ?: 0) * 10
            }
            out.add(Pair(min * 60000 + sec * 1000 + ms, text))
        }
        out.sortBy { it.first }
        return out
    }

    private val LRC_TIME = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val LRC_TIME_TAG = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")

    /** The same line carrying a translation it did not come with. */
    private fun withTranslation(line: LyricLine, text: String): LyricLine {
        val copy = LyricLine(line.text, text, line.start, line.end, line.opposite,
            line.sylStart, line.sylEnd, line.charEnd)
        copy.bg = line.bg
        return copy
    }

    @JvmStatic
    fun parse(body: String): List<LyricLine> {
        // Normalize the raw LRC before AutoParser sees it. This follows the proven LyricInfo
        // approach: word-level [time] tags are converted to Enhanced-LRC <time> tags first,
        // so an end timestamp such as [01:15.342] is timing metadata rather than lyric text.
        // Speaker prefixes are also removed before parsing, which keeps charEnd in the same
        // coordinate space as the final displayed text and avoids the old first-character loss.
        val normalized = normalizeLyricBody(normalizeSpeakerPrefixes(body))
        val lyrics = AutoParser().parse(normalized.body)
        val src = lyrics.lines
        val out = ArrayList<LyricLine>(src.size)
        // Where the tail of a line may run to when the file left it without an end of its own:
        // the arrival of the line after it. Looked up by time and not read off the list: the list
        // is sorted only at the end of this function, and a background vocal handed back as a
        // line of its own starts inside the line it echoes - taken as the next line, it would cut
        // the last word down to the few milliseconds before the echo.
        val mains = src.filter { it !is KaraokeLine.AccompanimentKaraokeLine }
            .map { it.start }.sorted().toIntArray()
        for (line in src) {
            val nextStart = nextAfter(mains, line.start, line.end)
            when (line) {
                // Background vocals overlap the main line in time, so they are not lines of their
                // own - the renderer finds the singing line by start time, and one would steal
                // the focus for the length of an echo. They hang under the main line they belong
                // to (the last one started by then), and stretch it if they outlast it.
                is KaraokeLine.AccompanimentKaraokeLine -> {
                    val b = karaoke(line, nextStart) ?: continue
                    val owner = out.lastOrNull { it.start <= b.start } ?: continue
                    if (owner.bg == null) {
                        owner.bg = b
                        if (b.end > owner.end) owner.end = b.end
                    }
                }
                is KaraokeLine -> {
                    val main = karaoke(line, nextStart)
                    if (main != null) {
                        out.add(main)
                        // Where the accompaniment actually arrives. The branch above is written
                        // for a parser that hands background vocals back as lines of their own,
                        // and lyrics-core 0.4.7 does not: it hangs them on the main line as a
                        // property, so that branch never fires and every background vocal was
                        // being dropped. Both are kept - which shape comes back is the library's
                        // business, and a version that goes back to separate lines still works.
                        val acc = (line as? KaraokeLine.MainKaraokeLine)
                            ?.accompanimentLines?.firstOrNull()
                        val b = acc?.let { karaoke(it, nextStart) }
                        if (b != null) {
                            main.bg = b
                            if (b.end > main.end) main.end = b.end
                        }
                    }
                }
                is SyncedLine -> {
                    // Instrumental breaks arrive as empty lines; a row of nothing would take a
                    // slot in the stack for its whole duration.
                    if (line.content.isBlank()) continue
                    out.add(LyricLine(line.content.trim(), line.translation,
                        line.start, line.end, false, null, null, null))
                }
            }
        }
        out.sortBy { it.start }
        var spoken = applySpeakerSides(out, normalized.speakerSides)
        if (normalized.translation.isNotEmpty()) {
            val assigned = assignWindow(spoken, normalized.translation, LOCAL_TRANSLATION_WINDOW_MS)
            val with = ArrayList<LyricLine>(spoken.size)
            for (i in spoken.indices) {
                val tr = assigned[i]
                with.add(if (tr == null) spoken[i] else withTranslation(spoken[i], tr))
            }
            spoken = with
        }
        return spoken
    }

    private data class SpeakerPrepared(
        val body: String,
        val speakerSides: Map<Int, Boolean>,
    )

    private data class BodyNormalized(
        val body: String,
        val translation: List<Pair<Int, String>>,
        val speakerSides: Map<Int, Boolean>,
    )

    private data class MainRange(val start: Int, val end: Int)

    /**
     * The old good Salt/LI route stripped duet labels before lyric normalization. Do the same in
     * HMC instead of removing the label after AutoParser has already calculated word ranges.
     * Once the parser never sees "女：" at all, its charEnd naturally lines up with "镜中的自己".
     */
    private fun normalizeSpeakerPrefixes(body: String): SpeakerPrepared {
        val rows = body.split('\n')
        data class Hit(val index: Int, val start: Int, val label: String, val prefixEnd: Int, val raw: String)

        val hits = ArrayList<Hit>()
        for ((index, raw) in rows.withIndex()) {
            val m = RAW_SPEAKER_PREFIX.find(raw) ?: continue
            val startMatch = LRC_TIME_ALL.find(raw) ?: continue
            val start = timeMillis(startMatch) ?: continue
            val label = m.groupValues[5]
            if (label in TOGETHER || isCredit(label + ":")) continue
            hits.add(Hit(index, start, label, m.range.last + 1, raw))
        }

        val counts = hits.groupingBy { it.label }.eachCount()
        val singers = LinkedHashSet<String>()
        for (hit in hits) if ((counts[hit.label] ?: 0) >= 2) singers.add(hit.label)
        if (singers.size < 2) return SpeakerPrepared(body, emptyMap())

        val order = singers.toList()
        val sideByStart = HashMap<Int, Boolean>()
        val out = rows.toMutableList()
        for (hit in hits) {
            if (hit.label !in singers && hit.label !in TOGETHER) continue
            val right = hit.label !in TOGETHER && order.indexOf(hit.label) % 2 == 1
            sideByStart[hit.start] = right
            val m = RAW_SPEAKER_PREFIX.find(hit.raw)!!
            out[hit.index] = hit.raw.removeRange(m.range.first + m.groupValues[1].length, m.range.last + 1)
        }
        return SpeakerPrepared(out.joinToString("\n"), sideByStart)
    }

    /**
     * Mirrors the useful part of the previous LyricNormalizer.wordLrcToElrc implementation:
     * [line]word[end] becomes [line]<line>word, and the final bracket tag is omitted when it has
     * no following text. This fixes [01:12.392]Goodbyes[01:15.342] before AutoParser can expose the
     * end timestamp as visible lyric text.
     */
    private fun normalizeLyricBody(prepared: SpeakerPrepared): BodyNormalized {
        val rows = prepared.body.split('\n')
        val parsed = rows.map { raw ->
            val matches = LRC_TIME_ALL.findAll(raw).toList()
            val times = matches.mapNotNull { timeMillis(it) }
            val first = matches.firstOrNull()
            val text = when {
                first != null -> raw.substring(first.range.last + 1).trim()
                else -> raw.trim()
            }
            Triple(raw, times, Pair(first, matches.lastOrNull())) to text
        }

        val mainRanges = parsed.mapNotNull { (pair, text) ->
            val (raw, times, tags) = pair
            val matches = LRC_TIME_ALL.findAll(raw).toList()
            if (times.size < 2 || text.isBlank()) return@mapNotNull null
            if (times.first() == times.last()) return@mapNotNull null
            val hasWordText = matches.drop(1).any { tag ->
                val prev = matches[matches.indexOf(tag) - 1]
                raw.substring(prev.range.last + 1, tag.range.first).isNotBlank()
            }
            if (!hasWordText) return@mapNotNull null
            MainRange(times.first(), times.last())
        }

        val translations = ArrayList<Pair<Int, String>>()
        val kept = ArrayList<String>(rows.size)

        for ((raw, times, _) in parsed.map { it.first }) {
            val matches = LRC_TIME_ALL.findAll(raw).toList()
            if (matches.size == 2 && times.firstOrNull() == times.lastOrNull()) {
                val text = raw.substring(matches[0].range.last + 1, matches[1].range.first).trim()
                if (text.isNotBlank() && !isTranslationNotice(text) && !isCredit(text)) {
                    val at = times.first()
                    val sortedRanges = mainRanges.sortedBy { it.start }
                    val nextIndex = sortedRanges.indexOfFirst { it.start >= at }
                    val next = if (nextIndex >= 0) sortedRanges[nextIndex] else null
                    val previous = if (nextIndex > 0) sortedRanges[nextIndex - 1] else null

                    // LDDC's local translation lane often sits immediately before the next
                    // original line (typically 1 ms earlier), not at the previous line's start.
                    // Prefer that structural signal over a nearest-start match.
                    val owner = when {
                        previous != null && next != null && next.start - at in 0..LOCAL_NEXT_LINE_TRANSLATION_MS -> previous
                        else -> sortedRanges.minByOrNull { distanceToRange(at, it) }
                    }
                    if (owner != null && isUsableLocalTranslationOwner(at, owner, next)) {
                        translations.add(Pair(owner.start, text))
                        continue
                    }
                } else if (text.isNotBlank()) {
                    // Known notices/credits are metadata, not the song's primary lyric.
                    continue
                }
                // Preserve an unpaired duplicate row as ordinary text, but without the tail tag.
                val first = raw.substring(0, matches[0].range.last + 1)
                kept.add(first + text)
                continue
            }
            kept.add(normalizeWordLrcLine(raw))
        }

        return BodyNormalized(kept.joinToString("\n"), translations, prepared.speakerSides)
    }

    private fun distanceToRange(at: Int, range: MainRange): Int = when {
        at < range.start -> range.start - at
        at > range.end -> at - range.end
        else -> 0
    }

    private fun isUsableLocalTranslationOwner(
        at: Int,
        owner: MainRange,
        next: MainRange?,
    ): Boolean {
        if (at in (owner.start - LOCAL_TRANSLATION_WINDOW_MS)..(owner.end + LOCAL_TRANSLATION_WINDOW_MS)) return true
        if (next != null && next.start >= at && next.start - at <= LOCAL_NEXT_LINE_TRANSLATION_MS) return true
        return distanceToRange(at, owner) <= LOCAL_TRANSLATION_WINDOW_MS
    }

    private fun normalizeWordLrcLine(raw: String): String {
        val matches = LRC_TIME_ALL.findAll(raw).toList()
        if (matches.size < 2) return raw
        val first = matches[0]
        val body = raw.substring(first.range.last + 1)
        val inline = LRC_TIME_ALL.findAll(body).toList()
        if (inline.isEmpty()) return raw

        // Multiple line timestamps at the very front are repeated LRC timing tags, not word tags.
        val repeatedLineTags = when {
            inline.size == 1 -> body.substring(0, inline[0].range.first).isBlank()
            else -> inline.drop(1).all { tag ->
                val i = inline.indexOf(tag)
                body.substring(inline[i - 1].range.last + 1, tag.range.first).isBlank()
            }
        }
        if (repeatedLineTags) return raw

        val lineTime = first.value
        val out = StringBuilder(lineTime)
        val leading = body.substring(0, inline[0].range.first)
        if (leading.isNotBlank()) {
            out.append('<').append(lineTime.substring(1, lineTime.length - 1)).append('>').append(leading)
        }
        for (i in inline.indices) {
            val tag = inline[i]
            val textStart = tag.range.last + 1
            val textEnd = if (i + 1 < inline.size) inline[i + 1].range.first else body.length
            val text = body.substring(textStart, textEnd)
            if (text.isNotBlank()) {
                val tagValue = tag.value
                out.append('<').append(tagValue.substring(1, tagValue.length - 1)).append('>')
            }
            out.append(text)
        }
        return out.toString()
    }

    private fun isTranslationNotice(text: String): Boolean {
        val s = text.replace(Regex("\\s+"), "")
        return s.startsWith("以下歌词翻译") ||
            s.contains("歌词翻译由") ||
            s.contains("翻译由") ||
            s.startsWith("translationprovided") ||
            s.startsWith("lyricstranslation")
    }

    /** Labels that mean everyone at once. Drawn on the first singer's side. */
    private val TOGETHER = setOf("合", "合唱", "全", "All", "ALL", "all")

    private val RAW_SPEAKER_PREFIX = Regex(
        "^(\\s*\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*(?:<[^>]+>\\s*)*)([^\\s\\d:：]{1,6})\\s*[:：]\\s*"
    )

    private val LRC_TIME_ALL = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    private const val LOCAL_TRANSLATION_WINDOW_MS = 1500
    /** LDDC commonly writes the translation 1-2 ms before the next original line. */
    private const val LOCAL_NEXT_LINE_TRANSLATION_MS = 20

    private fun timeMillis(m: MatchResult): Int? {
        val min = m.groupValues[1].toIntOrNull() ?: return null
        val sec = m.groupValues[2].toIntOrNull() ?: return null
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0
            1 -> (frac.toIntOrNull() ?: 0) * 100
            2 -> (frac.toIntOrNull() ?: 0) * 10
            else -> (frac.take(3).toIntOrNull() ?: 0)
        }
        return min * 60000 + sec * 1000 + ms
    }

    private fun isCredit(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (compact.contains("著作权") || compact.contains("版权")) return true
        return compact.matches(Regex("^(作词|作曲|编曲|制作|制作人|监制|统筹|混音|母带|录音|演奏|吉他|贝斯|鼓|键盘|词|曲)[:：].*"))
    }

    private fun assignWindow(
        lines: List<LyricLine>,
        entries: List<Pair<Int, String>>,
        windowMs: Int,
    ): Array<String?> {
        val starts = IntArray(lines.size) { lines[it].start }
        val best = arrayOfNulls<String>(lines.size)
        val gaps = IntArray(lines.size) { Int.MAX_VALUE }
        for ((at, text) in entries) {
            if (starts.isEmpty()) continue
            var i = starts.binarySearch(at)
            if (i < 0) {
                val ins = -i - 1
                i = when {
                    ins == 0 -> 0
                    ins >= starts.size -> starts.size - 1
                    at - starts[ins - 1] <= starts[ins] - at -> ins - 1
                    else -> ins
                }
            }
            val gap = kotlin.math.abs(starts[i] - at)
            if (gap <= windowMs && gap < gaps[i]) {
                gaps[i] = gap
                best[i] = text
            }
        }
        return best
    }

    /** Belt-and-suspenders guard for parser versions that still expose a terminal end-tag as text. */
    private val TRAILING_TIME_TAG = Regex("\\s*\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*$")

    internal fun stripTrailingTimeTag(text: String): String {
        val m = TRAILING_TIME_TAG.find(text) ?: return text
        if (m.range.first <= 0) return text
        return text.substring(0, m.range.first).trimEnd()
    }
    /**
     * The first of the sorted starts that is later than t: when the line starting at t is followed
     * by another. The last line of a song has nothing after it, and gets the fallback.
     */
    internal fun nextAfter(sorted: IntArray, t: Int, fallback: Int): Int {
        val i = sorted.binarySearch(t + 1)
        val at = if (i >= 0) i else -i - 1
        return if (at < sorted.size) sorted[at] else fallback
    }

    private fun karaoke(line: KaraokeLine, nextStart: Int): LyricLine? {
        val syl = line.syllables
        if (syl.isEmpty()) return null
        val text = StringBuilder()
        val starts = IntArray(syl.size)
        val ends = IntArray(syl.size)
        val chars = IntArray(syl.size)
        for ((k, s) in syl.withIndex()) {
            text.append(s.content)
            starts[k] = s.start
            ends[k] = s.end
            chars[k] = text.length
        }
        // Trailing spaces belong to the last word in English files and would push a wrapped
        // line's measured width past its ink. Leading ones cannot be trimmed without shifting
        // every syllable's character range, and the files do not have them.
        //
        // Some enhanced-LRC files also end a word-timed row with an explicit end timestamp, e.g.
        // [01:12.392]Goodbyes[01:15.342]. lyrics-core can expose that final tag as literal text.
        // Remove only that terminal tag; timestamps that occur before later words remain untouched.
        val rawText = text.toString()
        val shownText = stripTrailingTimeTag(rawText)
        var n = shownText.length
        while (n > 0 && shownText[n - 1].isWhitespace()) n--
        if (n == 0) return null
        for (k in chars.indices) if (chars[k] > n) chars[k] = n
        closeUntimedTail(starts, ends, line.end, nextStart)
        // The file's own romanisation - TTML's x-roman, a KRC's language block - joins the
        // translation the same way a separately shipped one does.
        val shown = shownText.substring(0, n)
        val below = if (displayRoma) withRoma(line.phonetic, shown, line.translation)
        else line.translation
        return LyricLine(shown, below, line.start, line.end,
            line.alignment == KaraokeAlignment.End, starts, ends, chars)
    }

    /**
     * The words at the end of a line that the source left without a span of their own take the
     * room it was leaving them.
     *
     * Word timing routinely runs out before the line does. A file that times a line by its words
     * alone has nowhere to read the last word's end from, and one that carries a duration per
     * word has nothing to say for a note the singer holds on: either way the last word arrives
     * with its end missing, or set equal to its own start. That is invisible anywhere else in a
     * line - sungChars() walks the syllables looking for the one the moment falls in, and a word
     * nobody is inside is simply stepped over - but at the end of a line there is no next word to
     * hand the fill on to, so the walk runs off the end of the array and reports the whole line
     * sung. The fill then crosses the last word in a single frame instead of sweeping it, and the
     * word is never the second long that the glow asks for.
     *
     * A word left without a span runs to the end of the line, and to the arrival of the line after
     * it when the file has no end to offer - which is the case for every file that times a line by
     * its words and nothing else. Not every such wait is a note, though: a singer will hold a word
     * through the last bar of a chorus but nobody holds one across an interlude, and the renderer
     * answers the second kind with its interlude dots (`LyricView.LULL_MS`, the same four seconds),
     * so past that the word keeps the nominal span and leaves the rest of the wait to them. Words
     * that share a start - which is what a wholly untimed tail looks like - divide the stretch
     * between them, rather than crossing together, so the fill still moves through them one at a
     * time.
     */
    internal fun closeUntimedTail(starts: IntArray, ends: IntArray, lineEnd: Int, nextStart: Int) {
        var k = 0
        while (k < starts.size) {
            if (ends[k] > starts[k]) {
                k++
                continue
            }
            // The run of untimed words this one belongs to.
            var last = k
            while (last + 1 < starts.size && ends[last + 1] <= starts[last + 1]) last++
            val from = starts[k]
            // A later word that starts later is where the run has to be over by. At the tail
            // there is none of those, and the line's own end closes it.
            var to = lineEnd
            for (m in last + 1 until starts.size) {
                if (starts[m] > from) {
                    to = starts[m]
                    break
                }
            }
            // A line whose own end is no later than its last word - which is what a file that
            // times lines by their words alone reports - leaves nothing to go on but the next
            // line's arrival.
            // The dots are drawn when what is left after this line's end reaches the lull, and
            // that end is the nominal one when the word does not run on - so it is the wait
            // after the nominal span that decides, or a gap just over four seconds would get
            // neither the held word nor the dots.
            if (to <= from) {
                val nominal = from + NOMINAL_WORD_MS * (last - k + 1)
                to = if (nextStart > from && nextStart - nominal < MAX_HELD_MS) nextStart
                else nominal
            }
            val count = last - k + 1
            for (m in k..last) ends[m] = from + (to - from) * (m - k + 1) / count
            k = last + 1
        }
    }

    /**
     * What a word is given when even the line it sits in has no end left to reach. Only a file
     * that times its lines by words alone gets here; about a word's worth of room, so it sweeps
     * rather than snaps.
     */
    private const val NOMINAL_WORD_MS = 300

    /**
     * How long a wait may be left after a last word's nominal span before it stops being a note
     * held across it and becomes an interlude, which the renderer is about to draw its dots
     * through anyway (the same four seconds; see LyricView.LULL_MS).
     */
    private const val MAX_HELD_MS = 4000

    /**
     * Apply singer-side information captured before AutoParser. If a source format defeated the
     * raw-prefix detector and AutoParser still exposed a singer label, strip that label here as a
     * safety net. Crucially, the fallback drops word ranges for that line rather than attempting a
     * guessed charEnd shift; the full text must remain visible even when the source's coordinate
     * space is ambiguous.
     */
    private fun applySpeakerSides(lines: List<LyricLine>, sides: Map<Int, Boolean>): List<LyricLine> {
        val preKnown = sides.isNotEmpty()
        val labels = lines.map { LABEL.find(it.text)?.groupValues?.get(1) }
        val counts = labels.filterNotNull().groupingBy { it }.eachCount()
        val detected = LinkedHashSet<String>()
        for (label in labels) {
            if (label != null && label !in TOGETHER && (counts[label] ?: 0) >= 2) {
                detected.add(label)
            }
        }
        val fallbackIsDuet = detected.size >= 2
        val order = if (fallbackIsDuet) detected.toList() else emptyList()

        if (!preKnown && !fallbackIsDuet) return lines

        val out = ArrayList<LyricLine>(lines.size)
        var currentRight = false
        for (line in lines) {
            val rawLabel = LABEL.find(line.text)?.groupValues?.get(1)
            val knownLabel = rawLabel != null && (rawLabel in TOGETHER || rawLabel in detected)

            val side = sides[line.start] ?: if (knownLabel) {
                currentRight = rawLabel !in TOGETHER && order.indexOf(rawLabel) % 2 == 1
                currentRight
            } else {
                currentRight
            }

            if (knownLabel) {
                val match = LABEL.find(line.text)!!
                val text = line.text.substring(match.range.last + 1)
                val copy = LyricLine(text, line.translation, line.start, line.end, side,
                    null, null, null)
                copy.bg = line.bg
                out.add(copy)
                continue
            }

            if (sides.containsKey(line.start) && side) {
                val copy = LyricLine(line.text, line.translation, line.start, line.end, true,
                    line.sylStart, line.sylEnd, line.charEnd)
                copy.bg = line.bg
                out.add(copy)
            } else {
                out.add(line)
            }
        }
        return out
    }

    /**
     * Speaker labels such as "女：" are parsed with the normal one-line label pattern. This is
     * kept separate from the raw-prefix regex because a provider can insert other timing syntax
     * between the line timestamp and the visible text.
     */
    private val LABEL = Regex("^([^\\s\\d:：]{1,6})\\s*[:：]\\s*")


}
