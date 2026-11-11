package dev.dogsc.core.project

import dev.dogsc.core.model.randomId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort bridge for projects exported by the upstream Swift app.
 *
 * The upstream JSON uses human-readable enum raw values (often Chinese), a `createdAt`
 * date and a different `BackgroundSource` / `CameraLayoutMode` encoding. The visual
 * and timeline models are otherwise the same, so translate those discriminators and
 * let the normal ProjectCodec validator decide whether the package is usable.
 */
object ProjectPackageCompat {
    private val captureSources = mapOf(
        "整块屏幕" to "display",
        "单个窗口" to "display",
        "自选区域" to "area",
        "iOS 设备" to "imported",
    )
    private val captureCodecs = mapOf(
        "HEVC" to "hevc",
        "H.264" to "h264",
        "ProRes 422" to "h264",
    )
    private val captureLimits = mapOf(
        "原始分辨率" to "native",
        "最高 4K" to "4k",
        "最高 1080p" to "1080p",
    )
    private val audioScopes = mapOf(
        "全部系统声音" to "all",
        "所选窗口 App" to "selectedApplication",
    )
    private val screenMotionStyles = mapOf("聚焦" to "focused", "平滑" to "smooth")
    private val cursorMotionStyles = mapOf(
        "平滑" to "smooth",
        "适中" to "medium",
        "快速" to "rapid",
        "无" to "none",
    )
    private val cameraShapes = mapOf(
        "方形" to "square",
        "横向" to "horizontal",
        "纵向" to "vertical",
        "原始比例" to "original",
        "圆形" to "circle",
    )
    private val easingValues = mapOf(
        "线性" to "linear",
        "二次方缓动" to "quadratic",
        "三次方缓动" to "cubic",
        "五次平滑" to "quintic",
        "弹簧" to "spring",
        "自定义曲线" to "custom",
    )
    private val openingPresets = mapOf(
        "聚拢" to "converge",
        "侧滑" to "sideSlide",
        "轻 3D" to "light3D",
    )
    private val openingElements = mapOf(
        "屏幕" to "screen",
        "摄像头" to "camera",
        "贴图" to "stickers",
    )
    private val screenFrames = mapOf(
        "devicePhonePortrait" to "devicePhone",
        "devicePhoneLandscape" to "devicePhone",
        "deviceTabletPortrait" to "deviceTablet",
        "deviceTabletLandscape" to "deviceTablet",
    )

    fun adapt(text: String): String? = runCatching {
        val root = ProjectCodec.json.parseToJsonElement(text).jsonObject
        adaptRoot(root).toString()
    }.getOrNull()

    private fun adaptRoot(root: JsonObject): JsonObject = buildJsonObject {
        root.forEach { (key, value) ->
            if (key !in setOf("id", "schemaVersion", "createdAtEpochMs")) put(key, value)
        }
        put("id", JsonPrimitive(randomId()))
        put("schemaVersion", JsonPrimitive(1))
        put("createdAtEpochMs", JsonPrimitive(0))
        root["capture"]?.asObject()?.let { put("capture", adaptCapture(it)) }
        root["canvas"]?.asObject()?.let { put("canvas", adaptCanvas(it)) }
        root["camera"]?.asObject()?.let { put("camera", adaptCamera(it)) }
        root["motion"]?.asObject()?.let { put("motion", adaptMotion(it)) }
        root["openingSequence"]?.asObject()?.let { put("openingSequence", adaptOpening(it)) }
        root["exportSettings"]?.asObject()?.let { put("exportSettings", adaptExportSettings(it)) }
        root["timeline"]?.asObject()?.let { put("timeline", adaptTimeline(it)) }
    }

    private fun adaptCapture(capture: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        capture["source"]?.stringValue()?.let { source ->
            captureSources[source]?.let { replacements["source"] = JsonPrimitive(it) }
        }
        capture["captureCodec"]?.stringValue()?.let { codec ->
            captureCodecs[codec]?.let { replacements["captureCodec"] = JsonPrimitive(it) }
        }
        capture["captureResolutionLimit"]?.stringValue()?.let { limit ->
            captureLimits[limit]?.let { replacements["captureResolutionLimit"] = JsonPrimitive(it) }
        }
        capture["systemAudioScope"]?.stringValue()?.let { scope ->
            audioScopes[scope]?.let { replacements["systemAudioScope"] = JsonPrimitive(it) }
        }
        capture["captureFrameRate"]?.let { replacements["captureFrameRate"] = JsonPrimitive(frameRate(it)) }
        return copy(capture, replacements, setOf("cameraCaptureResolution"))
    }

    private fun adaptCanvas(canvas: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        canvas["screenFrame"]?.stringValue()?.let { frame ->
            screenFrames[frame]?.let { replacements["screenFrame"] = JsonPrimitive(it) }
        }
        canvas["backgroundSource"]?.asObject()?.let {
            replacements["backgroundSource"] = adaptBackground(it)
        }
        return copy(canvas, replacements)
    }

    private fun adaptBackground(source: JsonObject): JsonObject {
        val kind = source["kind"]?.stringValue() ?: source["type"]?.stringValue() ?: "pattern"
        val type = when (kind) {
            "dynamicFlow" -> "dynamicFlow"
            "projectImage" -> "projectImage"
            "systemImage" -> "systemImage"
            "projectVideo" -> "projectVideo"
            "systemVideo" -> "systemVideo"
            else -> "pattern"
        }
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            (source["patternPreset"] ?: source["dynamicPreset"] ?: source["preset"])?.let {
                put("preset", it)
            }
            source["relativePath"]?.let { put("relativePath", it) }
            source["absolutePath"]?.let { put("absolutePath", it) }
        }
    }

    private fun adaptCamera(camera: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        camera["shape"]?.stringValue()?.let { shape ->
            cameraShapes[shape]?.let { replacements["shape"] = JsonPrimitive(it) }
        }
        return copy(camera, replacements)
    }

    private fun adaptMotion(motion: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        motion["screen"]?.stringValue()?.let { value ->
            screenMotionStyles[value]?.let { replacements["screen"] = JsonPrimitive(it) }
        }
        motion["cursor"]?.stringValue()?.let { value ->
            cursorMotionStyles[value]?.let { replacements["cursor"] = JsonPrimitive(it) }
        }
        return copy(motion, replacements)
    }

    private fun adaptOpening(opening: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        opening["preset"]?.stringValue()?.let { value ->
            openingPresets[value]?.let { replacements["preset"] = JsonPrimitive(it) }
        }
        listOf("includedElements", "elementOrder").forEach { key ->
            (opening[key] as? JsonArray)?.let { array ->
                replacements[key] = buildJsonArray {
                    array.forEach { element ->
                        val raw = element.stringValue()
                        add(JsonPrimitive(raw?.let { openingElements[it] } ?: raw ?: ""))
                    }
                }
            }
        }
        return copy(opening, replacements)
    }

    private fun adaptExportSettings(settings: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        settings["frameRate"]?.let { replacements["frameRate"] = JsonPrimitive(frameRate(it)) }
        return copy(settings, replacements)
    }

    private fun adaptTimeline(timeline: JsonObject): JsonObject {
        val replacements = mutableMapOf<String, JsonElement>()
        timeline["zoomClips"]?.asArray()?.let { clips ->
            replacements["zoomClips"] = buildJsonArray {
                clips.forEach { element ->
                    val clip = element.asObject() ?: return@forEach
                    val easing = clip["easing"]?.stringValue()
                    add(if (easing != null && easingValues.containsKey(easing)) copy(clip, mapOf("easing" to JsonPrimitive(easingValues.getValue(easing)))) else clip)
                }
            }
        }
        listOf("screenMotionClips", "cameraMotionClips").forEach { key ->
            timeline[key]?.asArray()?.let { clips ->
                replacements[key] = buildJsonArray {
                    clips.forEach { element ->
                        val clip = element.asObject() ?: return@forEach
                        val clipReplacements = mutableMapOf<String, JsonElement>()
                        clip["timing"]?.asObject()?.let { timing ->
                            timing["easing"]?.stringValue()?.let { easing ->
                                easingValues[easing]?.let {
                                    clipReplacements["timing"] = copy(timing, mapOf("easing" to JsonPrimitive(it)))
                                }
                            }
                        }
                        if (key == "cameraMotionClips") {
                            clip["target"]?.asObject()?.let { target ->
                                target["layout"]?.asObject()?.let { layout ->
                                    clipReplacements["target"] = copy(
                                        target,
                                        mapOf("layout" to adaptCameraLayout(layout)),
                                    )
                                }
                            }
                        }
                        add(copy(clip, clipReplacements))
                    }
                }
            }
        }
        return copy(timeline, replacements)
    }

    private fun adaptCameraLayout(layout: JsonObject): JsonObject {
        val kind = layout["kind"]?.stringValue() ?: layout["type"]?.stringValue()
        if (kind == "fullscreen") {
            return buildJsonObject { put("type", JsonPrimitive("fullscreen")) }
        }
        val shape = layout["shape"]?.stringValue()?.let { cameraShapes[it] } ?: "square"
        return buildJsonObject {
            put("type", JsonPrimitive("shape"))
            put("shape", JsonPrimitive(shape))
        }
    }

    private fun copy(
        source: JsonObject,
        replacements: Map<String, JsonElement>,
        remove: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        source.forEach { (key, value) ->
            if (key !in remove && key !in replacements) put(key, value)
        }
        replacements.forEach { (key, value) -> put(key, value) }
    }

    private fun frameRate(value: JsonElement): String {
        val number = value.jsonPrimitive.intOrNull
            ?: value.stringValue()?.toIntOrNull()
            ?: 60
        return if (number == 30) "30" else "60"
    }

    private fun JsonElement.stringValue(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    private fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()
    private fun JsonElement.asArray(): JsonArray? = runCatching { this as JsonArray }.getOrNull()
}
