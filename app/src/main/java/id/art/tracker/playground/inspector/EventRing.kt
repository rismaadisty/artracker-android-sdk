package id.art.tracker.playground.inspector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bounded, thread-safe buffer of the most recent items, newest first. Nothing here is persisted or logged. */
class EventRing(private val capacity: Int = 200) {
    private val lock = Any()
    private val deque = ArrayDeque<InspectedItem>()
    private var total = 0L
    private val itemsFlow = MutableStateFlow<List<InspectedItem>>(emptyList())
    private val totalFlow = MutableStateFlow(0L)

    /** Newest first. */
    val items: StateFlow<List<InspectedItem>> = itemsFlow
    val totalSeen: StateFlow<Long> = totalFlow

    fun add(item: InspectedItem) = addAll(listOf(item))

    /** [list] is oldest to newest, so its last element ends up first in [items]. */
    fun addAll(list: List<InspectedItem>) {
        try {
            synchronized(lock) {
                for (item in list) {
                    deque.addFirst(item)
                    if (deque.size > capacity) deque.removeLast()
                    total++
                }
                itemsFlow.value = deque.toList()
                totalFlow.value = total
            }
        } catch (_: Throwable) {
            // The inspector must never affect the SDK pipeline thread that feeds it.
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            itemsFlow.value = emptyList()
        }
    }
}
