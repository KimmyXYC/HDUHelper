package moe.nepnep.hduhelper.data.campuscode

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class QrPixels(val size: Int, val pixels: IntArray) {
    override fun toString() = "QrPixels([redacted])"
}

object QrCodeEncoder {
    fun encode(content: String): QrPixels {
        val size = 768
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ))
        return QrPixels(size, IntArray(size * size) { index -> if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt() })
    }
}
