package com.danemadsen.maise.tts

import java.text.BreakIterator
import java.util.Locale

private const val FIRST_CHUNK_TARGET_WORDS = 18
private const val SUBSEQUENT_CHUNK_TARGET_WORDS = 35
private const val MAX_PHONEME_ESTIMATE_CHARS = 380

/**
 * Splits input text into sentence/clause chunks optimized for fast time-to-first-audio (TTFA)
 * and continuous, natural speech synthesis without unnatural punctuation pauses.
 */
object AdaptiveChunker {

    fun chunkText(rawText: String): List<String> {
        val normalized = normalizeText(rawText).trim()
        if (normalized.isEmpty()) return emptyList()

        // 1. Split text into paragraphs by newline
        val paragraphs = normalized.split(Regex("""\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) return listOf(normalized)

        val sentences = mutableListOf<String>()

        // 2. Extract natural sentences using BreakIterator for each paragraph
        for (paragraph in paragraphs) {
            val iterator = BreakIterator.getSentenceInstance(Locale.US)
            iterator.setText(paragraph)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val sentence = paragraph.substring(start, end).trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
                start = end
                end = iterator.next()
            }
        }

        if (sentences.isEmpty()) return listOf(normalized)

        // 3. Group adjacent short sentences into target-sized chunks
        val chunks = mutableListOf<String>()
        var currentChunkBuilder = StringBuilder()
        var currentWordCount = 0

        for (sentence in sentences) {
            val isFirstChunk = chunks.isEmpty()
            val targetWords = if (isFirstChunk) FIRST_CHUNK_TARGET_WORDS else SUBSEQUENT_CHUNK_TARGET_WORDS
            val sentenceWords = sentence.split(Regex("""\s+""")).filter { it.isNotEmpty() }
            val sentenceWordCount = sentenceWords.size

            if (sentenceWordCount > targetWords || sentence.length > MAX_PHONEME_ESTIMATE_CHARS) {
                // Finalize any pending chunk before processing the oversized sentence
                if (currentChunkBuilder.isNotEmpty()) {
                    chunks.add(currentChunkBuilder.toString().trim())
                    currentChunkBuilder = StringBuilder()
                    currentWordCount = 0
                }

                // Split oversized sentence across clause boundaries (;, :, —, or commas if sentence is long)
                val clauses = splitLongSentence(sentence, targetWords)
                for (clause in clauses) {
                    val clauseWords = clause.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
                    val activeTarget = if (chunks.isEmpty()) FIRST_CHUNK_TARGET_WORDS else SUBSEQUENT_CHUNK_TARGET_WORDS

                    if (currentChunkBuilder.isNotEmpty() && (currentWordCount + clauseWords > activeTarget || (currentChunkBuilder.length + clause.length + 1) > MAX_PHONEME_ESTIMATE_CHARS)) {
                        chunks.add(currentChunkBuilder.toString().trim())
                        currentChunkBuilder = StringBuilder()
                        currentWordCount = 0
                    }

                    if (currentChunkBuilder.isNotEmpty()) {
                        currentChunkBuilder.append(" ").append(clause)
                    } else {
                        currentChunkBuilder.append(clause)
                    }
                    currentWordCount += clauseWords
                }
            } else {
                // Standard sentence: group with current chunk if within target budget
                val newLen = if (currentChunkBuilder.isEmpty()) sentence.length else currentChunkBuilder.length + 1 + sentence.length
                if (currentChunkBuilder.isNotEmpty() && (currentWordCount + sentenceWordCount > targetWords || newLen > MAX_PHONEME_ESTIMATE_CHARS)) {
                    chunks.add(currentChunkBuilder.toString().trim())
                    currentChunkBuilder = StringBuilder()
                    currentWordCount = 0
                }

                if (currentChunkBuilder.isNotEmpty()) {
                    currentChunkBuilder.append(" ").append(sentence)
                } else {
                    currentChunkBuilder.append(sentence)
                }
                currentWordCount += sentenceWordCount
            }
        }

        if (currentChunkBuilder.isNotEmpty()) {
            val chunkStr = currentChunkBuilder.toString().trim()
            if (chunkStr.isNotEmpty()) {
                chunks.add(chunkStr)
            }
        }

        // 4. Final safety pass: strictly enforce MAX_PHONEME_ESTIMATE_CHARS
        val finalChunks = mutableListOf<String>()
        for (chunk in chunks) {
            if (chunk.length <= MAX_PHONEME_ESTIMATE_CHARS) {
                finalChunks.add(chunk)
            } else {
                val words = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                var temp = mutableListOf<String>()
                var tempLen = 0
                for (w in words) {
                    if (tempLen + w.length + 1 > MAX_PHONEME_ESTIMATE_CHARS && temp.isNotEmpty()) {
                        finalChunks.add(temp.joinToString(" "))
                        temp = mutableListOf()
                        tempLen = 0
                    }
                    temp.add(w)
                    tempLen += w.length + 1
                }
                if (temp.isNotEmpty()) {
                    finalChunks.add(temp.joinToString(" "))
                }
            }
        }

        return finalChunks.filter { it.isNotBlank() }
    }

    private fun splitLongSentence(sentence: String, targetWords: Int): List<String> {
        val clauseRegex = Regex("""[^;:\u2014,]+[;:\u2014,]*""")
        val rawClauses = clauseRegex.findAll(sentence)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (rawClauses.isEmpty()) return listOf(sentence)

        val clauses = mutableListOf<String>()
        var currentBuilder = StringBuilder()
        var currentCount = 0

        for (c in rawClauses) {
            val cWords = c.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            if (currentBuilder.isNotEmpty() && (currentCount + cWords > targetWords)) {
                clauses.add(currentBuilder.toString().trim())
                currentBuilder = StringBuilder()
                currentCount = 0
            }
            if (currentBuilder.isNotEmpty()) {
                currentBuilder.append(" ").append(c)
            } else {
                currentBuilder.append(c)
            }
            currentCount += cWords
        }

        if (currentBuilder.isNotEmpty()) {
            clauses.add(currentBuilder.toString().trim())
        }

        return if (clauses.isNotEmpty()) clauses else listOf(sentence)
    }
}

