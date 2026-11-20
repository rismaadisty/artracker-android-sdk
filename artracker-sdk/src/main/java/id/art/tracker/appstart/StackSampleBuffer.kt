package id.art.tracker.appstart

/**
 * The samples of one startup profile, de-duplicated: a frame (`class|method|line|file`) is stored once, a stack (the
 * list of its frame indexes, root first) is stored once, and a sample is just a time and a stack index. Not thread safe:
 * the sampler thread fills it and the owner reads it only after that thread has ended.
 */
internal class StackSampleBuffer(private val maxSamples: Int = 1000, private val maxDepth: Int = 128) {
    private data class FrameKey(val cls: String, val method: String, val line: Int, val file: String?)

    private val frameIndex = HashMap<FrameKey, Int>()
    private val frameList = ArrayList<Map<String, Any?>>()
    private val stackIndex = HashMap<List<Int>, Int>()
    private val stackList = ArrayList<List<Int>>()
    private val sampleList = ArrayList<Map<String, Any?>>()

    val sampleCount: Int get() = sampleList.size

    /**
     * Adds one sample taken [tMs] after the process start. [leafFirst] is as returned by `Thread.getStackTrace`.
     * Only the [maxDepth] frames nearest the leaf are kept (the root end is cut). Returns false when the sample was
     * ignored: the buffer is full, or the stack is empty.
     */
    fun add(tMs: Long, leafFirst: Array<StackTraceElement>): Boolean {
        if (sampleList.size >= maxSamples || leafFirst.isEmpty()) return false
        val kept = leafFirst.copyOfRange(0, minOf(maxDepth, leafFirst.size))
        val stack = ArrayList<Int>(kept.size)
        for (i in kept.indices.reversed()) stack += frameIdOf(kept[i])
        val id = stackIndex.getOrPut(stack) {
            stackList += stack
            stackList.size - 1
        }
        sampleList += linkedMapOf<String, Any?>("t" to tMs, "s" to id)
        return true
    }

    private fun frameIdOf(e: StackTraceElement): Int {
        val key = FrameKey(e.className, e.methodName, e.lineNumber, e.fileName)
        return frameIndex.getOrPut(key) {
            val frame = linkedMapOf<String, Any?>("c" to e.className, "m" to e.methodName)
            if (e.lineNumber >= 0) frame["l"] = e.lineNumber
            e.fileName?.let { frame["f"] = it }
            frameList += frame
            frameList.size - 1
        }
    }

    /** `{c, m, l?, f?}` per distinct frame. */
    fun frames(): List<Map<String, Any?>> = frameList

    /** Root-first frame indexes per distinct stack. */
    fun stacks(): List<List<Int>> = stackList

    /** `{t, s}` per sample: ms since the process start, and the stack index. */
    fun samples(): List<Map<String, Any?>> = sampleList
}
