package mihon.data.sync.auth

/** Immutable process configuration; ordinary discovery retains renamed-space recovery. */
class SyncRepositoryScope private constructor(val repositoryName: String, val isolated: Boolean) {
    fun accepts(name: String): Boolean = !isolated || name == repositoryName

    companion object {
        val Default = SyncRepositoryScope(GitHubSyncSpaceClient.REPOSITORY_NAME, false)

        fun acceptance(name: String): SyncRepositoryScope {
            require(name.matches(Regex("mihon-sync-acceptance-[a-z0-9][a-z0-9-]{0,76}"))) {
                "Acceptance requires a dedicated mihon-sync-acceptance repository name"
            }
            return SyncRepositoryScope(name, true)
        }
    }
}
