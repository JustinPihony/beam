package beam.utils.watcher

import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._

object MethodWatcher {

  def withLoggingInvocationTime[T](
    name: String,
    logger: Logger = LoggerFactory.getLogger(this.getClass),
    timeUnit: TimeUnit = SECONDS
  )(m: => T): T = {
    val startTime = System.nanoTime()
    val res = m
    val stopTime = System.nanoTime()
    val duration = Duration(stopTime - startTime, NANOSECONDS)

    logger.info(
      s"Invocation of '{}' took {} {}",
      name,
      duration.toUnit(timeUnit).toString.toLowerCase(),
      timeUnit
    )

    res
  }

}
