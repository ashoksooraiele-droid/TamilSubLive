package com.ashok.tamilsublive

enum class TranslateMode {
    ONLINE_FAST,
    OFFLINE_MLKIT
}

data class SourceLanguageOption(
    val code: String,
    val label: String,
    val mlKitCode: String?
)

object SupportedSourceLanguages {
    val options = listOf(
        SourceLanguageOption("auto", "Auto Detect (Any)", null),
        SourceLanguageOption("en", "English", "en"),
        SourceLanguageOption("hi", "Hindi", "hi"),
        SourceLanguageOption("kn", "Kannada", "kn"),
        SourceLanguageOption("ml", "Malayalam", "ml"),
        SourceLanguageOption("te", "Telugu", "te"),
        SourceLanguageOption("es", "Spanish", "es"),
        SourceLanguageOption("fr", "French", "fr"),
        SourceLanguageOption("de", "German", "de"),
        SourceLanguageOption("ja", "Japanese", "ja"),
        SourceLanguageOption("ko", "Korean", "ko"),
        SourceLanguageOption("zh", "Chinese", "zh"),
        SourceLanguageOption("ar", "Arabic", "ar"),
        SourceLanguageOption("ru", "Russian", "ru")
    )
}

data class CueBlock(
    val number: String,
    val timeRange: String,
    val sourceText: String,
    val tamilText: String = "",
    val isTranslated: Boolean = false
)

data class SrtQueueItem(
    val id: String,
    val fileName: String,
    val rawContent: String,
    val cues: List<CueBlock> = emptyList(),
    val completedCues: Int = 0,
    val isCompleted: Boolean = false,
    val isTranslatingNow: Boolean = false,
    val isSaved: Boolean = false,
    val errorText: String? = null
)

object SubtitleCodec {
    fun buildSrtFromLines(
        linesInput: String,
        startMs: Long = 1000L,
        stepMs: Long = 3500L
    ): List<CueBlock> {
        val raw = linesInput.replace("\r\n", "\n").trim()
        if (raw.isEmpty()) return emptyList()

        val hasBlankSeparators = raw.contains(Regex("\n\s*\n")) || raw.contains("-->")
        if (hasBlankSeparators) {
            return parseBlocks(raw)
        }

        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var currentStart = startMs
        return lines.mapIndexed { idx, line ->
            val cueStart = currentStart
            val cueEnd = cueStart + stepMs
            currentStart = cueEnd + 400L
            CueBlock(
                number = (idx + 1).toString(),
                timeRange = "${formatSrtTime(cueStart)} --> ${formatSrtTime(cueEnd)}",
                sourceText = line
            )
        }
    }

    fun formatSrtTime(totalMillis: Long): String {
        val safe = totalMillis.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun parseBlocks(rawText: String): List<CueBlock> {
        val cleaned = rawText.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return emptyList()

        val rawChunks = cleaned.split(Regex("\n\s*\n"))
        val result = mutableListOf<CueBlock>()

        rawChunks.forEachIndexed { idx, chunk ->
            val lines = chunk.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEachIndexed

            when {
                lines.size >= 3 && lines[1].contains("-->") -> {
                    result.add(
                        CueBlock(
                            number = lines[0],
                            timeRange = lines[1],
                            sourceText = lines.drop(2).joinToString("\n")
                        )
                    )
                }
                lines.size >= 2 && lines[0].contains("-->") -> {
                    result.add(
                        CueBlock(
                            number = (idx + 1).toString(),
                            timeRange = lines[0],
                            sourceText = lines.drop(1).joinToString("\n")
                        )
                    )
                }
                else -> {
                    result.add(
                        CueBlock(
                            number = (idx + 1).toString(),
                            timeRange = "",
                            sourceText = lines.joinToString("\n")
                        )
                    )
                }
            }
        }
        return result
    }

    fun buildSrtOutput(blocks: List<CueBlock>): String {
        return blocks.mapIndexed { idx, cue ->
            val num = cue.number.ifBlank { (idx + 1).toString() }
            val time = cue.timeRange.ifBlank {
                val start = 1000L + idx * 3900L
                "${formatSrtTime(start)} --> ${formatSrtTime(start + 3500L)}"
            }
            val body = if (cue.isTranslated && cue.tamilText.isNotBlank()) {
                cue.tamilText
            } else {
                cue.sourceText
            }
            "$num\n$time\n$body"
        }.joinToString("\n\n")
    }

    fun makeTamilOutputFileName(originalName: String): String {
        val trimmed = originalName.trim().ifBlank { "subtitle.srt" }
        val dotIndex = trimmed.lastIndexOf('.')
        return if (dotIndex > 0) {
            val base = trimmed.substring(0, dotIndex)
            val ext = trimmed.substring(dotIndex)
            "${base}_ta$ext"
        } else {
            "${trimmed}_ta.srt"
        }
    }
}
