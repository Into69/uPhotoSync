package dev.uphotosync.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A tool to open on the Tools tab, asked for by a notification tap (e.g. the free-up-space
 * reminder). The Tools tab opens it and clears the request.
 */
object ToolLink {
    data class Request(val tool: String, val days: Long = 0)

    val pending = MutableStateFlow<Request?>(null)

    const val FREE_UP = "free_up"
}
