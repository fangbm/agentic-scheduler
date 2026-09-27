package dev.agenticscheduler.agent.tool

import dev.agenticscheduler.agent.history.AgentActionId
import dev.agenticscheduler.agent.permission.AgentPermissionDecision
import dev.agenticscheduler.agent.permission.AgentPermissionEngine
import dev.agenticscheduler.agent.permission.AgentPermissionPolicy
import dev.agenticscheduler.agent.permission.AgentToolCapability
import dev.agenticscheduler.application.history.AgentOriginWriteNotAllowed
import dev.agenticscheduler.application.history.MutationExecution
import dev.agenticscheduler.application.planner.PlanningProfileSettingsPreview
import dev.agenticscheduler.application.planner.PlanningProfileSettingsResult
import dev.agenticscheduler.application.planner.PlanningProfileAgentSaveResult
import dev.agenticscheduler.application.planner.PlanningProfileSettingsService
import dev.agenticscheduler.domain.id.PlanningProfileId
import dev.agenticscheduler.domain.planning.AllDayEventPolicy
import dev.agenticscheduler.domain.planning.PlanningProfile
import dev.agenticscheduler.domain.planning.PlanningProfileConfiguration
import dev.agenticscheduler.domain.planning.WeeklyAvailabilityWindow
import dev.agenticscheduler.sync.MutationId
import kotlin.time.Duration.Companion.minutes
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PLANNING_PROFILE_UPDATE_TOOL_NAME = "planningProfile.update"

@Serializable
data class PlanningProfileAvailabilityInput(
    val dayOfWeek: String,
    val start: String,
    val endExclusive: String,
)

/** CONFIGURED fields are required together; UNCONFIGURED rejects all of them. */
@Serializable
data class PlanningProfileUpdateToolInput(
    val planningProfileId: String,
    val name: String,
    val configuration: String,
    val timeZone: String? = null,
    val weeklyAvailability: List<PlanningProfileAvailabilityInput>? = null,
    val minimumFocusBlockMinutes: Long? = null,
    val preferredFocusBlockMinutes: Long? = null,
    val maximumFocusBlockMinutes: Long? = null,
    val allDayEventPolicy: String? = null,
)

data class PlanningProfileUpdateWritePreview(val before: PlanningProfile?, val after: PlanningProfile)
data class CommittedPlanningProfileUpdate(val profile: PlanningProfile, val mutationId: MutationId)

/** Typed profile mutation routed through PlanningProfileSettingsService and its transaction boundary. */
class PlanningProfileUpdateTool(
    private val settings: PlanningProfileSettingsService,
    private val permissions: AgentPermissionEngine = AgentPermissionEngine(),
) {
    val metadata = AgentToolMetadata(PLANNING_PROFILE_UPDATE_TOOL_NAME, AgentToolCapability.PLANNING_PROFILE_CHANGE, AgentToolAccess.WRITE)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun prepare(argumentsJson: String, policy: AgentPermissionPolicy): AgentToolOutcome<PlanningProfileUpdateWritePreview> {
        val profile = when (val decoded = decode(argumentsJson)) {
            is Decoded.Valid -> decoded.profile
            is Decoded.Invalid -> return decoded.outcome
        }
        val saved = settings.previewSave(profile)
        if (saved.before == null) return AgentToolOutcome.NotFound
        val preview = saved.toToolPreview()
        return when (permissions.evaluate(policy, metadata.capability)) {
            AgentPermissionDecision.AllowDirect -> AgentToolOutcome.Success(preview)
            AgentPermissionDecision.RequireConfirmation -> AgentToolOutcome.ConfirmationRequired(preview)
            AgentPermissionDecision.Deny -> AgentToolOutcome.PermissionDenied(metadata.capability)
        }
    }

    suspend fun commit(
        argumentsJson: String,
        shownPreview: PlanningProfileUpdateWritePreview,
        userConfirmed: Boolean,
        policy: AgentPermissionPolicy,
        agentActionId: AgentActionId,
        onCommitted: suspend (MutationExecution<PlanningProfileSettingsResult>) -> Unit = {},
    ): AgentToolOutcome<CommittedPlanningProfileUpdate> {
        val prepared = prepare(argumentsJson, policy)
        val current = when (prepared) {
            is AgentToolOutcome.Success -> prepared.payload
            is AgentToolOutcome.ConfirmationRequired -> prepared.preview
            is AgentToolOutcome.InvalidInput -> return prepared
            is AgentToolOutcome.PermissionDenied -> return prepared
            AgentToolOutcome.NotFound -> return AgentToolOutcome.NotFound
            else -> return AgentToolOutcome.InfrastructureFailure("UNEXPECTED_PROFILE_PREVIEW")
        }
        if (current != shownPreview) return AgentToolOutcome.Stale
        if (prepared is AgentToolOutcome.ConfirmationRequired && !userConfirmed) return AgentToolOutcome.PermissionDenied(metadata.capability)
        val profile = (decode(argumentsJson) as Decoded.Valid).profile
        return try {
            when (val result = settings.saveAgent(profile, shownPreview.before, agentActionId.value, onCommitted)) {
                is PlanningProfileAgentSaveResult.Success -> {
                    AgentToolOutcome.Success(CommittedPlanningProfileUpdate(result.profile, result.mutationId))
                }
                PlanningProfileAgentSaveResult.Stale -> AgentToolOutcome.Stale
                PlanningProfileAgentSaveResult.NotFound -> AgentToolOutcome.NotFound
                is PlanningProfileAgentSaveResult.BlockedBySyncConflict -> AgentToolOutcome.Conflict(result.blocks.map { it.conflictId })
            }
        } catch (_: AgentOriginWriteNotAllowed) {
            AgentToolOutcome.PermissionDenied(metadata.capability)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolOutcome.InfrastructureFailure("PLANNING_PROFILE_UPDATE_TRANSACTION")
        }
    }

    fun normalizedPreviewJson(preview: PlanningProfileUpdateWritePreview): String = json.encodeToString(
        ProfilePreviewSnapshot(preview.before?.toSnapshot(), preview.after.toSnapshot()),
    )

    private fun decode(argumentsJson: String): Decoded {
        val input = try { json.decodeFromString(PlanningProfileUpdateToolInput.serializer(), argumentsJson) }
        catch (_: SerializationException) { return Decoded.Invalid(invalidPlanningProfileInput("arguments", "INVALID_JSON")) }
        catch (_: IllegalArgumentException) { return Decoded.Invalid(invalidPlanningProfileInput("arguments", "INVALID_JSON")) }
        val issues = mutableListOf<AgentToolInputIssue>()
        val id = runCatching { PlanningProfileId(input.planningProfileId) }.getOrNull()
            ?: run { issues += AgentToolInputIssue("planningProfileId", "INVALID_ID"); null }
        val profile = try {
            val configuration = when (input.configuration) {
                "UNCONFIGURED" -> {
                    if (input.timeZone != null || input.weeklyAvailability != null || input.minimumFocusBlockMinutes != null ||
                        input.preferredFocusBlockMinutes != null || input.maximumFocusBlockMinutes != null || input.allDayEventPolicy != null
                    ) issues += AgentToolInputIssue("configuration", "NOT_APPLICABLE")
                    PlanningProfileConfiguration.Unconfigured
                }
                "CONFIGURED" -> {
                    val zone = input.timeZone ?: run { issues += AgentToolInputIssue("timeZone", "REQUIRED"); null }
                    val availability = input.weeklyAvailability ?: run { issues += AgentToolInputIssue("weeklyAvailability", "REQUIRED"); null }
                    val minimum = input.minimumFocusBlockMinutes ?: run { issues += AgentToolInputIssue("minimumFocusBlockMinutes", "REQUIRED"); null }
                    val preferred = input.preferredFocusBlockMinutes ?: run { issues += AgentToolInputIssue("preferredFocusBlockMinutes", "REQUIRED"); null }
                    val maximum = input.maximumFocusBlockMinutes ?: run { issues += AgentToolInputIssue("maximumFocusBlockMinutes", "REQUIRED"); null }
                    val policy = AllDayEventPolicy.entries.firstOrNull { it.name == input.allDayEventPolicy }
                        ?: run { issues += AgentToolInputIssue("allDayEventPolicy", if (input.allDayEventPolicy == null) "REQUIRED" else "UNKNOWN_VALUE"); null }
                    if (minimum != null && minimum <= 0) issues += AgentToolInputIssue("minimumFocusBlockMinutes", "MUST_BE_POSITIVE")
                    if (preferred != null && preferred <= 0) issues += AgentToolInputIssue("preferredFocusBlockMinutes", "MUST_BE_POSITIVE")
                    if (maximum != null && maximum <= 0) issues += AgentToolInputIssue("maximumFocusBlockMinutes", "MUST_BE_POSITIVE")
                    if (zone == null || availability == null || minimum == null || preferred == null || maximum == null || policy == null) null
                    else PlanningProfileConfiguration.Configured(
                        TimeZone.of(zone), availability.map { window ->
                            WeeklyAvailabilityWindow(
                                DayOfWeek.entries.firstOrNull { it.name == window.dayOfWeek }
                                    ?: throw InvalidProfileField("weeklyAvailability.dayOfWeek"),
                                LocalTime.parse(window.start), LocalTime.parse(window.endExclusive),
                            )
                        }.toImmutableList(),
                        minimum.minutes, preferred.minutes, maximum.minutes, policy,
                    )
                }
                else -> { issues += AgentToolInputIssue("configuration", "UNKNOWN_VALUE"); null }
            }
            if (issues.isNotEmpty() || id == null || configuration == null) null
            else PlanningProfile(requireNotNull(id), input.name, configuration)
        } catch (invalid: InvalidProfileField) {
            issues += AgentToolInputIssue(invalid.field, "INVALID_VALUE"); null
        } catch (_: IllegalArgumentException) {
            issues += AgentToolInputIssue("configuration", "INVALID_VALUE"); null
        }
        return if (issues.isEmpty() && profile != null) Decoded.Valid(profile) else Decoded.Invalid(AgentToolOutcome.InvalidInput(issues.ifEmpty { listOf(AgentToolInputIssue("profile", "INVALID")) }))
    }

    private sealed interface Decoded {
        data class Valid(val profile: PlanningProfile) : Decoded
        data class Invalid(val outcome: AgentToolOutcome.InvalidInput) : Decoded
    }

    @Serializable private data class ProfilePreviewSnapshot(val before: ProfileSnapshot?, val after: ProfileSnapshot)
    @Serializable private data class ProfileSnapshot(
        val id: String,
        val name: String,
        val configuration: String,
        val timeZone: String? = null,
        val weeklyAvailability: List<PlanningProfileAvailabilityInput>? = null,
        val minimumFocusBlock: String? = null,
        val preferredFocusBlock: String? = null,
        val maximumFocusBlock: String? = null,
        val allDayEventPolicy: String? = null,
    )
    private fun PlanningProfile.toSnapshot(): ProfileSnapshot = when (val config = configuration) {
        PlanningProfileConfiguration.Unconfigured -> ProfileSnapshot(id.value, name, "UNCONFIGURED")
        is PlanningProfileConfiguration.Configured -> ProfileSnapshot(
            id.value, name, "CONFIGURED", config.timeZone.id,
            config.weeklyAvailability.map { PlanningProfileAvailabilityInput(it.dayOfWeek.name, it.start.toString(), it.endExclusive.toString()) },
            config.minimumFocusBlock.toString(), config.preferredFocusBlock.toString(), config.maximumFocusBlock.toString(),
            config.allDayEventPolicy.name,
        )
    }
    private fun PlanningProfileSettingsPreview.toToolPreview() = PlanningProfileUpdateWritePreview(before, after)
    private class InvalidProfileField(val field: String) : IllegalArgumentException()
}

private fun invalidPlanningProfileInput(field: String, code: String) = AgentToolOutcome.InvalidInput(listOf(AgentToolInputIssue(field, code)))
