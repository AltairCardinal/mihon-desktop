package mihon.domain.reader

data class ReaderBoundedSamplingRegion(val bounds: PixelBounds, val pointIndices: List<Int>)

/** Partitions fixed points, never their coordinates, so long pages retain the same sampling contract. */
fun boundedReaderSamplingRegions(
    points: List<Pair<Int, Int>>,
    width: Int,
    height: Int,
    maxPixels: Long,
    codecContext: Int = 0,
): List<ReaderBoundedSamplingRegion> {
    require(width > 0 && height > 0 && maxPixels > 0 && codecContext >= 0)
    require(points.all { (x, y) -> x in 0 until width && y in 0 until height })
    val result = mutableListOf<ReaderBoundedSamplingRegion>()
    fun partition(indices: List<Int>) {
        if (indices.isEmpty()) return
        val minX = indices.minOf { points[it].first }
        val minY = indices.minOf { points[it].second }
        val maxX = indices.maxOf { points[it].first }
        val maxY = indices.maxOf { points[it].second }
        // WebP crops snap their origin to even pixels and need chroma interpolation context.
        val left = if (codecContext == 0) minX else (minX / 2 * 2 - codecContext).coerceAtLeast(0)
        val top = if (codecContext == 0) minY else (minY / 2 * 2 - codecContext).coerceAtLeast(0)
        val right = minOf(width.toLong(), maxX.toLong() + maxOf(1, codecContext)).toInt()
        val bottom = minOf(height.toLong(), maxY.toLong() + maxOf(1, codecContext)).toInt()
        val bounds = PixelBounds(left, top, right - left, bottom - top)
        if (bounds.width.toLong() * bounds.height <= maxPixels) {
            result += ReaderBoundedSamplingRegion(bounds, indices)
            return
        }
        require(indices.size > 1) { "A single sampling point exceeds the region resource limit" }
        val sorted = if (maxX - minX > maxY - minY) {
            indices.sortedBy { points[it].first }
        } else {
            indices.sortedBy { points[it].second }
        }
        val middle = sorted.size / 2
        partition(sorted.subList(0, middle))
        partition(sorted.subList(middle, sorted.size))
    }
    partition(points.indices.toList())
    return result
}
