package dev.agenticscheduler.agent.context

/** Provider-owned deterministic units; the generic HTTP profile measures UTF-8 bytes. */
interface BudgetMeter {
    val maxContextUnits: Long
    val reservedOutputUnits: Long
    fun measure(serialized: String): Long
}

enum class ContextClass(val percentCap: Int) {
    CURRENT_DOMAIN(35),
    RECENT_ACTIONS(20),
    RAW_MESSAGES(25),
    SUMMARY(10),
    RETRIEVED_HISTORY(10),
}

data class ContextCandidate(
    val id: String,
    val source: ContextClass,
    val serialized: String,
    val relevance: Int,
    val createdAtEpochMillis: Long,
    /** Same key at a lower authority never displaces a selected current fact. */
    val factKey: String? = null,
) { init { require(id.isNotBlank() && serialized.isNotBlank()) } }

data class ContextRequest(
    val systemInstructions: String,
    val toolSchemas: List<Pair<String, String>>,
    val currentCommand: String,
    val contextAnchor: String?,
    val candidates: List<ContextCandidate>,
) {
    init { require(systemInstructions.isNotBlank() && currentCommand.isNotBlank()) }
}

data class ContextPart(val source: String, val id: String, val serialized: String)

sealed interface ContextAssemblyResult {
    data class Ready(
        val parts: List<ContextPart>,
        val usedUnits: Long,
        val maxInputUnits: Long,
        val rawMessageUnits: Long,
    ) : ContextAssemblyResult

    data class ContextTooLarge(val mandatoryUnits: Long, val maxInputUnits: Long) : ContextAssemblyResult
}

class ContextAssembler(private val meter: BudgetMeter) {
    init { require(meter.maxContextUnits > 0 && meter.reservedOutputUnits > 0) }

    fun assemble(request: ContextRequest): ContextAssemblyResult {
        val minimumReserve = meter.maxContextUnits / 5 + if (meter.maxContextUnits % 5 > 0) 1 else 0
        val maxInput = (meter.maxContextUnits - maxOf(meter.reservedOutputUnits, minimumReserve)).coerceAtLeast(0)
        val mandatory = buildList {
            add(ContextPart("SYSTEM", "system", request.systemInstructions))
            request.toolSchemas.sortedBy(Pair<String, String>::first).forEach { (id, schema) ->
                add(ContextPart("TOOL_SCHEMA", id, schema))
            }
            add(ContextPart("CURRENT_COMMAND", "command", request.currentCommand))
            request.contextAnchor?.let { add(ContextPart("CONTEXT_ANCHOR", "anchor", it)) }
        }
        var used = 0L
        mandatory.forEach { part ->
            val cost = measured(part.serialized)
            if (cost > maxInput - used) return ContextAssemblyResult.ContextTooLarge(used + cost, maxInput)
            used += cost
        }

        val selected = mutableListOf<ContextPart>()
        val seenFacts = mutableSetOf<String>()
        var carry = 0L
        var rawUnits = 0L
        ContextClass.entries.forEach { source ->
            val available = maxInput - used
            val cap = minOf(available, percentOf(maxInput, source.percentCap) + carry)
            var classUsed = 0L
            request.candidates.asSequence()
                .filter { it.source == source }
                .sortedWith(compareByDescending<ContextCandidate> { it.relevance }
                    .thenByDescending { it.createdAtEpochMillis }.thenBy { it.id })
                .forEach { candidate ->
                    if (candidate.factKey != null && candidate.factKey in seenFacts) return@forEach
                    val cost = measured(candidate.serialized)
                    if (cost > cap - classUsed) return@forEach
                    selected += ContextPart(source.name, candidate.id, candidate.serialized)
                    candidate.factKey?.let(seenFacts::add)
                    classUsed += cost
                }
            used += classUsed
            if (source == ContextClass.RAW_MESSAGES) rawUnits = classUsed
            carry = cap - classUsed
        }
        return ContextAssemblyResult.Ready(mandatory + selected, used, maxInput, rawUnits)
    }

    private fun measured(value: String): Long = meter.measure(value).also { require(it >= 0) { "Budget meter returned negative units." } }
    private fun percentOf(value: Long, percent: Int): Long = (value / 100) * percent + ((value % 100) * percent) / 100
}

/** Conservative and deterministic when the configured provider has no exact tokenizer. */
class Utf8ByteBudgetMeter(
    override val maxContextUnits: Long,
    override val reservedOutputUnits: Long,
) : BudgetMeter {
    override fun measure(serialized: String): Long = serialized.encodeToByteArray().size.toLong()
}
