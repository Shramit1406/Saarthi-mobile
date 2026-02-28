package com.shramit.saarthi.models

import java.util.UUID

data class Police(
    val id: String = "POLICE_" + UUID.randomUUID().toString().substring(0, 8),
    val stationName: String,
    val stationId: String,
    val deviceId: String,
    val jurisdiction: String,
    val meshNodeId: String = id,         // Mesh identity
    val meshRole: Byte = 0x01,           // 0x01 = police role in mesh
    val isActive: Boolean = true
)