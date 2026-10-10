package mihon.data.sync.runtime

import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.runtime.SyncRunProblem

/** Presentation decisions are suggestions. Runtime admission still checks the actual binding and permissions. */
@kotlinx.serialization.Serializable
enum class SyncRecoveryAction {
    CONNECT_GITHUB,
    INSTALL_APP,
    AUTHORIZE_REPOSITORY,
    CREATE_SPACE,
    CHOOSE_SPACE,
    CHECK_CONDITIONS,
    NETWORK,
    STORAGE,
    UPDATE,
    BACKUP,
    EXTENSIONS,
    MIGRATION,
    READER,
    REPAIR_DATA,
    VERIFY_SYNC,
    CONTINUE_SETUP,
    EDIT_REPOSITORY_NAME,
    REPAIR_REPOSITORY_PROPERTIES,
    MANAGE_AUTHORIZATION,
    RESUME_SYNC,
    RESUME_IMPORT,
    WAIT_EXTERNAL,
    OFFICIAL_CREATE,
    RESTORE_REPOSITORY,
    RESTORE_INSTALLATION,
    ENABLE_SYNC,
    WAIT_SERVICE,
}

sealed interface SyncRecoveryActionAvailability {
    data object Ready : SyncRecoveryActionAvailability
    data class NeedsStep(val step: SyncRecoveryAction, val reason: SyncDiscoveryProblem? = null) :
        SyncRecoveryActionAvailability
    data class Waiting(val reason: SyncDiscoveryProblem? = null, val untilMillis: Long? = null) :
        SyncRecoveryActionAvailability
    data object NotApplicable : SyncRecoveryActionAvailability
}

data class SyncRecoveryActionDecision(
    val action: SyncRecoveryAction,
    val availability: SyncRecoveryActionAvailability = SyncRecoveryActionAvailability.Ready,
)

internal fun SyncPanelState.recoveryDecision(): SyncRecoveryActionDecision {
    fun ready(action: SyncRecoveryAction) = recoveryActionDecision(action)
    val latestSetupObservation = page == SyncPanelPage.SETUP && setupProblem != null
    val discovery = if (recoveryConditionsVerified) {
        null
    } else if (latestSetupObservation) {
        setupProblem
    } else {
        recoveryStepFailure?.discovery ?: setupProblem
            ?: recoveryFailure?.discovery
    }
    if (!latestSetupObservation && run?.state == SyncRunState.PAUSED_USER) return ready(SyncRecoveryAction.RESUME_SYNC)
    if (!latestSetupObservation && importPaused && importRemaining > 0) return ready(SyncRecoveryAction.RESUME_IMPORT)
    if (recoveryPersistenceFailed || problem == SyncRunProblem.STORAGE ||
        (
            !recoveryConditionsVerified &&
                recoveryFailure?.problem == SyncRunProblem.STORAGE
            ) ||
        discovery == SyncDiscoveryProblem.STORAGE_ERROR
    ) {
        return ready(SyncRecoveryAction.STORAGE)
    }
    if (!latestSetupObservation && !recoveryConditionsVerified &&
        (recoveryStepFailure?.httpStatus ?: recoveryFailure?.httpStatus ?: 0) >= 500
    ) {
        return SyncRecoveryActionDecision(
            SyncRecoveryAction.WAIT_SERVICE,
            SyncRecoveryActionAvailability.Waiting(SyncDiscoveryProblem.RETRYABLE),
        )
    }
    authFailure?.takeIf { !recoveryConditionsVerified && !latestSetupObservation }?.let { failure ->
        return when (failure) {
            GitHubAuthFailureReason.HTTP -> ready(SyncRecoveryAction.NETWORK)
            GitHubAuthFailureReason.MALFORMED_RESPONSE -> ready(SyncRecoveryAction.NETWORK)
            GitHubAuthFailureReason.RATE_LIMITED -> SyncRecoveryActionDecision(
                SyncRecoveryAction.WAIT_EXTERNAL,
                SyncRecoveryActionAvailability.Waiting(
                    SyncDiscoveryProblem.RATE_LIMITED,
                    authRetryAtMillis.takeIf {
                        it > nowMillis
                    },
                ),
            )
            GitHubAuthFailureReason.ACCESS_DENIED,
            GitHubAuthFailureReason.PERMISSION_DENIED,
            GitHubAuthFailureReason.EXPIRED,
            GitHubAuthFailureReason.REVOKED,
            -> ready(SyncRecoveryAction.CONNECT_GITHUB)
        }
    }
    if ((!latestSetupObservation && authRetryAtMillis > nowMillis) || discovery == SyncDiscoveryProblem.RATE_LIMITED) {
        return SyncRecoveryActionDecision(
            SyncRecoveryAction.WAIT_EXTERNAL,
            SyncRecoveryActionAvailability.Waiting(discovery, authRetryAtMillis.takeIf { it > nowMillis }),
        )
    }
    val init = if (recoveryConditionsVerified) null else initializationFailure ?: recoveryFailure?.initialization
    if (recoveryOfficialAction != null && !recoveryOfficialCheckAttempted &&
        discovery in setOf(
            SyncDiscoveryProblem.NEEDS_INSTALLATION,
            SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
            SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION,
            SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION,
            SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION,
            SyncDiscoveryProblem.INSTALLATION_SUSPENDED,
            SyncDiscoveryProblem.REPOSITORY_DISABLED,
        )
    ) {
        return ready(SyncRecoveryAction.CHECK_CONDITIONS)
    }
    if (init != null && (
            discovery == null || discovery in setOf(
                SyncDiscoveryProblem.INITIALIZATION_REQUIRES_ACTION,
                SyncDiscoveryProblem.INITIALIZATION_UNCONFIRMED,
                SyncDiscoveryProblem.CREATION_UNCONFIRMED,
            )
            )
    ) {
        return ready(
            when (init.reason) {
                SyncInitializationFailureReason.SPACE_IDENTITY_CHANGED,
                SyncInitializationFailureReason.REPOSITORY_IDENTITY_CHANGED,
                SyncInitializationFailureReason.NOT_EMPTY,
                SyncInitializationFailureReason.UNRECOGNIZED_DATA,
                SyncInitializationFailureReason.EXISTING_SPACE_REQUIRES_JOIN,
                -> SyncRecoveryAction.CHOOSE_SPACE
                SyncInitializationFailureReason.ATTEMPT_INVALID,
                SyncInitializationFailureReason.DEFAULT_BRANCH_CHANGED,
                SyncInitializationFailureReason.BOOTSTRAP_MISSING,
                SyncInitializationFailureReason.BOOTSTRAP_CHANGED,
                SyncInitializationFailureReason.BOOTSTRAP_UNCONFIRMED,
                SyncInitializationFailureReason.REQUEST_UNCONFIRMED,
                SyncInitializationFailureReason.UNKNOWN,
                -> SyncRecoveryAction.CONTINUE_SETUP
            },
        )
    }
    if (discovery != null) {
        return ready(
            when (discovery) {
                SyncDiscoveryProblem.AUTHORIZATION_REQUIRED -> if (recoveryAuthorization ==
                    SyncRecoveryAuthorization.CONFIRMED
                ) {
                    SyncRecoveryAction.CHECK_CONDITIONS
                } else {
                    SyncRecoveryAction.CONNECT_GITHUB
                }
                SyncDiscoveryProblem.ACCOUNT_CHANGED -> SyncRecoveryAction.CONNECT_GITHUB
                SyncDiscoveryProblem.NEEDS_INSTALLATION -> if (needsRepositoryPreparation) {
                    SyncRecoveryAction.OFFICIAL_CREATE
                } else {
                    SyncRecoveryAction.INSTALL_APP
                }
                SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                SyncDiscoveryProblem.NEEDS_INSTALLATION_ACCESS_PERMISSION,
                -> if (repositoryPreparedName != null && setupRepository == null && creationRepositoryId == null) {
                    SyncRecoveryAction.MANAGE_AUTHORIZATION
                } else {
                    SyncRecoveryAction.AUTHORIZE_REPOSITORY
                }
                SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION,
                SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION,
                SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE,
                -> SyncRecoveryAction.MANAGE_AUTHORIZATION
                SyncDiscoveryProblem.REPOSITORY_DISABLED -> SyncRecoveryAction.RESTORE_REPOSITORY
                SyncDiscoveryProblem.INSTALLATION_SUSPENDED -> SyncRecoveryAction.RESTORE_INSTALLATION
                SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE -> SyncRecoveryAction.CREATE_SPACE
                SyncDiscoveryProblem.REPOSITORY_IDENTITY_MISMATCH,
                SyncDiscoveryProblem.MULTIPLE_SPACES,
                -> SyncRecoveryAction.CHOOSE_SPACE
                SyncDiscoveryProblem.REPOSITORY_ARCHIVED,
                SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE,
                -> SyncRecoveryAction.REPAIR_REPOSITORY_PROPERTIES
                SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION -> SyncRecoveryAction.OFFICIAL_CREATE
                SyncDiscoveryProblem.NAME_OCCUPIED -> SyncRecoveryAction.EDIT_REPOSITORY_NAME
                SyncDiscoveryProblem.CREATION_UNCONFIRMED,
                SyncDiscoveryProblem.INITIALIZATION_REQUIRES_ACTION,
                SyncDiscoveryProblem.INITIALIZATION_UNCONFIRMED,
                -> SyncRecoveryAction.CONTINUE_SETUP
                SyncDiscoveryProblem.RETRYABLE, SyncDiscoveryProblem.MALFORMED -> SyncRecoveryAction.NETWORK
                SyncDiscoveryProblem.INCOMPATIBLE -> SyncRecoveryAction.UPDATE
                SyncDiscoveryProblem.STORAGE_ERROR -> SyncRecoveryAction.STORAGE
                SyncDiscoveryProblem.RATE_LIMITED -> SyncRecoveryAction.WAIT_EXTERNAL
            },
        )
    }
    if (!recoveryConditionsVerified && recoveryAuthorization != SyncRecoveryAuthorization.CONFIRMED &&
        (recovery?.reason == SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED || problem == SyncRunProblem.AUTHORIZATION)
    ) {
        return ready(SyncRecoveryAction.CONNECT_GITHUB)
    }
    if (recovery?.reason == SyncSpaceRecoveryReason.SWITCH_PENDING || pendingRecoveryPurpose != null) {
        return ready(SyncRecoveryAction.CONTINUE_SETUP)
    }
    val report = recoveryRepairReport
    val reasons = report?.let {
        it.batches.map { batch ->
            batch.reason
        } + it.events.map { event ->
            event.reason
        }
    }.orEmpty()
        .flatMap { it.split(Regex("[^A-Z_]+")) }.toSet()
    if (reasons.any {
            it in setOf(
                "UNKNOWN_PROTOCOL",
                "UNKNOWN_PAYLOAD_FIELD",
            )
        }
    ) {
        return ready(SyncRecoveryAction.UPDATE)
    }
    if (reasons.any {
            it in setOf(
                "INVALID_ACTOR", "INVALID_EPOCH", "INVALID_SEQUENCE", "INVALID_OBJECT_IDENTITY",
                "SAME_ID_DIFFERENT_CONTENT",
                "CAUSAL_CYCLE",
                "PARENT_FOREIGN_SPACE",
                "PARENT_FOREIGN_GENERATION",
                "CROSS_FIELD_PARENT",
            )
        }
    ) {
        return ready(SyncRecoveryAction.CREATE_SPACE)
    }
    if (recoveryExternalScopes.isNotEmpty()) return ready(SyncRecoveryAction.BACKUP)
    val field = report?.fields?.firstOrNull()
    if (field != null) {
        return ready(
            when (field.reason) {
                "SOURCE" -> SyncRecoveryAction.EXTENSIONS
                "DESCRIPTION" -> SyncRecoveryAction.READER
                "UNREADABLE" -> SyncRecoveryAction.STORAGE
                "IDENTITY" -> if (field.objectKey?.type ==
                    SyncObjectType.MANGA
                ) {
                    SyncRecoveryAction.MIGRATION
                } else {
                    SyncRecoveryAction.BACKUP
                }
                else -> SyncRecoveryAction.REPAIR_DATA
            },
        )
    }
    if ((report?.remaining ?: 0) > 0) {
        return ready(if (recoveryRepairMadeNoProgress) SyncRecoveryAction.BACKUP else SyncRecoveryAction.REPAIR_DATA)
    }
    if (recoveryConditionsVerified && connection?.enabled == true) return ready(SyncRecoveryAction.VERIFY_SYNC)
    if (problem == SyncRunProblem.NETWORK || recoveryFailure?.problem == SyncRunProblem.NETWORK) {
        return ready(SyncRecoveryAction.NETWORK)
    }
    if (recoveryAuthorization != SyncRecoveryAuthorization.CONFIRMED &&
        (recovery?.reason == SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED || problem == SyncRunProblem.AUTHORIZATION)
    ) {
        return ready(SyncRecoveryAction.CONNECT_GITHUB)
    }
    if (recovery?.reason == SyncSpaceRecoveryReason.SPACE_UNAVAILABLE) return ready(SyncRecoveryAction.CREATE_SPACE)
    if (connection == null) return ready(SyncRecoveryAction.CREATE_SPACE)
    if (!connection.enabled) return ready(SyncRecoveryAction.ENABLE_SYNC)
    if (problem == SyncRunProblem.UNKNOWN ||
        recoveryFailure?.problem == SyncRunProblem.UNKNOWN
    ) {
        return ready(SyncRecoveryAction.CHECK_CONDITIONS)
    }
    return ready(SyncRecoveryAction.VERIFY_SYNC)
}

internal fun SyncPanelState.recoveryAlternatives(): List<SyncRecoveryActionDecision> =
    buildList {
        addAll(
            listOf(
                SyncRecoveryAction.CHECK_CONDITIONS,
                SyncRecoveryAction.NETWORK,
                SyncRecoveryAction.BACKUP,
                SyncRecoveryAction.UPDATE,
                SyncRecoveryAction.STORAGE,
            ),
        )
        if (setupProblem != SyncDiscoveryProblem.INSTALLATION_SUSPENDED) {
            add(SyncRecoveryAction.CREATE_SPACE)
            add(SyncRecoveryAction.CHOOSE_SPACE)
        }
        if (connection != null || creationRepositoryId != null || setupInstallation != null) {
            add(SyncRecoveryAction.MANAGE_AUTHORIZATION)
        }
    }.filter { it != recoveryPrimaryAction.action }.map(::recoveryActionDecision)

private fun SyncPanelState.recoveryActionDecision(action: SyncRecoveryAction): SyncRecoveryActionDecision {
    if (action == SyncRecoveryAction.AUTHORIZE_REPOSITORY &&
        connection == null &&
        creationRepositoryId == null &&
        setupRepository == null
    ) {
        return SyncRecoveryActionDecision(
            action,
            SyncRecoveryActionAvailability.NeedsStep(
                SyncRecoveryAction.CREATE_SPACE,
                SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
            ),
        )
    }
    if (action == SyncRecoveryAction.BACKUP &&
        problem == SyncRunProblem.STORAGE &&
        recoveryBindingStatus == SyncBindingDecode.READ_FAILED
    ) {
        return SyncRecoveryActionDecision(
            action,
            SyncRecoveryActionAvailability.NeedsStep(
                SyncRecoveryAction.STORAGE,
                SyncDiscoveryProblem.STORAGE_ERROR,
            ),
        )
    }
    val availability = if (action in setOf(
            SyncRecoveryAction.CREATE_SPACE,
            SyncRecoveryAction.CHOOSE_SPACE,
            SyncRecoveryAction.ENABLE_SYNC,
            SyncRecoveryAction.VERIFY_SYNC,
            SyncRecoveryAction.REPAIR_DATA,
        )
    ) {
        when {
            recoveryBindingStatus == SyncBindingDecode.READ_FAILED -> SyncRecoveryActionAvailability.NeedsStep(
                SyncRecoveryAction.STORAGE,
                SyncDiscoveryProblem.STORAGE_ERROR,
            )
            recoveryBindingStatus == SyncBindingDecode.UNSUPPORTED -> SyncRecoveryActionAvailability.NeedsStep(
                SyncRecoveryAction.UPDATE,
                SyncDiscoveryProblem.INCOMPATIBLE,
            )
            !recoveryCredentialAvailable &&
                !canChangeSpace -> SyncRecoveryActionAvailability.NeedsStep(
                SyncRecoveryAction.CONNECT_GITHUB,
                SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
            )
            else -> SyncRecoveryActionAvailability.Ready
        }
    } else {
        SyncRecoveryActionAvailability.Ready
    }
    return SyncRecoveryActionDecision(action, availability)
}
