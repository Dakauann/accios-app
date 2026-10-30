package com.gdreducacional.totemapp.pickup

enum class OperationMode { STANDARD, PICKUP }

data class PickupStudent(
    val enrollmentId: String,
    val studentId: String,
    val name: String,
    val className: String,
    val photoBase64: String?,
    val validity: String,
    val validDate: String?
)

data class PickupMatch(
    val personId: String,
    val entityType: String,
    val personName: String,
    val confidence: Double,
    val snapshotId: String,
    val students: List<PickupStudent>,
    val selectedEnrollmentIds: Set<String> = emptySet()
)
