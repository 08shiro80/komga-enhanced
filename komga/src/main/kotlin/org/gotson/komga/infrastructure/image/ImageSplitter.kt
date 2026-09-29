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
  ): SplitResult {
    val image =
      ImageIO.read(imageBytes.inputStream())
        ?: throw IllegalArgumentException("Could not read image")

    val width = image.width
    val height = image.height

    if (height <= targetHeight) {
      logger.debug { "Image height ($height) <= target ($targetHeight), no split needed" }
      return SplitResult(format, listOf(imageBytes))
    }

    val outputFormat = resolveOutputFormat(format, image)
    val numParts = ceil(height.toDouble() / targetHeight).toInt()
    val evenHeight = ceil(height.toDouble() / numParts).toInt()
    logger.debug { "Splitting image ${width}x$height into $numParts parts (target: $targetHeight, even: $evenHeight)" }

    val result = mutableListOf<ByteArray>()

    for (i in 0 until numParts) {
      val startY = i * evenHeight
      val partHeight = minOf(evenHeight, height - startY)

      val subImage = image.getSubimage(0, startY, width, partHeight)

      ByteArrayOutputStream().use { baos ->
        val outputImage = BufferedImage(width, partHeight, outputImageType(outputFormat, image))
        outputImage.graphics.drawImage(subImage, 0, 0, null)

        if (!ImageIO.write(outputImage, outputFormat, baos)) {
          throw IllegalArgumentException("No ImageIO writer available for format '$outputFormat'")
        }
        result.add(baos.toByteArray())
      }

      logger.debug { "Created part ${i + 1}/$numParts: ${width}x$partHeight" }
    }

    return SplitResult(outputFormat, result)
  }

  fun splitWideImage(
    imageBytes: ByteArray,
    targetWidth: Int,
    format: String = "png",
  ): SplitResult {
    val image =
      ImageIO.read(imageBytes.inputStream())
        ?: throw IllegalArgumentException("Could not read image")

    val width = image.width
    val height = image.height

    if (width <= targetWidth) {
      logger.debug { "Image width ($width) <= target ($targetWidth), no split needed" }
      return SplitResult(format, listOf(imageBytes))
    }

    val outputFormat = resolveOutputFormat(format, image)
    val halfWidth = ceil(width.toDouble() / 2).toInt()
    logger.debug { "Splitting double page ${width}x$height in half ($halfWidth + ${width - halfWidth})" }

    val result = mutableListOf<ByteArray>()

    for (i in 0 until 2) {
      val startX = i * halfWidth
      val partWidth = minOf(halfWidth, width - startX)

      val subImage = image.getSubimage(startX, 0, partWidth, height)

      ByteArrayOutputStream().use { baos ->
        val outputImage = BufferedImage(partWidth, height, outputImageType(outputFormat, image))
        outputImage.graphics.drawImage(subImage, 0, 0, null)

        if (!ImageIO.write(outputImage, outputFormat, baos)) {
          throw IllegalArgumentException("No ImageIO writer available for format '$outputFormat'")
        }
        result.add(baos.toByteArray())
      }

      logger.debug { "Created part ${i + 1}/2: ${partWidth}x$height" }
    }

    return SplitResult(outputFormat, result)
  }

  // Source formats webp/jxl are reader-only in this runtime (no ImageWriterSpi) — writing them back throws.
  // Fall back to a writable format: PNG when the image carries alpha, otherwise JPEG (smaller, fine for the
  // opaque webtoon pages that dominate tall-image splits). Formats with a writer (png/jpg/gif/…) pass through.
  private fun resolveOutputFormat(
    requested: String,
    image: BufferedImage,
  ): String {
    if (ImageIO.getImageWritersByFormatName(requested).hasNext()) return requested
    return if (image.colorModel.hasAlpha()) "png" else "jpg"
  }

  private fun outputImageType(
    format: String,
    image: BufferedImage,
  ): Int =
    when {
      format == "jpg" || format == "jpeg" -> BufferedImage.TYPE_INT_RGB
      image.type != BufferedImage.TYPE_CUSTOM -> image.type
      image.colorModel.hasAlpha() -> BufferedImage.TYPE_INT_ARGB
      else -> BufferedImage.TYPE_INT_RGB
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

data class SplitResult(
  val format: String,
  val parts: List<ByteArray>,
)
