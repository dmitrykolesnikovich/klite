package klite.jobs

import klite.*
import klite.jdbc.Transaction
import klite.jdbc.TransactionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineStart.DEFAULT
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import java.time.Duration.between
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

interface Job {
  suspend fun run()
  val name get() = this::class.simpleName!!
}

class NamedJob(override val name: String, private val job: suspend () -> Unit): Job {
  override suspend fun run() = job()
}

open class JobRunner(
  private val db: DataSource,
  private val requestIdGenerator: RequestIdGenerator,
  val workerPool: ScheduledExecutorService = Executors.newScheduledThreadPool(Config.optional("JOB_WORKERS", "3").toInt())
): Extension, CoroutineScope {
  override val coroutineContext = SupervisorJob() + workerPool.asCoroutineDispatcher()
  private val logger = logger()
  private val seq = AtomicLong()
  private val runningJobs = ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

  override fun install(server: Server) {
    server.onStop(::gracefulStop)
  }

  internal fun runInTransaction(job: Job, start: CoroutineStart = DEFAULT): kotlinx.coroutines.Job {
    val threadName = ThreadNameContext("${requestIdGenerator.prefix}/${job.name}#${seq.incrementAndGet()}")
    val tx = Transaction(db)
    return launch(threadName + TransactionContext(tx), start) {
      try {
        logger.info("${job.name} started")
        run(job)
        tx.close(true)
      } catch (e: Exception) {
        logger.error("${job.name} failed", e)
        tx.close(false)
      }
    }.also { launched ->
      runningJobs += launched
      launched.invokeOnCompletion { runningJobs -= launched }
    }
  }

  open suspend fun run(job: Job) = job.run()

  open fun schedule(job: Job, delay: Long, period: Long, unit: TimeUnit) {
    val startAt = LocalDateTime.now().plus(delay, unit.toChronoUnit())
    logger.info("${job.name} will start at $startAt and run every $period $unit")
    workerPool.scheduleAtFixedRate({ runInTransaction(job, UNDISPATCHED) }, delay, period, unit)
  }

  fun scheduleDaily(job: Job, delayMinutes: Long = (Math.random() * 10).toLong()) =
    schedule(job, delayMinutes, 24 * 60, MINUTES)

  fun scheduleDaily(job: Job, vararg at: LocalTime) {
    val now = LocalDateTime.now()
    for (time in at) {
      val todayAt = time.atDate(now.toLocalDate())
      val runAt = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
      scheduleDaily(job, between(now, runAt).toMinutes())
    }
  }

  fun scheduleMonthly(job: Job, dayOfMonth: Int, vararg at: LocalTime) = scheduleDaily(NamedJob(job.name) {
    if (LocalDate.now().dayOfMonth == dayOfMonth) job.run()
  }, at = at)

  open fun gracefulStop() {
    runBlocking {
      runningJobs.forEach { it.cancelAndJoin() }
    }
    workerPool.shutdown()
    workerPool.awaitTermination(10, SECONDS)
  }
}
