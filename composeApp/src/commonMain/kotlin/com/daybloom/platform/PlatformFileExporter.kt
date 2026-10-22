package com.daybloom.platform

sealed interface ExportResult {
    data class Shared(val location: String) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/** Writes a text document and hands it to the platform share sheet. */
interface PlatformFileExporter {
    suspend fun shareText(fileName: String, mimeType: String, content: String): ExportResult
}
