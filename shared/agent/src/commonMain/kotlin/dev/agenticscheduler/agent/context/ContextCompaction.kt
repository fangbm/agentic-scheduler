package dev.agenticscheduler.agent.context

import dev.agenticscheduler.agent.history.AgentMessage
import dev.agenticscheduler.agent.history.AgentMessageId
import dev.agenticscheduler.agent.history.ContextSummary
import dev.agenticscheduler.agent.history.ContextSummaryId
import dev.agenticscheduler.agent.history.ProviderConfigId
import dev.agenticscheduler.application.id.EpochMillisecondsClock
import dev.agenticscheduler.application.id.UuidV7Generator
import kotlinx.coroutines.CancellationException

data class CompactionPressure(
    val selectedRawMessageUnits: Long,
    val assembledPromptUnits: Long,
    val maxInputUnits: Long,
) { init { require(selectedRawMessageUnits >= 0 && assembledPromptUnits >= 0 && maxInputUnits > 0) } }

sealed interface CompactionSelection {
    data object NotNeeded : CompactionSelection
    data class Prefix(val messages: List<AgentMessage>) : CompactionSelection
}

/** Selects only the oldest closed prefix; all source messages remain durable. */
object ContextCompactionSelector {
    fun select(
        messages: List<AgentMessage>,
        priorSummary: ContextSummary?,
        protectedMessageIds: Set<AgentMessageId>,
        pressure: CompactionPressure,
    ): CompactionSelection {
        val rawCap = percentOf(pressure.maxInputUnits, 25)
        val promptThreshold = percentOf(pressure.maxInputUnits, 80)
        if (pressure.selectedRawMessageUnits <= rawCap && pressure.assembledPromptUnits <= promptThreshold) {
            return CompactionSelection.NotNeeded
        }
        val ordered = messages.sortedWith(compareBy<AgentMessage>({ it.ordinal }, { it.id.value }))
        val afterSummary = if (priorSummary == null) 0 else {
            val end = ordered.indexOfFirst { it.id == priorSummary.sourceEndMessageId }
            require(end >= 0) { "Summary source range is absent from authoritative history." }
            end + 1
        }
        val keepRecentFrom = (ordered.size - 12).coerceAtLeast(0)
        val keepUnresolvedFrom = ordered.indexOfFirst { it.id in protectedMessageIds }.let { if (it < 0) ordered.size else it }
        val endExclusive = minOf(keepRecentFrom, keepUnresolvedFrom)
        val prefix = ordered.subList(afterSummary.coerceAtMost(endExclusive), endExclusive)
        return if (prefix.isEmpty()) CompactionSelection.NotNeeded else CompactionSelection.Prefix(prefix)
    }

    private fun percentOf(value: Long, percent: Int): Long = (value / 100) * percent + ((value % 100) * percent) / 100
}

fun interface ContextSummaryProvider {
    suspend fun summarize(previous: ContextSummary?, messages: List<AgentMessage>): String
}

sealed interface ContextCompactionResult {
    data object NotNeeded : ContextCompactionResult
    data class Saved(val summary: ContextSummary) : ContextCompactionResult
    data class Failed(val code: String) : ContextCompactionResult
}

class ContextCompactionService(
    private val provider: ContextSummaryProvider,
    private val appendSummary: suspend (ContextSummary) -> Unit,
    private val ids: UuidV7Generator,
    private val clock: EpochMillisecondsClock,
    private val summarySchemaVersion: Int,
) {
    init { require(summarySchemaVersion > 0) }

    suspend fun compact(
        messages: List<AgentMessage>,
        previous: ContextSummary?,
        protectedMessageIds: Set<AgentMessageId>,
        pressure: CompactionPressure,
        providerConfigId: ProviderConfigId?,
        model: String?,
    ): ContextCompactionResult {
        val selected = ContextCompactionSelector.select(messages, previous, protectedMessageIds, pressure)
        if (selected !is CompactionSelection.Prefix) return ContextCompactionResult.NotNeeded
        val text = try {
            provider.summarize(previous, selected.messages).takeIf(String::isNotBlank)
                ?: return ContextCompactionResult.Failed("EMPTY_SUMMARY")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            return ContextCompactionResult.Failed("SUMMARY_PROVIDER_FAILED")
        }
        val summary = ContextSummary(
            ContextSummaryId(ids.next()), selected.messages.first().threadId,
            previous?.sourceStartMessageId ?: selected.messages.first().id,
            selected.messages.last().id, summarySchemaVersion, text,
            clock.nowMilliseconds(), providerConfigId, model,
        )
        return try {
            appendSummary(summary)
            ContextCompactionResult.Saved(summary)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            ContextCompactionResult.Failed("SUMMARY_PERSISTENCE_FAILED")
        }
    }
}
