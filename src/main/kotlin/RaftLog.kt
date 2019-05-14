import java.util.logging.Logger

class RaftLog {

    private val logger = Logger.getLogger(this.javaClass.name)

    class LogEntry(val term: Int, val command: String)

    val entries: MutableList<LogEntry> = ArrayList()

    @Synchronized
    fun older(index: Int, term: Int): Boolean {
        if (term > lastTerm()) {
            return true
        }
        if (lastTerm() == term && entries.lastIndex <= index) {
            return true
        }
        return false
    }

    @Synchronized
    fun lastIndex(): Int {
        return entries.lastIndex
    }

    @Synchronized
    fun lastTerm(): Int {
        return if (entries.isEmpty()) {
            -1
        } else {
            entries.last().term
        }
    }

    @Synchronized
    fun termAt(index: Int): Int {
        return if (index < 0 || entries.size <= index) {
            -1
        } else {
            entries[index].term
        }
    }

    @Synchronized
    fun entryAt(index: Int): raft.LogEntry? {
        return if (index < 0 || entries.size <= index) {
            null
        } else {
            raft.LogEntry.newBuilder()
                .setTerm(entries[index].term)
                .setCommand(entries[index].command)
                .build()
        }
    }

    @Synchronized
    fun updateLog(prevLogTerm: Int, prevLogIndex: Int, entriesList: List<raft.LogEntry>): Boolean {
        logger.info("updateLog")
        try {
            if (prevLogIndex == lastIndex() && prevLogTerm == lastTerm()) {
                entries.addAll(entriesList.map { e -> LogEntry(e.term, e.command) })
                return true
            }
            while (lastIndex() > prevLogIndex) {
                entries.removeAt(lastIndex())
            }
            if (lastTerm() != prevLogTerm && lastTerm() != -1) {
                entries.removeAt(lastIndex())
            }
        } catch (e: Throwable) {
            logger.info("updateLog catched $e")
        }
        return false
    }

    @Synchronized
    fun append(command: String, term: Int) {
        entries.add(LogEntry(term, command))
    }

    @Synchronized
    override fun toString(): String {
        return entries.withIndex().joinToString(separator = "") {
            "[${it.index}]{term: ${it.value.term} | command: ${it.value.command}} "
        }
    }
}