package mihon.domain.sync

/** Explicit command provenance; ordinary metadata writes never become user operations by default. */
data class SyncMutationContext(
    val origin: SyncOrigin = SyncOrigin.METADATA_REFRESH,
    val uploadAllowed: Boolean = true,
    val importId: String? = null,
    val observedHeads: Map<SyncFieldKey, List<SyncEffectRef>>? = null,
) {
    companion object {
        val User = SyncMutationContext(SyncOrigin.USER)
        val Metadata = SyncMutationContext()
        val LocalOnly = SyncMutationContext(SyncOrigin.USER, uploadAllowed = false)
    }
}
