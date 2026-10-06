package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSProcessInfo

internal actual fun goldfishCores(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

internal actual fun <T> goldfishBlocking(block: suspend () -> T): T = runBlocking { block() }
