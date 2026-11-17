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
        ProjectValidator.validate(project)
        return project
    }

    fun decodeOrNull(text: String): RecorderProject? = runCatching { decode(text) }.getOrNull()
}
