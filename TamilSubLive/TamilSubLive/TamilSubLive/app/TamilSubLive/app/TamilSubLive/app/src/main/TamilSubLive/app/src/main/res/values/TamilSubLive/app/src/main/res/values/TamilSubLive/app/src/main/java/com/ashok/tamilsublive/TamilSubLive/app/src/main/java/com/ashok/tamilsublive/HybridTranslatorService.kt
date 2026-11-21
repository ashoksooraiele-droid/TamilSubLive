package com.ashok.tamilsublive

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HybridTranslatorService {
    private val translators = mutableMapOf<String, Translator>()
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private fun getTranslatorForSource(sourceLangCode: String): Translator {
        val normalizedSource = TranslateLanguage.fromLanguageTag(sourceLangCode) ?: TranslateLanguage.ENGLISH
        val key = "${normalizedSource}->${TranslateLanguage.TAMIL}"
        return translators[key] ?: run {
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(normalizedSource)
                .setTargetLanguage(TranslateLanguage.TAMIL)
                .build()
            Translation.getClient(opts).also { translators[key] = it }
        }
    }

    suspend fun detectLanguageCode(sampleTexts: List<String>, selectedOption: SourceLanguageOption): String {
        if (selectedOption.code != "auto") {
            return selectedOption.mlKitCode ?: selectedOption.code
        }
        val combinedSample = sampleTexts.joinToString(" ").trim()
        if (combinedSample.isEmpty()) return TranslateLanguage.ENGLISH
        return try {
            val detectedTag = languageIdentifier.identifyLanguage(combinedSample).await()
            if (detectedTag == "und" || detectedTag.isBlank()) {
                TranslateLanguage.ENGLISH
            } else {
                detectedTag
            }
        } catch (_: Exception) {
            TranslateLanguage.ENGLISH
        }
    }

    suspend fun downloadOfflineModelsIfNeeded(
        selectedOption: SourceLanguageOption,
        sampleTexts: List<String>,
        onStatus: (String) -> Unit
    ): String {
        val detectedOrChosenCode = detectLanguageCode(sampleTexts, selectedOption)
        val sourceTranslator = getTranslatorForSource(detectedOrChosenCode)
        val conditions = DownloadConditions.Builder().build()

        onStatus("Checking offline model ($detectedOrChosenCode -> ta)...")
        sourceTranslator.downloadModelIfNeeded(conditions).await()

        if (detectedOrChosenCode != TranslateLanguage.ENGLISH) {
            val englishTranslator = getTranslatorForSource(TranslateLanguage.ENGLISH)
            onStatus("Checking English -> Tamil model...")
            englishTranslator.downloadModelIfNeeded(conditions).await()
        }

        onStatus("Offline Tamil model ready ($detectedOrChosenCode -> ta)")
        return detectedOrChosenCode
    }

    suspend fun translateBatchOffline(
        items: List<String>,
        selectedOption: SourceLanguageOption,
        onStatus: ((String) -> Unit)? = null
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        val resolvedSourceCode = downloadOfflineModelsIfNeeded(selectedOption, items) { msg ->
            onStatus?.invoke(msg)
        }
        val primaryTranslator = getTranslatorForSource(resolvedSourceCode)
        val englishTranslator = if (resolvedSourceCode != TranslateLanguage.ENGLISH) {
            getTranslatorForSource(TranslateLanguage.ENGLISH)
        } else {
            null
        }

        return items.map { text ->
            try {
                primaryTranslator.translate(text).await()
            } catch (_: Exception) {
                if (englishTranslator != null) {
                    try {
                        englishTranslator.translate(text).await()
                    } catch (_: Exception) {
                        text
                    }
                } else {
                    text
                }
            }
        }
    }

    suspend fun translateBatchOnline(
        items: List<String>,
        selectedOption: SourceLanguageOption
    ): List<String> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val sourceParam = if (selectedOption.code == "auto") "auto" else selectedOption.code
        val sep = "\n<|SEP|>\n"
        val combined = items.joinToString(sep)

        try {
            val rawTranslated = callGoogleTranslateApi(combined, sourceParam)
            val splitItems = rawTranslated.split(Regex("\s*<\|SEP\|>\s*"))
            if (splitItems.size == items.size) {
                splitItems.map { it.trim() }
            } else {
                translateLineByLineOnline(items, sourceParam)
            }
        } catch (_: Exception) {
            translateLineByLineOnline(items, sourceParam)
        }
    }

    private fun translateLineByLineOnline(items: List<String>, sourceParam: String): List<String> {
        return items.map { single ->
            try {
                callGoogleTranslateApi(single, sourceParam)
            } catch (_: Exception) {
                single
            }
        }
    }

    private fun callGoogleTranslateApi(text: String, sourceLang: String): String {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val encodedSl = URLEncoder.encode(sourceLang, "UTF-8")
        val endpoint =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$encodedSl&tl=ta&dt=t&q=$encodedText"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        return try {
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val jsonArr = JSONArray(body)
            val segments = jsonArr.getJSONArray(0)
            val builder = StringBuilder()
            for (i in 0 until segments.length()) {
                val entry = segments.getJSONArray(i)
                if (!entry.isNull(0)) {
                    builder.append(entry.getString(0))
                }
            }
            builder.toString().ifBlank { text }
        } finally {
            connection.disconnect()
        }
    }

    fun release() {
        translators.values.forEach { it.close() }
        translators.clear()
        try {
            languageIdentifier.close()
        } catch (_: Exception) {
        }
    }
}
