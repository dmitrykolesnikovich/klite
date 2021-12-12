package klite

import com.sun.net.httpserver.HttpServer
import klite.RequestMethod.GET
import kotlinx.coroutines.*
import java.lang.Runtime.getRuntime
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.reflect.KFunction
import kotlin.reflect.full.primaryConstructor

class Server(
  val port: Int = Config.optional("PORT")?.toInt() ?: 8080,
  val numWorkers: Int = Config.optional("NUM_WORKERS")?.toInt() ?: getRuntime().availableProcessors(),
  val registry: MutableRegistry = DependencyInjectingRegistry().apply {
    register<RequestLogger>()
    register<TextBodyRenderer>()
    register<TextBodyParser>()
    register<FormUrlEncodedParser>()
  },
  val globalDecorators: MutableList<Decorator> = registry.requireAllDecorators().toMutableList(),
  val errorHandler: ErrorHandler = registry.require(),
  val bodyRenderers: List<BodyRenderer> = registry.requireAll(),
  val bodyParsers: List<BodyParser> = registry.requireAll(),
  val pathParamRegexer: PathParamRegexer = registry.require(),
  val notFoundHandler: Handler = globalDecorators.wrap { throw NotFoundException(path) },
  val httpExchangeCreator: KFunction<HttpExchange> = XForwardedHttpExchange::class.primaryConstructor!!,
): Registry by registry {
  private val logger = logger()
  val workerPool = Executors.newFixedThreadPool(numWorkers)
  val requestScope = CoroutineScope(SupervisorJob() + workerPool.asCoroutineDispatcher())
  private val http = HttpServer.create()

  fun start(gracefulStopDelaySec: Int = 3) {
    logger.info("Listening on $port")
    http.bind(InetSocketAddress(port), 0)
    http.start()
    if (gracefulStopDelaySec >= 0) getRuntime().addShutdownHook(thread(start = false) { stop(gracefulStopDelaySec) })
  }

  private val onStopHandlers = mutableListOf<Runnable>()
  fun onStop(handler: Runnable) { onStopHandlers += handler }

  fun stop(delaySec: Int = 1) {
    logger.info("Stopping gracefully")
    http.stop(delaySec)
    onStopHandlers.forEach { it.run() }
  }

  fun use(extension: Extension) = extension.install(this)
  fun decorator(decorator: Decorator) { globalDecorators += decorator }

  fun context(prefix: String, block: Router.() -> Unit = {}) =
    Router(prefix, registry, pathParamRegexer, globalDecorators, bodyRenderers, bodyParsers).also { router ->
      http.createContext(prefix) { ex ->
        requestScope.launch {
          val exchange = httpExchangeCreator.call(ex, bodyRenderers, bodyParsers)
          handle(exchange, router.route(exchange))
        }
      }
      router.block()
    }

  fun assets(prefix: String, handler: AssetsHandler) {
    http.createContext(prefix) { ex ->
      requestScope.launch(Dispatchers.IO) {
        val exchange = HttpExchange(ex, bodyRenderers, emptyList())
        handle(exchange, handler.takeIf { exchange.method == GET })
      }
    }
  }

  private suspend fun handle(exchange: HttpExchange, handler: Handler?) {
    try {
      val result = (handler ?: notFoundHandler).invoke(exchange).takeIf { it != Unit }
      if (!exchange.isResponseStarted)
        exchange.render(if (result == null) StatusCode.NoContent else StatusCode.OK, result)
    } catch (e: Exception) {
      errorHandler(exchange, e)
    } finally {
      exchange.close()
    }
  }
}

interface Extension {
  fun install(server: Server)
}
