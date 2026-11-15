package dev.uphotosync.app.data

/**
 * What "free up space" means, shared by the Tools screen, its setting and the reminder
 * notification, so the reminder's numbers always match what the tool then shows.
 */
object FreeUp {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** The Free up space tool's starting age when nothing asked for another. */
    const val DEFAULT_DAYS = 180L

    /** Ages offered (days, label). 0 means any age. */
    val AGES = listOf(30L to "1 month", 90L to "3 months", 180L to "6 months", 365L to "1 year")

    fun label(days: Long): String = AGES.firstOrNull { it.first == days }?.second
        ?: if (days % 30 == 0L) "${days / 30} months" else "$days days"

    /** Backed-up files taken more than [days] ago (any age for 0), oldest first. */
    fun candidates(items: List<MediaItem>, days: Long, isBackedUp: (MediaItem) -> Boolean, now: Long = System.currentTimeMillis()): List<MediaItem> {
        val cutoff = now - days * DAY_MS
        return items.filter { isBackedUp(it) && (days == 0L || it.takenAt < cutoff) }.sortedBy { it.takenAt }
    }
}
