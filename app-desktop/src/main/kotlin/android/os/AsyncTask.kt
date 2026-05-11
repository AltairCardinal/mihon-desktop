package android.os

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Desktop stub for android.os.AsyncTask.
 * Runs doInBackground on a thread pool; onPostExecute runs on the same thread for simplicity.
 */
@Suppress("DEPRECATION")
abstract class AsyncTask<Params, Progress, Result> {

    enum class Status { PENDING, RUNNING, FINISHED }

    var status: Status = Status.PENDING
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null

    protected abstract fun doInBackground(vararg params: Params): Result

    protected open fun onPreExecute() {}

    protected open fun onPostExecute(result: Result) {}

    protected open fun onProgressUpdate(vararg values: Progress) {}

    protected open fun onCancelled(result: Result?) {}

    protected open fun onCancelled() {}

    @Suppress("UNCHECKED_CAST")
    fun execute(vararg params: Params): AsyncTask<Params, Progress, Result> {
        status = Status.RUNNING
        onPreExecute()
        future = executor.submit {
            try {
                val result = doInBackground(*params)
                status = Status.FINISHED
                onPostExecute(result)
            } catch (_: Exception) {
                status = Status.FINISHED
            }
        }
        return this
    }

    fun cancel(mayInterruptIfRunning: Boolean): Boolean =
        future?.cancel(mayInterruptIfRunning) ?: false

    fun isCancelled(): Boolean = future?.isCancelled ?: false

    protected fun publishProgress(vararg values: Progress) {
        onProgressUpdate(*values)
    }

    companion object {
        @JvmStatic
        fun execute(runnable: Runnable) {
            Executors.newSingleThreadExecutor().execute(runnable)
        }
    }
}
