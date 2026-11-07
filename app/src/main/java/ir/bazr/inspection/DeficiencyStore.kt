package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/**
 * Encrypted, offline-first corrective-action records.
 * Records are independent of inspection drafts. Server records are merged only after
 * mobile_sync returns them through the authenticated, assigned-mission boundary.
 */
class DeficiencyStore(private val secure: SecureStore) {
    companion object {
        private const val INDEX_KEY = "deficiency_index_v2"
        private const val PREFIX = "deficiency_record:"
        private const val MAX_RECORDS = 2000
    }

    private fun recordKey(id: String) = "$PREFIX$id"
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))

    @Synchronized
    private fun readIndex(): MutableList<String> {
        val saved = try { secure.loadStrict(INDEX_KEY)?.let(::JSONArray) } catch (_: Exception) { null }
        val ids = linkedSetOf<String>()
        if (saved != null) {
            for (i in 0 until saved.length()) {
                val id = saved.optString(i).trim()
                if (validId(id) && secure.hasEncryptedRecord(recordKey(id))) ids.add(id)
            }
        }
        // Recover committed records if the index write was interrupted. Existing records are never removed.
        secure.encryptedRecordNames(PREFIX).forEach { key ->
            key.removePrefix(PREFIX).takeIf(::validId)?.let(ids::add)
        }
        if (ids.size > MAX_RECORDS) throw IOException("تعداد سوابق نواقص از حد مجاز بیشتر است")
        val result = ids.toMutableList()
        val normalized = JSONArray(result)
        if (saved == null || (0 until saved.length()).map { saved.optString(it) } != result) {
            secure.saveDurable(INDEX_KEY, normalized.toString())
        }
        return result
    }

    private fun readRecord(id: String): JSONObject? {
        if (!validId(id)) return null
        val key = recordKey(id)
        if (!secure.hasEncryptedRecord(key)) return null
        val raw = secure.loadStrict(key) ?: return null
        return try {
            JSONObject(raw).also {
                if (it.optString("id") != id) throw IOException("شناسه رکورد نواقص با کلید ذخیره‌سازی تطابق ندارد")
            }
        } catch (error: Exception) {
            throw IOException("رکورد نقص قابل بازیابی نیست؛ اطلاعات اصلی حفظ شده است", error)
        }
    }

    @Synchronized
    fun entries(): List<JSONObject> {
        val ids = readIndex()
        return ids.mapNotNull { id ->
            try { readRecord(id) } catch (_: Exception) {
                JSONObject().put("id", id).put("integrity_error", true)
                    .put("organization", "رکورد نیازمند بازیابی").put("status", "integrity_error")
                    .put("sync_state", "blocked")
            }
        }.sortedByDescending { it.optString("updated_at", it.optString("created_at")) }
    }

    @Synchronized fun get(id: String): JSONObject? = readRecord(id)

    @Synchronized
    fun pendingEntries(): List<JSONObject> = entries().filter {
        !it.optBoolean("integrity_error") && it.optString("sync_state") in setOf("local_pending", "failed")
    }

    @Synchronized
    fun forMission(missionKey: String): List<JSONObject> =
        entries().filter { !it.optBoolean("integrity_error") && it.optString("mission_key") == missionKey }

    @Synchronized
    fun forQuestion(missionKey: String, questionId: String): List<JSONObject> =
        forMission(missionKey).filter { it.optString("question_id") == questionId }

    @Synchronized
    fun isEvidenceReferenced(reference: String): Boolean = entries().any { record ->
        val refs = record.optJSONArray("linked_evidence_refs") ?: JSONArray()
        (0 until refs.length()).any { refs.optString(it) == reference }
    }

    @Synchronized
    fun create(
        missionKey: String,
        organizationId: String,
        organization: String,
        questionId: String,
        questionIndex: Int,
        sectionId: String,
        questionTitle: String,
        description: String,
        severity: String,
        dueDateIso: String,
        dueDateJalali: String,
        actor: String,
        inspectorRef: String,
        linkedEvidenceRefs: List<String>
    ): JSONObject {
        require(missionKey.isNotBlank() && missionKey.length <= 190)
        require(organizationId.length <= 100)
        require(questionId.isNotBlank() && questionId.length <= 190)
        require(questionIndex >= 0)
        require(organization.isNotBlank() && organization.length <= 300)
        require(questionTitle.isNotBlank() && questionTitle.length <= 1500)
        require(sectionId.length <= 100)
        require(DeficiencyWorkflowRules.validateDescription(description) == null)
        require(severity in DeficiencyWorkflowRules.severities)
        require(DeficiencyWorkflowRules.validDueDate(dueDateJalali))
        require(!PersianCalendar.isBefore(dueDateJalali))
        require(linkedEvidenceRefs.size in 0..EvidenceRefs.MAX_PER_QUESTION)
        require(linkedEvidenceRefs.distinct().size == linkedEvidenceRefs.size)
        require(linkedEvidenceRefs.all { it.isNotBlank() && it.length <= 220 })

        val ids = readIndex()
        if (ids.size >= MAX_RECORDS) throw IOException("ظرفیت ذخیره سوابق نواقص تکمیل شده است")
        var id: String
        do {
            id = (System.currentTimeMillis() * 1000L + ThreadLocalRandom.current().nextInt(1000)).toString()
        } while (ids.contains(id) || secure.hasEncryptedRecord(recordKey(id)))
        val now = Instant.now().toString()
        val event = JSONObject()
            .put("from_status", "")
            .put("to_status", DeficiencyWorkflowRules.OPEN)
            .put("note", "ثبت اولیه نقص")
            .put("actor", actor.take(120))
            .put("at", now)
        val record = JSONObject()
            .put("id", id)
            .put("mission_key", missionKey)
            .put("organization_id", organizationId)
            .put("organization", organization)
            .put("question_id", questionId)
            .put("question_index", questionIndex)
            .put("section_id", sectionId)
            .put("question_title", questionTitle)
            .put("description", description.trim())
            .put("severity", severity)
            .put("due_date", dueDateIso)
            .put("due_date_jalali", PersianCalendar.normalize(dueDateJalali))
            .put("status", DeficiencyWorkflowRules.OPEN)
            .put("resolution_note", "")
            .put("linked_evidence_count", linkedEvidenceRefs.size)
            .put("linked_evidence_refs", JSONArray(linkedEvidenceRefs))
            .put("sync_state", "local_pending")
            .put("sync_error", "")
            .put("server_id", "")
            .put("source", "android_inspector")
            .put("inspector_ref", inspectorRef.trim().take(120))
            .put("created_by", actor.take(120))
            .put("created_at", now)
            .put("updated_at", now)
            .put("events", JSONArray().put(event))
        secure.saveDurable(recordKey(id), record.toString())
        ids.add(id)
        secure.saveDurable(INDEX_KEY, JSONArray(ids).toString())
        return JSONObject(record.toString())
    }

    @Synchronized
    fun changeStatus(id: String, nextStatus: String, note: String, actor: String): JSONObject {
        require(nextStatus in DeficiencyWorkflowRules.statuses)
        val record = readRecord(id) ?: throw IOException("رکورد نقص پیدا نشد")
        if (record.optBoolean("integrity_error")) throw IOException("رکورد نقص فقط‌خواندنی است")
        val current = record.optString("status")
        require(DeficiencyWorkflowRules.canTransition(current, nextStatus)) {
            "تغییر وضعیت از «${DeficiencyWorkflowRules.statusLabel(current)}» به «${DeficiencyWorkflowRules.statusLabel(nextStatus)}» مجاز نیست"
        }
        val cleanNote = note.trim()
        if (DeficiencyWorkflowRules.requiresActionNote(nextStatus) && cleanNote.length < 8) {
            throw IOException("برای این اقدام، توضیح حداقل ۸ نویسه‌ای الزامی است")
        }
        if (cleanNote.length > 1500) throw IOException("توضیح پیگیری نباید بیشتر از ۱۵۰۰ نویسه باشد")
        val now = Instant.now().toString()
        val events = record.optJSONArray("events") ?: JSONArray()
        events.put(JSONObject()
            .put("from_status", current)
            .put("to_status", nextStatus)
            .put("note", cleanNote)
            .put("actor", actor.take(120))
            .put("at", now))
        record.put("status", nextStatus)
            .put("resolution_note", if (cleanNote.isNotBlank()) cleanNote else record.optString("resolution_note"))
            .put("events", events)
            .put("updated_at", now)
            .put("sync_state", "local_pending")
            .put("sync_error", "")
        secure.saveDurable(recordKey(id), record.toString())
        return JSONObject(record.toString())
    }

    @Synchronized
    fun markSynced(id: String, serverId: String, serverUpdatedAt: String = "") {
        require(serverId.isNotBlank() && serverId.length <= 190)
        val record = readRecord(id) ?: throw IOException("رکورد نقص پیدا نشد")
        record.put("sync_state", "synced")
            .put("server_id", serverId)
            .put("sync_error", "")
            .put("server_conflict", "")
            .put("synced_at", Instant.now().toString())
        if (serverUpdatedAt.isNotBlank()) record.put("server_updated_at", serverUpdatedAt)
        secure.saveDurable(recordKey(id), record.toString())
    }

    @Synchronized
    fun markSyncError(id: String, safeMessage: String) {
        val record = readRecord(id) ?: throw IOException("رکورد نقص پیدا نشد")
        record.put("sync_state", "failed")
            .put("sync_error", safeMessage.take(500))
            .put("sync_error_at", Instant.now().toString())
        secure.saveDurable(recordKey(id), record.toString())
    }

    @Synchronized
    fun resolveConflictUseServer(id: String): JSONObject {
        val record = readRecord(id) ?: throw IOException("رکورد نقص پیدا نشد")
        if (record.optString("sync_state") != "conflict") throw IOException("برای این نقص تعارض فعالی وجود ندارد")
        val shadow = runCatching { JSONObject(record.optString("server_conflict")) }.getOrElse { throw IOException("نسخه سرور قابل بازیابی نیست") }
        record.put("sync_state", "synced")
        secure.saveDurable(recordKey(id), record.toString())
        mergeServerFinding(shadow)
        return readRecord(id) ?: throw IOException("بازیابی نسخه سرور انجام نشد")
    }

    @Synchronized
    fun resolveConflictKeepLocal(id: String): JSONObject {
        val record = readRecord(id) ?: throw IOException("رکورد نقص پیدا نشد")
        if (record.optString("sync_state") != "conflict") throw IOException("برای این نقص تعارض فعالی وجود ندارد")
        val shadow = runCatching { JSONObject(record.optString("server_conflict")) }.getOrElse { throw IOException("نسخه سرور قابل بازیابی نیست") }
        val remoteVersion = shadow.optString("_updated_at", shadow.optString("updatedAt"))
        if (remoteVersion.isBlank()) throw IOException("نسخه سرور برای ارسال مجدد مشخص نیست")
        record.put("server_updated_at", remoteVersion)
            .put("sync_state", "local_pending")
            .put("server_conflict", "")
            .put("sync_error", "")
            .put("updated_at", Instant.now().toString())
        secure.saveDurable(recordKey(id), record.toString())
        return JSONObject(record.toString())
    }

    @Synchronized
    fun mergeServerFinding(payload: JSONObject) {
        val id = payload.optString("_server_key", payload.optString("id")).trim()
        if (!validId(id)) return
        val existing = readRecord(id)
        val remoteStatus = DeficiencyWorkflowRules.fromServerStatus(payload.optString("status", "باز"))
        if (existing != null && existing.optString("sync_state") in setOf("local_pending", "failed", "conflict")) {
            val baseServerVersion = existing.optString("server_updated_at")
            val remoteVersion = payload.optString("_updated_at", payload.optString("updatedAt"))
            val hasServerCopy = existing.optString("server_id").isNotBlank()
            if (remoteStatus in setOf(DeficiencyWorkflowRules.APPROVED, DeficiencyWorkflowRules.REJECTED)) {
                // A supervisor's final decision is authoritative over a stale local state.
            } else if (hasServerCopy && baseServerVersion.isNotBlank() && remoteVersion.isNotBlank() && baseServerVersion != remoteVersion) {
                existing.put("sync_state", "conflict")
                    .put("server_conflict", payload.toString())
                    .put("sync_error", "نسخه سرور پس از آخرین همگام‌سازی تغییر کرده است؛ قبل از ارسال مجدد، تعارض را بررسی کنید.")
                secure.saveDurable(recordKey(id), existing.toString())
                return
            } else return
        }
        val now = Instant.now().toString()
        val remoteSeverity = DeficiencyWorkflowRules.fromServerSeverity(payload.optString("severity", "متوسط"))
        val due = PersianCalendar.normalize(payload.optString("dueDate", payload.optString("due"))).orEmpty()
        val events = serverEvents(payload.optJSONArray("history"))
            ?: existing?.optJSONArray("events")
            ?: JSONArray()
        val record = existing ?: JSONObject().put("id", id).put("created_at", payload.optString("createdAt", now))
        record.put("id", id)
            .put("mission_key", payload.optString("missionKey", payload.optString("mission_key", payload.optString("missionId"))))
            .put("organization_id", payload.optString("orgId", payload.optString("org_id")))
            .put("organization", payload.optString("orgName", payload.optString("organization", "دستگاه اجرایی")))
            .put("question_id", payload.optString("questionId", payload.optString("question_id")))
            .put("question_index", payload.optInt("questionIndex", payload.optInt("question_index")))
            .put("section_id", payload.optString("sectionId", payload.optString("section_id")))
            .put("question_title", payload.optString("questionTitle", payload.optString("question_title", payload.optString("title", "شاخص بازرسی"))))
            .put("description", payload.optString("description", payload.optString("note")))
            .put("severity", remoteSeverity)
            .put("due_date_jalali", due)
            .put("status", remoteStatus)
            .put("resolution_note", payload.optString("resolutionNote", payload.optString("resolution_note")))
            .put("source", payload.optString("source", existing?.optString("source", "server")))
            .put("inspector_ref", payload.optString("inspectorRef", payload.optString("inspectorCode", existing?.optString("inspector_ref", "") ?: "")))
            .put("mobile_user_id", payload.optInt("mobileUserId", existing?.optInt("mobile_user_id", 0) ?: 0))
            .put("created_by", payload.optString("createdBy", existing?.optString("created_by", "") ?: ""))
            .put("updated_at", payload.optString("_updated_at", payload.optString("updatedAt", now)))
            .put("server_updated_at", payload.optString("_updated_at", payload.optString("updatedAt", now)))
            .put("sync_state", "synced")
            .put("server_id", id)
            .put("server_conflict", "")
            .put("sync_error", "")
            .put("server_evidence_files", payload.optJSONArray("evidenceFiles") ?: existing?.optJSONArray("server_evidence_files") ?: JSONArray())
            .put("server_evidence_ids", payload.optJSONArray("evidenceIds") ?: existing?.optJSONArray("server_evidence_ids") ?: JSONArray())
            .put("events", events)
        if (payload.has("orgId") || payload.has("org_id")) record.put("organization_id", payload.optString("orgId", payload.optString("org_id")))
        secure.saveDurable(recordKey(id), record.toString())
        val ids = readIndex()
        if (id !in ids) {
            ids.add(id)
            secure.saveDurable(INDEX_KEY, JSONArray(ids).toString())
        }
    }

    private fun serverEvents(source: JSONArray?): JSONArray? {
        if (source == null || source.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until source.length()) {
            val e = source.optJSONObject(i) ?: continue
            val fromRaw = e.optString("fromStatus", e.optString("from_status"))
            val toRaw = e.optString("toStatus", e.optString("to_status", e.optString("status")))
            out.put(JSONObject()
                .put("from_status", if (fromRaw.isBlank()) "" else DeficiencyWorkflowRules.fromServerStatus(fromRaw))
                .put("to_status", DeficiencyWorkflowRules.fromServerStatus(toRaw))
                .put("note", e.optString("note", e.optString("description")))
                .put("actor", e.optString("actor", e.optString("createdBy", "سامانه")))
                .put("at", e.optString("at", e.optString("createdAt", ""))))
        }
        return if (out.length() == 0) null else out
    }
}
