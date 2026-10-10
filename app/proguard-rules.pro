-dontobfuscate

-keep,allowoptimization class eu.kanade.**
-keep,allowoptimization class tachiyomi.**
-keep,allowoptimization class mihon.**

# AndroidJUnitRunner calls this public API from the separate instrumentation APK.
# AGP removes dependencies shared with the host from that APK, so release shrinking
# must retain this entry point even when the host itself no longer calls it.
# Keep the same rule in ordinary releases so tests exercise the shipped artifact.
-keep,allowoptimization class androidx.tracing.Trace { public static *; }

# Keep common dependencies used in extensions
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class kotlin.time.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
# Separately compiled extensions call this Zstd facade; host-only calls can be inlined away.
-keep,allowoptimization class com.squareup.zstd.okio.OkioZstd { public static *; }
# zstd-kmp 0.4.0 JNI looks up these base classes and fields by name, even for decompression.
# Its Android AAR supplies no consumer rules; prevent class merging and field relocation.
-keep class com.squareup.zstd.ZstdCompressor {
    int inputBytesProcessed;
    int outputBytesProcessed;
}
-keep class com.squareup.zstd.ZstdDecompressor {
    int inputBytesProcessed;
    int outputBytesProcessed;
}
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class app.cash.quickjs.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }

# From extensions-lib
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.NetworkHelper { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.RequestsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.AppInfo { public protected *; }

##---------------Begin: proguard configuration for RxJava 1.x  ----------
-dontwarn sun.misc.**

-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
   long producerIndex;
   long consumerIndex;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}

-dontnote rx.internal.util.PlatformDependent
##---------------End: proguard configuration for RxJava 1.x  ----------

##---------------Begin: proguard configuration for okhttp  ----------
-keepclasseswithmembers class okhttp3.MultipartBody$Builder { *; }
##---------------End: proguard configuration for okhttp  ----------

##---------------Begin: proguard configuration for kotlinx.serialization  ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.** # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class eu.kanade.**$$serializer { *; }
-keepclassmembers class eu.kanade.** {
    *** Companion;
}
-keepclasseswithmembers class eu.kanade.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** {
    <methods>;
}
##---------------End: proguard configuration for kotlinx.serialization  ----------

# XmlUtil
-keep public enum nl.adaptivity.xmlutil.EventType { *; }

# Firebase
-keep class com.google.firebase.installations.** { *; }
-keep interface com.google.firebase.installations.** { *; }

# Separately compiled release acceptance calls these existing production APIs.
# Preserve only the referenced member signatures in every release; their bodies
# remain optimizable and all other members retain ordinary R8 shrinking.
-keepclassmembers,allowoptimization class eu.kanade.domain.base.BasePreferences {
    eu.kanade.domain.base.ExtensionInstallerPreference extensionInstaller();
}

-keepclassmembers,allowoptimization class eu.kanade.domain.base.BasePreferences$ExtensionInstaller {
    eu.kanade.domain.base.BasePreferences$ExtensionInstaller valueOf(java.lang.String);
}

-keepclassmembers,allowoptimization class eu.kanade.domain.base.ExtensionInstallerPreference {
    void delete();
    eu.kanade.domain.base.BasePreferences$ExtensionInstaller get();
    boolean isSet();
    void set(eu.kanade.domain.base.BasePreferences$ExtensionInstaller);
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch {
    kotlinx.coroutines.flow.StateFlow getState();
    kotlinx.coroutines.flow.StateFlow getSuggestions();
    boolean resume(java.util.List);
    boolean start(java.util.List);
    void stop();
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.ExtensionManager {
    java.lang.Object findAvailableExtensions(kotlin.coroutines.Continuation);
    mihon.domain.extension.service.ExtensionInstallArbiter getInstallArbiter();
    kotlinx.coroutines.flow.StateFlow getInstallErrors();
    kotlinx.coroutines.flow.StateFlow getInstalledExtensionsFlow();
    kotlinx.coroutines.flow.StateFlow getInventory();
    kotlinx.coroutines.flow.StateFlow getUntrustedExtensionsFlow();
    kotlinx.coroutines.flow.Flow installExtension(eu.kanade.tachiyomi.extension.model.Extension$Available);
    kotlinx.coroutines.flow.StateFlow isInitialized();
    void uninstallExtension(eu.kanade.tachiyomi.extension.model.Extension);
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.model.Extension {
    java.lang.String getPkgName();
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.model.Extension$Installed {
    java.lang.String getPkgName();
    java.util.List getSources();
    boolean isShared();
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.model.ExtensionArtifactMapperKt {
    eu.kanade.tachiyomi.extension.model.Extension$Available toAvailable(mihon.domain.extension.model.ExtensionArtifact);
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.extension.util.ExtensionLoader {
    android.content.pm.PackageInfo getExtensionPackageInfoFromPkgName(android.content.Context,java.lang.String);
}

-keepclassmembers,allowoptimization interface eu.kanade.tachiyomi.source.Source {
    long getId();
    java.lang.String getLang();
    java.lang.String getName();
}

-keepclassmembers,allowoptimization interface eu.kanade.tachiyomi.source.SourceFactory {
    java.util.List createSources();
}

-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader {
    <init>(java.lang.String,java.lang.String,java.lang.ClassLoader);
}

-keepclassmembers,allowoptimization class mihon.domain.extension.model.ExtensionArtifact {
    java.lang.String getName();
    java.lang.String getPackageName();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.service.ExtensionInstallArbiter {
    boolean isBusy(java.lang.String);
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.ExtensionInventory {
    boolean getInitialized();
    java.util.Map getRecords();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.ExtensionSuggestion {
    mihon.domain.extension.model.ExtensionArtifact getArtifact();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.ExtensionSuggestionPanelKt {
    java.lang.String suggestionIdentityKey(mihon.domain.extension.suggestion.SuggestionIdentity);
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.ExtensionSuggestionPreferences {
    tachiyomi.core.common.preference.Preference getExpanded();
    tachiyomi.core.common.preference.Preference getIgnored();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.ExtensionSuggestions {
    java.util.List getSuggestions();
    boolean isLoading();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.SuggestionBatchItem {
    mihon.domain.extension.suggestion.SuggestionBatchResult getResult();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.SuggestionBatchState {
    long getId();
    java.util.List getItems();
    mihon.domain.extension.suggestion.SuggestionBatchPause getPauseReason();
    java.util.List getRemaining();
    boolean getRunning();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.SuggestionIdentity$Companion {
    mihon.domain.extension.suggestion.SuggestionIdentity of(mihon.domain.extension.model.ExtensionArtifact);
}

-keepclassmembers,allowoptimization class mihon.domain.extensionrepo.model.ExtensionRepo {
    <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}

-keepclassmembers,allowoptimization interface mihon.domain.extensionrepo.repository.ExtensionRepoRepository {
    java.lang.Object deleteRepo(java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object getRepo(java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object insertRepo(mihon.domain.extensionrepo.model.ExtensionRepo,kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class tachiyomi.core.common.i18n.LocalizeKt {
    java.lang.String stringResource(android.content.Context,dev.icerock.moko.resources.StringResource);
    java.lang.String stringResource(android.content.Context,dev.icerock.moko.resources.StringResource,java.lang.Object[]);
}

-keepclassmembers,allowoptimization interface tachiyomi.core.common.preference.Preference {
    void delete();
    java.lang.Object get();
    boolean isSet();
    void set(java.lang.Object);
}

-keepclassmembers,allowoptimization class tachiyomi.data.DatabaseHandler {
    java.lang.Object await(boolean,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class tachiyomi.domain.manga.model.Manga {
    tachiyomi.domain.manga.model.Manga copy$default(tachiyomi.domain.manga.model.Manga,long,long,boolean,long,long,int,long,long,long,long,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.util.List,long,java.lang.String,eu.kanade.tachiyomi.source.model.UpdateStrategy,boolean,long,java.lang.Long,long,java.lang.String,kotlinx.serialization.json.JsonObject,int,java.lang.Object);
    long getId();
    long getSource();
    java.lang.String getUrl();
}

-keepclassmembers,allowoptimization class tachiyomi.domain.manga.model.Manga$Companion {
    tachiyomi.domain.manga.model.Manga create();
}

-keepclassmembers,allowoptimization interface tachiyomi.domain.manga.repository.MangaRepository {
    java.lang.Object getMangaByUrlAndSourceId(java.lang.String,long,kotlin.coroutines.Continuation);
    java.lang.Object insertNetworkManga(java.util.List,kotlin.coroutines.Continuation);
}

-keepclassmembers,allowoptimization class tachiyomi.i18n.MR$strings {
    tachiyomi.i18n.MR$strings INSTANCE;
    dev.icerock.moko.resources.StringResource getBrowse();
    dev.icerock.moko.resources.StringResource getExt_install();
    dev.icerock.moko.resources.StringResource getExtension_batch_confirm();
    dev.icerock.moko.resources.StringResource getExtension_batch_install_count();
    dev.icerock.moko.resources.StringResource getExtension_batch_retry();
    dev.icerock.moko.resources.StringResource getExtension_suggestions_expand();
    dev.icerock.moko.resources.StringResource getExtension_suggestions_ignore();
    dev.icerock.moko.resources.StringResource getExtension_suggestions_open_website();
    dev.icerock.moko.resources.StringResource getExtension_suggestions_title();
    dev.icerock.moko.resources.StringResource getExtension_suggestions_website();
    dev.icerock.moko.resources.StringResource getLabel_extensions();
}

-keepclassmembers,allowoptimization class mihon.domain.extension.suggestion.SuggestionIdentity {
    mihon.domain.extension.suggestion.SuggestionIdentity$Companion Companion;
}

# The same external APK observes foreground lifecycle and removes only its owned SQL rows.
-keep,allowoptimization class androidx.lifecycle.ProcessLifecycleOwner {
    androidx.lifecycle.ProcessLifecycleOwner$Companion Companion;
}
-keep,allowoptimization class androidx.lifecycle.ProcessLifecycleOwner$Companion {
    androidx.lifecycle.LifecycleOwner get();
}
-keep,allowoptimization interface androidx.lifecycle.LifecycleOwner {
    androidx.lifecycle.Lifecycle getLifecycle();
}
-keep,allowoptimization class androidx.lifecycle.Lifecycle {
    androidx.lifecycle.Lifecycle$State getCurrentState();
}
-keep,allowoptimization class androidx.lifecycle.Lifecycle$State {
    androidx.lifecycle.Lifecycle$State STARTED;
    boolean isAtLeast(androidx.lifecycle.Lifecycle$State);
}
-keep,allowoptimization interface app.cash.sqldelight.db.SqlDriver {
    app.cash.sqldelight.db.QueryResult execute(java.lang.Integer,java.lang.String,int,kotlin.jvm.functions.Function1);
}
-keep,allowoptimization interface app.cash.sqldelight.db.SqlPreparedStatement {
    void bindLong(int,java.lang.Long);
    void bindString(int,java.lang.String);
}

# Exact external formal history fixture ABI. R8/resource shrinking stay enabled.
-keepclassmembers,allowoptimization class mihon.data.sync.journal.SyncLocalJournal {
    public <init>(tachiyomi.data.DatabaseHandler);
    public java.lang.Object connect(java.lang.String,long,mihon.domain.sync.transport.SyncRepository,java.lang.String,long,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowoptimization class mihon.domain.sync.transport.SyncRepository {
    public <init>(java.lang.String,java.lang.String,java.lang.String);
}
-keepclassmembers,allowoptimization class mihon.domain.sync.SyncBatchCodec {
    public static mihon.domain.sync.SyncBatchCodec INSTANCE;
    public mihon.domain.sync.SyncBatchDecodeResult decode(java.lang.String);
}
-keepclassmembers,allowoptimization class mihon.domain.sync.SyncBatchDecodeResult$Accepted {
    public mihon.domain.sync.SyncBatch getBatch();
}
-keepclassmembers,allowoptimization class mihon.data.sync.inbox.SyncInboxStore {
    public <init>(tachiyomi.data.DatabaseHandler);
    public java.lang.Object ingest(mihon.domain.sync.SyncBatch,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowoptimization class mihon.data.sync.inbox.SyncReceptionResult {
    public boolean getAccepted();
}
-keepclassmembers,allowoptimization class mihon.data.sync.runtime.SyncRuntime {
    public mihon.data.sync.inbox.SyncInboxProjector getProjector();
}
-keepclassmembers,allowoptimization class mihon.data.sync.inbox.SyncInboxProjector {
    public java.lang.Object project(java.lang.String,long,int,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.ReaderActivity {
    public eu.kanade.tachiyomi.ui.reader.ReaderViewModel getViewModel();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.ReaderViewModel {
    public kotlinx.coroutines.flow.StateFlow getState();
    private eu.kanade.tachiyomi.ui.reader.ReaderViewModel$ReadingActivation readingActivation;
    private tachiyomi.domain.reader.model.ReaderOpenContext initialOpenContext;
    private kotlinx.coroutines.Job catalogJob;
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.ReaderViewModel$State {
    public int getCurrentPage();
    public eu.kanade.tachiyomi.ui.reader.model.ReaderChapter getCurrentChapter();
    public eu.kanade.tachiyomi.ui.reader.model.ViewerChapters getViewerChapters();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.ReaderViewModel$ReadingActivation {
    private tachiyomi.domain.reader.interactor.ReadingProgressSession session;
}
-keepclassmembers,allowoptimization class tachiyomi.domain.reader.interactor.ReadingProgressSession {
    private tachiyomi.domain.reader.model.ReadingSyncSnapshot snapshot;
}
-keepclassmembers,allowoptimization class tachiyomi.domain.reader.model.ReaderOpenContext {
    private int pageIndex;
    private tachiyomi.domain.reader.model.ReadingSyncSnapshot snapshot;
}
-keepclassmembers,allowoptimization class tachiyomi.domain.reader.model.ReadingSyncSnapshot {
    private java.util.Map heads;
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.model.ReaderChapter {
    public eu.kanade.tachiyomi.data.database.models.Chapter getChapter();
    public java.util.List getPages();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.model.ViewerChapters {
    public eu.kanade.tachiyomi.ui.reader.model.ReaderChapter getPrevChapter();
    public eu.kanade.tachiyomi.ui.reader.model.ReaderChapter getNextChapter();
}
-keepclassmembers,allowoptimization interface eu.kanade.tachiyomi.data.database.models.Chapter {
    public java.lang.Long getId();
}
-keepclassmembers,allowoptimization interface eu.kanade.tachiyomi.source.model.SChapter {
    public java.lang.String getUrl();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.AndroidReaderProgressCoordinator {
    public java.lang.Object awaitAccepted(long,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowoptimization class com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView {
    public boolean isReady();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences {
    public tachiyomi.core.common.preference.Preference defaultReadingMode();
}
-keepclassmembers,allowoptimization class eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder {
    public eu.kanade.tachiyomi.ui.reader.model.ReaderPage getPage();
}
-keepclassmembers,allowoptimization class mihon.domain.sync.SyncEffectRef {
    private mihon.domain.sync.SyncEventId eventId;
}
-keepclassmembers,allowoptimization class mihon.domain.sync.SyncEventId {
    private java.lang.String actorId;
}
