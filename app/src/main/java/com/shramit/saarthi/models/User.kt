package com.shramit.saarthi.models

import java.util.UUID

data class User(
    val id: String = "USER_" + UUID.randomUUID().toString().substring(0, 8),
    val name: String = "Citizen",
    val deviceId: String,
    val meshNodeId: String = id,          // Used as mesh identity
    val isPolice: Boolean = false,        // Role in mesh network
    val emergencyContact: String = "100", // Police
    val createdAt: Long = System.currentTimeMillis()
)