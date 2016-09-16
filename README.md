# DVote by Theofilos Mouratidis(mtheofilos "at" di "dot" uoa "dot" gr)

DVote is a university project for the Distributed Systems class at UOA. DVote is a distributed voting system able to emulate national style elections. Both master and workers run for a day, then the master collects the results.

The nodes utilize the Http protocol for exchanging messages. The servers run on all available cores, therefore the voting fragments use concurrent programming. The storage is in-memory (ConcurrentHashMap) with a thread writing every request to the disk.
