package mihon.domain.reader

/** Physical viewport slot for a portrait page that is alone in a dual-page display unit. */
enum class ReaderPortraitSingleSlot { FULL, LEFT, RIGHT }

/** Physical placement consumes the shared structural reason, not a platform's pager index. */
fun portraitSinglePageSlot(
    direction: ReaderDirection,
    reason: ReaderDualPageSingleReason,
): ReaderPortraitSingleSlot {
    require(direction != ReaderDirection.VERTICAL)
    val trailing = when (reason) {
        ReaderDualPageSingleReason.FIRST_PAGE,
        ReaderDualPageSingleReason.OPENING,
        ReaderDualPageSingleReason.MANUAL_BOUNDARY,
        ReaderDualPageSingleReason.MATCH_BOUNDARY,
        -> false
        ReaderDualPageSingleReason.CHAPTER_TAIL,
        ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD,
        -> true
        ReaderDualPageSingleReason.ONLY_PAGE,
        ReaderDualPageSingleReason.SPREAD,
        ReaderDualPageSingleReason.AMBIGUOUS,
        ReaderDualPageSingleReason.UNRESOLVED,
        -> return ReaderPortraitSingleSlot.FULL
    }
    return if ((direction == ReaderDirection.RTL) == trailing) {
        ReaderPortraitSingleSlot.RIGHT
    } else {
        ReaderPortraitSingleSlot.LEFT
    }
}
