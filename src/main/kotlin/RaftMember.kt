import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import raft.RaftServerGrpc

class RaftMember(port: Int) {

    var channel: RaftServerGrpc.RaftServerBlockingStub

    var nextIndex: Int = 0

    var matchIndex: Int = -1

    init {
        val server = ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext()
            .build()
        channel = RaftServerGrpc.newBlockingStub(server)
    }
}