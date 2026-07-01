import java.io.{BufferedInputStream, File}
import java.net.URI
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest

import sbt._

object WdModelDownload {
  val downloadWdModel = taskKey[Unit]("Download SmilingWolf/wd-vit-tagger-v3 model files")

  def run(baseDirectory: File, log: Logger): Unit = {
    val modelDir = baseDirectory / "models" / "wd-vit-tagger-v3"
    val files = Seq(
      (
        "model.onnx",
        "https://huggingface.co/SmilingWolf/wd-vit-tagger-v3/resolve/main/model.onnx",
        Some("35f23693620b668f4d53fd3c62bf65e40af739bc52c7eb0fbc49258b58d065b6")
      ),
      (
        "selected_tags.csv",
        "https://huggingface.co/SmilingWolf/wd-vit-tagger-v3/resolve/main/selected_tags.csv",
        None
      )
    )

    IO.createDirectory(modelDir)
    files.foreach { case (name, url, expectedSha256) =>
      val dest = modelDir / name
      if(dest.isFile && expectedSha256.forall(expected => sha256(dest) == expected)) {
        log.info(s"${dest} already exists")
      } else {
        val tmp = modelDir / s"${name}.part"
        log.info(s"Downloading ${url}")
        download(url, tmp)
        expectedSha256.foreach { expected =>
          val actual = sha256(tmp)
          if(actual != expected) {
            IO.delete(tmp)
            sys.error(s"Checksum mismatch for ${name}: expected ${expected}, got ${actual}")
          }
        }
        Files.move(tmp.toPath, dest.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info(s"Wrote ${dest}")
      }
    }
  }

  private def download(url: String, dest: File): Unit = {
    val in = new BufferedInputStream(URI.create(url).toURL.openStream())
    try {
      Files.copy(in, dest.toPath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      in.close()
    }
  }

  private def sha256(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new BufferedInputStream(Files.newInputStream(file.toPath))
    val buffer = new Array[Byte](1024 * 1024)
    try {
      Iterator
        .continually(in.read(buffer))
        .takeWhile(_ != -1)
        .foreach(read => digest.update(buffer, 0, read))
    } finally {
      in.close()
    }
    digest.digest().map(byte => "%02x".format(byte & 0xff)).mkString
  }
}
