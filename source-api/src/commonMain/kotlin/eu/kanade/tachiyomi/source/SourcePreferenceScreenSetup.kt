package eu.kanade.tachiyomi.source

/**
 * Host-facing outcome for collecting a source's preference schema.
 *
 * Platform consumers may supply a setup bridge, but schema creation always crosses the
 * [ConfigurableSource.setupPreferenceScreen] boundary represented by this result.
 */
sealed interface SourcePreferenceScreenSetup {
    data object Missing : SourcePreferenceScreenSetup
    data object NonConfigurable : SourcePreferenceScreenSetup
    data object Success : SourcePreferenceScreenSetup
    data class Failure(val error: Throwable) : SourcePreferenceScreenSetup
}

/**
 * Collects a configurable source schema while preserving missing, unsupported, and setup-failure boundaries.
 */
fun setupSourcePreferenceScreen(
    source: Source?,
    screen: PreferenceScreen,
    setup: (ConfigurableSource, PreferenceScreen) -> Unit = ConfigurableSource::setupPreferenceScreen,
): SourcePreferenceScreenSetup = when (source) {
    null -> SourcePreferenceScreenSetup.Missing
    !is ConfigurableSource -> SourcePreferenceScreenSetup.NonConfigurable
    else -> try {
        setup(source, screen)
        SourcePreferenceScreenSetup.Success
    } catch (error: Exception) {
        SourcePreferenceScreenSetup.Failure(error)
    } catch (error: LinkageError) {
        SourcePreferenceScreenSetup.Failure(error)
    }
}
