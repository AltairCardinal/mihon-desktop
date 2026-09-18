package tachiyomi.data.creator

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.CreatorWorkArchiveFilter
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.CreatorIdentityEditor

/** Each platform must obtain the editor from its actual author ScreenModel. */
suspend fun verifyCreatorIdentityEditor(
    handler: DatabaseHandler,
    bind: (CreatorRepositoryImpl, ManageCreatorIdentity, Long) -> CreatorIdentityEditor,
) {
    var failNext = false
    val repository = CreatorRepositoryImpl(handler, identityMutationHook = {
        if (failNext) {
            failNext = false
            error("identity write failed")
        }
    })
    val target = repository.upsertCreator("Main")
    val source = repository.upsertCreator("Alias")
    val editor = bind(repository, ManageCreatorIdentity(repository), target.id)
    try {
        editor.openAliases().join()
        editor.select(source.id)
        failNext = true
        editor.submit().join()
        check(editor.state.value.open && editor.state.value.selected == setOf(source.id))
        check(editor.state.value.error == "identity write failed")
        editor.submit().join()
        check(!editor.state.value.open && editor.state.value.focusTarget == "add")
        editor.chooseDisplayName("Alias")
        check(editor.state.value.pendingName == "Alias")
        editor.cancelDisplayName()
        check(editor.state.value.focusTarget == "alias:Alias")
        editor.chooseDisplayName("Alias")
        editor.saveDisplayName().join()
        check(editor.state.value.identity?.displayName == "Alias")
        check(editor.state.value.focusTarget == "title")
        val root = repository.upsertCreator("Current")
        repository.mergeCreatorIdentities(target.id, root.id)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(5000) { editor.state.first { it.identity?.id == root.id } }
        }
        check(editor.state.value.identity?.names?.toSet() == setOf("Main", "Alias", "Current"))
        repository.followCreator(root.id, null, null)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(5000) { editor.state.first { it.identity?.followed == true } }
        }
    } finally {
        editor.close()
    }
}

/** Platform state projections must apply the shared filters without regrouping equal titles. */
fun verifyCreatorWorkFilterProjection(
    project: (
        CreatorWorkArchive,
        CreatorWorkArchiveFilter,
    ) -> CreatorWorkArchive,
) {
    val version = SourceWorkArchiveVersion(
        1, SourceWorkNaturalKey(42, "/one"), null, "Book",
        LanguageProjectionContract(
            LanguageDimension.READING,
            "und",
            LanguageCertainty.UNKNOWN,
            LanguageEvidenceKind.UNKNOWN,
        ),
        0, false, null, 0, null,
    )
    val archive = CreatorWorkArchive(
        listOf(
            CanonicalWorkArchiveGroup(1, "one", "Book", listOf(version)),
            CanonicalWorkArchiveGroup(
                2,
                "two",
                "Book",
                listOf(version.copy(sourceWorkId = 2, naturalKey = SourceWorkNaturalKey(43, "/two"))),
            ),
        ),
        emptyList(),
        emptyList(),
    )
    check(project(archive, CreatorWorkArchiveFilter(query = "missing")).works.isEmpty())
    check(project(archive, CreatorWorkArchiveFilter(query = "book")).works.map { it.workId } == listOf(1L, 2L))
    check(project(archive, CreatorWorkArchiveFilter(sourceId = 43)).works.map { it.workId } == listOf(2L))
    check(archive.works.size == 2)
}
