package org.gotson.komga.infrastructure.image

import io.github.oshai.kotlinlogging.KotlinLogging
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.util.exif.ExifFilterUtils
import net.coobird.thumbnailator.util.exif.ExifUtils
import net.coobird.thumbnailator.util.exif.Orientation
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

private const val WEBP_NIGHT_MONKEYS = "com.github.gotson.nightmonkeys.webp.imageio.plugins.WebpImageReaderSpi"

@Service
class ImageConverter(
  private val imageAnalyzer: ImageAnalyzer,
  private val contentDetector: ContentDetector,
) {
  companion object {
    private const val MAX_PARALLEL_DECODES = 2

    private val decodeSemaphore = Semaphore(MAX_PARALLEL_DECODES)
  }

  val supportedReadFormats by lazy { ImageIO.getReaderFormatNames().toList() }
  val supportedReadMediaTypes by lazy { ImageIO.getReaderMIMETypes().toList() }
  val supportedWriteFormats by lazy { ImageIO.getWriterFormatNames().toList() }
  val supportedWriteMediaTypes by lazy { ImageIO.getWriterMIMETypes().toList() }

  init {
    chooseWebpReader()
    logger.info { "Supported read formats: $supportedReadFormats" }
    logger.info { "Supported read mediaTypes: $supportedReadMediaTypes" }
    logger.info { "Supported write formats: $supportedWriteFormats" }
    logger.info { "Supported write mediaTypes: $supportedWriteMediaTypes" }
  }

  private fun chooseWebpReader() {
    val providers =
      IIORegistry
        .getDefaultInstance()
        .getServiceProviders(
          ImageReaderSpi::class.java,
          { it is ImageReaderSpi && it.mimeTypes.contains("image/webp") },
          false,
        ).asSequence()
        .toList()

    if (providers.size > 1) {
      logger.debug { "WebP reader providers: ${providers.map { it.javaClass.canonicalName }}" }
      providers.firstOrNull { it.javaClass.canonicalName == WEBP_NIGHT_MONKEYS }?.let { nightMonkeys ->
        (providers - nightMonkeys).forEach {
          logger.debug { "Deregister provider: ${it.javaClass.canonicalName}" }
          IIORegistry.getDefaultInstance().deregisterServiceProvider(it)
        }
      }
    }
  }

  private val supportsTransparency = listOf("png")

  fun canConvertMediaType(
    from: String,
    to: String,
  ) = supportedReadMediaTypes.contains(from) && supportedWriteMediaTypes.contains(to)

  fun convertImage(
    imageBytes: ByteArray,
    format: String,
  ): ByteArray =
    ByteArrayOutputStream().use { baos ->
      val image = ImageIO.read(imageBytes.inputStream())

      val result =
        if (!supportsTransparency.contains(format) && containsAlphaChannel(image)) {
          if (containsTransparency(image))
            logger.info { "Image contains alpha channel but is not opaque, visual artifacts may appear" }
          else
            logger.info { "Image contains alpha channel but is opaque, conversion should not generate any visual artifacts" }
          BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().drawImage(image, 0, 0, Color.WHITE, null)
          }
        } else {
          image
        }

      ImageIO.write(result, format, baos)

      baos.toByteArray()
    }

  fun resizeImageToByteArray(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): ByteArray {
    decodeSemaphore.acquire()
    try {
      val builder = resizeImageBuilder(imageBytes, format, size) ?: return imageBytes

      return ByteArrayOutputStream().use {
        builder.toOutputStream(it)
        it.toByteArray()
      }
    } finally {
      decodeSemaphore.release()
    }
  }

  fun resizeImageToBufferedImage(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): BufferedImage {
    decodeSemaphore.acquire()
    try {
      val builder = resizeImageBuilder(imageBytes, format, size) ?: return ImageIO.read(imageBytes.inputStream())

      return builder.asBufferedImage()
    } finally {
      decodeSemaphore.release()
    }
  }

  private fun resizeImageBuilder(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): Thumbnails.Builder<out InputStream>? {
    val dimension = imageAnalyzer.getDimension(imageBytes.inputStream())
    val longestEdge =
      dimension?.let {
        val longestEdge = max(it.height, it.width)
        if (longestEdge <= size) {
          val mediaType = contentDetector.detectMediaType(imageBytes.inputStream())
          if (mediaType == format.mediaType) return null
        }
        longestEdge
      }

    val resizeTo = if (longestEdge != null) min(longestEdge, size) else size

    val sourceBytes =
      if (dimension != null && supportedWriteFormats.contains(format.imageIOFormat))
        decodeSubsampled(imageBytes, max(dimension.width, dimension.height), resizeTo)
          ?.let { bufferedImageToBytes(it, format) }
          ?: imageBytes
      else
        imageBytes

    return Thumbnails
      .of(sourceBytes.inputStream())
      .size(resizeTo, resizeTo)
      .imageType(BufferedImage.TYPE_INT_ARGB)
      .outputFormat(format.imageIOFormat)
  }

  private fun bufferedImageToBytes(
    image: BufferedImage,
    format: ImageType,
  ): ByteArray? {
    val baos = ByteArrayOutputStream()
    val written = ImageIO.write(image, format.imageIOFormat, baos)
    return if (written && baos.size() > 0) baos.toByteArray() else null
  }

  private fun decodeSubsampled(
    imageBytes: ByteArray,
    sourceLongestEdge: Int,
    targetSize: Int,
  ): BufferedImage? {
    if (sourceLongestEdge <= targetSize * 2) return null
    return try {
      ImageIO.createImageInputStream(imageBytes.inputStream()).use { ciis ->
        val readers = ImageIO.getImageReaders(ciis)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
          reader.input = ciis
          val factor = max(1, sourceLongestEdge / (targetSize * 2))
          if (factor < 2) return null
          val orientation =
            try {
              ExifUtils.getExifOrientation(reader, 0)
            } catch (e: Exception) {
              logger.debug(e) { "Could not read EXIF orientation, assuming none" }
              null
            }
          val param = reader.defaultReadParam
          param.setSourceSubsampling(factor, factor, 0, 0)
          val image = reader.read(0, param)
          if (orientation != null && orientation != Orientation.TOP_LEFT) {
            ExifFilterUtils.getFilterForOrientation(orientation).apply(image)
          } else {
            image
          }
        } finally {
          reader.dispose()
        }
      }
    } catch (e: java.io.IOException) {
      logger.debug(e) { "Subsampled decode failed, falling back to full decode" }
      null
    }
  }

  private fun containsAlphaChannel(image: BufferedImage): Boolean = image.colorModel.hasAlpha()

  private fun containsTransparency(image: BufferedImage): Boolean {
    val alphaRaster = image.alphaRaster ?: return false
    val pixel = IntArray(1)
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        alphaRaster.getPixel(x, y, pixel)
        if (pixel[0] == 0) return true
      }
    }
    return false
  }
}
