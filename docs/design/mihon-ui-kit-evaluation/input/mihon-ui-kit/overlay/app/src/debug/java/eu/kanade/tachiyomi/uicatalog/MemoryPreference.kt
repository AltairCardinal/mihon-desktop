package eu.kanade.tachiyomi.uicatalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.preference.Preference

/** Only for the catalog. Never backed by the application's real preference store. */
class MemoryPreference<T>(private val name: String, private val initial: T) : Preference<T> {
    private val state = MutableStateFlow(initial)
    private var written = false
    override fun key(): String = "ui_catalog:$name"
    override fun get(): T = state.value
    override fun set(value: T) { written = true; state.value = value }
    override fun isSet(): Boolean = written
    override fun delete() { written = false; state.value = initial }
    override fun defaultValue(): T = initial
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
