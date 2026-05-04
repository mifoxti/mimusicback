package com.example.utils

import com.example.config.UPLOAD_IMAGE_MAX_EDGE_PX
import com.example.config.UPLOAD_IMAGE_MAX_PIXELS
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/**
 * Декодирует растр с проверкой габаритов по заголовку, затем перекодирует в **PNG** (без потерь для пикселей).
 * Не подходит для анимированных GIF как видео — читается первый кадр.
 */
object SafeImage {
    fun rasterToLosslessPngBytes(input: ByteArray, maxInputBytes: Int): ByteArray {
        require(input.size <= maxInputBytes)
        val iis: ImageInputStream = ImageIO.createImageInputStream(ByteArrayInputStream(input))
            ?: throw IllegalArgumentException("Не удалось открыть изображение")
        val readers = ImageIO.getImageReaders(iis)
        val reader = readers.asSequence().firstOrNull()
            ?: run {
                iis.close()
                throw IllegalArgumentException("Формат изображения не поддерживается")
            }
        reader.input = iis
        try {
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            if (w <= 0 || h <= 0) {
                throw IllegalArgumentException("Некорректный размер изображения")
            }
            if (w > UPLOAD_IMAGE_MAX_EDGE_PX || h > UPLOAD_IMAGE_MAX_EDGE_PX) {
                throw IllegalArgumentException(
                    "Изображение слишком большое (${w}×$h), максимум по стороне $UPLOAD_IMAGE_MAX_EDGE_PX px",
                )
            }
            val pixels = w.toLong() * h.toLong()
            if (pixels > UPLOAD_IMAGE_MAX_PIXELS) {
                throw IllegalArgumentException("Слишком много пикселей (лимит защиты от декомпрессионных атак)")
            }
            val img: BufferedImage = reader.read(0)
            val out = ByteArrayOutputStream(img.width * img.height / 4 + 1024)
            if (!ImageIO.write(img, "png", out)) {
                throw IllegalStateException("Не удалось записать PNG")
            }
            val png = out.toByteArray()
            if (png.size > maxInputBytes) {
                throw IllegalArgumentException(
                    "После безопасной конвертации PNG слишком большой (${png.size} байт), лимит $maxInputBytes",
                )
            }
            return png
        } finally {
            reader.dispose()
            iis.close()
        }
    }

    /** Проверка, что ImageIO умеет хоть один растровый reader для потока (без полного декода). */
    fun canProbeFormat(input: ByteArray): Boolean {
        val iis = ImageIO.createImageInputStream(ByteArrayInputStream(input)) ?: return false
        iis.use {
            return ImageIO.getImageReaders(iis).asSequence().any()
        }
    }
}
