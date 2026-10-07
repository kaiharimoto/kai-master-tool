package com.kaiharimoto.neue.duel

actual fun duelStamp(ms: Long): String = java.text.SimpleDateFormat("d MMM, HH:mm").format(java.util.Date(ms))
