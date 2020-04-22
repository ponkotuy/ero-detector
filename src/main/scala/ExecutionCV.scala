import java.io.InputStream
import java.nio.file.Paths
import java.util.Scanner

import labels.Rate
import utils.{Files, MyCloudVision, ThreadSafeQueue}

import scala.jdk.CollectionConverters._

object ExecutionCV {
  val ThreadCount = 2

  def main(args: Array[String]): Unit = {
    val files = readFiles(System.in) ++ args
    val groups = files.grouped(16).toVector
    val queue = ThreadSafeQueue(groups)
    val runner = new RunGroup(queue)
    (1 to ThreadCount).foreach { _ => new Thread(runner).start() }
    Iterator.continually {
      Thread.sleep(100L)
      queue.size == 0
    }.dropWhile(identity).next()
  }

  def readFiles(source: InputStream): Seq[String] = {
    val scanner = new Scanner(source)
    scanner.asScala.toVector
  }
}

class RunGroup(queue: ThreadSafeQueue[Seq[String]]) extends Runnable {
  import RunGroup._

  val client = new MyCloudVision

  override def run(): Unit = {
    Iterator.continually{
      queue.poll().fold(false){ xs =>
        val paths = xs.map(Paths.get(_)).filter(Files.isRegularFile(_)).filter(Files.isReadable)
        val images = paths.map(MyCloudVision.loadImagePath).map(_.get)
        val annotations = client.safeSearchDetections(images: _*).toList
        paths.zip(annotations).foreach{ case (path, anno) =>
          val fname = path.toString
          println(s"${fname}: Adult=${anno.getAdultValue} Racy=${anno.getRacyValue}")
          splitDir(fname, Rate.fromCV(anno))
        }
        true
      }
    }.dropWhile(identity).next()
  }
}

object RunGroup {
  def splitDir(fname: String, rate: Rate): Unit = {
    val file = Paths.get(fname)
    val destDir = rate.path(file.getParent)
    Files.mkdirs(destDir)
    Files.move(file, destDir.resolve(file.getFileName))
  }
}
