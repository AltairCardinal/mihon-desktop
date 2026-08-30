package mihon.desktop.reader

import mihon.domain.reader.PixelBounds

internal data class DesktopReaderAnimationFrameMetadata(
    val durationMillis: Long,
    val bounds: PixelBounds,
) {
    init {
        require(durationMillis >= 0L) { "Animation frame duration must be non-negative" }
        require(bounds.width > 0 && bounds.height > 0) { "Animation frame bounds must be positive" }
    }
}

internal data class DesktopReaderAnimationMetadata(
    val frames: List<DesktopReaderAnimationFrameMetadata>,
    /** Encoded repeats after the first playthrough; `null` means repeat forever. */
    val repeatCount: Int?,
) {
    init {
        require(frames.size >= 2) { "Animation metadata requires at least two frames" }
        require(repeatCount == null || repeatCount >= 0) { "Animation repeat count must be non-negative" }
    }

    val frameCount: Int get() = frames.size
}

internal data class DesktopReaderAnimationDrawToken(
    val frameIndex: Int,
    val revision: Long,
)

internal data class DesktopReaderAnimationPlaybackSnapshot(
    val frameIndex: Int,
    val completedIterations: Int,
    val running: Boolean,
    val drawToken: DesktopReaderAnimationDrawToken?,
)

/** Deterministic frame clock shared by production playback and focused lifecycle tests. */
internal class DesktopReaderAnimationPlaybackController(
    private val metadata: DesktopReaderAnimationMetadata?,
    private val minimumFrameDurationMillis: Long,
) {
    private val lock = Any()
    private var frameIndex = 0
    private var elapsedInFrameMillis = 0L
    private var completedIterations = 0
    private var running = metadata != null
    private var revision = 0L
    private var drawToken = metadata?.let { DesktopReaderAnimationDrawToken(frameIndex = 0, revision = revision) }

    init {
        require(minimumFrameDurationMillis > 0L) { "Minimum animation frame duration must be positive" }
    }

    fun snapshot(): DesktopReaderAnimationPlaybackSnapshot = synchronized(lock) {
        DesktopReaderAnimationPlaybackSnapshot(
            frameIndex = frameIndex,
            completedIterations = completedIterations,
            running = running,
            drawToken = drawToken,
        )
    }

    fun advanceBy(elapsedMillis: Long) {
        synchronized(lock) {
            require(elapsedMillis >= 0L) { "Animation elapsed time must be non-negative" }
            val animation = metadata ?: return
            if (!running || elapsedMillis == 0L) return
            var remaining = elapsedMillis
            while (running) {
                val duration = animation.frames[frameIndex].durationMillis.coerceAtLeast(minimumFrameDurationMillis)
                val untilNextFrame = duration - elapsedInFrameMillis
                if (remaining < untilNextFrame) {
                    elapsedInFrameMillis += remaining
                    return
                }
                remaining -= untilNextFrame
                elapsedInFrameMillis = 0L
                if (frameIndex == animation.frames.lastIndex) {
                    completedIterations++
                    if (animation.repeatCount?.let { completedIterations.toLong() > it.toLong() } == true) {
                        running = false
                        return
                    }
                    publishFrame(0)
                } else {
                    publishFrame(frameIndex + 1)
                }
                if (remaining == 0L) return
            }
        }
    }

    fun detach() = synchronized(lock) {
        running = false
    }

    private fun publishFrame(index: Int) {
        frameIndex = index
        revision++
        drawToken = DesktopReaderAnimationDrawToken(index, revision)
    }
}
