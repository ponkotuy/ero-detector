package utils

import java.util.concurrent.LinkedBlockingQueue

import scala.jdk.CollectionConverters._

case class ThreadSafeQueue[A](init: Seq[A]) {
  private[this] val queue = new LinkedBlockingQueue[A](init.asJava)
  def poll(): Option[A] = Option(queue.poll())
  def size: Int = queue.size()
}
