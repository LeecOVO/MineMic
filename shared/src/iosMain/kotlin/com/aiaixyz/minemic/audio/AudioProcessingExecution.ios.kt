@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.aiaixyz.minemic.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_create

internal actual fun createAudioProcessingExecution(): AudioProcessingExecution = object : AudioProcessingExecution {
    private val queue = dispatch_queue_create("MineMic.audio-processing",
        dispatch_queue_attr_make_with_qos_class(null, QOS_CLASS_USER_INITIATED, 0))
    override val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatch_async(queue) { block.run() }
        }
    }
    // GCD owns the threads; the ARC-managed queue has no private thread to join.
    override fun close() {}
}
