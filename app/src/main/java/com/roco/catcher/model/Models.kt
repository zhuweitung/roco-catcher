package com.roco.catcher.model

import kotlinx.serialization.Serializable

data class AppSettings(
    val helperIp: String = "",
    val helperPort: Int? = null,
    val targetNotifyEnabled: Boolean = true,
    val lowSpeedNotifyEnabled: Boolean = true,
) {
    val hasEndpoint: Boolean
        get() = helperIp.isNotBlank() && helperPort != null && helperPort in 1..65535
}

data class RecentTaskSettings(
    val targetCount: Int = 1,
    val minRatePerMinute: Double = 0.0,
    val uid: String? = null,
    val targetName: String? = null,
    val targets: List<CaptureTarget> = emptyList(),
) {
    fun resolvedTargetNames(): List<String> {
        return listOfNotNull(targetName?.takeIf { it.isNotBlank() })
    }
}

@Serializable
data class HelperUser(
    val uid: String,
    val name: String,
    val avatar: Long?,
) {
    override fun toString(): String = name
}

data class EvolutionChain(
    val name: String,
    val pets: List<PetDefinition>,
)

data class PetDefinition(
    val id: String,
    val name: String,
    val chainName: String,
)

@Serializable
sealed class CaptureTarget {
    abstract val displayName: String
    abstract val targetBaseConfIds: Set<String>

    @Serializable
    data class Chain(
        override val displayName: String,
        override val targetBaseConfIds: Set<String>,
        val petNames: List<String>,
    ) : CaptureTarget()

    @Serializable
    data class SinglePet(
        override val displayName: String,
        val petId: String,
        val chainName: String,
    ) : CaptureTarget() {
        override val targetBaseConfIds: Set<String> = setOf(petId)
    }
}

enum class TargetSearchMode(val label: String) {
    Chain("进化链"),
    SinglePet("单一精灵"),
}

data class TargetSearchResult(
    val title: String,
    val subtitle: String,
    val mode: TargetSearchMode,
    val target: CaptureTarget,
) {
    override fun toString(): String = "$title\n$subtitle"
}

@Serializable
data class CaptureTaskConfig(
    val user: HelperUser,
    val target: CaptureTarget? = null,
    val targetCount: Int,
    val minRatePerMinute: Double,
    val targets: List<CaptureTarget> = emptyList(),
) {
    fun resolvedTargets(): List<CaptureTarget> {
        return if (targets.isNotEmpty()) targets else listOfNotNull(target)
    }

    val displayName: String
        get() {
            val list = resolvedTargets()
            return when {
                list.isEmpty() -> ""
                list.size == 1 -> list.first().displayName
                list.size <= 3 -> list.joinToString("、") { it.displayName }
                else -> list.take(2).joinToString("、") { it.displayName } + " 等${list.size}项"
            }
        }

    val allTargetBaseConfIds: Set<String>
        get() = resolvedTargets().flatMap { it.targetBaseConfIds }.toSet()
}

@Serializable
enum class TaskStatus(val label: String) {
    Idle("未开始"),
    Connecting("连接中"),
    Running("监听中"),
    Reconnecting("重连中"),
    Paused("已暂停"),
    Failed("连接失败"),
}

@Serializable
enum class LowSpeedKind {
    Disabled,
    WarmingUp,
    Normal,
    Pending,
    Alerted,
    SuppressedAfterTargetReached,
}

const val LOW_SPEED_WARM_UP_MILLIS = 60_000L
const val LOW_SPEED_PENDING_MILLIS = 30_000L

@Serializable
data class LowSpeedState(
    val kind: LowSpeedKind = LowSpeedKind.Disabled,
    val startedEffectiveMillis: Long = 0L,
)

@Serializable
data class CaughtPetEvent(
    val gid: Long,
    val baseConfId: String,
    val petName: String?,
    val caughtAtMillis: Long,
    val receivedAtMillis: Long = caughtAtMillis,
    val effectiveRunMillis: Long,
)

@Serializable
data class ThrowBallEvent(
    val ballId: Long?,
    val thrownAtMillis: Long,
    val receivedAtMillis: Long = thrownAtMillis,
)

@Serializable
data class RatePoint(
    val bucketIndex: Long,
    val displayTimeMillis: Long,
    val count: Int,
    val ratePerMinute: Double,
)

@Serializable
data class CaptureTaskState(
    val status: TaskStatus = TaskStatus.Idle,
    val config: CaptureTaskConfig? = null,
    val taskStartedAtMillis: Long? = null,
    val caughtGids: Set<Long> = emptySet(),
    val caughtEvents: List<CaughtPetEvent> = emptyList(),
    val throwBallCount: Int = 0,
    val throwBallEvents: List<ThrowBallEvent> = emptyList(),
    val targetNotifySent: Boolean = false,
    val targetReachedAtMillis: Long? = null,
    val lowSpeedState: LowSpeedState = LowSpeedState(),
    val activeRunStartedAtMillis: Long? = null,
    val accumulatedRunMillis: Long = 0L,
    val rateHistory: List<RatePoint> = emptyList(),
    val errorMessage: String? = null,
) {
    val caughtCount: Int
        get() = caughtGids.size

    val targetReached: Boolean
        get() = config != null && caughtCount >= config.targetCount
}

