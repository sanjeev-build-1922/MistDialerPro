package com.example.mistdialer

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*

/**
 * SCREEN 01 — CLEAN & SMOOTH RECENTS (120FPS Optimized)
 * - Strictly ordered by newest call first (latest incoming or outgoing call is ALWAYS at the top)
 * - Unanswered Outgoing calls show `↗ Outgoing` in Nothing Red below the name
 * - 3-dot icon (⋮) on the left of "RECENTS" heading to open Settings
 * - Circular profile avatar (tap avatar -> opens Contact Action Card with Save Contact option)
 * - Tap anywhere else on the row (or bare Call icon) -> immediately places call
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsScreen(
    callLogs: List<CallLogItem>,
    contacts: List<ContactItem>,
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    expandedLogId: String?,
    onToggleExpand: (String) -> Unit,
    onOpenDialPad: () -> Unit,
    onOpenActionCard: (ContactItem) -> Unit,
    onPlaceCall: (String) -> Unit,
    onSendSms: (String) -> Unit,
    onCopyNumber: (String) -> Unit,
    onSaveNumber: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenCallDetails: (CallLogItem) -> Unit = {}
) {
    val sortedLogs = remember(callLogs) {
        callLogs.sortedByDescending { it.dateMillis }
    }

    val filteredLogs = remember(sortedLogs, selectedFilter) {
        if (selectedFilter == "ALL") {
            sortedLogs
        } else {
            sortedLogs.filter { it.type.name == selectedFilter }
        }
    }

    val contactsById = remember(contacts) {
        val map = HashMap<String, ContactItem>(contacts.size * 2)
        for (c in contacts) {
            map[c.id] = c
            if (c.normalizedPhoneKey.isNotEmpty()) {
                map.putIfAbsent(c.normalizedPhoneKey, c)
            }
        }
        map
    }

    val missedCount = remember(sortedLogs) { sortedLogs.count { it.type == CallType.MISSED } }
    val todayLogs = remember(filteredLogs) { filteredLogs.filter { it.dateGroup == "TODAY" } }
    val olderLogs = remember(filteredLogs) { filteredLogs.filter { it.dateGroup != "TODAY" } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with 3-Dot Settings Trigger just to the left of RECENTS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BareThreeDotsIcon(
                    tint = NothingTokens.TextPrimary,
                    size = 22.dp,
                    onClick = onOpenSettings
                )
                Spacer(modifier = Modifier.width(6.dp))
                NdotText(text = "RECENTS", fontSize = 24.sp)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SoftGlassBox(
                    shape = CircleShape,
                    onClick = onOpenSearch,
                    modifier = Modifier.size(38.dp)
                ) {
                    BareSearchIcon(tint = Color.White, size = 17.dp)
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(NothingTokens.NothingRed)
                        .clickable { onOpenDialPad() },
                    contentAlignment = Alignment.Center
                ) {
                    VectorKeypadIcon(tint = Color.White, size = 18.dp)
                }
            }
        }

        // Filter Chips
        val filters = remember {
            listOf(
                "ALL" to "All",
                "MISSED" to "Missed",
                "INCOMING" to "Incoming",
                "OUTGOING" to "Outgoing"
            )
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters, key = { it.first }) { (key, label) ->
                val isSelected = selectedFilter == key
                SoftGlassBox(
                    isSelected = isSelected,
                    onClick = { onFilterChange(key) }
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.Black else NothingTokens.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NdotText(
                        text = "NO ${if (selectedFilter == "ALL") "RECENT" else selectedFilter} CALLS",
                        fontSize = 14.sp,
                        color = NothingTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoNumText(
                        text = "Your call timeline for this filter is clean",
                        fontSize = 11.sp,
                        color = NothingTokens.TextMuted
                    )
                }
            }
        } else {
            // Strictly Chronological List (Latest Call Always at the Very Top)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 104.dp)
            ) {
                if (todayLogs.isNotEmpty()) {
                    item(key = "header_today", contentType = "section_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NdotText(
                                text = "TODAY",
                                fontSize = 11.sp,
                                color = NothingTokens.TextSecondary
                            )
                            if (missedCount > 0) {
                                MonoNumText(
                                    text = "$missedCount MISSED",
                                    fontSize = 10.sp,
                                    color = NothingTokens.NothingRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    items(
                        items = todayLogs,
                        key = { it.id },
                        contentType = { "recent_row" }
                    ) { log ->
                        val matched = contactsById[log.contactId] ?: contactsById[log.normalizedPhoneKey]
                        RecentCallRowItem(
                            log = log,
                            matchedContact = matched,
                            isExpanded = expandedLogId == log.id,
                            onRowCallClick = { onPlaceCall(log.number) },
                            onAvatarClick = { contact -> onOpenActionCard(contact) },
                            onToggleExpand = { onToggleExpand(log.id) },
                            onOpenCallDetails = { onOpenCallDetails(log) },
                            onSaveContact = { onSaveNumber(log.number) },
                            onSendSms = { onSendSms(log.number) },
                            onCopyNumber = { onCopyNumber(log.number) }
                        )
                    }
                }

                if (olderLogs.isNotEmpty()) {
                    item(key = "header_earlier", contentType = "section_header") {
                        NdotText(
                            text = "EARLIER",
                            fontSize = 11.sp,
                            color = NothingTokens.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    items(
                        items = olderLogs,
                        key = { it.id },
                        contentType = { "recent_row" }
                    ) { log ->
                        val matched = contactsById[log.contactId] ?: contactsById[log.normalizedPhoneKey]
                        RecentCallRowItem(
                            log = log,
                            matchedContact = matched,
                            isExpanded = expandedLogId == log.id,
                            onRowCallClick = { onPlaceCall(log.number) },
                            onAvatarClick = { contact -> onOpenActionCard(contact) },
                            onToggleExpand = { onToggleExpand(log.id) },
                            onOpenCallDetails = { onOpenCallDetails(log) },
                            onSaveContact = { onSaveNumber(log.number) },
                            onSendSms = { onSendSms(log.number) },
                            onCopyNumber = { onCopyNumber(log.number) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCallRowItem(
    log: CallLogItem,
    matchedContact: ContactItem?,
    isExpanded: Boolean,
    onRowCallClick: () -> Unit,
    onAvatarClick: (ContactItem) -> Unit,
    onToggleExpand: () -> Unit,
    onOpenCallDetails: () -> Unit,
    onSaveContact: () -> Unit,
    onSendSms: () -> Unit,
    onCopyNumber: () -> Unit
) {
    val contact = matchedContact ?: remember(log.id, log.name, log.number) {
        ContactItem(
            id = log.contactId,
            name = log.name,
            displayName = log.name,
            role = "Unsaved Number",
            tag = log.simLabel,
            number = log.number,
            isSavedContact = log.isSaved
        )
    }
    val isMissed = log.type == CallType.MISSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowCallClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CircleMonochromeAvatar(
                    initials = contact.initials,
                    size = 44.dp,
                    onClick = { onAvatarClick(contact) }
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = log.name,
                            color = if (isMissed) NothingTokens.NothingRed else NothingTokens.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isMissed) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (log.groupCount > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MonoNumText(
                                text = "(${log.groupCount})",
                                fontSize = 11.sp,
                                color = NothingTokens.TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CallTypeBadge(
                            type = log.type,
                            isUnansweredOutgoing = log.isUnansweredOutgoing
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MonoNumText(
                            text = log.timestamp,
                            fontSize = 11.sp,
                            color = NothingTokens.TextSecondary
                        )
                    }
                }
            }

            // Bare Call Icon
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRowCallClick),
                contentAlignment = Alignment.Center
            ) {
                BareCallIcon(tint = NothingTokens.TextPrimary, size = 19.dp)
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            FrostedMistBox(
                shape = MistShapes.Radius18,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InlineDrawerButton(
                        iconText = "＋",
                        label = "Save",
                        accentColor = NothingTokens.NothingRed,
                        onClick = onSaveContact
                    )
                    InlineDrawerButton(
                        iconText = "ⓘ",
                        label = "Details",
                        onClick = onOpenCallDetails
                    )
                    InlineDrawerButton(
                        iconText = "✉",
                        label = "Message",
                        onClick = onSendSms
                    )
                    InlineDrawerButton(
                        iconText = "⎘",
                        label = "Copy",
                        onClick = onCopyNumber
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineDrawerButton(
    iconText: String,
    label: String,
    accentColor: Color = NothingTokens.TextPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MistShapes.Radius12)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = iconText, color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = NothingTokens.TextPrimary, fontSize = 11.sp)
    }
}

@Composable
fun CallTypeBadge(
    type: CallType,
    isUnansweredOutgoing: Boolean = false
) {
    val (symbol, label, color) = when (type) {
        CallType.MISSED -> Triple("● ", "Missed", NothingTokens.NothingRed)
        CallType.INCOMING -> Triple("↙ ", "Incoming", NothingTokens.TextSecondary)
        CallType.OUTGOING -> Triple(
            "↗ ",
            "Outgoing",
            if (isUnansweredOutgoing) NothingTokens.NothingRed else NothingTokens.TextSecondary
        )
        CallType.DECLINED -> Triple("✕ ", "Declined", NothingTokens.NothingRed)
    }
    val isRed = (type == CallType.MISSED) ||
            (type == CallType.DECLINED) ||
            (type == CallType.OUTGOING && isUnansweredOutgoing)

    Text(
        text = "$symbol$label",
        color = color,
        fontSize = 11.sp,
        fontWeight = if (isRed) FontWeight.SemiBold else FontWeight.Normal
    )
}

/**
 * SCREEN 02 — SLEEK FLOATING DETACHED DIAL PAD CONSOLE
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialPadSheetOverlay(
    dialNumber: String,
    onDigitPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
    contacts: List<ContactItem>,
    onPlaceCall: (String) -> Unit,
    onSaveNumber: (String) -> Unit,
    onOpenContactCard: (ContactItem) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_DTMF, 65)
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { toneGenerator?.release() }
    }

    val cleanDigits = remember(dialNumber) { dialNumber.filter { it.isDigit() } }
    val matchedContact = remember(cleanDigits, contacts) {
        if (cleanDigits.length >= 3) {
            contacts.find { it.normalizedPhoneKey.contains(cleanDigits.takeLast(8)) || it.number.filter { c -> c.isDigit() }.contains(cleanDigits) }
        } else {
            null
        }
    }

    val dialKeys = remember {
        listOf(
            "1" to "مه",
            "2" to "ABC",
            "3" to "DEF",
            "4" to "GHI",
            "5" to "JKL",
            "6" to "MNO",
            "7" to "PQRS",
            "8" to "TUV",
            "9" to "WXYZ"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAD000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        FocalGlassBox(
            shape = MistShapes.Radius36,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clickable(enabled = false) {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(MistShapes.Pill)
                        .background(Color(0x38FFFFFF))
                        .clickable { onDismiss() }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    NdotText(
                        text = dialNumber,
                        fontSize = if (dialNumber.length > 14) 20.sp else 28.sp,
                        color = Color.White,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (matchedContact != null) {
                        SoftGlassBox(onClick = { onOpenContactCard(matchedContact) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(NothingTokens.EmeraldActive)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${matchedContact.displayName} · ${matchedContact.number}",
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else if (dialNumber.isNotBlank()) {
                        SoftGlassBox(onClick = { onSaveNumber(dialNumber) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                VectorSaveContactIcon(tint = NothingTokens.NothingRed, size = 15.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Save to Contacts",
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                for (row in 0 until 3) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (col in 0 until 3) {
                            val (digit, sub) = dialKeys[row * 3 + col]
                            DialPadCircleButton(
                                mainText = digit,
                                subText = sub,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    toneGenerator?.startTone(ToneGenerator.TONE_DTMF_1, 65)
                                    onDigitPress(digit)
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DialPadCircleButton(
                        mainText = "*",
                        subText = "",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigitPress("*")
                        }
                    )
                    DialPadCircleButton(
                        mainText = "0",
                        subText = "+",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigitPress("0")
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDigitPress("+")
                        }
                    )
                    DialPadCircleButton(
                        mainText = "#",
                        subText = "",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigitPress("#")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SoftGlassBox(
                        shape = CircleShape,
                        onClick = { onDismiss() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(text = "↓", color = NothingTokens.TextPrimary, fontSize = 16.sp)
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                if (dialNumber.isNotBlank()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPlaceCall(dialNumber)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BareCallIcon(tint = Color.Black, size = 24.dp)
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NothingTokens.NothingRed)
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onBackspace()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onClearAll()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⌫", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialPadCircleButton(
    mainText: String,
    subText: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(NothingTokens.GlassCBg)
            .border(1.dp, NothingTokens.GlassCBorder, CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NdotText(
                text = mainText,
                fontSize = 22.sp,
                color = Color.White,
                letterSpacing = 0.sp
            )
            if (subText.isNotEmpty()) {
                MonoNumText(
                    text = subText,
                    fontSize = 9.sp,
                    color = NothingTokens.TextSecondary
                )
            }
        }
    }
}

/**
 * SCREEN 03 — CLEAN CONTACTS DIRECTORY
 */
@Composable
fun ContactsScreen(
    contacts: List<ContactItem>,
    sortBy: String = "first",
    onPlaceCall: (String) -> Unit,
    onOpenActionCard: (ContactItem) -> Unit,
    onAddNewContact: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeLetter by remember { mutableStateOf("ALL") }
    var favOnly by remember { mutableStateOf(false) }

    val sortedContacts = remember(contacts, sortBy) {
        if (sortBy == "last") {
            contacts.sortedBy { it.displayName.substringAfterLast(" ").uppercase() }
        } else {
            contacts.sortedBy { it.displayName.uppercase() }
        }
    }

    val availableLetters = remember(sortedContacts) {
        listOf("ALL") + sortedContacts.map { it.letter }.distinct().sorted()
    }

    val filteredContacts = remember(sortedContacts, searchQuery, activeLetter, favOnly) {
        sortedContacts.filter { c ->
            val matchesQuery = searchQuery.isBlank() ||
                    c.displayName.contains(searchQuery, ignoreCase = true) ||
                    c.number.contains(searchQuery)
            val matchesLetter = activeLetter == "ALL" || c.letter == activeLetter
            val matchesFav = !favOnly || c.isFavourite
            matchesQuery && matchesLetter && matchesFav
        }
    }

    val grouped = remember(filteredContacts) {
        filteredContacts.groupBy { it.letter }.toSortedMap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BareThreeDotsIcon(
                            tint = NothingTokens.TextPrimary,
                            size = 22.dp,
                            onClick = onOpenSettings
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        NdotText(text = "CONTACTS", fontSize = 24.sp)
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(NothingTokens.NothingRed)
                            .clickable { onAddNewContact(searchQuery) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                SoftGlassBox(
                    shape = MistShapes.Pill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp)
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
                                            text = "Search contacts...",
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableLetters.take(4).forEach { letter ->
                            SoftGlassBox(
                                isSelected = activeLetter == letter,
                                onClick = { activeLetter = letter }
                            ) {
                                NdotText(
                                    text = letter,
                                    fontSize = 10.sp,
                                    color = if (activeLetter == letter) Color.Black else NothingTokens.TextPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    SoftGlassBox(
                        isSelected = favOnly,
                        onClick = { favOnly = !favOnly }
                    ) {
                        Text(
                            text = "Favourites",
                            color = if (favOnly) Color.Black else NothingTokens.TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 18.dp),
                    contentPadding = PaddingValues(bottom = 104.dp)
                ) {
                    grouped.forEach { (letter, sectionContacts) ->
                        item(key = "section_$letter", contentType = "letter_header") {
                            NdotText(
                                text = letter,
                                fontSize = 11.sp,
                                color = NothingTokens.TextSecondary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                            )
                        }

                        items(
                            items = sectionContacts,
                            key = { it.id },
                            contentType = { "contact_row" }
                        ) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlaceCall(contact.number) }
                                    .padding(horizontal = 20.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    CircleMonochromeAvatar(
                                        initials = contact.initials,
                                        size = 44.dp,
                                        onClick = { onOpenActionCard(contact) }
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            color = NothingTokens.TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 0.6.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        MonoNumText(text = contact.number, fontSize = 11.sp)
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable { onPlaceCall(contact.number) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BareCallIcon(tint = NothingTokens.TextPrimary, size = 19.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.52f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            availableLetters.forEach { letter ->
                val isSelected = activeLetter == letter
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 8.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) NothingTokens.NothingRed else Color(0x66FFFFFF))
                        .clickable { activeLetter = letter }
                )
            }
        }
    }
}

/**
 * SCREEN 08 — PREMIUM CONTACT ACTION CARD OVERLAY
 */
@Composable
fun ContactActionCardOverlay(
    contact: ContactItem,
    onDismiss: () -> Unit,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (ContactItem) -> Unit,
    onSaveContact: (ContactItem) -> Unit,
    onToggleFavourite: (ContactItem) -> Unit,
    onOpenFullDetails: ((ContactItem) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8000000))
            .clickable { onDismiss() }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleMonochromeAvatar(
                        initials = contact.initials,
                        size = 48.dp,
                        hasAccentBorder = true
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        NdotText(text = contact.name, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        MonoNumText(text = contact.number, fontSize = 12.sp, color = NothingTokens.TextPrimary)
                    }
                }

                SoftGlassBox(
                    shape = CircleShape,
                    onClick = { onDismiss() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(text = "✕", color = NothingTokens.TextPrimary, fontSize = 12.sp)
                }
            }

            FocalGlassBox(
                shape = MistShapes.Radius32,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionCardPillButton(
                            icon = "✆",
                            label = "Call",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onCall(contact.number)
                            }
                        )
                        ActionCardPillButton(
                            icon = "✉",
                            label = "Message",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onText(contact.number)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionCardPillButton(
                            icon = "＋",
                            label = "Save Contact",
                            accentIconColor = NothingTokens.NothingRed,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onDismiss()
                                onSaveContact(contact)
                            }
                        )
                        ActionCardPillButton(
                            icon = if (contact.isFavourite) "★" else "☆",
                            label = if (contact.isFavourite) "Favourited" else "Add to fav.",
                            accentIconColor = if (contact.isFavourite) NothingTokens.NothingRed else Color.White,
                            modifier = Modifier.weight(1f),
                            onClick = { onToggleFavourite(contact) }
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(NothingTokens.NothingRed)
                                .clickable { onCopy(contact.number) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⎘", color = Color.White, fontSize = 17.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable { onShare(contact) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "⤴", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0x14FFFFFF))
                                .border(1.dp, Color(0x40FFFFFF), CircleShape)
                                .clickable {
                                    if (onOpenFullDetails != null) {
                                        onDismiss()
                                        onOpenFullDetails(contact)
                                    } else {
                                        onSaveContact(contact)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "ⓘ", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCardPillButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    accentIconColor: Color = Color.White,
    onClick: () -> Unit
) {
    SoftGlassBox(
        shape = MistShapes.Pill,
        onClick = onClick,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, color = accentIconColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = NothingTokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * SCREEN 11 — CLEAN FAVOURITES GRID
 * Includes "+ Add Favourite" button and picker dialog to easily pin contacts to favourites
 */
@Composable
fun FavouritesScreen(
    contacts: List<ContactItem>,
    onToggleFavourite: (ContactItem) -> Unit = {},
    onPlaceCall: (String) -> Unit,
    onSendSms: (String) -> Unit,
    onOpenActionCard: (ContactItem) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val favourites = remember(contacts) { contacts.filter { it.isFavourite } }
    var showAddFavPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BareThreeDotsIcon(
                    tint = NothingTokens.TextPrimary,
                    size = 22.dp,
                    onClick = onOpenSettings
                )
                Spacer(modifier = Modifier.width(6.dp))
                NdotText(text = "FAVOURITES", fontSize = 24.sp)
            }

            // High-Contrast Red Circle "+" Add Button to add contacts to favourites
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(NothingTokens.NothingRed)
                    .clickable { showAddFavPicker = true },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (favourites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NdotText(text = "NO FAVOURITES PINNED", fontSize = 14.sp, color = NothingTokens.TextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(MistShapes.Pill)
                            .background(Color.White)
                            .clickable { showAddFavPicker = true }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "+ Add Favourite",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 6.dp, bottom = 104.dp)
            ) {
                items(favourites, key = { it.id }) { contact ->
                    FrostedMistBox(
                        shape = MistShapes.Radius24,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaceCall(contact.number) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircleMonochromeAvatar(
                                    initials = contact.initials,
                                    size = 46.dp,
                                    onClick = { onOpenActionCard(contact) }
                                )
                                BareCallIcon(tint = NothingTokens.TextPrimary, size = 18.dp)
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            NdotText(
                                text = contact.name,
                                fontSize = 13.sp,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            MonoNumText(
                                text = contact.number,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet to choose any contact to add to Favourites
    if (showAddFavPicker) {
        var pickerQuery by remember { mutableStateOf("") }
        val nonFavContacts = remember(contacts, pickerQuery) {
            contacts.filter { c ->
                !c.isFavourite && (
                    pickerQuery.isBlank() ||
                    c.displayName.contains(pickerQuery, ignoreCase = true) ||
                    c.number.contains(pickerQuery)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xD0000000))
                .clickable { showAddFavPicker = false }
                .padding(horizontal = 20.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            FocalGlassBox(
                shape = MistShapes.Radius32,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            NdotText(text = "ADD TO FAVOURITES", fontSize = 16.sp)
                            MonoNumText(
                                text = "Tap any contact to star and pin",
                                fontSize = 10.sp,
                                color = NothingTokens.TextSecondary
                            )
                        }
                        SoftGlassBox(
                            shape = CircleShape,
                            onClick = { showAddFavPicker = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text(text = "✕", color = Color.White, fontSize = 12.sp)
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
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BareSearchIcon(tint = NothingTokens.TextSecondary, size = 15.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            BasicTextField(
                                value = pickerQuery,
                                onValueChange = { pickerQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = NothingTokens.TextPrimary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(NothingTokens.NothingRed),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (pickerQuery.isEmpty()) {
                                        MonoNumText(
                                            text = "Search contact name or number...",
                                            fontSize = 12.sp,
                                            color = NothingTokens.TextSecondary
                                        )
                                    }
                                    inner()
                                }
                            )
                            if (pickerQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = NothingTokens.TextSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { pickerQuery = "" }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (nonFavContacts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            NdotText(
                                text = if (pickerQuery.isBlank()) "ALL CONTACTS ALREADY FAVOURITES" else "NO MATCHING CONTACTS",
                                fontSize = 12.sp,
                                color = NothingTokens.TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(nonFavContacts, key = { "add_fav_${it.id}" }) { contact ->
                                FrostedMistBox(
                                    shape = MistShapes.Radius18,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onToggleFavourite(contact)
                                            showAddFavPicker = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            CircleMonochromeAvatar(
                                                initials = contact.initials,
                                                size = 38.dp
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
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(MistShapes.Pill)
                                                .background(NothingTokens.NothingRed)
                                                .padding(horizontal = 12.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = "+ Add",
                                                color = Color.White,
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
    }
}
