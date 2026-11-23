package com.histbot.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class BpeTokenizer(context: Context, assetName: String = "bpe_hist.json") {
    private val vocab = HashMap<String, Int>()
    private val idToToken = HashMap<Int, String>()
    private val bpeRanks = HashMap<Pair<String, String>, Int>()
    private val byteEncoder = HashMap<Int, Char>()
    private val byteDecoder = HashMap<Char, Byte>()
    private val cache = HashMap<String, List<String>>()

    val eosId: Int

    private val pat = Regex(
        "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    )

    init {
        initByteMaps()
        val jsonStr = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        val model = root.getJSONObject("model")

        val vocabObj = model.getJSONObject("vocab")
        val keys = vocabObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = vocabObj.getInt(k)
            vocab[k] = id
            idToToken[id] = k
        }

        val added = root.optJSONArray("added_tokens")
        if (added != null) {
            for (i in 0 until added.length()) {
                val t = added.getJSONObject(i)
                val id = t.getInt("id")
                val content = t.getString("content")
                vocab[content] = id
                idToToken[id] = content
            }
        }

        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val arr = merges.optJSONArray(i)
            if (arr != null && arr.length() >= 2) {
                bpeRanks[Pair(arr.getString(0), arr.getString(1))] = i
            } else {
                val s = merges.optString(i)
                val parts = s.split(" ")
                if (parts.size == 2) {
                    bpeRanks[Pair(parts[0], parts[1])] = i
                }
            }
        }

        eosId = vocab["<|endoftext|>"] ?: 0
    }

    private fun initByteMaps() {
        val bs = ArrayList<Int>()
        for (b in 33..126) bs.add(b)
        for (b in 161..172) bs.add(b)
        for (b in 174..255) bs.add(b)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        for (i in bs.indices) {
            val c = cs[i].toChar()
            byteEncoder[bs[i]] = c
            byteDecoder[c] = bs[i].toByte()
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word = token.map { it.toString() }.toMutableList()
        if (word.size <= 1) return word

        while (word.size > 1) {
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val pair = Pair(word[i], word[i + 1])
                val rank = bpeRanks[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }
            if (bestPair == null) break

            val (first, second) = bestPair
            val newWord = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
        }

        if (cache.size < 4000) cache[token] = word
        return word
    }

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        for (match in pat.findAll(text)) {
            val bytes = match.value.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size)
            for (b in bytes) {
                sb.append(byteEncoder[b.toInt() and 0xFF] ?: ' ')
            }
            for (tok in bpe(sb.toString())) {
                vocab[tok]?.let { ids.add(it) }
            }
        }
        return ids.toIntArray()
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId) continue
            idToToken[id]?.let { sb.append(it) }
        }
        val out = ByteArrayOutputStream()
        for (ch in sb.toString()) {
            val b = byteDecoder[ch]
            if (b != null) out.write(b.toInt() and 0xFF)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
