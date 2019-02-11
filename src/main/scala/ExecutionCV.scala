import java.nio.file.Paths

import labels.Rate
import utils.{Files, MyCloudVision}

object ExecutionCV {
  def main(args: Array[String]): Unit = {
    val client = new MyCloudVision
    args.grouped(10).foreach { xs =>
      val paths = xs.map(Paths.get(_)).filter(Files.isRegularFile(_)).filter(Files.isReadable)
      val images = paths.map(MyCloudVision.loadImagePath).map(_.get)
      val annotations = client.safeSearchDetections(images: _*).toList
      paths.zip(annotations).foreach{ case (path, anno) =>
        val fname = path.toString
        println(s"${fname}: Adult=${anno.getAdultValue} Racy=${anno.getRacyValue}")
        splitDir(fname, Rate.fromCV(anno))
      }
    }
  }

  def splitDir(fname: String, rate: Rate): Unit = {
    val file = Paths.get(fname)
    val destDir = rate.path(file.getParent)
    Files.mkdirs(destDir)
    Files.move(file, destDir.resolve(file.getFileName))
  }
}
