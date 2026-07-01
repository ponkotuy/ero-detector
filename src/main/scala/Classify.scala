import java.io.InputStream
import java.nio.file.{Path => JPath, Paths}

import cats.data.ValidatedNel
import cats.syntax.all._
import com.monovore.decline.{Argument, CommandApp, Opts}
import labels.Rate
import utils.{Files, MyCloudVision, ThreadSafeQueue}

import scala.io.Source

object Classify extends CommandApp(
  name = "Classify",
  header = "Classify images into negative / neutral / positive directories.",
  main = ClassifyCli.opts.map(ClassifyRunner.run)
)

object ClassifyCli {
  val opts: Opts[ClassifyConfig] = {
    val classifier =
      Opts
        .option[ClassifierBackend](
          "classifier",
          help = "Classifier backend to use.",
          short = "c"
        )
        .withDefault(ClassifierBackend.Cv)

    val batchSize =
      Opts
        .option[Int](
          "batch-size",
          help = "Number of images to classify per request.",
          short = "b"
        )
        .withDefault(16)
        .validate("batch-size must be greater than 0")(_ > 0)

    val threadCount =
      Opts
        .option[Int](
          "threads",
          help = "Number of worker threads.",
          short = "t"
        )
        .withDefault(2)
        .validate("threads must be greater than 0")(_ > 0)

    val readStdin =
      Opts
        .flag(
          "stdin",
          help = "Read image paths from stdin. This is automatic when no files are provided."
        )
        .orFalse

    val files =
      Opts.arguments[String](metavar = "file").orEmpty

    (classifier, batchSize, threadCount, readStdin, files).mapN(ClassifyConfig.apply)
  }
}

case class ClassifyConfig(
  classifier: ClassifierBackend,
  batchSize: Int,
  threadCount: Int,
  readStdin: Boolean,
  files: Seq[String]
)

object ClassifyRunner {
  def readFiles(source: InputStream): Seq[String] = {
    Source.fromInputStream(source).mkString.split("\\s+").filter(_.nonEmpty).toVector
  }

  def run(config: ClassifyConfig): Unit = {
    val stdinFiles =
      if(config.readStdin || config.files.isEmpty) readFiles(System.in)
      else Seq.empty
    val files = stdinFiles ++ config.files

    if(files.isEmpty) {
      System.err.println("No image files were provided.")
      sys.exit(1)
    }

    val groups = files.grouped(config.batchSize).toVector
    val queue = ThreadSafeQueue(groups)
    val threads = (1 to config.threadCount).map { idx =>
      new Thread(
        new ClassifyWorker(queue, () => config.classifier.create),
        s"classify-${config.classifier.name}-$idx"
      )
    }

    threads.foreach(_.start())
    threads.foreach(_.join())
  }
}

enum ClassifierBackend(val name: String) {
  case Cv extends ClassifierBackend("cv")

  def create: ImageClassifier =
    this match {
      case Cv => new CloudVisionClassifier
    }
}

object ClassifierBackend {
  val valuesByName: Map[String, ClassifierBackend] =
    values.map(value => value.name -> value).toMap

  given Argument[ClassifierBackend] with {
    override def read(string: String): ValidatedNel[String, ClassifierBackend] =
      valuesByName
        .get(string.toLowerCase)
        .toValidNel(s"Unknown classifier '$string'. Available: ${valuesByName.keys.toSeq.sorted.mkString(", ")}")

    override def defaultMetavar: String = "classifier"
  }
}

trait ImageClassifier extends AutoCloseable {
  def classify(paths: Seq[JPath]): Seq[ClassifiedImage]

  override def close(): Unit = ()
}

case class ClassifiedImage(path: JPath, rate: Rate, details: String)

class CloudVisionClassifier extends ImageClassifier {
  private val client = new MyCloudVision

  override def classify(paths: Seq[JPath]): Seq[ClassifiedImage] = {
    val images = paths.flatMap { path =>
      MyCloudVision.loadImagePath(path).map(path -> _)
    }
    if(images.isEmpty) {
      return Seq.empty
    }

    val annotations = client.safeSearchDetections(images.map(_._2)*).toList

    images.map(_._1).zip(annotations).map { case (path, anno) =>
      ClassifiedImage(
        path,
        Rate.fromCV(anno),
        s"Adult=${anno.getAdultValue} Racy=${anno.getRacyValue}"
      )
    }
  }

  override def close(): Unit = client.close()
}

class ClassifyWorker(
  queue: ThreadSafeQueue[Seq[String]],
  createClassifier: () => ImageClassifier
) extends Runnable {
  import ClassifyWorker._

  override def run(): Unit = {
    val classifier = createClassifier()
    try {
      Iterator
        .continually {
          queue.poll().fold(false) { xs =>
            val paths = xs.map(Paths.get(_)).filter(Files.isRegularFile(_)).filter(Files.isReadable)
            classifier.classify(paths).foreach { classified =>
              println(s"${classified.path}: ${classified.details}")
              splitDir(classified.path, classified.rate)
            }
            true
          }
        }
        .dropWhile(identity)
        .next()
    } finally {
      classifier.close()
    }
  }
}

object ClassifyWorker {
  def splitDir(file: JPath, rate: Rate): Unit = {
    val destDir = rate.path(file.getParent)
    Files.mkdirs(destDir)
    Files.move(file, destDir.resolve(file.getFileName))
  }
}
