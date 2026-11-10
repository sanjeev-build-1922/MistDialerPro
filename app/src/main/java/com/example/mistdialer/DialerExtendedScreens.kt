package com.example.mistdialer

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*

@Immutable
data class DialerSettingsState(
    val themeMode: String = "dark",
    val glassIntensity: String = "balanced",
    val dotIntensity: String = "standard",
    val portraitStyle: String = "full",
    val motionMode: String = "spring",
    val hapticsEnabled: Boolean = true,
    val showCallCodec: Boolean = true,
    val sortBy: String = "first",
    val groupConsecutiveCalls: Boolean = true
)

/**
 * SCREEN 04 — CLEAN CONTACT DETAILS
 * Includes "+ Save to Contacts" AND "Custom Ringtone" picker for this specific contact.
 */
@Composable
fun ContactDetailsScreen(
    contact: ContactItem,
    callLogs: List<CallLogItem>,
    customRingtoneTitle: String = "Default Ringtone",
    onBack: () -> Unit,
    onToggleFavourite: (ContactItem) -> Unit,
    onPlaceCall: (String) -> Unit,
    onSendSms: (String) -> Unit,
    onSaveContact: (ContactItem) -> Unit,
    onPickRingtone: (ContactItem) -> Unit = {},
    onCopyNumber: (String) -> Unit,
    onOpenActionCard: (ContactItem) -> Unit
) {
    val contactLogs = remember(callLogs, contact) {
        callLogs.filter {
            it.contactId == contact.id ||
                    (contact.normalizedPhoneKey.isNotEmpty() && it.normalizedPhoneKey == contact.normalizedPhoneKey)
        }.ifEmpty {
            listOf(
                CallLogItem(
                    id = "hist_1",
                    contactId = contact.id,
                    name = contact.name,
                    number = contact.number,
                    type = CallType.INCOMING,
                    groupCount = 1,
                    timestamp = "14:20",
                    dateGroup = "TODAY",
                    duration = "12m 08s"
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 104.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftGlassBox(
                shape = CircleShape,
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Text(text = "←", color = Color.White, fontSize = 16.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftGlassBox(
                    shape = CircleShape,
                    onClick = { onToggleFavourite(contact) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        text = if (contact.isFavourite) "★" else "☆",
                        color = if (contact.isFavourite) NothingTokens.NothingRed else Color.White,
                        fontSize = 16.sp
                    )
                }

                SoftGlassBox(
                    shape = CircleShape,
                    onClick = { onOpenActionCard(contact) },
                    modifier = Modifier.size(40.dp)
                ) {
                    BareThreeDotsIcon(tint = Color.White, size = 18.dp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircleMonochromeAvatar(
                initials = contact.initials,
                size = 84.dp,
                hasAccentBorder = true
            )

            Spacer(modifier = Modifier.height(14.dp))

            NdotText(
                text = contact.name,
                fontSize = 24.sp,
                letterSpacing = 1.8.sp
            )

            if (contact.displayName.uppercase() != contact.number.uppercase()) {
                Spacer(modifier = Modifier.height(4.dp))
                MonoNumText(
                    text = contact.number,
                    fontSize = 13.sp,
                    color = NothingTokens.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftGlassBox(onClick = { onSaveContact(contact) }) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VectorSaveContactIcon(tint = NothingTokens.NothingRed, size = 16.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save to Contacts",
                            color = NothingTokens.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                SoftGlassBox(onClick = { onPickRingtone(contact) }) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "♪ Ringtone",
                            color = NothingTokens.EmeraldActive,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 4-Column Quick Action Strip (Call | Message | Save | Copy)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(MistShapes.Radius18)
                    .background(Color.White)
                    .clickable { onPlaceCall(contact.number) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BareCallIcon(tint = Color.Black, size = 18.dp)
                Spacer(modifier = Modifier.height(5.dp))
                Text(text = "Call", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            ContactDetailActionTile(
                icon = "✉",
                label = "Message",
                modifier = Modifier.weight(1f),
                onClick = { onSendSms(contact.number) }
            )
            ContactDetailActionTile(
                icon = "＋",
                label = "Save",
                iconColor = NothingTokens.NothingRed,
                modifier = Modifier.weight(1f),
                onClick = { onSaveContact(contact) }
            )
            ContactDetailActionTile(
                icon = "⎘",
                label = "Copy",
                modifier = Modifier.weight(1f),
                onClick = { onCopyNumber(contact.number) }
            )
        }

        // Phone Numbers & Custom Ringtone Frosted-Mist Card
        FrostedMistBox(
            shape = MistShapes.Radius22,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaceCall(contact.number) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        MonoNumText(text = "MOBILE · PRIMARY", fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(3.dp))
                        NdotText(text = contact.number, fontSize = 15.sp, letterSpacing = 1.sp)
                    }
                    BareCallIcon(tint = Color.White, size = 20.dp)
                }

                if (contact.secondaryNumber.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaceCall(contact.secondaryNumber) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            MonoNumText(text = "SECONDARY", fontSize = 10.sp)
                            Spacer(modifier = Modifier.height(3.dp))
                            NdotText(text = contact.secondaryNumber, fontSize = 15.sp, letterSpacing = 1.sp)
                        }
                        BareCallIcon(tint = Color.White, size = 20.dp)
                    }
                }

                // Contact-Specific Ringtone Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickRingtone(contact) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        MonoNumText(text = "CUSTOM CONTACT RINGTONE", fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "♪ $customRingtoneTitle",
                            color = NothingTokens.EmeraldActive,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    MonoNumText(
                        text = "CHANGE →",
                        fontSize = 10.sp,
                        color = NothingTokens.TextPrimary
                    )
                }
            }
        }

        // Recent Interactions Section
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            NdotText(
                text = "RECENT INTERACTIONS",
                fontSize = 11.sp,
                color = NothingTokens.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FrostedMistBox(
                shape = MistShapes.Radius22,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    contactLogs.forEach { log ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaceCall(log.number) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CallTypeBadge(
                                    type = log.type,
                                    isUnansweredOutgoing = log.isUnansweredOutgoing
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "${log.dateGroup} · ${log.timestamp}",
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 12.sp
                                )
                            }
                            MonoNumText(text = log.duration, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDetailActionTile(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    iconColor: Color = Color.White,
    onClick: () -> Unit
) {
    SoftGlassBox(
        shape = MistShapes.Radius18,
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, color = iconColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, color = NothingTokens.TextPrimary, fontSize = 11.sp)
        }
    }
}

/**
 * SCREEN 09 — DEDICATED CALL DETAILS
 */
@Composable
fun CallDetailsScreen(
    log: CallLogItem,
    contact: ContactItem,
    showCodec: Boolean,
    isBlocked: Boolean = false,
    onBack: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onSendSms: (String) -> Unit,
    onSaveNumber: (String) -> Unit,
    onEditBeforeCall: (String) -> Unit,
    onCopyNumber: (String) -> Unit,
    onOpenContactDetails: (ContactItem) -> Unit,
    onOpenActionCard: (ContactItem) -> Unit,
    onBlockNumber: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 96.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftGlassBox(
                shape = CircleShape,
                onClick = onBack,
                modifier = Modifier.size(38.dp)
            ) {
                Text(text = "←", color = Color.White, fontSize = 16.sp)
            }
            NdotText(text = "CALL DETAILS", fontSize = 13.sp, color = NothingTokens.TextSecondary)
            SoftGlassBox(
                shape = CircleShape,
                onClick = { onOpenActionCard(contact) },
                modifier = Modifier.size(38.dp)
            ) {
                BareThreeDotsIcon(tint = Color.White, size = 18.dp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FrostedMistBox(
            shape = MistShapes.Radius24,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenContactDetails(contact) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleMonochromeAvatar(initials = contact.initials, size = 52.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        NdotText(text = contact.name, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        MonoNumText(text = log.number, fontSize = 12.sp, color = NothingTokens.TextPrimary)
                    }
                }
                BareCallIcon(
                    tint = Color.White,
                    size = 20.dp,
                    modifier = Modifier.clickable { onPlaceCall(log.number) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MistShapes.Radius18)
                    .background(Color.White)
                    .clickable { onPlaceCall(log.number) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Call Back",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            SoftGlassBox(
                shape = MistShapes.Radius18,
                onClick = { onSaveNumber(log.number) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "＋ Save Contact",
                    color = NothingTokens.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        FrostedMistBox(
            shape = MistShapes.Radius22,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryRow(label = "Call Status") {
                    CallTypeBadge(
                        type = log.type,
                        isUnansweredOutgoing = log.isUnansweredOutgoing
                    )
                }
                TelemetryRow(label = "Date & Time") {
                    MonoNumText(
                        text = "${log.dateGroup} · ${log.timestamp}",
                        fontSize = 12.sp,
                        color = NothingTokens.TextPrimary
                    )
                }
                TelemetryRow(label = "Duration") {
                    NdotText(text = log.duration, fontSize = 12.sp)
                }
                if (showCodec) {
                    TelemetryRow(label = "Network & Audio") {
                        MonoNumText(
                            text = "${log.codec} · ${log.simLabel}",
                            fontSize = 11.sp,
                            color = NothingTokens.TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FrostedMistBox(
            shape = MistShapes.Radius22,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                ManagementRow(
                    title = "Save to Contacts",
                    trailing = "＋",
                    onClick = { onSaveNumber(log.number) }
                )
                ManagementRow(
                    title = "Send SMS Message",
                    trailing = "✉",
                    onClick = { onSendSms(log.number) }
                )
                ManagementRow(
                    title = "Edit number before calling",
                    trailing = "→",
                    onClick = { onEditBeforeCall(log.number) }
                )
                ManagementRow(
                    title = "Copy phone number",
                    trailing = log.number,
                    onClick = { onCopyNumber(log.number) }
                )
                ManagementRow(
                    title = if (isBlocked) "Unblock this number" else "Block & auto-reject calls",
                    trailing = if (isBlocked) "✓ BLOCKED" else "⊘",
                    titleColor = if (isBlocked) NothingTokens.EmeraldActive else NothingTokens.NothingRed,
                    onClick = { onBlockNumber(log.number) }
                )
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    valueContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = NothingTokens.TextSecondary, fontSize = 12.sp)
        valueContent()
    }
}

@Composable
private fun ManagementRow(
    title: String,
    trailing: String,
    titleColor: Color = NothingTokens.TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = titleColor, fontSize = 13.sp)
        MonoNumText(text = trailing, fontSize = 11.sp, color = titleColor)
    }
}

/**
 * SCREEN 10 — INTEGRATED UNIFIED SEARCH
 */
@Composable
fun UnifiedSearchScreen(
    contacts: List<ContactItem>,
    callLogs: List<CallLogItem>,
    onBack: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onSaveNumber: (String) -> Unit,
    onOpenContactDetails: (ContactItem) -> Unit,
    onOpenCallDetails: (CallLogItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("ALL") }

    val matchedContacts = remember(contacts, query) {
        if (query.isBlank()) contacts.take(5)
        else contacts.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                    it.number.contains(query) ||
                    it.role.contains(query, ignoreCase = true)
        }
    }

    val matchedLogs = remember(callLogs, query) {
        if (query.isBlank()) callLogs.take(4)
        else callLogs.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.number.contains(query) ||
                    it.type.name.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        FrostedMistBox(
            shape = MistShapes.Pill,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    color = NothingTokens.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 10.dp)
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = NothingTokens.TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(NothingTokens.NothingRed),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            MonoNumText(
                                text = "Search name or number...",
                                fontSize = 12.sp,
                                color = NothingTokens.TextMuted
                            )
                        }
                        inner()
                    }
                )
                if (query.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = NothingTokens.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { query = "" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "CONTACTS", "HISTORY").forEach { sc ->
                val active = scope == sc
                SoftGlassBox(
                    isSelected = active,
                    onClick = { scope = sc }
                ) {
                    MonoNumText(
                        text = sc,
                        fontSize = 10.sp,
                        color = if (active) Color.Black else NothingTokens.TextPrimary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (matchedContacts.isEmpty() && matchedLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NdotText(
                        text = "NO MATCHING RESULTS",
                        fontSize = 14.sp,
                        color = NothingTokens.NothingRed
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(MistShapes.Pill)
                                .background(Color.White)
                                .clickable { onPlaceCall(query) }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = "Call $query",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        SoftGlassBox(onClick = { onSaveNumber(query) }) {
                            Text(
                                text = "＋ Save to Contacts",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 104.dp)
            ) {
                if ((scope == "ALL" || scope == "CONTACTS") && matchedContacts.isNotEmpty()) {
                    item(key = "search_contacts_hdr") {
                        NdotText(
                            text = "CONTACTS",
                            fontSize = 11.sp,
                            color = NothingTokens.TextSecondary,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    items(matchedContacts, key = { "sc_${it.id}" }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlaceCall(c.number) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircleMonochromeAvatar(
                                    initials = c.initials,
                                    size = 42.dp,
                                    onClick = { onOpenContactDetails(c) }
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = c.name,
                                        color = NothingTokens.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    MonoNumText(text = c.number, fontSize = 11.sp)
                                }
                            }
                            BareCallIcon(tint = NothingTokens.TextPrimary, size = 19.dp)
                        }
                    }
                }

                if ((scope == "ALL" || scope == "HISTORY") && matchedLogs.isNotEmpty()) {
                    item(key = "search_logs_hdr") {
                        NdotText(
                            text = "HISTORY",
                            fontSize = 11.sp,
                            color = NothingTokens.TextSecondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                        )
                    }
                    items(matchedLogs, key = { "sl_${it.id}" }) { l ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenCallDetails(l) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = l.name,
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CallTypeBadge(
                                        type = l.type,
                                        isUnansweredOutgoing = l.isUnansweredOutgoing
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    MonoNumText(text = l.duration, fontSize = 10.sp)
                                }
                            }
                            MonoNumText(text = l.timestamp, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 12 — CLEAN SETTINGS ARCHITECTURE
 */
@Composable
fun SettingsScreen(
    settings: DialerSettingsState,
    customRingtoneCount: Int = 0,
    onUpdateSettings: (DialerSettingsState) -> Unit,
    onNavigateScreen: (String) -> Unit,
    onTestIncomingCallPopup: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftGlassBox(
                shape = CircleShape,
                onClick = { onNavigateScreen("recents") },
                modifier = Modifier.size(36.dp)
            ) {
                Text(text = "←", color = Color.White, fontSize = 15.sp)
            }
            NdotText(text = "SETTINGS", fontSize = 24.sp)
        }

        SettingsSectionHeader(title = "01 · RINGTONE & CALL EXPERIENCE", accent = true)
        FrostedMistBox(shape = MistShapes.Radius22, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsCleanNavRow(
                    title = "Contact Specific Ringtones",
                    badge = if (customRingtoneCount > 0) "$customRingtoneCount Custom Set →" else "Select Contact →",
                    badgeColor = NothingTokens.NothingRed,
                    onClick = { onNavigateScreen("contact_ringtones") }
                )
                SettingsCleanNavRow(
                    title = "Test Incoming Call Screen & Popup",
                    badge = "Simulate Ringing →",
                    badgeColor = NothingTokens.EmeraldActive,
                    onClick = onTestIncomingCallPopup
                )
                SettingsCleanNavRow(
                    title = "Call Portrait Mode",
                    badge = settings.portraitStyle.uppercase(),
                    onClick = {
                        val next = if (settings.portraitStyle == "full") "natural" else "full"
                        onUpdateSettings(settings.copy(portraitStyle = next))
                    }
                )
                SettingsCleanToggleRow(
                    title = "HD Voice & VoNR Readout",
                    checked = settings.showCallCodec,
                    onCheckedChange = { onUpdateSettings(settings.copy(showCallCodec = it)) }
                )
            }
        }

        SettingsSectionHeader(title = "02 · GENERAL & APPEARANCE")
        FrostedMistBox(shape = MistShapes.Radius22, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsCleanNavRow(
                    title = "Appearance & Mist Glass",
                    badge = settings.themeMode.uppercase(),
                    onClick = { onNavigateScreen("appearance") }
                )
                SettingsCleanToggleRow(
                    title = "Acoustic Haptic Feedback",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = { onUpdateSettings(settings.copy(hapticsEnabled = it)) }
                )
            }
        }

        SettingsSectionHeader(title = "03 · CONTACTS & HISTORY")
        FrostedMistBox(shape = MistShapes.Radius22, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsCleanNavRow(
                    title = "Contact Sort Order",
                    badge = if (settings.sortBy == "first") "First Name" else "Last Name",
                    onClick = {
                        val next = if (settings.sortBy == "first") "last" else "first"
                        onUpdateSettings(settings.copy(sortBy = next))
                    }
                )
                SettingsCleanToggleRow(
                    title = "Group Consecutive Calls",
                    checked = settings.groupConsecutiveCalls,
                    onCheckedChange = { onUpdateSettings(settings.copy(groupConsecutiveCalls = it)) }
                )
                SettingsCleanNavRow(
                    title = "Manage Favourites",
                    badge = "Open →",
                    onClick = { onNavigateScreen("favourites") }
                )
            }
        }

        SettingsSectionHeader(title = "04 · PRIVACY & RESILIENCE")
        FrostedMistBox(shape = MistShapes.Radius22, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsCleanNavRow(
                    title = "System Permissions & Role",
                    badge = "Verified",
                    badgeColor = NothingTokens.EmeraldActive,
                    onClick = { onNavigateScreen("permissions") }
                )
                SettingsCleanNavRow(
                    title = "Resilience & Edge States",
                    badge = "Inspect →",
                    badgeColor = NothingTokens.NothingRed,
                    onClick = { onNavigateScreen("edge_states") }
                )
            }
        }
    }
}

/**
 * SCREEN 14 — CONTACT-SPECIFIC RINGTONE SELECTOR
 */
@Composable
fun ContactRingtoneScreen(
    contacts: List<ContactItem>,
    customRingtoneMap: Map<String, String>,
    onBack: () -> Unit,
    onPickRingtoneForContact: (ContactItem) -> Unit,
    onResetContactRingtone: (ContactItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyCustom by remember { mutableStateOf(false) }

    val filteredContacts = remember(contacts, searchQuery, showOnlyCustom, customRingtoneMap) {
        contacts.filter { c ->
            val matchesSearch = searchQuery.isBlank() ||
                    c.displayName.contains(searchQuery, ignoreCase = true) ||
                    c.number.contains(searchQuery)
            val hasCustom = customRingtoneMap.containsKey(c.id)
            matchesSearch && (!showOnlyCustom || hasCustom)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftGlassBox(
                shape = CircleShape,
                onClick = onBack,
                modifier = Modifier.size(38.dp)
            ) {
                Text(text = "←", color = Color.White, fontSize = 15.sp)
            }
            Column {
                NdotText(text = "CONTACT RINGTONES", fontSize = 20.sp)
                MonoNumText(
                    text = "Tap any contact to choose a phone ringtone",
                    fontSize = 10.sp,
                    color = NothingTokens.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        SoftGlassBox(
            shape = MistShapes.Pill,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    BareSearchIcon(tint = NothingTokens.TextSecondary, size = 15.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = NothingTokens.TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(NothingTokens.NothingRed),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                MonoNumText(
                                    text = "Search contact name or number...",
                                    fontSize = 12.sp,
                                    color = NothingTokens.TextSecondary
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = NothingTokens.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { searchQuery = "" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftGlassBox(
                isSelected = !showOnlyCustom,
                onClick = { showOnlyCustom = false }
            ) {
                Text(
                    text = "All Contacts (${contacts.size})",
                    color = if (!showOnlyCustom) Color.Black else NothingTokens.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (!showOnlyCustom) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            SoftGlassBox(
                isSelected = showOnlyCustom,
                onClick = { showOnlyCustom = true }
            ) {
                Text(
                    text = "Custom Set (${customRingtoneMap.size})",
                    color = if (showOnlyCustom) Color.Black else NothingTokens.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (showOnlyCustom) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredContacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                NdotText(
                    text = "NO CONTACTS FOUND",
                    fontSize = 14.sp,
                    color = NothingTokens.TextSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredContacts, key = { "ring_${it.id}" }) { contact ->
                    val assignedTitle = customRingtoneMap[contact.id]
                    val hasCustom = !assignedTitle.isNullOrBlank()

                    FrostedMistBox(
                        shape = MistShapes.Radius20,
                        borderColor = if (hasCustom) NothingTokens.EmeraldActive.copy(alpha = 0.5f)
                        else NothingTokens.GlassBBorderTop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickRingtoneForContact(contact) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircleMonochromeAvatar(
                                    initials = contact.initials,
                                    size = 42.dp,
                                    hasAccentBorder = hasCustom
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        color = NothingTokens.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    MonoNumText(
                                        text = contact.number,
                                        fontSize = 11.sp,
                                        color = NothingTokens.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (hasCustom) "♪ $assignedTitle" else "♪ Default Phone Ringtone",
                                        color = if (hasCustom) NothingTokens.EmeraldActive else NothingTokens.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (hasCustom) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (hasCustom) {
                                    SoftGlassBox(
                                        onClick = { onResetContactRingtone(contact) }
                                    ) {
                                        Text(
                                            text = "Reset",
                                            color = NothingTokens.NothingRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(MistShapes.Pill)
                                        .background(if (hasCustom) Color.White else NothingTokens.NothingRed)
                                        .clickable { onPickRingtoneForContact(contact) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (hasCustom) "Change" else "Select",
                                        color = if (hasCustom) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, accent: Boolean = false) {
    NdotText(
        text = title,
        fontSize = 10.sp,
        color = if (accent) NothingTokens.NothingRed else NothingTokens.TextSecondary
    )
}

@Composable
private fun SettingsCleanNavRow(
    title: String,
    badge: String,
    badgeColor: Color = NothingTokens.TextSecondary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = NothingTokens.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        MonoNumText(text = badge, fontSize = 11.sp, color = badgeColor)
    }
}

@Composable
private fun SettingsCleanToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = NothingTokens.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(MistShapes.Pill)
                .background(if (checked) NothingTokens.NothingRed else Color(0xFF3F3F46))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * SCREEN 13 — APPEARANCE & MIST GLASS STUDIO
 */
@Composable
fun AppearanceScreen(
    settings: DialerSettingsState,
    onUpdateSettings: (DialerSettingsState) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftGlassBox(shape = CircleShape, onClick = onBack, modifier = Modifier.size(36.dp)) {
                Text(text = "←", color = Color.White, fontSize = 15.sp)
            }
            NdotText(text = "APPEARANCE & MIST", fontSize = 14.sp)
            Text(
                text = "Reset",
                color = NothingTokens.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onUpdateSettings(DialerSettingsState()) }
            )
        }

        FrostedMistBox(shape = MistShapes.Radius24, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                MonoNumText(text = "LIVE SURFACE PREVIEW", fontSize = 10.sp)
                Spacer(modifier = Modifier.height(10.dp))
                SoftGlassBox(shape = MistShapes.Radius18, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleMonochromeAvatar(initials = "AG", size = 42.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                NdotText(text = "ARIA GUPTA", fontSize = 13.sp)
                                MonoNumText(text = "+91 98455 67890", fontSize = 11.sp)
                            }
                        }
                        BareCallIcon(tint = Color.White, size = 19.dp)
                    }
                }
            }
        }

        AppearanceOptionGroup(
            title = "1. Surface Foundation",
            options = listOf("dark" to "Dark OLED", "atmosphere" to "Charcoal", "light" to "Light Mist"),
            selectedKey = settings.themeMode,
            onSelect = { onUpdateSettings(settings.copy(themeMode = it)) }
        )

        AppearanceOptionGroup(
            title = "2. Frosted-Mist Glass Intensity",
            options = listOf("subtle" to "Subtle 10dp", "balanced" to "Balanced 22dp", "deep" to "Deep 32dp"),
            selectedKey = settings.glassIntensity,
            onSelect = { onUpdateSettings(settings.copy(glassIntensity = it)) }
        )

        AppearanceOptionGroup(
            title = "3. Dot-Matrix Typography Intensity",
            options = listOf("restrained" to "Restrained", "standard" to "Standard", "expressive" to "Expressive"),
            selectedKey = settings.dotIntensity,
            onSelect = { onUpdateSettings(settings.copy(dotIntensity = it)) }
        )

        AppearanceOptionGroup(
            title = "4. Motion & Spring Physics",
            options = listOf("spring" to "Spring", "reduced" to "Reduced", "off" to "Off"),
            selectedKey = settings.motionMode,
            onSelect = { onUpdateSettings(settings.copy(motionMode = it)) }
        )
    }
}

@Composable
private fun AppearanceOptionGroup(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    FrostedMistBox(shape = MistShapes.Radius22, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, color = NothingTokens.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (key, label) ->
                    val active = selectedKey == key
                    SoftGlassBox(
                        shape = MistShapes.Radius12,
                        isSelected = active,
                        onClick = { onSelect(key) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            color = if (active) Color.Black else NothingTokens.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * SCREEN 15 — NON-IDEAL & EDGE STATES HUB
 */
@Immutable
private data class EdgeStateSpec(
    val id: String,
    val label: String,
    val title: String,
    val subtitle: String,
    val cta: String
)

@Composable
fun EdgeStatesScreen(
    onOpenDialPad: () -> Unit,
    onNavigateScreen: (String) -> Unit,
    onTriggerCall: (String) -> Unit
) {
    val catalog = remember {
        listOf(
            EdgeStateSpec("no_recents", "No Recents", "NO RECENT CALLS", "Your incoming, outgoing, and missed call timeline is clean.", "Open Dial Pad"),
            EdgeStateSpec("no_contacts", "No Contacts", "DIRECTORY IS EMPTY", "Create a new contact or import from your SIM or Google account.", "Browse Contacts"),
            EdgeStateSpec("no_favs", "No Favourites", "NO FAVOURITES PINNED", "Star important contacts for instant 1-tap calling.", "Open Favourites"),
            EdgeStateSpec("empty_search", "Empty Search", "ZERO MATCHES FOUND", "No contact or call history entry matched your query.", "Open Search"),
            EdgeStateSpec("unknown_caller", "Unknown Caller", "UNVERIFIED CALLER ID", "Carrier did not provide a CNAM identity; displaying abstract geometric fallback.", "Launch Unknown Call"),
            EdgeStateSpec("missing_photo", "Missing Photo", "ADAPTIVE MONOGRAM FALLBACK", "Contacts without portraits render a high-contrast circular monogram badge.", "View Contacts"),
            EdgeStateSpec("perm_denied", "Permission Denied", "PERMISSION REQUIRED", "Contacts or Call Log access was denied. Grant permission in Settings to continue.", "Open Permissions"),
            EdgeStateSpec("unsupported", "Unsupported Video", "CARRIER VIDEO UNAVAILABLE", "Your mobile operator does not currently support native ViLTE video calling on this line.", "Voice Call Instead"),
            EdgeStateSpec("loading", "Loading Skeleton", "SYNCHRONIZING CONTACTS", "Indexing local vCard entries through Frosted-Mist shimmer placeholders.", "View Directory"),
            EdgeStateSpec("temp_error", "Temp Failure", "RADIO / SIM TEMPORARILY BUSY", "Cellular modem is switching 5G bands. Please retry the call in a moment.", "Retry Call Now")
        )
    }

    var selectedId by remember { mutableStateOf("no_recents") }
    val current = catalog.find { it.id == selectedId } ?: catalog.first()

    val shimmerTransition = rememberInfiniteTransition(label = "mist_shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 104.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftGlassBox(
                shape = CircleShape,
                onClick = { onNavigateScreen("settings") },
                modifier = Modifier.size(36.dp)
            ) {
                Text(text = "←", color = Color.White, fontSize = 15.sp)
            }
            NdotText(text = "EDGE STATES", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        for (row in 0 until 5) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until 2) {
                    val item = catalog[row * 2 + col]
                    val active = item.id == selectedId
                    SoftGlassBox(
                        shape = MistShapes.Radius12,
                        isSelected = active,
                        onClick = { selectedId = item.id },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = item.label,
                            color = if (active) Color.Black else NothingTokens.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        FrostedMistBox(
            shape = MistShapes.Radius28,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (current.id == "loading") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(3) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MistShapes.Radius16)
                                    .background(Color.White.copy(alpha = 0.06f * shimmerAlpha))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f * shimmerAlpha))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .width(110.dp)
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.25f * shimmerAlpha))
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(76.dp)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.14f * shimmerAlpha))
                                    )
                                }
                            }
                        }
                    }
                } else {
                    SoftGlassBox(
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        NdotText(
                            text = when (current.id) {
                                "unknown_caller" -> "?"
                                "perm_denied", "temp_error" -> "!"
                                else -> "◌"
                            },
                            fontSize = 20.sp,
                            color = NothingTokens.NothingRed,
                            letterSpacing = 0.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                NdotText(text = current.title, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = current.subtitle,
                    color = NothingTokens.TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .clip(MistShapes.Pill)
                        .background(Color.White)
                        .clickable {
                            when (current.id) {
                                "no_recents" -> onOpenDialPad()
                                "no_contacts", "missing_photo", "loading" -> onNavigateScreen("contacts")
                                "no_favs" -> onNavigateScreen("favourites")
                                "empty_search" -> onNavigateScreen("search")
                                "perm_denied" -> onNavigateScreen("permissions")
                                else -> onTriggerCall("+44 7700 900412")
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = current.cta,
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
