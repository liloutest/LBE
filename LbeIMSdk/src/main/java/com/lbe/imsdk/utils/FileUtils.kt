package com.lbe.imsdk.utils

object FileUtils {

    fun isImage(mime: String): Boolean {
        return mime.contains("image",ignoreCase = true)
    }

    fun isGif(mime: String): Boolean {
        return mime.contains("gif",ignoreCase = true)
    }

}