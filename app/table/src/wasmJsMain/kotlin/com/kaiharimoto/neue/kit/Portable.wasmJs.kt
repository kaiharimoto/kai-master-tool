package com.kaiharimoto.neue.kit

import com.kaiharimoto.mastertool.core.ai.text.ChatChart

internal actual fun <T> guarded(lock: Any, block: () -> T): T = block()

internal actual fun twoPlaces(value: Float): String = ChatChart.fixed(value.toDouble(), 2)
