package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

/** Local-day proxy payload bytes in the current native process, independent of profile reloads. */
@Serializable
data class ProxyUsage(val wifi: Long = 0, val mobile: Long = 0, val day: String = "")
