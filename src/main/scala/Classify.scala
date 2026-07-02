import java.io.InputStream
import java.awt.{Color, RenderingHints}
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.file.{Path => JPath, Paths}
import java.util
import java.util.concurrent.atomic.AtomicReference

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession, TensorInfo}
import cats.data.ValidatedNel
import cats.syntax.all._
import com.monovore.decline.{Argument, CommandApp, Opts}
import javax.imageio.ImageIO
import labels.Rate
import utils.{Files, MyCloudVision, ThreadSafeQueue}

import scala.io.Source
import scala.util.control.NonFatal

object Classify extends CommandApp(
  name = "Classify",
  header = "Classify images into rating directories.",
  main = ClassifyCli.opts.map(ClassifyRunner.run)
)

given rateArgument: Argument[Rate] with {
  override def read(string: String): ValidatedNel[String, Rate] =
    string.toLowerCase match {
      case "negative" => Rate.Negative.validNel
      case "neutral-nega" => Rate.NeutralNega.validNel
      case "neutral" => Rate.Neutral.validNel
      case "neutral-posi" => Rate.NeutralPosi.validNel
      case "positive" => Rate.Positive.validNel
      case _ => s"Unknown rate '$string'. Available: negative, neutral-nega, neutral, neutral-posi, positive".invalidNel
    }

  override def defaultMetavar: String = "rate"
}

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

    val dryRun =
      Opts
        .flag(
          "dry-run",
          help = "Print classification results without moving image files."
        )
        .orFalse

    val files =
      Opts.arguments[String](metavar = "file").orEmpty

    val wdModel =
      Opts
        .option[String](
          "wd-model",
          help = "Path to SmilingWolf/wd-vit-tagger-v3 model.onnx."
        )
        .map(Paths.get(_))
        .withDefault(Paths.get("models/wd-vit-tagger-v3/model.onnx"))

    val wdTags =
      Opts
        .option[String](
          "wd-tags",
          help = "Path to SmilingWolf/wd-vit-tagger-v3 selected_tags.csv."
        )
        .map(Paths.get(_))
        .withDefault(Paths.get("models/wd-vit-tagger-v3/selected_tags.csv"))

    (
      classifier,
      batchSize,
      threadCount,
      readStdin,
      dryRun,
      files,
      wdModel,
      wdTags
    ).mapN(ClassifyConfig.apply)
  }
}

case class ClassifyConfig(
  classifier: ClassifierBackend,
  batchSize: Int,
  threadCount: Int,
  readStdin: Boolean,
  dryRun: Boolean,
  files: Seq[String],
  wdModel: JPath,
  wdTags: JPath
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

    config.classifier.validate(config).foreach { error =>
      System.err.println(error)
      sys.exit(1)
    }

    val groups = files.grouped(config.batchSize).toVector
    val queue = ThreadSafeQueue(groups)
    val failure = new AtomicReference[Throwable](null)
    val threads = (1 to config.threadCount).map { idx =>
      new Thread(
        new ClassifyWorker(queue, () => config.classifier.create(config), config.dryRun, failure),
        s"classify-${config.classifier.name}-$idx"
      )
    }

    threads.foreach(_.start())
    threads.foreach(_.join())
    Option(failure.get()).foreach(throw _)
  }
}

enum ClassifierBackend(val name: String) {
  case Cv extends ClassifierBackend("cv")
  case Wd extends ClassifierBackend("wd")

  def create(config: ClassifyConfig): ImageClassifier =
    this match {
      case Cv => new CloudVisionClassifier
      case Wd => new WdVitTaggerClassifier(config.wdModel, config.wdTags, WdRateMapping)
    }

  def validate(config: ClassifyConfig): Option[String] =
    this match {
      case Cv => None
      case Wd =>
        val missing = Seq(config.wdModel, config.wdTags)
          .filterNot(path => Files.isRegularFile(path) && Files.isReadable(path))
        missing.headOption.map { path =>
          s"WD classifier requires readable model files. Missing or unreadable: ${path}. Run `sbt downloadWdModel` to download them."
        }
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

object WdRateMapping {
  def rateFor(prediction: WdPrediction): Rate = {
    prediction.rating match {
      case "general" => Rate.Negative
      case "explicit" => Rate.Positive
      case "sensitive" | "questionable" =>
        if(prediction.scoreOf("general") > prediction.scoreOf("explicit")) {
          Rate.NeutralNega
        } else {
          Rate.NeutralPosi
        }
      case _ => Rate.Negative
    }
  }
}

case class WdRatingIndex(name: String, index: Int)
case class WdPrediction(rating: String, score: Float, scores: Seq[(String, Float)]) {
  private lazy val scoresByName = scores.toMap

  def scoreOf(rating: String): Float = scoresByName.getOrElse(rating, 0.0f)
}

class WdVitTaggerClassifier(
  modelPath: JPath,
  tagsPath: JPath,
  rateMapping: WdRateMapping.type
) extends ImageClassifier {
  ImageIO.scanForPlugins()

  private val env = OrtEnvironment.getEnvironment
  private val session = env.createSession(modelPath.toString, new OrtSession.SessionOptions)
  private val inputName = session.getInputNames.iterator.next()
  private val outputName = session.getOutputNames.iterator.next()
  private val targetSize = readTargetSize()
  private val ratingIndexes = WdVitTaggerClassifier.loadRatingIndexes(tagsPath)

  override def classify(paths: Seq[JPath]): Seq[ClassifiedImage] = {
    if(paths.isEmpty) {
      return Seq.empty
    }

    val images = paths.flatMap { path =>
      WdVitTaggerClassifier.prepareImage(path, targetSize) match {
        case Some(image) => Some(path -> image)
        case None =>
          System.err.println(s"Skipped unreadable image: ${path}")
          None
      }
    }
    if(images.isEmpty) {
      return Seq.empty
    }

    val input = FloatBuffer.wrap(images.flatMap(_._2).toArray)
    val shape = Array(images.size.toLong, targetSize.toLong, targetSize.toLong, 3L)

    val tensor = OnnxTensor.createTensor(env, input, shape)
    try {
      val result = session.run(util.Map.of(inputName, tensor))
      try {
        val output = result.get(outputName).get.asInstanceOf[OnnxTensor].getValue.asInstanceOf[Array[Array[Float]]]
        images.map(_._1).zip(output).map { case (path, scores) =>
          val prediction = predictRating(scores)
          ClassifiedImage(
            path,
            rateMapping.rateFor(prediction),
            WdVitTaggerClassifier.formatDetails(prediction)
          )
        }
      } finally {
        result.close()
      }
    } finally {
      tensor.close()
    }
  }

  override def close(): Unit = session.close()

  private def readTargetSize(): Int = {
    val inputInfo = session.getInputInfo.get(inputName)
    val tensorInfo = inputInfo.getInfo.asInstanceOf[TensorInfo]
    val shape = tensorInfo.getShape
    shape(1).toInt
  }

  private def predictRating(scores: Array[Float]): WdPrediction = {
    val ratingScores = ratingIndexes.map { rating =>
      rating.name -> scores(rating.index)
    }
    val (rating, score) = ratingScores.maxBy(_._2)
    WdPrediction(rating, score, ratingScores)
  }
}

object WdVitTaggerClassifier {
  def loadRatingIndexes(tagsPath: JPath): Seq[WdRatingIndex] = {
    val source = Source.fromFile(tagsPath.toFile)
    val lines =
      try {
        source.getLines().drop(1).toVector
      } finally {
        source.close()
      }

    val indexes = lines.zipWithIndex.flatMap { case (line, index) =>
      val cols = line.split(",", -1)
      if(cols.length >= 3 && cols(2) == "9") Some(WdRatingIndex(cols(1), index))
      else None
    }

    if(indexes.isEmpty) {
      throw new IllegalArgumentException(s"No rating tags were found in ${tagsPath}")
    }

    indexes
  }

  def prepareImage(path: JPath, targetSize: Int): Option[Array[Float]] = {
    Option(ImageIO.read(path.toFile)).map { source =>
      val square = squareOnWhite(source)
      val resized =
        if(square.getWidth == targetSize) square
        else resize(square, targetSize)
      toBgrFloatArray(resized)
    }
  }

  private def squareOnWhite(source: BufferedImage): BufferedImage = {
    val maxDim = Math.max(source.getWidth, source.getHeight)
    val image = new BufferedImage(maxDim, maxDim, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      g.setColor(Color.WHITE)
      g.fillRect(0, 0, maxDim, maxDim)
      val x = (maxDim - source.getWidth) / 2
      val y = (maxDim - source.getHeight) / 2
      g.drawImage(source, x, y, null)
    } finally {
      g.dispose()
    }
    image
  }

  private def resize(source: BufferedImage, targetSize: Int): BufferedImage = {
    val image = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.drawImage(source, 0, 0, targetSize, targetSize, null)
    } finally {
      g.dispose()
    }
    image
  }

  private def toBgrFloatArray(image: BufferedImage): Array[Float] = {
    val values = new Array[Float](image.getWidth * image.getHeight * 3)
    var i = 0
    var y = 0
    while(y < image.getHeight) {
      var x = 0
      while(x < image.getWidth) {
        val rgb = image.getRGB(x, y)
        values(i) = (rgb & 0xff).toFloat
        values(i + 1) = ((rgb >> 8) & 0xff).toFloat
        values(i + 2) = ((rgb >> 16) & 0xff).toFloat
        i += 3
        x += 1
      }
      y += 1
    }
    values
  }

  def formatDetails(prediction: WdPrediction): String = {
    val allScores = Seq("general", "sensitive", "questionable", "explicit")
      .map { name => f"${name}=${prediction.scoreOf(name)}%.4f" }
      .mkString(" ")
    f"Rating=${prediction.rating} Score=${prediction.score}%.4f ${allScores}"
  }
}

class ClassifyWorker(
  queue: ThreadSafeQueue[Seq[String]],
  createClassifier: () => ImageClassifier,
  dryRun: Boolean,
  failure: AtomicReference[Throwable]
) extends Runnable {
  import ClassifyWorker._

  override def run(): Unit = {
    var classifier: ImageClassifier = null
    try {
      classifier = createClassifier()
      Iterator
        .continually {
          queue.poll().fold(false) { xs =>
            val paths = xs.map(Paths.get(_)).filter(Files.isRegularFile(_)).filter(Files.isReadable)
            classifier.classify(paths).foreach { classified =>
              println(s"${classified.path}: Rate=${classified.rate.name} ${classified.details}")
              if(!dryRun) {
                splitDir(classified.path, classified.rate)
              }
            }
            true
          }
        }
        .dropWhile(identity)
        .next()
    } catch {
      case NonFatal(error) => failure.compareAndSet(null, error)
    } finally {
      if(classifier != null) {
        classifier.close()
      }
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
