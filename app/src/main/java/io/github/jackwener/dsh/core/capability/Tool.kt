package io.github.jackwener.dsh.core.capability

import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

@Serializable
data class ToolDescriptor(
    val name: String,
    val group: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: Risk,
    val needsUnlock: Boolean,
    /**
     * The single source of truth for this tool's time budget (docs/ENGINE-CONTRACT.md §3.1). The
     * plugin uses it as the dsh tool timeout and sends it back in `capability/invoke`; the app answers
     * (or gives up waiting for the user) before it runs out, so a timed-out call never acts later.
     */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
        const val UNLOCK_TIMEOUT_MS = 15 * 60_000L
    }
}

sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val mimeType: String, val base64: String) : Content
}

class ToolResult(
    val ok: Boolean,
    val content: List<Content>,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** Measured effect of a screen/app action, for the loop guard (not sent to the engine). */
    val outcome: ActionOutcome? = null,
    /** Fingerprint of the screen the call left behind ([Frame.fingerprint]), for the loop guard. */
    val screen: Int? = null,
    /** Native compound tools consume this directly; it never crosses the engine bridge. */
    val observation: ScreenObservation? = null,
) {
    fun withOutcome(o: ActionOutcome?, screen: Int? = this.screen) = ToolResult(ok, content, errorCode, errorMessage, o, screen, observation)
    fun withObservation(value: ScreenObservation) = ToolResult(ok, content, errorCode, errorMessage, outcome, screen, value)
    fun failed(code: String, message: String) = ToolResult(false, content + Content.Text(message), code, message, outcome, screen, observation)

    /** Keep compound progress machine-readable even when a bridge cancellation interrupts it. */
    fun partialFailure(code: String, message: String): ToolResult {
        val next = content.toMutableList()
        val first = (next.firstOrNull() as? Content.Text)?.text
        val json = first?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (json != null) next[0] = Content.Text(JsonObject(json + mapOf("stopReason" to JsonPrimitive(code)) +
            (if ("items" in json) mapOf("truncated" to JsonPrimitive(true)) else emptyMap())).toString())
        observation?.let { next += Content.Text("Last observed app: ${it.frame.packageName}\n${it.frame.render().take(6000)}") }
        next += Content.Text(message)
        return ToolResult(false, next, code, message, outcome, screen, observation)
    }

    /** Adds a paragraph to the model-facing text (the error message too, for failed calls). */
    fun appendText(text: String): ToolResult {
        val i = content.indexOfLast { it is Content.Text }
        val next = if (i < 0) listOf(Content.Text(text)) + content
            else content.toMutableList().also { it[i] = Content.Text((it[i] as Content.Text).text + "\n\n" + text) }
        return ToolResult(ok, next, errorCode, errorMessage?.let { "$it\n\n$text" }, outcome, screen, observation)
    }

    /** Puts a line before the model-facing text (e.g. which screen the call used). */
    fun prependText(text: String): ToolResult {
        val i = content.indexOfFirst { it is Content.Text }
        val next = if (i < 0) listOf(Content.Text(text)) + content
            else content.toMutableList().also { it[i] = Content.Text(text + "\n" + (it[i] as Content.Text).text) }
        return ToolResult(ok, next, errorCode, errorMessage?.let { "$text\n$it" }, outcome, screen, observation)
    }

    companion object {
        fun text(text: String) = ToolResult(true, listOf(Content.Text(text)))
        fun image(mime: String, b64: String, caption: String? = null) =
            ToolResult(true, listOfNotNull(caption?.let { Content.Text(it) }, Content.Image(mime, b64)))
        fun error(code: String, message: String) = ToolResult(false, listOf(Content.Text(message)), code, message)
    }
}

/** Thrown by tools when an Android permission or service is missing. */
open class PermissionMissingException(val what: String) : Exception(what)
class InvalidArgsException(message: String) : Exception(message)

/**
 * One capability call. [deadlineMs] (SystemClock.elapsedRealtime) bounds every wait for the user;
 * [waitingFor] names what the call is blocked on so a timeout can say "waiting for the user" rather
 * than "failed".
 */
class CallContext(
    val sessionId: String,
    val callId: String,
    val unattended: Boolean,
    val deadlineMs: Long = Long.MAX_VALUE,
    /** A new user turn invalidates an in-flight compound plan, including after permission waits. */
    val checkCurrent: () -> Unit = {},
    private val onProgress: (ToolResult) -> Unit = {},
) {
    @Volatile var waitingFor: String? = null
    @Volatile var progress: ToolResult? = null
        private set
    fun report(result: ToolResult) { progress = result; onProgress(result) }
    fun partialFailure(code: String, message: String): ToolResult? {
        return progress?.partialFailure(code, message)
    }

    /** Runs a user-facing wait, recording what it waits for (cleared when it ends or is cancelled). */
    suspend fun <T> waitFor(what: String, block: suspend () -> T): T {
        waitingFor = what
        try { return block().also { checkCurrent() } } finally { waitingFor = null }
    }
}

abstract class Tool(
    val name: String,
    val group: ToolGroup,
    val risk: Risk,
    val needsUnlock: Boolean,
    val description: String,
    schema: SchemaBuilder.() -> Unit,
    /** Shizuku dependency; CapabilityHub appends the live availability to the description. */
    val shell: ShellUse = ShellUse.None,
    /** What happens without Shizuku (model-facing, e.g. "open the Wi-Fi panel for the user to tap"). */
    val shellFallback: String? = null,
    /** See [ToolDescriptor.timeoutMs]. */
    timeoutMs: Long = if (needsUnlock) ToolDescriptor.UNLOCK_TIMEOUT_MS else ToolDescriptor.DEFAULT_TIMEOUT_MS,
) {
    val descriptor = ToolDescriptor(name, group.id, description, SchemaBuilder().apply(schema).build(), risk, needsUnlock, timeoutMs)
    abstract suspend fun invoke(args: JsonObject, call: CallContext): ToolResult
}

/** Small JSON-Schema DSL for tool inputs. */
class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, o: JsonObject) { props[name] = o; if (required) this.required += name }
    fun string(name: String, desc: String, required: Boolean = false, enum: List<String>? = null) = add(name, required, buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    })
    fun int(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "integer"); put("description", desc) })
    fun number(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "number"); put("description", desc) })
    fun bool(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "boolean"); put("description", desc) })
    fun strings(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject {
        put("type", "array"); put("description", desc); putJsonObject("items") { put("type", "string") }
    })
    fun obj(name: String, desc: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "object"); put("description", desc) })
    fun raw(name: String, schema: JsonObject, required: Boolean = false) = add(name, required, schema)

    fun build() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }
}

// ---- argument helpers ---------------------------------------------------------------------------

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(k: String): String = str(k)?.takeIf { it.isNotEmpty() } ?: throw InvalidArgsException("missing '$k'")
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { p ->
    p.intOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.let { Math.round(it).toInt() }
}
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.let { Math.round(it) } }
fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: p.contentOrNull?.lowercase()?.toBooleanStrictOrNull() }
/** A string array; a single string is accepted as a one-element list (models often send that). */
fun JsonObject.strs(k: String): List<String> = when (val v = this[k]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(v.contentOrNull?.takeIf { it.isNotEmpty() })
    else -> emptyList()
}
fun JsonElement?.asList(): List<JsonElement> = (this as? JsonArray)?.jsonArray ?: emptyList()
