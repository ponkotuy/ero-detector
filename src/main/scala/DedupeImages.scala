import java.io.InputStream
import java.nio.file.{Path => JPath, Paths}
import java.util.concurrent.Executors

import cats.syntax.all._
import com.monovore.decline.{CommandApp, Opts}
import utils.{ColorHuMoment, Files, HuMoments}

import scala.io.Source

object DedupeImages extends CommandApp(
  name = "DedupeImages",
  header = "Remove duplicated images by color Hu moments.",
  main = DedupeImagesCli.opts.map(DedupeImagesRunner.run)
)

object DedupeImagesCli {
  val opts: Opts[DedupeImagesConfig] = {
    val threadCount =
      Opts
        .option[Int](
          "threads",
          help = "Number of worker threads.",
          short = "t"
        )
        .withDefault(Runtime.getRuntime.availableProcessors)
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
          help = "Print duplicated images without removing them."
        )
        .orFalse

    val files =
      Opts.arguments[String](metavar = "file").orEmpty

    (threadCount, readStdin, dryRun, files).mapN(DedupeImagesConfig.apply)
  }
}

case class DedupeImagesConfig(
  threadCount: Int,
  readStdin: Boolean,
  dryRun: Boolean,
  files: Seq[String]
)

object DedupeImagesRunner {

  /** この値未満の差分を重複とみなす。 */
  val Threshold = 1.0e-9

  def readFiles(source: InputStream): Seq[String] = {
    Source.fromInputStream(source).mkString.split("\\s+").filter(_.nonEmpty).toVector
  }

  def run(config: DedupeImagesConfig): Unit = {
    val stdinFiles =
      if(config.readStdin || config.files.isEmpty) readFiles(System.in)
      else Seq.empty
    val files = stdinFiles ++ config.files

    if(files.isEmpty) {
      System.err.println("No image files were provided.")
      sys.exit(1)
    }

    val paths = files.map(Paths.get(_)).filter(Files.isRegularFile(_))
    val moments = calculate(paths, config.threadCount).sortBy(_.first)

    val duplicated = moments.zip(moments.drop(1)).flatMap { case (kept, candidate) =>
      val diff = kept.diff(candidate)
      if(diff < Threshold) {
        println(s"${kept.path.getFileName} ${candidate.path.getFileName} ${diff}")
        Some(candidate.path)
      } else {
        None
      }
    }

    if(!config.dryRun) {
      duplicated.foreach(Files.delete)
    }
  }

  /** 読み込めない画像は存在しないものとして除外する。 */
  private def calculate(paths: Seq[JPath], threadCount: Int): Seq[ColorHuMoment] = {
    val pool = Executors.newFixedThreadPool(threadCount)
    try {
      val futures = paths.map { path =>
        pool.submit[Option[ColorHuMoment]](() => HuMoments.ofImage(path))
      }
      futures.flatMap(_.get())
    } finally {
      pool.shutdown()
    }
  }
}
