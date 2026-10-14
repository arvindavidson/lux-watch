package com.luxwatch.gadget.noise

/**
 * Commands this device offers Muse, sent in link.register as `commands_v2`
 * (the SDK's COMMAND_SPECS / esp32 add_command shape), and the result of
 * running one for a link.invoke.
 */
data class CommandSpec(
    val name: String,
    val description: String,
    val required: List<Param> = emptyList(),
    val optional: List<Param> = emptyList(),
    /** How long Muse should wait for the result; null = the server default (30 s). */
    val timeoutMs: Long? = null,
) {
    data class Param(val name: String, val type: String, val description: String)

    internal fun toJson(): String = buildString {
        append("{\"description\":").append(Json.escape(description))
        append(",\"required\":").append(params(required))
        append(",\"optional\":").append(params(optional))
        if (timeoutMs != null) append(",\"timeout_ms\":").append(timeoutMs)
        append('}')
    }

    private fun params(list: List<Param>) = list.joinToString(",", "{", "}") {
        "${Json.escape(it.name)}:{\"type\":${Json.escape(it.type)},\"description\":${Json.escape(it.description)}}"
    }

    companion object {
        internal fun catalog(specs: List<CommandSpec>): String =
            specs.joinToString(",", "{", "}") { "${Json.escape(it.name)}:${it.toJson()}" }
    }
}

/** What a command returns: `ok(payload)` or `error(message)`, as in the SDK's executor. */
sealed class CommandResult {
    /** [payloadJson] is a JSON object. */
    data class Ok(val payloadJson: String = "{}") : CommandResult()
    data class Error(val message: String) : CommandResult()
}

/** Runs one invoked command. [paramsJson] is the invoke's `params` object ("{}" if none). */
fun interface CommandHandler {
    suspend fun invoke(command: String, paramsJson: String): CommandResult
}

/** Reading command params (flat JSON objects). */
object CommandParams {
    fun string(paramsJson: String, key: String): String? = Json.str(paramsJson, key)

    fun bool(paramsJson: String, key: String): Boolean? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)").find(paramsJson)?.groupValues?.get(1)?.toBooleanStrict()

    /** The nested object under [key] ("" if absent). */
    fun obj(paramsJson: String, key: String): String = Json.obj(paramsJson, key)
}
