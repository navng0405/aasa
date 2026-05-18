package com.aasa.eldercare.data.seeder

import com.aasa.eldercare.data.AppDatabase
import com.aasa.eldercare.data.entity.MedicationEntity
import com.aasa.eldercare.data.entity.MemoryEntity
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.entity.TrustedRelationshipTypes

/**
 * Seeds the database with the minimum data demos rely on:
 *  - a "Metformin" medication for a diabetes care routine
 *  - "Priya" as the primary trusted contact
 *  - one favorite-music memory
 *
 * Seeding is idempotent – running it on every cold start is safe.
 */
object DemoDataSeeder {

    private const val DEMO_MEDICATION_NAME = "Metformin"
    private const val LEGACY_DEMO_MEDICATION_NAME = "BP tablet"
    private const val DEMO_CONTACT_NAME = "Priya"
    private const val DEMO_NEIGHBOR_HELPER_NAME = "Mrs Wong"
    private const val MEMORY_TYPE_FAVORITE_MUSIC = "FAVORITE_MUSIC"

    suspend fun seed(database: AppDatabase) {
        seedMedication(database)
        seedTrustedContact(database)
        seedFavoriteMusicMemory(database)
    }

    /**
     * Wipe every table and reapply the demo seed. Used by the
     * "Reset demo data" button on the home screen.
     */
    suspend fun reset(database: AppDatabase) {
        database.clearAllTables()
        seed(database)
    }

    private suspend fun seedMedication(database: AppDatabase) {
        val dao = database.medicationDao()
        dao.deleteMedicationByName(LEGACY_DEMO_MEDICATION_NAME.lowercase())
        if (dao.findMedicationByName(DEMO_MEDICATION_NAME.lowercase()) != null) return
        dao.insertMedication(
            MedicationEntity(
                name = DEMO_MEDICATION_NAME,
                dosage = "500 mg tablet",
                scheduleTime = "08:00 AM"
            )
        )
    }

    private suspend fun seedTrustedContact(database: AppDatabase) {
        val dao = database.trustedContactDao()
        val primary = dao.findByName(DEMO_CONTACT_NAME.lowercase())
            ?: run {
                val id = dao.insertContact(
                    TrustedContactEntity(
                        name = DEMO_CONTACT_NAME,
                        relationship = "Daughter",
                        phoneNumber = "+91-9000000001",
                        isPrimary = true,
                        relationshipType = TrustedRelationshipTypes.CARE_RECIPIENT
                    )
                )
                TrustedContactEntity(
                    id = id,
                    name = DEMO_CONTACT_NAME,
                    relationship = "Daughter",
                    phoneNumber = "+91-9000000001",
                    isPrimary = true,
                    relationshipType = TrustedRelationshipTypes.CARE_RECIPIENT
                )
            }
        if (dao.findByName(DEMO_NEIGHBOR_HELPER_NAME.lowercase()) == null) {
            dao.insertContact(
                TrustedContactEntity(
                    name = DEMO_NEIGHBOR_HELPER_NAME,
                    relationship = "Neighbor helper",
                    phoneNumber = "+91-9000000002",
                    isPrimary = false,
                    relationshipType = TrustedRelationshipTypes.CARE_PROVIDER,
                    pairedContactId = primary.id
                )
            )
        }
    }

    private suspend fun seedFavoriteMusicMemory(database: AppDatabase) {
        val dao = database.memoryDao()
        if (dao.findMemoriesByType(MEMORY_TYPE_FAVORITE_MUSIC).isNotEmpty()) return
        dao.insertMemory(
            MemoryEntity(
                type = MEMORY_TYPE_FAVORITE_MUSIC,
                title = "Favorite music",
                value = "Old Hindi songs from the 1970s"
            )
        )
    }
}
