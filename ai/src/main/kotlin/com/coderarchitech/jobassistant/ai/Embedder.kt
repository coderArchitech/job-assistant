package com.coderarchitech.jobassistant.ai

/**
 * Turns text into vectors for the embedding floor (design: Matching and scoring). Implementations
 * are the bundled on-device model (decision D05) and the OpenAI embeddings API.
 */
interface Embedder {
    /**
     * Identifies the model. It is stored with every vector, and a change re-embeds the profile,
     * because vectors from different models cannot be compared.
     */
    val id: String

    /** Returns one vector per text, in order; every vector has the same length. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}
