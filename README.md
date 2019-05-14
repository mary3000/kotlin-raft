# kotlin-raft

This is implementation of [Raft](https://en.wikipedia.org/wiki/Raft_(computer_science)) algorithm on Kotlin language.  

### Build
`./gradlew build`

`RaftWerver.kt` runs one instance of node in the cluster.  
Parameters: `$port1 $port2 ... $portn $number`  
`portn` - port for nth node  
`number` - number of port for this instance of node

### Usage

You can do GET and POST requests to cluster nodes via port == nodePort + 100.

On GET you will see some information about node state, e.g.:
```
RaftServer 
port: 5130 
leader port: 5110
currentTerm: 1 
log: [0]{term: 1 | command: yeah6} [1]{term: 1 | command: yeah6} [2]{term: 1 | command: yeah6} [3]{term: 1 | command: yeah6} [4]{term: 1 | command: yeah6} [5]{term: 1 | command: yeah6}  
commitIndex: 5 
lastApplied: -1
``` 

On POST you will be redirected to the leader and the command you sent will be applied to the log.

### Note
Probably now it suffers from bugs. Sorry ¯\_(ツ)_/¯

##### TODO:
- Replicated State Machine
- Fix communication inside the server
- Build key-value storage
- Tests

Resources:
- 
- [web-site](https://raft.github.io/)
- [raft paper](https://raft.github.io/raft.pdf)
