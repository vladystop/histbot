package com.histbot.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.random.Random

enum class PowerMode(
    val title: String,
    val shortLabel: String,
    val maxTokens: Int,
    val temperature: Float,
    val maxCtxChars: Int
) {
    LOW("Низкий (быстро, 1–2 предложения)", "Низк. ▾", 70, 0.25f, 260),
    MEDIUM("Средний (баланс скорости и длины)", "Средн. ▾", 160, 0.30f, 520),
    HIGH("Высокий (максимально подробно)", "Высок. ▾", 260, 0.35f, 850)
}

class HistModel(context: Context, private val tokenizer: BpeTokenizer) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val blockSize = 384
    private val topP = 0.85f
    private val repetitionPenalty = 1.18f

    init {
        val modelFile = File(context.filesDir, "gpt_hist_30m_rag_int8.onnx")
        if (!modelFile.exists()) {
            context.assets.open("gpt_hist_30m_rag_int8.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        session = env.createSession(modelFile.absolutePath, opts)
    }

    fun generate(
        contextText: String,
        question: String,
        stopFlag: AtomicBoolean,
        mode: PowerMode = PowerMode.MEDIUM,
        onProgress: (String) -> Unit
    ): String {
        val qClean = question.trim().replaceFirstChar { it.uppercase() }
        val ctxTrimmed = if (contextText.length > mode.maxCtxChars) {
            contextText.take(mode.maxCtxChars)
        } else {
            contextText
        }
        val prompt = "Контекст: $ctxTrimmed\nВопрос: $qClean\nОтвет:"
        val promptIds = tokenizer.encode(prompt).toMutableList()
        val trimmed = if (promptIds.size > blockSize - 16) {
            promptIds.takeLast(blockSize - 16).toMutableList()
        } else {
            promptIds
        }

        val generated = ArrayList<Int>()
        val allIds = ArrayList<Int>(trimmed)

        for (step in 0 until mode.maxTokens) {
            if (stopFlag.get()) break

            val cond = if (allIds.size > blockSize) allIds.takeLast(blockSize) else allIds
            val inputArr = LongArray(cond.size) { cond[it].toLong() }

            val nextId = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputArr),
                longArrayOf(1L, cond.size.toLong())
            ).use { tensor ->
                session.run(mapOf("input_ids" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (result[0].value as Array<FloatArray>)[0].copyOf()
                    sampleToken(logits, generated, mode.temperature)
                }
            }

            if (nextId == tokenizer.eosId) break
            allIds.add(nextId)
            generated.add(nextId)

            val decoded = tokenizer.decode(generated).trimStart().trimEnd('')
            if (decoded.isNotEmpty()) onProgress(decoded)
        }

        return tokenizer.decode(generated).trim()
    }

    private fun sampleToken(logits: FloatArray, generated: List<Int>, temperature: Float): Int {
        if (repetitionPenalty != 1.0f && generated.isNotEmpty()) {
            for (tid in generated.toSet()) {
                if (tid in logits.indices) {
                    if (logits[tid] < 0f) logits[tid] *= repetitionPenalty
                    else logits[tid] /= repetitionPenalty
                }
            }
        }

        val invTemp = 1.0f / temperature.coerceAtLeast(1e-5f)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            logits[i] *= invTemp
            if (logits[i] > maxLogit) maxLogit = logits[i]
        }

        val probs = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val p = exp((logits[i] - maxLogit).toDouble()).toFloat()
            probs[i] = p
            sum += p
        }

        val sortedIdx = probs.indices.sortedByDescending { probs[it] }
        val candidates = ArrayList<Pair<Int, Float>>()
        var cum = 0.0
        for (idx in sortedIdx) {
            val p = (probs[idx] / sum).toFloat()
            candidates.add(Pair(idx, p))
            cum += p
            if (cum >= topP) break
        }

        val candSum = candidates.sumOf { it.second.toDouble() }
        var r = Random.nextDouble() * candSum
        for ((idx, p) in candidates) {
            r -= p
            if (r <= 0.0) return idx
        }
        return candidates.first().first
    }
}
