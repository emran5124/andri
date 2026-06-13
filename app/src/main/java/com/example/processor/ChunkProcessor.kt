package com.example.processor

class ChunkProcessor {

    /**
     * Splits the file contents based on a custom separator, trimming fragments 
     * and filtering out empty lines or segments.
     */
    fun splitIntoSections(content: String, separator: String): List<String> {
        if (content.isBlank()) return emptyList()
        return content.split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
