package dev.dogsc.core.project

import dev.dogsc.core.model.ProjectValidator
import dev.dogsc.core.model.RecorderProject
import kotlinx.serialization.json.Json

object ProjectCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
    }

    fun encode(project: RecorderProject): String = json.encodeToString(RecorderProject.serializer(), project)

    fun decode(text: String): RecorderProject {
        val project = json.decodeFromString(RecorderProject.serializer(), text)
        require(project.schemaVersion <= RecorderProject.CurrentSchemaVersion) {
            "项目格式版本 ${project.schemaVersion} 高于当前支持的 ${RecorderProject.CurrentSchemaVersion}，请升级应用后再打开。"
        }
        ProjectValidator.validate(project)
        return project
    }

    fun decodeOrNull(text: String): RecorderProject? = decodeWithResult(text).getOrNull()

    /**
     * Decode with the failure reason preserved, so callers can tell a corrupt file
     * apart from a validation rejection instead of silently swallowing both.
     */
    fun decodeWithResult(text: String): Result<RecorderProject> = runCatching { decode(text) }
}
