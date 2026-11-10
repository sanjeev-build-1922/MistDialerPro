package com.example.mistdialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED,
    DECLINED
}

private val PAREN_REGEX = Regex("\\(.*?\\)")
private val SPACE_REGEX = Regex("\\s+")

internal fun extractNormalizedKey(rawNumber: String): String {
    val sb = StringBuilder(rawNumber.length)
    for (i in 0 until rawNumber.length) {
        val c = rawNumber[i]
        if (c.isDigit()) sb.append(c)
    }
    val digits = sb.toString()
    return if (digits.length >= 8) digits.substring(digits.length - 8) else digits
}

@Immutable
data class ContactItem(
    val id: String,
    val name: String,
    val displayName: String,
    val role: String,
    val tag: String,
    val number: String,
    val secondaryNumber: String = "",
    val isFavourite: Boolean = false,
    val isSavedContact: Boolean = true
) {
    val letter: String = displayName.trim().firstOrNull()?.uppercaseChar()?.let {
        if (it in 'A'..'Z') it.toString() else "#"
    } ?: "#"

    val initials: String = displayName
        .replace(PAREN_REGEX, "")
        .trim()
        .split(SPACE_REGEX)
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "#" }

    val normalizedPhoneKey: String = extractNormalizedKey(number)
}

@Immutable
data class CallLogItem(
    val id: String,
    val contactId: String,
    val name: String,
    val number: String,
    val type: CallType,
    val groupCount: Int,
    val timestamp: String,
    val dateGroup: String,
    val duration: String,
    val codec: String = "HD Voice · 5G VoNR",
    val simLabel: String = "SIM 1",
    val isSaved: Boolean = false,
    val isUnansweredOutgoing: Boolean = false,
    val dateMillis: Long = System.currentTimeMillis()
) {
    val normalizedPhoneKey: String = extractNormalizedKey(number)
}

object DialerRepository {

    private const val RINGTONE_PREFS = "mist_contact_ringtones_v2"
    private const val SETTINGS_PREFS = "mist_dialer_settings_v2"
    private const val BLOCKED_PREFS = "mist_blocked_numbers_v2"

    @Volatile
    private var cachedContacts: List<ContactItem> = emptyList()

    @Volatile
    private var contactsByPhoneKey: Map<String, ContactItem> = emptyMap()

    @Volatile
    private var contactsById: Map<String, ContactItem> = emptyMap()

    val sampleContacts = listOf(
        ContactItem(
            id = "amelia",
            name = "AMELIA PARKER",
            displayName = "Amelia Parker",
            role = "Principal Architect",
            tag = "Work",
            number = "+91 98201 45678",
            secondaryNumber = "+44 20 7946 0192",
            isFavourite = true
        ),
        ContactItem(
            id = "adam",
            name = "ADAM (GYM)",
            displayName = "Adam Vance (Gym)",
            role = "Performance Coach",
            tag = "Personal",
            number = "+91 98112 30940",
            isFavourite = false
        ),
        ContactItem(
            id = "aiden",
            name = "AIDEN AL-FARSI",
            displayName = "Aiden Al-Farsi",
            role = "Acoustic Systems Lead",
            tag = "Work",
            number = "+91 98333 45460",
            secondaryNumber = "+971 50 492 8810",
            isFavourite = true
        ),
        ContactItem(
            id = "aria",
            name = "ARIA GUPTA",
            displayName = "Aria Gupta",
            role = "Design Systems Architect",
            tag = "Work",
            number = "+91 98455 67890",
            secondaryNumber = "+91 22 4091 7721",
            isFavourite = true
        ),
        ContactItem(
            id = "mladen",
            name = "MLADEN HOYSS",
            displayName = "Mladen Hoyss",
            role = "Creative Director",
            tag = "Work",
            number = "+44 7700 900412",
            secondaryNumber = "+91 98209 88410",
            isFavourite = true
        )
    )

    val sampleCallLogs = listOf(
        CallLogItem(
            id = "log1",
            contactId = "mladen",
            name = "MLADEN HOYSS",
            number = "+44 7700 900412",
            type = CallType.INCOMING,
            groupCount = 1,
            timestamp = "17:10",
            dateGroup = "TODAY",
            duration = "41m 00s",
            isSaved = true,
            isUnansweredOutgoing = false,
            dateMillis = System.currentTimeMillis() - 600_000L
        ),
        CallLogItem(
            id = "log2",
            contactId = "aria",
            name = "ARIA GUPTA",
            number = "+91 98455 67890",
            type = CallType.MISSED,
            groupCount = 2,
            timestamp = "16:08",
            dateGroup = "TODAY",
            duration = "Missed (2)",
            isSaved = true,
            isUnansweredOutgoing = false,
            dateMillis = System.currentTimeMillis() - 1_800_000L
        ),
        CallLogItem(
            id = "log3",
            contactId = "amelia",
            name = "AMELIA PARKER",
            number = "+91 98201 45678",
            type = CallType.OUTGOING,
            groupCount = 1,
            timestamp = "14:20",
            dateGroup = "TODAY",
            duration = "Not answered",
            isSaved = true,
            isUnansweredOutgoing = true,
            dateMillis = System.currentTimeMillis() - 3_600_000L
        )
    )

    init {
        rebuildContactIndices(sampleContacts)
    }

    private fun rebuildContactIndices(contacts: List<ContactItem>) {
        cachedContacts = contacts
        val byKey = HashMap<String, ContactItem>(contacts.size * 2)
        val byId = HashMap<String, ContactItem>(contacts.size * 2)
        for (c in contacts) {
            byId[c.id] = c
            if (c.normalizedPhoneKey.length >= 4) {
                byKey.putIfAbsent(c.normalizedPhoneKey, c)
            }
            if (c.secondaryNumber.isNotBlank()) {
                val secKey = extractNormalizedKey(c.secondaryNumber)
                if (secKey.length >= 4) {
                    byKey.putIfAbsent(secKey, c)
                }
            }
        }
        contactsByPhoneKey = byKey
        contactsById = byId
    }

    // ========================================================================
    // PERSISTENT SETTINGS & BLOCK LIST
    // ========================================================================

    fun loadSettings(context: Context): DialerSettingsState {
        val sp = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        return DialerSettingsState(
            themeMode = sp.getString("themeMode", "dark") ?: "dark",
            glassIntensity = sp.getString("glassIntensity", "balanced") ?: "balanced",
            dotIntensity = sp.getString("dotIntensity", "standard") ?: "standard",
            portraitStyle = sp.getString("portraitStyle", "full") ?: "full",
            motionMode = sp.getString("motionMode", "spring") ?: "spring",
            hapticsEnabled = sp.getBoolean("hapticsEnabled", true),
            showCallCodec = sp.getBoolean("showCallCodec", true),
            sortBy = sp.getString("sortBy", "first") ?: "first",
            groupConsecutiveCalls = sp.getBoolean("groupConsecutiveCalls", true)
        )
    }

    fun saveSettings(context: Context, settings: DialerSettingsState) {
        val sp = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        sp.edit().apply {
            putString("themeMode", settings.themeMode)
            putString("glassIntensity", settings.glassIntensity)
            putString("dotIntensity", settings.dotIntensity)
            putString("portraitStyle", settings.portraitStyle)
            putString("motionMode", settings.motionMode)
            putBoolean("hapticsEnabled", settings.hapticsEnabled)
            putBoolean("showCallCodec", settings.showCallCodec)
            putString("sortBy", settings.sortBy)
            putBoolean("groupConsecutiveCalls", settings.groupConsecutiveCalls)
            apply()
        }
    }

    fun isNumberBlocked(context: Context, rawNumber: String): Boolean {
        val key = extractNormalizedKey(rawNumber)
        if (key.isEmpty()) return false
        val sp = context.getSharedPreferences(BLOCKED_PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean("blocked_$key", false)
    }

    fun toggleBlockNumber(context: Context, rawNumber: String): Boolean {
        val key = extractNormalizedKey(rawNumber)
        if (key.isEmpty()) return false
        val sp = context.getSharedPreferences(BLOCKED_PREFS, Context.MODE_PRIVATE)
        val next = !sp.getBoolean("blocked_$key", false)
        sp.edit().putBoolean("blocked_$key", next).apply()
        return next
    }

    // ========================================================================
    // CONTACT-SPECIFIC CUSTOM RINGTONE MANAGEMENT (O(1) BATCHED)
    // ========================================================================

    fun setContactCustomRingtone(context: Context, contact: ContactItem, ringtoneUri: Uri): String {
        val title = try {
            RingtoneManager.getRingtone(context, ringtoneUri)?.getTitle(context)
                ?: ringtoneUri.lastPathSegment
                ?: "Custom Ringtone"
        } catch (_: Throwable) {
            "Custom Ringtone"
        }

        val sp = context.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val numKey = contact.normalizedPhoneKey
        sp.edit().apply {
            putString("uri_id_${contact.id}", ringtoneUri.toString())
            putString("title_id_${contact.id}", title)
            if (numKey.isNotEmpty()) {
                putString("uri_num_$numKey", ringtoneUri.toString())
                putString("title_num_$numKey", title)
            }
            apply()
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED && contact.id.all { it.isDigit() }
        ) {
            try {
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri.toString())
                }
                val contactUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contact.id
                )
                context.contentResolver.update(contactUri, values, null, null)
            } catch (_: Throwable) {
            }
        }
        return title
    }

    fun clearContactCustomRingtone(context: Context, contact: ContactItem) {
        val sp = context.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val numKey = contact.normalizedPhoneKey
        sp.edit().apply {
            remove("uri_id_${contact.id}")
            remove("title_id_${contact.id}")
            if (numKey.isNotEmpty()) {
                remove("uri_num_$numKey")
                remove("title_num_$numKey")
            }
            apply()
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED && contact.id.all { it.isDigit() }
        ) {
            try {
                val values = ContentValues().apply {
                    putNull(ContactsContract.Contacts.CUSTOM_RINGTONE)
                }
                val contactUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contact.id
                )
                context.contentResolver.update(contactUri, values, null, null)
            } catch (_: Throwable) {
            }
        }
    }

    fun getContactCustomRingtoneInfo(context: Context, contact: ContactItem): Pair<String?, String> {
        val sp = context.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val numKey = contact.normalizedPhoneKey
        val uriById = sp.getString("uri_id_${contact.id}", null)
        val titleById = sp.getString("title_id_${contact.id}", null)
        if (!uriById.isNullOrBlank()) {
            return uriById to (titleById ?: "Custom Ringtone")
        }
        if (numKey.isNotEmpty()) {
            val uriByNum = sp.getString("uri_num_$numKey", null)
            val titleByNum = sp.getString("title_num_$numKey", null)
            if (!uriByNum.isNullOrBlank()) {
                return uriByNum to (titleByNum ?: "Custom Ringtone")
            }
        }
        return null to "Default Ringtone"
    }

    fun getCustomRingtoneUriForIncomingNumber(context: Context, rawNumber: String): Uri? {
        val clean = rawNumber.trim()
        if (clean.isEmpty()) return null
        val sp = context.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val numKey = extractNormalizedKey(clean)
        if (numKey.isNotEmpty()) {
            val savedUri = sp.getString("uri_num_$numKey", null)
            if (!savedUri.isNullOrBlank()) {
                return try {
                    Uri.parse(savedUri)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        val matched = findCachedContactByNumber(clean)
        if (matched != null) {
            val savedById = sp.getString("uri_id_${matched.id}", null)
            if (!savedById.isNullOrBlank()) {
                return try {
                    Uri.parse(savedById)
                } catch (_: Throwable) {
                    null
                }
            }
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(clean)
                )
                val proj = arrayOf(ContactsContract.PhoneLookup.CUSTOM_RINGTONE)
                context.contentResolver.query(lookupUri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.CUSTOM_RINGTONE)
                        val customRingStr = if (idx >= 0) cursor.getString(idx) else null
                        if (!customRingStr.isNullOrBlank()) {
                            return Uri.parse(customRingStr)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Single-pass O(N) map builder using SharedPreferences.all snapshot (zero repeated IPC/disk reads).
     */
    fun getAllCustomRingtoneTitles(context: Context, contacts: List<ContactItem>): Map<String, String> {
        val sp = context.getSharedPreferences(RINGTONE_PREFS, Context.MODE_PRIVATE)
        val allEntries = sp.all
        if (allEntries.isEmpty()) return emptyMap()

        val map = HashMap<String, String>()
        for (c in contacts) {
            val uriById = allEntries["uri_id_${c.id}"] as? String
            if (!uriById.isNullOrBlank()) {
                val title = (allEntries["title_id_${c.id}"] as? String) ?: "Custom Ringtone"
                map[c.id] = title
                continue
            }
            val numKey = c.normalizedPhoneKey
            if (numKey.isNotEmpty()) {
                val uriByNum = allEntries["uri_num_$numKey"] as? String
                if (!uriByNum.isNullOrBlank()) {
                    val title = (allEntries["title_num_$numKey"] as? String) ?: "Custom Ringtone"
                    map[c.id] = title
                }
            }
        }
        return map
    }

    // ========================================================================
    // O(1) FAST CONTACT & CALL LOG LOADING
    // ========================================================================

    fun findCachedContactByNumber(rawNumber: String): ContactItem? {
        val key = extractNormalizedKey(rawNumber)
        if (key.length < 4) return null
        contactsByPhoneKey[key]?.let { return it }
        val pool = if (cachedContacts.isNotEmpty()) cachedContacts else sampleContacts
        return pool.find { c -> c.normalizedPhoneKey.isNotEmpty() && c.normalizedPhoneKey.endsWith(key) }
    }

    fun lookupContactByNumber(context: Context, rawNumber: String): ContactItem? {
        findCachedContactByNumber(rawNumber)?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val clean = rawNumber.trim()
        if (clean.isEmpty()) return null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(clean)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.STARRED
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val starIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.STARRED)
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: clean else clean
                    val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: clean else clean
                    val starred = if (starIdx >= 0) cursor.getInt(starIdx) == 1 else false
                    return ContactItem(
                        id = id,
                        name = displayName.uppercase(),
                        displayName = displayName,
                        role = "Saved Contact",
                        tag = if (starred) "Favourite" else "Mobile",
                        number = clean,
                        isFavourite = starred,
                        isSavedContact = true
                    )
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun toggleContactFavouriteInDb(context: Context, contact: ContactItem, newStarred: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED && contact.id.all { it.isDigit() }
        ) {
            try {
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (newStarred) 1 else 0)
                }
                val contactUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_URI,
                    contact.id
                )
                context.contentResolver.update(contactUri, values, null, null)
            } catch (_: Throwable) {
            }
        }
    }

    fun loadContacts(context: Context): List<ContactItem> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            rebuildContactIndices(sampleContacts)
            return sampleContacts
        }

        val result = ArrayList<ContactItem>(256)
        val seenIds = HashSet<String>(256)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val starIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                while (cursor.moveToNext() && result.size < 800) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                    if (id.isNotEmpty() && !seenIds.add(id)) continue
                    val displayName = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "Unknown"
                    val number = (if (numIdx >= 0) cursor.getString(numIdx) else null) ?: ""
                    val starred = if (starIdx >= 0) cursor.getInt(starIdx) == 1 else false

                    if (number.isNotBlank()) {
                        result.add(
                            ContactItem(
                                id = id.ifEmpty { number },
                                name = displayName.uppercase(),
                                displayName = displayName,
                                role = "Saved Contact",
                                tag = if (starred) "Favourite" else "Mobile",
                                number = number,
                                isFavourite = starred,
                                isSavedContact = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        val finalContacts = if (result.isEmpty()) sampleContacts else result
        rebuildContactIndices(finalContacts)
        return finalContacts
    }

    fun loadCallLogs(
        context: Context,
        contacts: List<ContactItem>,
        groupConsecutive: Boolean = true
    ): List<CallLogItem> {
        if (contacts.isNotEmpty() && contacts !== cachedContacts) {
            rebuildContactIndices(contacts)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val session = CallStateController.sessionCallLogs
            return (session + sampleCallLogs).sortedByDescending { it.dateMillis }
        }

        val rawLogs = ArrayList<CallLogItem>(200)
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
        val nowMs = System.currentTimeMillis()

        val startOfTodayMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfYesterdayMs = startOfTodayMs - 24 * 60 * 60 * 1000L

        val fastLookup = contactsByPhoneKey

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (cursor.moveToNext() && rawLogs.size < 200) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                    val rawNumber = (if (numIdx >= 0) cursor.getString(numIdx) else null) ?: "Unknown"
                    val cachedName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val normKey = extractNormalizedKey(rawNumber)
                    val matchedContact = if (normKey.length >= 4) fastLookup[normKey] else null

                    val hasSavedName = !cachedName.isNullOrBlank() || matchedContact != null
                    val displayName = (matchedContact?.displayName
                        ?: cachedName?.takeIf { it.isNotBlank() }
                        ?: rawNumber).uppercase()

                    val callTypeInt = if (typeIdx >= 0) cursor.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val callType = when (callTypeInt) {
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> CallType.DECLINED
                        else -> CallType.INCOMING
                    }

                    val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else nowMs
                    val durationSec = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    val isUnansweredOutgoing = (callType == CallType.OUTGOING && durationSec == 0L)

                    val dateGroup = when {
                        dateMs >= startOfTodayMs -> "TODAY"
                        dateMs >= startOfYesterdayMs -> "YESTERDAY"
                        else -> "EARLIER"
                    }

                    val timestamp = when (dateGroup) {
                        "TODAY" -> timeFormat.format(Date(dateMs))
                        "YESTERDAY" -> "Yesterday · ${timeFormat.format(Date(dateMs))}"
                        else -> dateFormat.format(Date(dateMs))
                    }

                    val durationStr = when {
                        callType == CallType.MISSED -> "Missed"
                        callType == CallType.DECLINED -> "Declined"
                        isUnansweredOutgoing -> "Not answered"
                        else -> "${durationSec / 60}m ${String.format(Locale.US, "%02d", durationSec % 60)}s"
                    }

                    rawLogs.add(
                        CallLogItem(
                            id = id.ifEmpty { "${rawLogs.size}_$dateMs" },
                            contactId = matchedContact?.id ?: rawNumber,
                            name = displayName,
                            number = rawNumber,
                            type = callType,
                            groupCount = 1,
                            timestamp = timestamp,
                            dateGroup = dateGroup,
                            duration = durationStr,
                            isSaved = hasSavedName,
                            isUnansweredOutgoing = isUnansweredOutgoing,
                            dateMillis = dateMs
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        val sessionLogs = CallStateController.sessionCallLogs.filter { (nowMs - it.dateMillis) < 5_000L }
        val deduplicatedSession = sessionLogs.filter { sLog ->
            val sTail = sLog.normalizedPhoneKey
            rawLogs.none { dbLog ->
                sTail.isNotEmpty() && sTail == dbLog.normalizedPhoneKey && abs(dbLog.dateMillis - sLog.dateMillis) < 300_000L
            }
        }

        val sortedCombined = (deduplicatedSession + rawLogs).sortedByDescending { it.dateMillis }
        if (sortedCombined.isEmpty()) return sampleCallLogs

        if (!groupConsecutive) return sortedCombined

        // Google Phone-style grouping of consecutive calls from the same number & type in the same dateGroup
        val grouped = ArrayList<CallLogItem>(sortedCombined.size)
        for (item in sortedCombined) {
            val prev = grouped.lastOrNull()
            if (prev != null &&
                prev.normalizedPhoneKey.isNotEmpty() &&
                prev.normalizedPhoneKey == item.normalizedPhoneKey &&
                prev.dateGroup == item.dateGroup &&
                prev.type == item.type
            ) {
                grouped[grouped.lastIndex] = prev.copy(groupCount = prev.groupCount + 1)
            } else {
                grouped.add(item)
            }
        }
        return grouped
    }

    fun placeCall(context: Context, rawNumber: String): Boolean {
        val cleanNumber = rawNumber.trim()
        if (cleanNumber.isEmpty()) return false
        val uri = Uri.fromParts("tel", cleanNumber, null)

        CallStateController.startSimulatedCall(
            number = cleanNumber,
            stage = UiCallStage.OUTGOING,
            isUnknown = false
        )

        try {
            val uiIntent = Intent(context, CallActivity::class.java).apply {
                putExtra(CallActivity.EXTRA_PHONE_NUMBER, cleanNumber)
                putExtra(CallActivity.EXTRA_INITIAL_STAGE, "OUTGOING")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(uiIntent)
        } catch (_: Exception) {
        }

        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    telecomManager.placeCall(uri, Bundle())
                } else {
                    val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                }
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    fun saveNumberToContacts(context: Context, rawNumber: String, suggestedName: String = "") {
        try {
            val insertIntent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, rawNumber.trim())
                if (suggestedName.isNotBlank() && suggestedName != rawNumber.trim()) {
                    putExtra(ContactsContract.Intents.Insert.NAME, suggestedName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(insertIntent)
        } catch (_: Exception) {
        }
    }

    fun openSmsComposer(context: Context, rawNumber: String, bodyText: String = "") {
        try {
            val smsUri = Uri.parse("smsto:${rawNumber.trim()}")
            val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                if (bodyText.isNotBlank()) {
                    putExtra("sms_body", bodyText)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun shareContactText(context: Context, contact: ContactItem) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${contact.displayName} · ${contact.number}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Contact"))
        } catch (_: Exception) {
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
