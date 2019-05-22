import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveText
import io.ktor.response.header
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import raft.*
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.concurrent.fixedRateTimer

@ExperimentalCoroutinesApi
class RaftServer(private val port: Int, ports: IntArray) : RaftServerGrpc.RaftServerImplBase() {

    private val logger = Logger.getLogger(this.javaClass.name)

    private val cluster: Array<RaftMember>

    private val majority: Int

    private val heartBeatDelay: Long = 1000

    private var currentTerm = AtomicInteger(0)

    @Volatile
    private var votedFor = -1

    @Volatile
    private var commitIndex = -1

    //TODO: RSM
    private var lastApplied = -1

    private var log = RaftLog()

    private var convertTo = Channel<State>(capacity = Channel.CONFLATED)

    private var electionTimer = ElectionTimer(convertTo)

    private var heartbeats = Timer()

    enum class State {
        Follower, Candidate, Leader
    }

    @Volatile
    var currentState: State = State.Follower

    init {
        cluster = ports.filter { p -> p != port }.map { p -> RaftMember(p) }.toTypedArray()
        majority = ports.size / 2 + 1
        electionTimer.waitForHeartbeats()
        var electionCoro: Job = Job()
        var prevElectionCoro: Job

        GlobalScope.launch {
            for (newState in convertTo) {
                currentState = newState
                heartbeats.cancel()
                electionTimer.cancel()
                electionCoro.cancel()

                when (newState) {
                    State.Candidate -> {
                        electionTimer.waitForElection()
                        prevElectionCoro = electionCoro
                        electionCoro = launch {
                            try {
                                prevElectionCoro.join()
                            } catch (e: CancellationException) {
                                // do nothing
                            }
                            beginElection()
                        }
                    }
                    State.Follower -> {
                        electionTimer.waitForHeartbeats()
                    }
                    State.Leader -> {
                        sendHeartBeats()
                    }
                }
            }
        }

        startServer()
    }

    private fun startServer() {
        val toKtorPort = 100
        embeddedServer(Netty, port + toKtorPort) {
            routing {
                get("/") {
                    call.respondText("RaftServer \nport: ${port + toKtorPort} \nleader port: ${votedFor + toKtorPort}\n" +
                            "currentTerm: ${currentTerm.get()} \nlog: $log \ncommitIndex: $commitIndex \nlastApplied: $lastApplied",
                        ContentType.Text.Plain)
                }
                post("/") {
                    if (currentState == State.Leader) {
                        val command = call.receiveText()
                        log.append(command, currentTerm.get())
                        call.respondText("Log: $log\n")
                    } else {
                        call.response.header("Location", "http://127.0.0.1:${votedFor + toKtorPort}/")
                        call.respond(HttpStatusCode.TemporaryRedirect, "redirected to leader")
                    }
                }
            }
        }.start(false)
    }

    private suspend fun beginElection() {
        logger.info("beginElection")

        currentTerm.incrementAndGet()
        votedFor = port

        val votes = cluster.map {
            GlobalScope.async {
                val response = kotlin.runCatching {
                    it.channel.requestVote(
                        Vote.newBuilder()
                            .setTerm(currentTerm.get())
                            .setCandidateId(votedFor)
                            .setLastLogIndex(log.lastIndex())
                            .setLastLogTerm(log.lastTerm())
                            .build()
                    )
                }.getOrNull()
                if (response != null && response.voteGranted) {
                    1
                } else {
                    0
                }
            }
        }

        var cnt = 0
        var finished = false
        while (!finished) {
            select<Unit> {
                votes.forEach {
                    it.onAwait { res ->
                        cnt += res
                        if (cnt >= majority) {
                            finished = true
                            convertTo.send(State.Leader)
                        }
                    }
                }
            }
        }
    }

    private fun sendHeartBeats() {
        cluster.forEach { it.nextIndex = log.lastIndex() + 1 }

        heartbeats = fixedRateTimer(initialDelay = 0, period = heartBeatDelay) {
            logger.info("sendHeartBeats $currentState port: $port, votedFor: $votedFor, term: ${currentTerm.get()}")
            cluster.forEach {
                val res = kotlin.runCatching {
                    val entry = Entries.newBuilder().setTerm(currentTerm.get()).setLeaderId(port).setLeaderCommit(commitIndex)
                    val entryToSend = log.entryAt(it.nextIndex)
                    if (entryToSend != null) {
                        entry.addEntries(entryToSend)
                    }
                    val response = it.channel.appendEntries(
                        entry.setPrevLogIndex(it.nextIndex - 1)
                            .setPrevLogTerm(log.termAt(it.nextIndex - 1))
                            .build()
                    )
                    if (response.success) {
                        it.matchIndex = it.nextIndex
                        if (log.lastIndex() >= it.nextIndex) {
                            it.nextIndex += 1
                        }
                    } else if (response.term <= currentTerm.get()) {
                        assert(it.nextIndex > 0)
                        it.nextIndex -= 1
                    } else {
                        GlobalScope.launch { convertTo.send(State.Follower) }
                        return@fixedRateTimer
                    }
                    updateCommitted()
                }
                if (res.isFailure) {
                    logger.info("sendHeartBeats catched $res")
                }
            }
        }
    }

    private fun updateCommitted() {
        var commitCandidate = commitIndex + 1
        while (log.lastIndex() >= commitCandidate && log.termAt(commitCandidate) == currentTerm.get()) {
            val matchSum = cluster.sumBy { if (it.matchIndex >= commitCandidate) 1 else 0 }
            if (matchSum >= majority - 1) {
                commitIndex = commitCandidate
            } else {
                break
            }
            commitCandidate += 1
        }
    }

    override fun appendEntries(request: Entries?, responseObserver: StreamObserver<AppendEntryResponse>?) {
        logger.info("appendEntries $currentState port: $port, votedFor: $votedFor, term: $currentTerm")
        if (request == null) {
            throw NullPointerException()
        }
        val response = AppendEntryResponse.newBuilder().setTerm(currentTerm.get()).setSuccess(false)
        if (request.term >= currentTerm.get()) {
            keepFollow(request.term, request.leaderId)
            if (log.updateLog(request.prevLogTerm, request.prevLogIndex, request.entriesList)) {
                response.success = true
            }
            if (request.leaderCommit > commitIndex) {
                commitIndex = min(request.leaderCommit, log.lastIndex())
            }
        }

        responseObserver?.onNext(response.build())
        responseObserver?.onCompleted()
    }

    override fun requestVote(request: Vote?, responseObserver: StreamObserver<VoteResponse>?) {
        logger.info("requestVote $currentState port: $port, votedFor: $votedFor, term: $currentTerm")
        if (request == null) {
            throw NullPointerException()
        }
        val response = VoteResponse.newBuilder().setTerm(currentTerm.get()).setVoteGranted(false)
        if ((request.term > currentTerm.get() || request.term == currentTerm.get() && (votedFor == -1 || votedFor == request.candidateId))
            && log.older(request.lastLogIndex, request.lastLogTerm)) {
            keepFollow(request.term, request.candidateId)
            response.voteGranted = true
        }
        logger.info("voted for $votedFor")
        currentTerm.set(max(currentTerm.get(), request.term))

        responseObserver?.onNext(response.build())
        responseObserver?.onCompleted()
    }

    private fun keepFollow(term: Int, leaderId: Int) {
        currentTerm.set(term)
        votedFor = leaderId
        GlobalScope.launch { convertTo.send(State.Follower) }
    }
}

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    val ports = args.dropLast(1).map { str -> str.toInt() }.toIntArray()
    val portIndex = args.last().toInt() - 1
    val raft = RaftServer(ports[portIndex], ports)
    ServerBuilder.forPort(ports[portIndex])
        .addService(raft)
        .build()
        .start()
        .awaitTermination()
}