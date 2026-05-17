package com.aasa.eldercare.data.repository

import com.aasa.eldercare.data.dao.TrustedContactDao
import com.aasa.eldercare.data.entity.TrustedContactEntity
import com.aasa.eldercare.data.entity.TrustedRelationshipTypes
import kotlinx.coroutines.flow.Flow

class TrustedContactRepository(
    private val dao: TrustedContactDao
) {

    fun getAllContacts(): Flow<List<TrustedContactEntity>> = dao.getAllContacts()

    suspend fun findByName(name: String): TrustedContactEntity? =
        dao.findByName(name.trim().lowercase())

    suspend fun findPrimaryContact(): TrustedContactEntity? = dao.findPrimaryContact()

    suspend fun findPairedCareProviderFor(contactId: Long): TrustedContactEntity? {
        val contact = dao.findPairedContact(
            pairedContactId = contactId,
            relationshipType = TrustedRelationshipTypes.CARE_PROVIDER
        )
        return contact?.takeIf {
            it.providerConsentGranted && it.recipientConsentGranted
        }
    }

    suspend fun setProviderConsent(contactId: Long, granted: Boolean): Boolean {
        val current = dao.findById(contactId) ?: return false
        dao.insertContact(current.copy(providerConsentGranted = granted))
        return true
    }

    suspend fun setRecipientConsent(contactId: Long, granted: Boolean): Boolean {
        val current = dao.findById(contactId) ?: return false
        dao.insertContact(current.copy(recipientConsentGranted = granted))
        return true
    }

    suspend fun upsert(contact: TrustedContactEntity): Long = dao.insertContact(contact)

    /**
     * Convenience used by the agent: try to resolve the contact Gemma
     * mentioned, falling back to the primary contact when the elder
     * just says "call them".
     */
    suspend fun resolveContact(name: String?): TrustedContactEntity? {
        val byName = name?.takeIf { it.isNotBlank() }?.let { findByName(it) }
        return byName ?: findPrimaryContact()
    }
}
