package ec.newabi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

object BlockingProbe {
    @JvmStatic fun defaultValue(): String = runBlocking { "default-value" }
    @JvmStatic fun explicitContext(): String = runBlocking(CoroutineName("external")) {
        coroutineContext[CoroutineName]!!.name
    }
    @JvmStatic fun failure(): String = runBlocking { error("external-failure") }
    @JvmStatic fun cancelled(job: Job): String = runBlocking(job) { "must-not-run" }
}
