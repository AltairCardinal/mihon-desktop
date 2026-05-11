package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository

class FakeExtensionRepoRepository : ExtensionRepoRepository {

    private val repos = mutableListOf<ExtensionRepo>()
    private val flow = MutableStateFlow<List<ExtensionRepo>>(emptyList())

    private fun emit() { flow.value = repos.toList() }

    override fun subscribeAll(): Flow<List<ExtensionRepo>> = flow

    override suspend fun getAll(): List<ExtensionRepo> = repos.toList()

    override suspend fun getRepo(baseUrl: String): ExtensionRepo? =
        repos.find { it.baseUrl == baseUrl }

    override suspend fun getRepoBySigningKeyFingerprint(fingerprint: String): ExtensionRepo? =
        repos.find { it.signingKeyFingerprint == fingerprint }

    override fun getCount(): Flow<Int> = flow.map { it.size }

    override suspend fun insertRepo(
        baseUrl: String,
        name: String,
        shortName: String?,
        website: String,
        signingKeyFingerprint: String,
    ) {
        if (repos.any { it.baseUrl == baseUrl }) throw SaveExtensionRepoException(Exception("duplicate baseUrl"))
        if (repos.any { it.signingKeyFingerprint == signingKeyFingerprint }) throw SaveExtensionRepoException(Exception("duplicate fingerprint"))
        repos += ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
        emit()
    }

    override suspend fun upsertRepo(
        baseUrl: String,
        name: String,
        shortName: String?,
        website: String,
        signingKeyFingerprint: String,
    ) {
        repos.removeAll { it.baseUrl == baseUrl }
        repos += ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
        emit()
    }

    override suspend fun replaceRepo(newRepo: ExtensionRepo) {
        repos.removeAll { it.signingKeyFingerprint == newRepo.signingKeyFingerprint }
        repos += newRepo
        emit()
    }

    override suspend fun deleteRepo(baseUrl: String) {
        repos.removeAll { it.baseUrl == baseUrl }
        emit()
    }
}
