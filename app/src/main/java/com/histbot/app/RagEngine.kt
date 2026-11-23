package com.histbot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class KbItem(val title: String, val text: String)
data class RagHit(val item: KbItem?, val sourceLabel: String)

class RagEngine(private val context: Context) {
    private val kbFile = File(context.filesDir, "hist_kb.json")
    private val kb = ArrayList<KbItem>()
    private val index = ArrayList<Triple<Set<String>, Set<String>, KbItem>>()

    private val stopwords = setOf(
        "что", "такое", "кто", "такой", "такая", "такие", "расскажи", "про", "об", "обо",
        "известно", "кратко", "это", "как", "где", "когда", "был", "была", "было", "были",
        "для", "при", "или", "под", "над", "из", "по", "на", "в", "о", "с", "и", "а",
        "посмотри", "найди", "поищи", "интернете", "интернет", "википедии", "википедия",
        "скажи", "подскажи", "пожалуйста", "можешь", "нужно", "мне",
        "момента", "начинает", "начинается", "отсчитываться", "государства", "государство",
        "упоминание", "упоминания", "первое", "первый", "первая", "первые", "его", "ее", "её", "их"
    )
    private val smallTalk = setOf(
        "почти", "да", "нет", "ок", "окей", "ладно", "понятно", "ясно", "спасибо", "ага", "угу", "круто", "классно"
    )
    private val greetings = listOf("привет", "здравствуй", "добрый день", "кто ты", "что ты умеешь", "как тебя зовут")
    private val wordRegex = Regex("""[а-яА-ЯёЁa-zA-Z0-9\-]+""")
    private val stemRegex = Regex(
        """(иями|ями|ами|ого|его|ому|ему|ими|ыми|ою|ею|ой|ей|ий|ый|ая|яя|ое|ее|ые|ие|ом|ем|ам|ям|ах|ях|ов|ев|ей|ью|ия|ии|ию|ей|а|я|у|ю|ы|и|е|о|ь)$"""
    )

    init {
        loadKbFromDisk()
    }

    private fun loadKbFromDisk() {
        kb.clear()
        index.clear()
        if (!kbFile.exists()) {
            context.assets.open("hist_kb.json").use { input ->
                kbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val arr = JSONArray(kbFile.readText(Charsets.UTF_8))
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val item = KbItem(obj.getString("title"), obj.getString("text"))
            kb.add(item)
            index.add(Triple(tokenize(item.title).toSet(), tokenize(item.text).toSet(), item))
        }
    }

    fun resetCache(): Int {
        if (kbFile.exists()) {
            kbFile.delete()
        }
        loadKbFromDisk()
        return kb.size
    }

    fun articleCount(): Int = kb.size

    private fun stemRu(word: String): String {
        val w = word.lowercase().replace('ё', 'е')
        return if (w.length <= 4) w else stemRegex.replace(w, "")
    }

    private fun tokenize(text: String): List<String> {
        return wordRegex.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 && it !in stopwords }
            .map { stemRu(it) }
            .toList()
    }

    fun isGreeting(question: String): Boolean {
        val q = question.lowercase().trim()
        return greetings.any { q.startsWith(it) }
    }

    private fun isSmallTalk(question: String): Boolean {
        val words = wordRegex.findAll(question.lowercase()).map { it.value }.toList()
        return words.size == 1 && words[0] in smallTalk
    }

    fun search(question: String, onStatus: (String) -> Unit): RagHit {
        if (isGreeting(question) || isSmallTalk(question)) {
            return RagHit(null, "")
        }

        val qSet = tokenize(question).toSet()
        if (qSet.isNotEmpty()) {
            var bestItem: KbItem? = null
            var bestScore = 0.0
            for ((tStems, bStems, item) in index) {
                if (tStems.isEmpty()) continue
                var tHits = 0
                for (w in qSet) if (w in tStems) tHits++
                if (tHits == 0) continue

                val titleCoverage = tHits.toDouble() / tStems.size
                val queryCoverage = tHits.toDouble() / qSet.size
                if (titleCoverage < 0.75 || queryCoverage < 0.75) continue

                val missingWords = qSet.count { it !in tStems && it !in bStems }
                if (missingWords > 0) continue

                var bHits = 0
                for (w in qSet) if (w in bStems) bHits++

                var score = titleCoverage * 10.0 + queryCoverage * 5.0 + bHits * 0.5
                if (tStems == qSet) score += 10.0
                if (score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            }
            if (bestItem != null) {
                return RagHit(bestItem, "Локальная база: ${bestItem.title}")
            }
        }

        onStatus("Ищу в Википедии...")
        val webItem = fetchWikipedia(question)
        if (webItem != null) {
            if (kb.none { it.title.equals(webItem.title, ignoreCase = true) }) {
                kb.add(webItem)
                index.add(Triple(tokenize(webItem.title).toSet(), tokenize(webItem.text).toSet(), webItem))
                saveKb()
            }
            return RagHit(webItem, "Википедия: ${webItem.title} (сохранено)")
        }

        return RagHit(null, "Источник не найден")
    }

    private fun cleanText(raw: String): String {
        var t = raw.replace("\u0301", "")
        t = Regex("""(?:^|\n)[^\n]*см\.\s*(?:статью|также)[^\n]*(?:\n|$)""", RegexOption.IGNORE_CASE).replace(t, "\n")
        t = Regex("""\[\d+]""").replace(t, "")
        t = Regex(
            """\([^)]*(?:монг\.|англ\.|лат\.|греч\.|фр\.|нем\.|исп\.|ит\.|кит\.|яп\.|араб\.|тюрк\.|татар\.|перс\.|рус\.|церк\.-слав\.|МФА)[^)]*\)""",
            RegexOption.IGNORE_CASE
        ).replace(t, "")
        t = Regex("""[^а-яА-ЯёЁa-zA-Z0-9\s.,;:!?()—–«»"'/-]""").replace(t, "")
        t = Regex("""\(\s*[;:,]+\s*""").replace(t, "(")
        t = Regex("""\s*[;:,]+\s*\)""").replace(t, ")")
        t = Regex("""\([\s,;:.—–/-]*\)""").replace(t, "")
        t = Regex("""\s+([,.;:!?])""").replace(t, "$1")
        t = Regex("""[ \t\n]+""").replace(t, " ")
        return t.trim()
    }

    private fun cutSentences(text: String, maxChars: Int = 500): String {
        val sents = text.split(Regex("""(?<=[.!?])\s+"""))
        val res = ArrayList<String>()
        var total = 0
        for (s in sents) {
            if (s.isBlank()) continue
            if (total + s.length > maxChars && res.isNotEmpty()) break
            res.add(s)
            total += s.length + 1
        }
        var out = res.joinToString(" ").trim()
        if (out.length > maxChars) {
            out = out.substring(0, maxChars).substringBeforeLast(" ") + "."
        }
        return out
    }

    private fun fetchWikipedia(question: String): KbItem? {
        return try {
            val words = wordRegex.findAll(question)
                .map { it.value }
                .filter { it.length > 1 && it.lowercase() !in stopwords }
                .toList()
            val query = words.joinToString(" ").trim()
            if (query.isEmpty()) return null

            val qStems = tokenize(query).toSet()
            val qEnc = URLEncoder.encode(query, "UTF-8")
            val searchJson = httpGet(
                "https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch=$qEnc&srlimit=5&format=json&utf8=1"
            )
            val results = JSONObject(searchJson).getJSONObject("query").getJSONArray("search")
            if (results.length() == 0) return null

            var bestTitle = results.getJSONObject(0).getString("title")
            var bestHits = -1
            for (i in 0 until results.length()) {
                val candTitle = results.getJSONObject(i).getString("title")
                val candStems = tokenize(candTitle).toSet()
                val hits = qStems.count { it in candStems }
                if (hits > bestHits) {
                    bestHits = hits
                    bestTitle = candTitle
                }
            }

            val tEnc = URLEncoder.encode(bestTitle, "UTF-8")
            val extractJson = httpGet(
                "https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=$tEnc&format=json&utf8=1"
            )
            val pages = JSONObject(extractJson).getJSONObject("query").getJSONObject("pages")
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.getJSONObject(keys.next())
                val extract = cleanText(page.optString("extract", ""))
                if (extract.length >= 60) {
                    return KbItem(cleanText(page.optString("title", bestTitle)), cutSentences(extract, 500))
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "HistBot30M/1.0 (android-rag)")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun saveKb() {
        try {
            val arr = JSONArray()
            for (item in kb) {
                arr.put(JSONObject().put("title", item.title).put("text", item.text))
            }
            kbFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
