package eu.kanade.tachiyomi.extension.util

import mihon.domain.extension.model.ExtensionArtifact

/** One pending installation's verified repository; the id is never persisted or reused. */
data class ExtensionOriginConfirmation(val id: String, val artifact: ExtensionArtifact)
