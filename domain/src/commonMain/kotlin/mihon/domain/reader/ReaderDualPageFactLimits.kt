package mihon.domain.reader

/** Shared bounds for metadata probes and optional fact supplementation; visible loading remains independent. */
object ReaderDualPageFactLimits {
    const val MAX_CONCURRENT_REQUESTS = 2
    const val MAX_ADDITIONAL_PAGES = 16
    const val MAX_NEW_BYTES = 32L * 1024 * 1024
    const val MAX_WAIT_MILLIS = 15_000L

    // A malformed native image header must not turn a metadata probe into an unbounded encoded read.
    const val MAX_HEADER_BYTES = 512L * 1024
}
