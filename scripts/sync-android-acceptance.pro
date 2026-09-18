# AndroidJUnitRunner calls this API from the separate test APK after app/test dependency deduplication.
# This file is loaded only by sync-android-acceptance.init.gradle, never by a normal release build.
-keep,allowoptimization class androidx.tracing.Trace { public static *; }

# Preserve only cross-APK members whose descriptors, invocation kind or fields differ in the release DEX.
# These explicit entry points keep their signatures; R8 still optimizes their production method bodies.
# Keep this list aligned with the isolated instrumentation's actual cross-APK calls.
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.data.sync.AndroidSyncSecureStore {
    public java.lang.Object compareAndSet(
        java.lang.String, java.lang.String, java.lang.String, kotlin.coroutines.Continuation
    );
    public java.lang.Object read(java.lang.String, kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class mihon.data.sync.auth.PersistentGitHubCredentialStore {
    public java.lang.Object read(kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class mihon.data.sync.crypto.SyncAeadEngineFactory {
    public static mihon.data.sync.crypto.SyncAeadEngineFactory INSTANCE;
    public mihon.domain.sync.crypto.SyncAeadEngine create();
}

-keepclassmembers,allowoptimization class mihon.data.sync.journal.SyncBaselineStore {
    public java.lang.Object connectAndImport(
        java.lang.String, long, mihon.domain.sync.transport.SyncRepository, java.lang.String, long,
        kotlin.coroutines.Continuation
    );
    public static java.lang.Object process$default(
        mihon.data.sync.journal.SyncBaselineStore, java.lang.String, int, kotlin.coroutines.Continuation, int,
        java.lang.Object
    );
}

-keepclassmembers,allowoptimization class mihon.data.sync.journal.SyncImportProgress {
    public long getRemaining();
}

-keepclassmembers,allowoptimization class mihon.data.sync.journal.SyncLocalJournal {
    public <init>(tachiyomi.data.DatabaseHandler);
    public static java.lang.Object pendingEvents$default(
        mihon.data.sync.journal.SyncLocalJournal, java.lang.String, long, int, long, kotlin.coroutines.Continuation,
        int, java.lang.Object
    );
}

-keepclassmembers,allowoptimization class mihon.data.sync.runtime.SyncConnection {
    public java.lang.String getSpaceId();
    public boolean getEnabled();
    public mihon.domain.sync.transport.SyncRepository getRepository();
    public java.lang.String getProtectionMode();
    public java.lang.String getAccountLogin();
    public boolean getUnsupportedFormat();
}

-keepclassmembers,allowoptimization class mihon.data.sync.runtime.SyncRunRecord {
    public mihon.domain.sync.runtime.SyncRunResult getResult();
}

# These getters are invoked by the separate prepare/verify APK, even when app-only R8 callers inline them.
-keepclassmembers,allowoptimization class mihon.data.sync.runtime.SyncRuntime {
    public mihon.domain.sync.runtime.SyncPreferences getPreferences();
    public java.util.List records();
    public java.lang.Object connection(kotlin.coroutines.Continuation);
    public java.lang.Object disconnect(kotlin.coroutines.Continuation);
    public mihon.data.sync.journal.SyncBaselineStore getBaseline();
    public mihon.domain.sync.runtime.SyncCoordinator getCoordinator();
    public mihon.data.sync.auth.PersistentGitHubCredentialStore getCredentials();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncBatchEncryption {
    public static mihon.domain.sync.crypto.SyncBatchEncryption INSTANCE;
    public mihon.domain.sync.SyncBatch decrypt(
        mihon.domain.sync.crypto.SyncAeadEngine, mihon.domain.sync.crypto.SyncSecret,
        mihon.domain.sync.crypto.SyncEncryptedBatch, mihon.domain.sync.crypto.SyncSpaceMaterial
    );
    public mihon.domain.sync.crypto.SyncEncryptedBatch encrypt(
        mihon.domain.sync.crypto.SyncAeadEngine, mihon.domain.sync.crypto.SyncSecret, mihon.domain.sync.SyncBatch,
        java.lang.String, mihon.domain.sync.crypto.SyncSpaceMaterial
    );
}

-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncEncryptedBatch {
    public static mihon.domain.sync.crypto.SyncEncryptedBatch copy$default(
        mihon.domain.sync.crypto.SyncEncryptedBatch, int, java.lang.String, long, java.lang.String,
        java.lang.String, byte[], mihon.domain.sync.crypto.SyncPayload, java.lang.String, long, long, long,
        int, java.lang.Object
    );
}

# Optional-password format probes exercise optimized production bodies in the release APK.
-keepclassmembers,allowoptimization class mihon.data.sync.crypto.SyncSpaceCrypto {
    public static mihon.data.sync.crypto.SyncSpaceCrypto INSTANCE;
    public mihon.domain.sync.crypto.SyncSpaceMaterial create(java.lang.String, long, java.lang.String);
    public java.lang.Object unlock-gIAlu-s(mihon.domain.sync.crypto.SyncSpaceDescriptor, java.lang.String);
}
-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncSpaceDescriptorCodec {
    public static mihon.domain.sync.crypto.SyncSpaceDescriptorCodec INSTANCE;
    public byte[] encode(mihon.domain.sync.crypto.SyncSpaceDescriptor);
    public java.lang.Object decode-IoAF18A(byte[]);
}
-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncSpaceMaterial {
    public mihon.domain.sync.crypto.SyncSpaceDescriptor getDescriptor();
    public mihon.domain.sync.crypto.SyncSecret getSecret();
}
-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncSpaceDescriptor {
    public java.lang.String getMode();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncAeadCiphertext {
    public byte[] getBytes();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncSecret {
    public static mihon.domain.sync.crypto.SyncSecret$Companion Companion;
    public byte[] getBytes();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.crypto.SyncSecret$Companion {
    public mihon.domain.sync.crypto.SyncSecret fromBytes(byte[]);
}

-keepclassmembers,allowoptimization class mihon.domain.sync.runtime.SyncCoordinator {
    public java.lang.Object cancelAndJoin(kotlin.coroutines.Continuation);
    public java.lang.Object synchronize(mihon.domain.sync.runtime.SyncTrigger, kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class mihon.domain.sync.runtime.SyncPreferences {
    public tachiyomi.core.common.preference.Preference getStartup();
    public void setInterval(int);
    public int intervalMinutes();
}

-keepclassmembers,allowoptimization interface tachiyomi.core.common.preference.Preference {
    public java.lang.Object get();
    public void set(java.lang.Object);
}

-keepclassmembers,allowoptimization class mihon.domain.sync.runtime.SyncRunResult {
    public mihon.domain.sync.runtime.SyncRunProblem getProblem();
    public mihon.domain.sync.runtime.SyncRunStatus getStatus();
}

-keepclassmembers,allowoptimization interface mihon.domain.sync.security.SyncSecureStore {
    public java.lang.Object compareAndSet(
        java.lang.String, java.lang.String, java.lang.String, kotlin.coroutines.Continuation
    );
    public java.lang.Object read(java.lang.String, kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncEffect {
    public <init>(
        java.lang.String, mihon.domain.sync.SyncObjectKey, mihon.domain.sync.SyncField,
        mihon.domain.sync.SyncEffectKind, java.util.List, kotlinx.serialization.json.JsonObject, int,
        kotlin.jvm.internal.DefaultConstructorMarker
    );
    public mihon.domain.sync.SyncEffectKind getKind();
    public mihon.domain.sync.SyncObjectKey getObjectKey();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncEventEnvelope {
    public <init>(
        int, java.lang.String, long, java.lang.String, long, long, mihon.domain.sync.SyncCategory, java.util.List,
        mihon.domain.sync.SyncOrigin, java.lang.String, long, java.lang.String, java.lang.String, int,
        kotlin.jvm.internal.DefaultConstructorMarker
    );
    public java.lang.String getActorId();
    public mihon.domain.sync.SyncCategory getCategory();
    public java.util.List getEffects();
    public mihon.domain.sync.SyncOrigin getOrigin();
    public long getSeq();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncMutationContext {
    public static mihon.domain.sync.SyncMutationContext$Companion Companion;
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncMutationContext$Companion {
    public mihon.domain.sync.SyncMutationContext getUser();
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncObjectDescriptor {
    public <init>(
        mihon.domain.sync.SyncObjectKey, java.lang.String, java.lang.String, java.lang.String, java.lang.String,
        java.lang.Double, java.lang.Long, java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker
    );
}

-keepclassmembers,allowoptimization class mihon.domain.sync.SyncObjectKey {
    public <init>(
        mihon.domain.sync.SyncObjectType, java.lang.String, java.lang.String, java.lang.String, java.lang.String,
        int, kotlin.jvm.internal.DefaultConstructorMarker
    );
    public java.lang.String getOriginalUrl();
    public java.lang.String getSourceId();
}

-keepclassmembers,allowoptimization interface tachiyomi.data.Database {
    public tachiyomi.data.Sync_journalQueries getSync_journalQueries();
}

-keepclassmembers,allowoptimization interface tachiyomi.data.DatabaseHandler {
    public static java.lang.Object await$default(
        tachiyomi.data.DatabaseHandler, boolean, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation,
        int, java.lang.Object
    );
}

-keepclassmembers,allowoptimization class tachiyomi.data.Sync_actors {
    public long getNext_seq();
}

-keepclassmembers,allowoptimization class tachiyomi.data.Sync_journalQueries {
    public app.cash.sqldelight.Query getActiveActor();
}

-keepclassmembers,allowoptimization class tachiyomi.domain.chapter.model.Chapter {
    public static tachiyomi.domain.chapter.model.Chapter$Companion Companion;
    public static tachiyomi.domain.chapter.model.Chapter copy$default(
        tachiyomi.domain.chapter.model.Chapter, long, long, boolean, boolean, long, long, long, java.lang.String,
        java.lang.String, long, double, java.lang.String, long, long, kotlinx.serialization.json.JsonObject, int,
        java.lang.Object
    );
    public long getId();
    public boolean getRead();
}

-keepclassmembers,allowoptimization class tachiyomi.domain.chapter.model.Chapter$Companion {
    public tachiyomi.domain.chapter.model.Chapter create();
}

-keepclassmembers,allowoptimization class tachiyomi.domain.chapter.model.ChapterUpdate {
    public <init>(
        long, java.lang.Long, java.lang.Boolean, java.lang.Boolean, java.lang.Long, java.lang.Long, java.lang.Long,
        java.lang.String, java.lang.String, java.lang.Long, java.lang.Double, java.lang.String, java.lang.Long,
        kotlinx.serialization.json.JsonObject, mihon.domain.sync.SyncMutationContext, int,
        kotlin.jvm.internal.DefaultConstructorMarker
    );
}

-keepclassmembers,allowoptimization interface tachiyomi.domain.chapter.repository.ChapterRepository {
    public java.lang.Object getChapterById(long, kotlin.coroutines.Continuation);
    public java.lang.Object addAll(java.util.List, kotlin.coroutines.Continuation);
    public java.lang.Object update(tachiyomi.domain.chapter.model.ChapterUpdate, kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class tachiyomi.domain.manga.interactor.UpdateLibraryMembership {
    public static java.lang.Object await$default(
        tachiyomi.domain.manga.interactor.UpdateLibraryMembership, tachiyomi.domain.manga.model.Manga, boolean,
        java.util.List, long, kotlin.coroutines.Continuation, int, java.lang.Object
    );
}

-keepclassmembers,allowoptimization class tachiyomi.domain.manga.model.Manga {
    public static tachiyomi.domain.manga.model.Manga$Companion Companion;
    public static tachiyomi.domain.manga.model.Manga copy$default(
        tachiyomi.domain.manga.model.Manga, long, long, boolean, long, long, int, long, long, long, long,
        java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.util.List,
        long, java.lang.String, eu.kanade.tachiyomi.source.model.UpdateStrategy, boolean, long, java.lang.Long,
        long, java.lang.String, kotlinx.serialization.json.JsonObject, int, java.lang.Object
    );
    public boolean getFavorite();
    public long getId();
}

-keepclassmembers,allowoptimization class tachiyomi.domain.manga.model.Manga$Companion {
    public tachiyomi.domain.manga.model.Manga create();
}

-keepclassmembers,allowoptimization interface tachiyomi.domain.manga.repository.MangaRepository {
    public java.lang.Object getMangaById(long, kotlin.coroutines.Continuation);
    public java.lang.Object insertNetworkManga(java.util.List, kotlin.coroutines.Continuation);
}
