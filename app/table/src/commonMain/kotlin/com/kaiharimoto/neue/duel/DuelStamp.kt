package com.kaiharimoto.neue.duel

/** [ms] as a replay's default name dates it, `7 Oct, 14:05`, in the person's own time zone. */
expect fun duelStamp(ms: Long): String
