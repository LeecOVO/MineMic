@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.aiaixyz.minemic
import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
fun bytesFromData(data: NSData): ByteArray = ByteArray(data.length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
}
fun dataFromBytes(bytes: ByteArray): NSData = if (bytes.isEmpty()) NSData() else
    bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
