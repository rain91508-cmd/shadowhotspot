package com.shadowhotspot.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/** Renders `text` into a QR-code [Bitmap], or null if encoding fails. */
object QrGenerator {
    fun encode(text: String, size: Int = 512): Bitmap? {
        return runCatching {
            val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }
}
