package com.example.craftandroid

/** Future extension points. The MVP does NOT create network requests or ship API credentials. */
interface AiExplanationGateway {
    /** Only the fields explicitly approved by the user should leave the device. */
    suspend fun explainApprovedReport(redactedReportJson: String): Result<String>
}

interface CloudBuildGateway {
    /** Only submit files after an explicit user action and confirmation. */
    suspend fun submitAuthorizedProject(sourceArchive: ByteArray): Result<String>
}

object DisabledAiGateway : AiExplanationGateway {
    override suspend fun explainApprovedReport(redactedReportJson: String): Result<String> =
        Result.failure(UnsupportedOperationException("AI belum dikonfigurasi."))
}

object DisabledCloudBuildGateway : CloudBuildGateway {
    override suspend fun submitAuthorizedProject(sourceArchive: ByteArray): Result<String> =
        Result.failure(UnsupportedOperationException("Cloud build belum dikonfigurasi."))
}
