package com.pcwake.app.ui

import androidx.compose.ui.graphics.Color
import com.pcwake.app.ui.theme.StatusChecking
import com.pcwake.app.ui.theme.StatusOffline
import com.pcwake.app.ui.theme.StatusOnline

fun statusColor(online: Boolean?): Color = when (online) {
    true -> StatusOnline
    false -> StatusOffline
    null -> StatusChecking
}

fun statusText(online: Boolean?): String = when (online) {
    true -> "Online"
    false -> "Offline"
    null -> "Checking..."
}
