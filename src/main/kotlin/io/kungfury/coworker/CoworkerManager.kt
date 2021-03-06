package io.kungfury.coworker

import io.kungfury.coworker.consul.ServiceChecker
import io.kungfury.coworker.dbs.ConnectionManager
import io.kungfury.coworker.dbs.ConnectionType
import io.kungfury.coworker.dbs.Marginalia.AddMarginalia
import io.kungfury.coworker.dbs.TextSafety
import io.kungfury.coworker.internal.CoworkerJavaRunnable
import io.kungfury.coworker.internal.CoworkerKotlinRunnable
import io.kungfury.coworker.internal.DescribedWork
import io.kungfury.coworker.internal.WorkNotification
import io.kungfury.coworker.utils.NetworkUtils

import kotlinx.coroutines.experimental.TimeoutCancellationException
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.runBlocking

import org.slf4j.LoggerFactory

import java.io.IOException
import java.lang.reflect.Constructor
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.Future

import kotlin.concurrent.thread
import kotlin.reflect.full.primaryConstructor

/**
 * The manager object for Coworker. Is in charge of running work.
 *
 * @param connectionManager
 *  The connection manager to the datastore.
 * @param checkWorkEvery
 *  The TemporalAmount for when to check the DB for missed notifications.
 * @param nstrandMap
 *  The nstrand mapping of <strand name, max running at a time>.
 * @param threads
 *  The amount of threads to run (cannot be lower than one).
 * @param serviceChecker
 *  The optional service checker to check for dead nodes in consul.
 */
class CoworkerManager(
    private val connectionManager: ConnectionManager,
    threads: Int,
    private val serviceChecker: ServiceChecker?,
    private val configurationInput: CoworkerConfigurationInput
) {
    private val LOGGER = LoggerFactory.getLogger(CoworkerManager::class.java)

    private val nThreads = if (threads < 1) { 1 } else { threads }
    private val executorService = Executors.newFixedThreadPool(nThreads)
    private var futures = ArrayList<Future<*>>()
    private val futureWorkMap = HashMap<Int, Long>()

    // Ensure we start off checking old work.
    private var lastCheckedWork: Instant = Instant.now().minusSeconds(10).minus(configurationInput.getWorkCheckDelay())
    private var nextCalculatedCheck: Long = Instant.now().minusSeconds(5).epochSecond
    private val workNotifiedAbout = ArrayList<WorkNotification>()

    private var listened: ReceiveChannel<String> = connectionManager.listenToChannel("workers")
    private val networkAddr: String = NetworkUtils.getLocalHostLANAddress().hostAddress

    // The parameter size used for calling constructors.
    private val PARAMETER_SIZE = 5

    /**
     * Starts this Coworker manager.
     *
     * NOTE: This will hijack the main thread as the "Master Process" queues threads underneath it.
     */
    fun Start() {
        LOGGER.info("Starting Coworker Manager...")

        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking { WorkGarbage.Cleanup(connectionManager) }
        })

        // Cleanup any work left behind by restart.
        runBlocking { ReleaseToPoolForHosts(listOf(networkAddr)) }

        thread(name = "CleanupThread") {
            while (true) {
                LOGGER.info("Checking if we should cleanup.")
                if (WorkGarbage.ShouldCleanup()) {
                    LOGGER.info("We should Cleanup.")
                    runBlocking { WorkGarbage.Cleanup(connectionManager) }
                }
                if (serviceChecker != null) {
                    val newOfflineNodes = runBlocking { serviceChecker.getNewOfflineNodes().await() }
                    if (newOfflineNodes.isNotEmpty()) {
                        runBlocking { ReleaseToPoolForHosts(newOfflineNodes) }
                    }
                }
                Thread.sleep(1000)
            }
        }

        while (true) {
            CleanupCompletedWork()
            if (futures.size >= nThreads) {
                continue
            }
            ProcessNotifications()
            FindHeadlessWork()

            while (futures.size < nThreads) {
                val foundWork = FindAndLockWork()
                if (foundWork == null) {
                    break
                }

                try {
                    val clazz = Class.forName(foundWork.workUniqueName)
                    val kclazz = clazz.kotlin
                    val isKotlin = DelayedKotlinWork::class.java.isAssignableFrom(clazz)

                    if (isKotlin) {
                        val constructor = kclazz.primaryConstructor

                        if (constructor == null || constructor.parameters.size != PARAMETER_SIZE) {
                            throw IllegalStateException("KClass Constructor for: ${foundWork.workUniqueName} does not accept $PARAMETER_SIZE params.")
                        }

                        val work = constructor.call(
                            connectionManager,
                            foundWork.workId,
                            foundWork.Stage,
                            foundWork.Strand,
                            foundWork.Priority
                        ) as DelayedKotlinWork
                        val future = executorService.submit(CoworkerKotlinRunnable(foundWork, work))

                        futures.add(future)
                        futureWorkMap[future.hashCode()] = foundWork.workId
                    } else {
                        val canBeWorked = DelayedJavaWork::class.java.isAssignableFrom(clazz)
                        if (!canBeWorked) {
                            throw IllegalStateException("Work for class: ${foundWork.workUniqueName} is not an instance of DelayedJavaWork!")
                        }
                        var constructor: Constructor<*>? = null
                        for (constru in clazz.declaredConstructors) {
                            if (constru.parameters.size == PARAMETER_SIZE) {
                                constructor = constru
                                break
                            }
                        }

                        if (constructor == null) {
                            throw IllegalStateException("Failed to find constructor with proper arg length!")
                        }

                        val work = constructor.newInstance(
                            connectionManager,
                            foundWork.workId,
                            foundWork.Stage,
                            foundWork.Strand,
                            foundWork.Priority
                        ) as DelayedJavaWork
                        val future = executorService.submit(CoworkerJavaRunnable(foundWork, work))

                        futures.add(future)
                        futureWorkMap[future.hashCode()] = foundWork.workId
                    }
                } catch (exc: Exception) {
                    LOGGER.error("Failed to find, and call constructor for: [ ${foundWork.workUniqueName} ] Exception: [ $exc ].")
                    runBlocking { FailWork(foundWork.workId, foundWork.workUniqueName, "${exc.localizedMessage}\n  ${exc.stackTrace.joinToString("\n  ")}") }
                }
            }
        }
    }

    /**
     * Checks futures to see if they've been cancelled/finished, and cleans them up.
     */
    private fun CleanupCompletedWork() {
        if (futures.isNotEmpty()) {
            futures.retainAll { future ->
                if (future.isDone || future.isCancelled) {
                    val workId = futureWorkMap[future.hashCode()]!!
                    if (future.isCancelled) {
                        try {
                            runBlocking { ReleaseToPool(workId) }
                        } catch (exc: Exception) {
                            LOGGER.error("Failed to release cancelled $workId back to the free pool! Will try again.")
                            return@retainAll true
                        }
                        LOGGER.info("Released cancelled $workId back to the free pool since it was cancelled.")
                    } else {
                        try {
                            val isWorkLocked = runBlocking { IsWorkLocked(workId) }
                            if (isWorkLocked && !WorkGarbage.isScheduledForDelete(workId)) {
                                runBlocking { ReleaseToPool(workId) }
                            }
                        } catch (exc: Exception) {
                            LOGGER.error("Failed to check if work exited cleanly and remove if it didn't! [ $exc ]")
                            return@retainAll true
                        }
                    }
                    false
                } else {
                    true
                }
            }
        }
    }

    /**
     * Processes Notifications that have been received from postgres.
     */
    private fun ProcessNotifications() {
        try {
            if (listened.isClosedForReceive) {
                listened = connectionManager.listenToChannel("workers")
            }
        } catch (exc: Exception) {
            LOGGER.error("Failed to refresh notification connection due to: $exc")
            return
        }

        var polled = listened.poll()
        while (polled != null) {
            LOGGER.debug("Found polled event: $polled")
            try {
                val split = polled.split(";")
                if (split.size != 5) {
                    throw IllegalStateException("Polled event: [ $polled ] does not match format.")
                }

                val parsed = WorkNotification()
                parsed.Id = split[0].toLong()
                parsed.Priority = split[1].toInt()
                parsed.QueuedAt = split[2].toLong()
                parsed.Stage = split[3].toInt()
                parsed.Strand = split[4]

                workNotifiedAbout.add(parsed)
            } catch (exc: Exception) {
                LOGGER.error("Failed to process notifications from postgres: $exc")
            }
            polled = listened.poll()
        }
    }

    /**
     * Finds work that don't have a notification but are in the database.
     */
    private fun FindHeadlessWork() {
        val thisInstant = Instant.now()
        if (thisInstant.epochSecond > nextCalculatedCheck) {
            LOGGER.info("Checking for work that was orphaned.")
            try {
                runBlocking {
                    when (connectionManager.CONNECTION_TYPE) {
                        ConnectionType.POSTGRES -> {
                            connectionManager.executeTransaction({ connection ->
                                val statement = connection.prepareStatement(AddMarginalia(
                                    "CoworkerManager_findHeadlessWork",
                                    "SELECT" +
                                        " id," +
                                        " stage," +
                                        " strand," +
                                        " priority," +
                                        " COALESCE(run_at, created_at) AS queued_at " +
                                        "FROM" +
                                        " public.delayed_work " +
                                        "WHERE" +
                                        " locked_by IS NULL " +
                                        "AND" +
                                        " id != ANY(?)"
                                ))
                                val ids = workNotifiedAbout.map { work ->
                                    work.Id
                                }.toMutableList()
                                if (ids.isEmpty()) {
                                    // POSTGRES needs at least one value otherwise it tries to
                                    // compare against the empty set, and this results in 0 jobs being returned.
                                    ids.add(-1)
                                }
                                statement.setArray(1, connection.createArrayOf("BIGINT", ids.toTypedArray()))
                                val rs = statement.executeQuery()

                                while (rs.next()) {
                                    val id = rs.getLong("id")
                                    val notification = WorkNotification()
                                    notification.Id = id
                                    notification.Priority = rs.getInt("Priority")
                                    notification.QueuedAt = rs.getTimestamp("queued_at")
                                        .toLocalDateTime().toEpochSecond(ZoneOffset.UTC)
                                    notification.Stage = rs.getInt("stage")
                                    notification.Strand = rs.getString("strand")
                                    workNotifiedAbout.add(notification)
                                }
                            }, true)
                        }
                    }
                }
                nextCalculatedCheck = thisInstant.plus(configurationInput.getWorkCheckDelay()).epochSecond
                lastCheckedWork = thisInstant
            } catch (exc: Exception) {
                LOGGER.error("Failed to check for orphaned work: [ $exc ].")
            }
        } else {
            LOGGER.info("Won't check for headless work in the DB since we checked recently.")
        }
    }

    /**
     * Attempts to find a free work that needs to be done.
     */
    private fun FindAndLockWork(): DescribedWork? {
        if (workNotifiedAbout.isEmpty()) {
            return null
        }

        try {
            val instant = Instant.now().epochSecond
            workNotifiedAbout.sortBy { work -> work.Priority.toLong() + (instant - work.QueuedAt) }

            var locked: DescribedWork? = null
            var lockedToRemove: WorkNotification? = null
            for (work in workNotifiedAbout) {
                if (instant < work.QueuedAt) {
                    // We haven't hit run at yet.
                    continue
                }
                val lockWorkResult = runBlocking { AttemptLockWork(work.Id) }
                if (lockWorkResult.first) {
                    val isAtMax = runBlocking { ValidateNStrand(work.Strand) }
                    if (!isAtMax) {
                        lockedToRemove = work
                        locked = lockWorkResult.second
                        break
                    } else {
                        runBlocking { ReleaseToPool(work.Id) }
                    }
                }
            }

            if (locked == null) {
                LOGGER.info("Failed to find work to work that wasn't already picked up!")
            } else {
                workNotifiedAbout.remove(lockedToRemove)
                return locked
            }
        } catch (exc: Exception) {
            LOGGER.error("Failed to find, and lock work: [ $exc ]!\n  ${exc.stackTrace.joinToString("\n  ")}")
        }

        LOGGER.info("Failed to find any work to work!")
        return null
    }

    /**
     * Attempts to lock a particular piece of work.
     *
     * @param id
     *  The ID of the work to potentially lock.
     * @return
     *  Returns a pair of <succesfully_locked, locked_work>.
     */
    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun AttemptLockWork(id: Long): Pair<Boolean, DescribedWork?> {
        LOGGER.info("AttemptLockWork called for $id")

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                return connectionManager.executeTransaction ({ connection: Connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_attemptLock",
                        "WITH select_work AS ( " +
                            "SELECT * FROM public.delayed_work WHERE id = ? AND locked_by IS NULL LIMIT 1 FOR UPDATE SKIP LOCKED " +
                            "), " +
                            "stamp_work AS (" +
                            " UPDATE public.delayed_work SET locked_by = ? FROM select_work WHERE delayed_work.id = select_work.id RETURNING delayed_work.id " +
                            ") " +
                            "SELECT work_unique_name, stage, state, strand, priority, COALESCE(run_at, created_at) AS queued_at FROM public.delayed_work JOIN stamp_work USING (id)"
                    ))
                    statement.setLong(1, id)
                    statement.setString(2, networkAddr)
                    val rs = statement.executeQuery()

                    if (rs == null) {
                        Pair(false, null)
                    } else {
                        if (rs.next()) {
                            Pair(true, DescribedWork(
                                rs.getString("work_unique_name"),
                                id,
                                rs.getInt("stage"),
                                rs.getString("strand"),
                                rs.getString("state"),
                                rs.getInt("priority"),
                                rs.getTimestamp("queued_at").toLocalDateTime().toEpochSecond(ZoneOffset.UTC)
                            ))
                        } else {
                            Pair(false, null)
                        }
                    }
                }, true)
            }
        }
    }

    /**
     * Checks if a work we're about to work has too many instances working.
     *
     * @param strand
     *  The strand of the work to check.
     * @param checkEquals
     *  Check if the count is currently over, or is just at the max capacity.
     */
    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun ValidateNStrand(strand: String): Boolean {
        val nstrand = configurationInput.getNstrandMap()
        if (nstrand.isEmpty()) {
            return false
        }

        var maxNStrand = -1
        for (strandPair in nstrand.keys) {
            val strandKey = strandPair.first
            val strandRegex = strandPair.second

            if (strandKey == strand) {
                maxNStrand = nstrand[strandPair]!!
                break
            } else if (strandRegex.matches(strand)) {
                maxNStrand = nstrand[strandPair]!!
                break
            }
        }
        // Short circut
        if (maxNStrand == -1) {
            return false
        }

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                return connectionManager.executeTransaction({ connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_releaseIfStrandedCount",
                        "SELECT COUNT(*) as strand_count FROM public.delayed_work WHERE strand = ? AND locked_by IS NOT NULL"
                    ))
                    statement.setString(1, TextSafety.EnforceStringPurity(strand, true))
                    val rs = statement.executeQuery()

                    rs.getLong("strand_count") > maxNStrand
                }, true)
            }
        }
    }

    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun ReleaseToPoolForHosts(list: List<String>) {
        LOGGER.info("ReleaseToPoolForHosts called with: [ ${list.joinToString(",")} ].")

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                connectionManager.executeTransaction ({ connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_ReleaseToPoolForHosts",
                        "UPDATE public.delayed_work SET locked_by = NULL WHERE locked_by = ANY(?)"
                    ))
                    statement.setArray(1, connection.createArrayOf("VARCHAR", list.toTypedArray()))
                    statement.execute()
                }, true)
            }
        }
    }

    /**
     * Releases a piece of work back into the work queue.
     *
     * @param id
     *  The id of the piece of work to release back into the work queue.
     * @return
     *  If the work was released back into the pool.
     */
    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun ReleaseToPool(id: Long): Boolean {
        LOGGER.info("ReleaseToPool called for $id")

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                return connectionManager.executeTransaction({ connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_ReleaseToPool",
                        "UPDATE public.delayed_work SET locked_by = NULL WHERE id = ?"
                    ))
                    statement.setLong(1, id)
                    val bool = statement.execute()

                    bool
                }, true)
            }
        }
    }

    /**
     * Determines if a particular piece of work is locked.
     *
     * @param id
     *  The ID of the piece of work.
     */
    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun IsWorkLocked(id: Long): Boolean {
        LOGGER.info("IsWorkLocked called for $id")

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                return connectionManager.executeTransaction({ connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_isWorkLocked",
                        "SELECT id FROM public.delayed_work WHERE id = ? AND locked_by = ? LIMIT 1"
                    ))
                    statement.setLong(1, id)
                    statement.setString(2, networkAddr)
                    val rs = statement.executeQuery()

                    rs.next()
                }, true)
            }
        }
    }

    /**
     * Marks a piece of work as failed.
     *
     * @param id
     *  The id of the work to fail.
     * @param workName
     *  The name of this piece of work
     * @param failureReason
     *  The reason the piece of work failed.
     */
    @Throws(TimeoutCancellationException::class, IOException::class, IllegalStateException::class, Exception::class)
    private suspend fun FailWork(id: Long, workName: String, failureReason: String) {
        LOGGER.info("FailWork called for $id")

        when (connectionManager.CONNECTION_TYPE) {
            ConnectionType.POSTGRES -> {
                connectionManager.executeTransaction ({ connection: Connection ->
                    val statement = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_FailWork",
                        "DELETE FROM public.delayed_work WHERE id = ?"
                    ))
                    statement.setLong(1, id)
                    statement.execute()

                    val createFailed = connection.prepareStatement(AddMarginalia(
                        "CoworkerManager_failWorkCreate",
                        "INSERT INTO public.failed_work(id, failed_at, stage, work_unique_name, failed_msg, state, run_by) VALUES ( ?, current_timestamp, ?, ?, ?, ?, ? )"
                    ))
                    createFailed.setLong(1, id)
                    createFailed.setInt(2, -1)
                    createFailed.setString(3, workName)
                    createFailed.setString(4, failureReason)
                    createFailed.setString(5, "")
                    createFailed.setString(6, NetworkUtils.getLocalHostLANAddress().hostAddress)
                    createFailed.execute()
                }, true)
            }
        }
    }
}