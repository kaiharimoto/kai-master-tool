package com.kaiharimoto.neue.kit

import androidx.compose.runtime.Composable

/** Whether a soft keyboard is up: always false on the desk, which has none. */
@Composable
expect fun softKeyboardVisible(): Boolean
