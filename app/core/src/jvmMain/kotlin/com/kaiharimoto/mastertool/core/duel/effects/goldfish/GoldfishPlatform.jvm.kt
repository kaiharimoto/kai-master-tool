package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import kotlinx.coroutines.runBlocking

internal actual fun goldfishCores(): Int = Runtime.getRuntime().availableProcessors()

internal actual fun <T> goldfishBlocking(block: suspend () -> T): T = runBlocking { block() }
