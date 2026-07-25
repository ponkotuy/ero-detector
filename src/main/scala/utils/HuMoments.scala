package utils

import java.awt.image.BufferedImage
import java.nio.file.{Path => JPath}

import javax.imageio.ImageIO

import scala.util.control.NonFatal

/**
 * OpenCV の `cv2.moments` / `cv2.HuMoments` と同じ定義の Hu 不変モーメント。
 *
 * 画素値は `cv2.imread` と一致するように読み込む。ImageIO が返す BufferedImage を
 * そのまま `getRGB` で読むと TYPE_BYTE_GRAY などで sRGB 変換が挟まって値がずれるため、
 * アルファを持たない画像は TYPE_3BYTE_BGR へ描き直してから読む。
 * アルファを持つ画像は `cv2.imread` がアルファを捨てるので `getRGB` の値がそのまま一致する。
 */
object HuMoments {
  ImageIO.scanForPlugins()

  /** 1チャンネルあたりの Hu モーメントの個数。 */
  val Size = 7

  private val ShiftBgr = Array(0, 8, 16)

  /** 読み込めない画像は None を返す。 */
  def ofImage(path: JPath): Option[ColorHuMoment] =
    readBgr(path).map { image =>
      val width = image.getWidth
      val height = image.getHeight
      val accumulators = Array.fill(3)(new MomentAccumulator)
      val row = new Array[Int](width)

      var y = 0
      while(y < height) {
        image.getRGB(0, y, width, 1, row, 0, width)
        var channel = 0
        while(channel < 3) {
          accumulators(channel).addRow(row, width, y, ShiftBgr(channel))
          channel += 1
        }
        y += 1
      }

      ColorHuMoment(path, accumulators.map(_.huMoments))
    }

  private def readBgr(path: JPath): Option[BufferedImage] =
    try {
      Option(ImageIO.read(path.toFile)).map { source =>
        if(source.getColorModel.hasAlpha) source
        else
          source.getType match {
            case BufferedImage.TYPE_3BYTE_BGR | BufferedImage.TYPE_INT_RGB | BufferedImage.TYPE_INT_BGR => source
            case _ => toThreeByteBgr(source)
          }
      }
    } catch {
      case NonFatal(_) => None
    }

  private def toThreeByteBgr(source: BufferedImage): BufferedImage = {
    val image = new BufferedImage(source.getWidth, source.getHeight, BufferedImage.TYPE_3BYTE_BGR)
    val g = image.createGraphics()
    try {
      g.drawImage(source, 0, 0, null)
    } finally {
      g.dispose()
    }
    image
  }
}

/** BGR 各チャンネルの Hu モーメント。`bgr` は B, G, R の順に7要素ずつ。 */
case class ColorHuMoment(path: JPath, bgr: Array[Array[Double]]) {

  /** ソートキー。Bチャンネルの第1 Hu モーメント。 */
  def first: Double = bgr(0)(0)

  /** チャンネルごとの差分ベクトルのL2ノルムの合計。 */
  def diff(other: ColorHuMoment): Double = {
    var sum = 0.0
    var channel = 0
    while(channel < 3) {
      val mine = bgr(channel)
      val theirs = other.bgr(channel)
      var squared = 0.0
      var i = 0
      while(i < HuMoments.Size) {
        val d = mine(i) - theirs(i)
        squared += d * d
        i += 1
      }
      sum += Math.sqrt(squared)
      channel += 1
    }
    sum
  }
}

/** 1チャンネル分の生モーメントを行ごとに積算する。 */
private class MomentAccumulator {
  private var m00 = 0.0
  private var m10 = 0.0
  private var m01 = 0.0
  private var m11 = 0.0
  private var m20 = 0.0
  private var m02 = 0.0
  private var m30 = 0.0
  private var m03 = 0.0
  private var m21 = 0.0
  private var m12 = 0.0

  def addRow(row: Array[Int], width: Int, y: Int, shift: Int): Unit = {
    var s0 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0

    var x = 0
    while(x < width) {
      val value = ((row(x) >> shift) & 0xff).toDouble
      val dx = x.toDouble
      s0 += value
      s1 += value * dx
      s2 += value * dx * dx
      s3 += value * dx * dx * dx
      x += 1
    }

    val dy = y.toDouble
    m00 += s0
    m10 += s1
    m20 += s2
    m30 += s3
    m01 += s0 * dy
    m02 += s0 * dy * dy
    m03 += s0 * dy * dy * dy
    m11 += s1 * dy
    m21 += s2 * dy
    m12 += s1 * dy * dy
  }

  /** 中心モーメント→正規化モーメント→Hu 不変量。 */
  def huMoments: Array[Double] = {
    if(m00 == 0.0) {
      return new Array[Double](HuMoments.Size)
    }

    val cx = m10 / m00
    val cy = m01 / m00
    val mu20 = m20 - cx * m10
    val mu02 = m02 - cy * m01
    val mu11 = m11 - cx * m01
    val mu30 = m30 - 3 * cx * m20 + 2 * cx * cx * m10
    val mu03 = m03 - 3 * cy * m02 + 2 * cy * cy * m01
    val mu21 = m21 - 2 * cx * m11 - cy * m20 + 2 * cx * cx * m01
    val mu12 = m12 - 2 * cy * m11 - cx * m02 + 2 * cy * cy * m10

    val inverseSquared = 1.0 / (m00 * m00)
    val inverseSesqui = 1.0 / Math.pow(m00, 2.5)
    val n20 = mu20 * inverseSquared
    val n02 = mu02 * inverseSquared
    val n11 = mu11 * inverseSquared
    val n30 = mu30 * inverseSesqui
    val n03 = mu03 * inverseSesqui
    val n21 = mu21 * inverseSesqui
    val n12 = mu12 * inverseSesqui

    val a = n30 + n12
    val b = n21 + n03
    val c = n30 - 3 * n12
    val d = 3 * n21 - n03

    Array(
      n20 + n02,
      (n20 - n02) * (n20 - n02) + 4 * n11 * n11,
      c * c + d * d,
      a * a + b * b,
      c * a * (a * a - 3 * b * b) + d * b * (3 * a * a - b * b),
      (n20 - n02) * (a * a - b * b) + 4 * n11 * a * b,
      d * a * (a * a - 3 * b * b) - c * b * (3 * a * a - b * b)
    )
  }
}
