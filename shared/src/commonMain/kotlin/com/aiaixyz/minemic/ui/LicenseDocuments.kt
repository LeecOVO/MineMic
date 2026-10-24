package com.aiaixyz.minemic.ui

internal data class LicenseDocument(val source: String, val rawText: String) {
    val title: String get() = when (source) {
        "WebRTC-LICENSE" -> "WebRTC Audio Processing"
        "WebRTC-PATENTS" -> "WebRTC 专利授权"
        "Standalone-COPYING" -> "WebRTC 独立发行版"
        "RNNoise-0.2-COPYING" -> "RNNoise 0.2"
        "RNNoise-COPYING" -> "RNNoise 附带声明"
        "Abseil-LICENSE" -> "Abseil"
        "Ooura-LICENSE" -> "Ooura FFT"
        "PFFFT-LICENSE" -> "PFFFT"
        "FFT-LICENSE" -> "FFT"
        "SPL-sqrt-LICENSE" -> "SPL 平方根例程"
        else -> source
    }
}

internal data class LicenseBundle(val introduction: String, val documents: List<LicenseDocument>)

internal fun parseLicenseBundle(text: String): LicenseBundle {
    val normalized = text.replace("\r\n", "\n")
    val headers = Regex("(?m)^={5,}\\n([^\\n]+)\\n={5,}\\n").findAll(normalized).toList()
    if (headers.isEmpty()) return LicenseBundle("", listOf(LicenseDocument("许可声明", normalized)))
    val documents = headers.mapIndexed { index, header ->
        val end = headers.getOrNull(index + 1)?.range?.first ?: normalized.length
        LicenseDocument(header.groupValues[1].trim(), normalized.substring(header.range.last + 1, end).trim('\n'))
    }
    val primary = listOf("WebRTC-LICENSE", "WebRTC-PATENTS", "RNNoise-0.2-COPYING", "Abseil-LICENSE")
    return LicenseBundle(normalized.substring(0, headers.first().range.first).trim(),
        documents.sortedBy { primary.indexOf(it.source).let { rank -> if (rank < 0) primary.size else rank } })
}

// Reflow only presentation whitespace and source-comment prefixes. Original text stays available.
internal fun licenseParagraphs(raw: String): List<String> {
    val sourceComment = raw.trimStart().startsWith("/*") && raw.trimEnd().endsWith("*/")
    val lines = raw.replace("\r\n", "\n").lines().map { line ->
        val trimmed = line.trim()
        when {
            sourceComment && (trimmed == "/*" || trimmed == "*/" || trimmed == "*") -> ""
            sourceComment && trimmed.startsWith("* ") -> trimmed.removePrefix("* ").trim()
            else -> trimmed
        }
    }
    return lines.joinToString("\n").split(Regex("\n[ \\t]*\n+"))
        .map { paragraph -> paragraph.lines().joinToString(" ").trim() }.filter { it.isNotEmpty() }
}
