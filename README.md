# DVote
### by Theofilos Mouratidis(mtheofilos "at" di "dot" uoa "dot" gr)

## About

DVote is a university project for the Distributed Systems class at UOA. DVote is a distributed voting system able to emulate national style elections. Both master and workers run for a day, then the master collects the results.

## In general

DVote is a structured master-slave distributed system, so the nodes are defined before the application starts. It focuses on availability and fault/partition-tolerance and has eventual consistency. The nodes utilize the http protocol for exchanging messages. The http servers run on all available cores, therefore the nodes use concurrent programming. Data is replicated across a series of nodes, a simple replication scheme, by a defined replication factor. It is guaranteed that with replicaton factor N, the system will work 100% with N-2 failures at any given time. Of course the system can work with more failures, but with a less succession percentage (inconsistencies or data loss problems will arise). 

## The Algorithm

Both master and workers read a **config.properties** file at their launch directory. The file includes parameters such as:
  
  - **workers(String[]):** a list of hosts separated by comma **e.g workers=slave-1,slave-2** *(default: localhost)*
  - **worker.port(int):** the port that worker http servers listen to *(default: 8080)*
  - **master.port(int):** the port that the master server listens to *(default: 8000)*
  - **replication(int):** the replication factor

The workers must be started first and then the master. 

### Master

The master server accepts **POST** requests at `/vote` with parameters `voter=Integer&canditate=Short` where these values are defined by a web application with accounts. At every second the master checks the availability of the workers. Then the server redirects the request to the suitable workers based on the voter ID and availability (a simple mod is used as it is assumed that everyone will vote, but a hash is more appropriate). If the replication factor is for example 3, the workers are 8 and the hash function returns `H(voter) % 8 == 7`, 7 8 and 1 are selected to store the value (workers are in a ring). If at least one of them responds successfully then the master sends a `200: OK` response to the voter. The master handles the timestamps and has a structure that holds the last timestamps of successful vote store requests. If a worker drops and after a while comes back, the master issues a request to a consistent replica to send the votes between the two timestamps to the revived server. When the voting stops, the master requests available and consistent replicas to retrieve the voting fragments, then sums up the data and returns the voting results.

### Worker

The worker servers accept requests from the master about availability, data store, vote results and consistency resolve. The server holds a `ConcurrentHashMap` for the votes and a `data.bin` file of the votes. It is guaranteed that at any given time both in-memory and disk storage hold the same data. On a store request, the worker checks if the voter already vote and sends a `400: Already Voted` response, then issues a write request and waits for the data to be written, when the data is persisted to the disk it is written to the in-memory storage. When the worker shuts down (for whatever reason) and then start again, it will read the disk data, write it in memory and then start the http server to respond that he is alive. With this data storage logic, it is guaranteed that there won't be any data loss at the local level (except unavoidable hardware failures, where  replication resolves the problem). The voting results and the consistency resolve are performed with the in-memory data for performance.

### Results

The system was tested on a 5 node cluster, with a master and 4 workers and replication factor of 2. The system was able to handle ~1462 requests per second (mean time). The server performed a bit faster when 2 workers dropped (1 and 4 to avoid data loss) as it had to send less store requests, but of course it introduces consistency problems and it takes some time to recover (with 10k total votes the recovery was immediate, but on more it will be increased).

### References

  1. Ghemawat, Sanjay, Howard Gobioff, and Shun-Tak Leung. "The Google file system." ACM SIGOPS operating systems review. Vol. 37. No. 5. ACM, 2003.
  2. Hunt, Patrick, et al. "ZooKeeper: Wait-free Coordination for Internet-scale Systems." USENIX Annual Technical Conference. Vol. 8. 2010.
  3. Stoica, Ion, et al. "Chord: A scalable peer-to-peer lookup service for internet applications." ACM SIGCOMM Computer Communication Review 31.4 (2001): 149-160.
  4. Anderson, Todd, et al. "Replication, consistency, and practicality: are these mutually exclusive?." ACM SIGMOD Record 27.2 (1998): 484-495.
  5. Rabin, Michael O. "Efficient dispersal of information for security, load balancing, and fault tolerance." Journal of the ACM (JACM) 36.2 (1989): 335-348.
  6. Gustavsson, Sanny, and Sten F. Andler. "Self-stabilization and eventual consistency in replicated real-time databases." Proceedings of the first workshop on Self-healing systems. ACM, 2002.
