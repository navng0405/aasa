package com.aasa.eldercare.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person in the elder's trusted circle (family, caregiver, doctor).
 * Exactly one contact may be marked [isPrimary] – that's the default
 * fallback when the agent isn't told *who* to call.
 */
@Entity(
    tableName = "trusted_contacts",
    indices = [Index(value = ["nameLower"], unique = true)]
)
data class TrustedContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relationship: String,
    val phoneNumber: String,
    val isPrimary: Boolean = false,
    val relationshipType: String = TrustedRelationshipTypes.CARE_RECIPIENT,
    val pairedContactId: Long? = null,
    val providerConsentGranted: Boolean = false,
    val recipientConsentGranted: Boolean = false,
    @ColumnInfo(name = "nameLower") val nameLower: String = name.lowercase()
)

object TrustedRelationshipTypes {
    const val CARE_RECIPIENT = "CARE_RECIPIENT"
    const val CARE_PROVIDER = "CARE_PROVIDER"
}
