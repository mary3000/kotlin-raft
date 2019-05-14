import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.logging.Logger
import kotlin.concurrent.schedule

@ExperimentalCoroutinesApi
class ElectionTimer(var channel: Channel<RaftServer.State>) {

    private val logger = Logger.getLogger(this.javaClass.name)

    private var timer: Timer = Timer()

    var timeout: Long = 5000

    fun waitForHeartbeats() {
        timeout = LongRange(3000, 7000).random()
        timer = Timer()
        timer.schedule(timeout) {
            runBlocking {
                logger.info("election timeout")
                channel.send(RaftServer.State.Candidate)
            }
        }
    }

    // TODO: send extra msg to election-coro to stop after timeout
    fun waitForElection() {
        waitForHeartbeats()
    }

    fun cancel() {
        timer.cancel()
    }
}