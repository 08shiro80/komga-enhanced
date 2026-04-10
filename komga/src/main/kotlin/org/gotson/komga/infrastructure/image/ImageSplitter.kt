package org.gotson.komga.infrastructure.image

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}

/**
 * Service for splitting tall images into multiple pages.
 * Similar to TachiyomiSY's "split tall images" feature.
 */
@Service
class ImageSplitter(
  private val imageAnalyzer: ImageAnalyzer,
) {
  /**
   * Splits a tall image into multiple parts based on the target height.
   *
   * @param imageBytes The original image bytes
   * @param targetHeight The maximum height for each split part
   * @param format The output format (e.g., "png", "jpg")
   * @return List of byte arrays, each representing a split image part
   */
  fun splitTallImage(
    imageBytes: ByteArray,
    targetHeight: Int,
    format: String = "png",
  ): List<ByteArray> {
    val image =
      ImageIO.read(imageBytes.inputStream())
        ?: throw IllegalArgumentException("Could not read image")

    val width = image.width
    val height = image.height

    if (height <= targetHeight) {
      logger.debug { "Image height ($height) <= target ($targetHeight), no split needed" }
      return listOf(imageBytes)
    }

    val numParts = ceil(height.toDouble() / targetHeight).toInt()
    logger.debug { "Splitting image ${width}x$height into $numParts parts (target height: $targetHeight)" }

    val result = mutableListOf<ByteArray>()

    for (i in 0 until numParts) {
      val startY = i * targetHeight
      val partHeight = minOf(targetHeight, height - startY)

      val subImage = image.getSubimage(0, startY, width, partHeight)

      ByteArrayOutputStream().use { baos ->
        val outputImage = BufferedImage(width, partHeight, image.type)
        outputImage.graphics.drawImage(subImage, 0, 0, null)

        ImageIO.write(outputImage, format, baos)
        result.add(baos.toByteArray())
      }

      logger.debug { "Created part ${i + 1}/$numParts: ${width}x$partHeight" }
    }

    return result
  }

  fun splitWideImage(
    imageBytes: ByteArray,
    targetWidth: Int,
    format: String = "png",
  ): List<ByteArray> {
    val image =
      ImageIO.read(imageBytes.inputStream())
        ?: throw IllegalArgumentException("Could not read image")

    val width = image.width
    val height = image.height

    if (width <= targetWidth) {
      logger.debug { "Image width ($width) <= target ($targetWidth), no split needed" }
      return listOf(imageBytes)
    }

    val numParts = ceil(width.toDouble() / targetWidth).toInt()
    logger.debug { "Splitting image ${width}x$height into $numParts horizontal parts (target width: $targetWidth)" }

    val result = mutableListOf<ByteArray>()

    for (i in 0 until numParts) {
      val startX = i * targetWidth
      val partWidth = minOf(targetWidth, width - startX)

      val subImage = image.getSubimage(startX, 0, partWidth, height)

      ByteArrayOutputStream().use { baos ->
        val outputImage = BufferedImage(partWidth, height, image.type)
        outputImage.graphics.drawImage(subImage, 0, 0, null)

        ImageIO.write(outputImage, format, baos)
        result.add(baos.toByteArray())
      }

      logger.debug { "Created part ${i + 1}/$numParts: ${partWidth}x$height" }
    }

    return result
  }

  /**
   * Checks if an image should be split based on aspect ratio.
   *
   * @param imageStream The image input stream
   * @param maxAspectRatio The maximum height/width ratio before considering split (default 2.0)
   * @return True if the image is considered "tall" and should be split
   */
  fun shouldSplit(
    imageStream: InputStream,
    maxAspectRatio: Double = 2.0,
  ): Boolean {
    val dimension = imageAnalyzer.getDimension(imageStream) ?: return false
    val aspectRatio = dimension.height.toDouble() / dimension.width.toDouble()
    return aspectRatio > maxAspectRatio
  }

  /**
   * Gets information about how an image would be split.
   *
   * @param imageBytes The image bytes
   * @param targetHeight The target height for splitting
   * @return SplitInfo with details about the potential split
   */
  fun getSplitInfo(
    imageBytes: ByteArray,
    targetHeight: Int,
  ): SplitInfo {
    val dimension =
      imageAnalyzer.getDimension(imageBytes.inputStream())
        ?: return SplitInfo(0, 0, 0, 0)

    val width = dimension.width
    val height = dimension.height
    val numParts = if (height > targetHeight) ceil(height.toDouble() / targetHeight).toInt() else 1

    return SplitInfo(
      originalWidth = width,
      originalHeight = height,
      targetHeight = targetHeight,
      numberOfParts = numParts,
    )
  }
}

data class SplitInfo(
  val originalWidth: Int,
  val originalHeight: Int,
  val targetHeight: Int,
  val numberOfParts: Int,
)
