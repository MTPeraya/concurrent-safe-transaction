package com.mtperaya.consumer.service

import com.typesafe.scalalogging.LazyLogging
import scala.util.{Failure, Success, Try}

class RetryPolicy(maxRetries: Int = 3, initialBackoffMs: Long = 500) extends LazyLogging {

  def retry[T](operationName: String)(block: => T): T = {
    var attempt = 0
    var backoff = initialBackoffMs
    var lastError: Throwable = null

    while (attempt <= maxRetries) {
      attempt += 1
      Try(block) match {
        case Success(result) =>
          return result
        case Failure(ex) =>
          lastError = ex
          if (attempt <= maxRetries) {
            logger.warn(
              s"Attempt $attempt/$maxRetries for '$operationName' failed: ${ex.getMessage}. Retrying in ${backoff}ms..."
            )
            try {
              Thread.sleep(backoff)
            } catch {
              case _: InterruptedException =>
                Thread.currentThread().interrupt()
                throw ex
            }
            backoff = Math.min(backoff * 2, 5000)
          } else {
            logger.error(s"All $maxRetries retry attempts exhausted for '$operationName': ${ex.getMessage}")
          }
      }
    }
    throw lastError
  }
}
