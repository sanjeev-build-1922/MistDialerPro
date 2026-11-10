package com.example.mistdialer

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var resumeTrigger by mutableLongStateOf(0L)
    private var openDialpadRequestTick by mutableLongStateOf(0L)
    private var initialDialNumberState by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enable120HzHighRefreshRate()
        parseIncomingDialIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = NothingTokens.BgFoundation,
                    surface = NothingTokens.GlassBBg,
                    primary = NothingTokens.NothingRed,
                    onPrimary = Color.White,
                    onBackground = NothingTokens.TextPrimary,
                    onSurface = NothingTokens.TextPrimary
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NothingTokens.BgFoundation
                ) {
                    NothingDialerRoot(
                        initialDialNumber = initialDialNumberState,
                        openDialpadRequestTick = openDialpadRequestTick,
                        activityResumeTick = resumeTrigger
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIncomingDialIntent(intent)
    }

    private fun parseIncomingDialIntent(intent: Intent?) {
        val schemeNumber = intent?.data?.schemeSpecificPart ?: ""
        if (schemeNumber.isNotBlank()) {
            initialDialNumberState = schemeNumber
            openDialpadRequestTick = System.currentTimeMillis()
        } else if (intent?.getBooleanExtra(EXTRA_OPEN_DIALPAD, false) == true) {
            openDialpadRequestTick = System.currentTimeMillis()
        }
    }

    override fun onResume() {
        super.onResume()
        CallStateController.isMainActivityVisible = true
        resumeTrigger = System.currentTimeMillis()
    }

    override fun onPause() {
        CallStateController.isMainActivityVisible = false
        super.onPause()
    }

    private fun enable120HzHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = display
                if (display != null) {
                    val supportedModes = display.supportedModes
                    val maxMode = supportedModes.maxByOrNull { it.refreshRate }
                    if (maxMode != null && maxMode.refreshRate >= 90f) {
                        window.attributes = window.attributes.apply {
                            preferredDisplayModeId = maxMode.modeId
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val modes = window.windowManager.defaultDisplay.supportedModes
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null && maxMode.refreshRate >= 90f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val EXTRA_OPEN_DIALPAD = "extra_open_dialpad"
    }
}

@Composable
fun NothingDialerRoot(
    initialDialNumber: String = "",
    openDialpadRequestTick: Long = 0L,
    activityResumeTick: Long = 0L
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val runtimePermissions = remember {
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.WRITE_CALL_LOG)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var isDefaultDialer by remember { mutableStateOf(checkIsDefaultDialer(context)) }
    var hasContactsPermission by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS))
    }
    var hasCallLogPermission by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.READ_CALL_LOG))
    }
    var hasPhonePermission by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.CALL_PHONE))
    }
    var canUseFullScreenPopup by remember {
        mutableStateOf(checkCanUseFullScreenIntent(context))
    }

    var currentScreen by remember {
        mutableStateOf(
            if (isDefaultDialer && hasContactsPermission && hasCallLogPermission) "recents" else "permissions"
        )
    }

    var contacts by remember { mutableStateOf(DialerRepository.sampleContacts) }
    var callLogs by remember { mutableStateOf(DialerRepository.sampleCallLogs) }
    var customRingtoneMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var settings by remember { mutableStateOf(DialerRepository.loadSettings(context)) }

    var selectedContact by remember { mutableStateOf(DialerRepository.sampleContacts.last()) }
    var selectedCallLog by remember { mutableStateOf(DialerRepository.sampleCallLogs.first()) }
    var pendingCallNumberAfterRole by remember { mutableStateOf<String?>(null) }
    var ringtoneTargetContact by remember { mutableStateOf<ContactItem?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    val liveCallState by CallStateController.uiState.collectAsState()
    val callLogUpdateTick by CallStateController.callLogUpdateTick.collectAsState()

    var reloadJob by remember { mutableStateOf<Job?>(null) }

    fun reloadDeviceDataCoalesced(delayMs: Long = 0L) {
        reloadJob?.cancel()
        reloadJob = coroutineScope.launch {
            if (delayMs > 0L) delay(delayMs)
            val (loadedContacts, loadedLogs, loadedRingtones) = withContext(Dispatchers.IO) {
                val cList = DialerRepository.loadContacts(context)
                val lList = DialerRepository.loadCallLogs(
                    context = context,
                    contacts = cList,
                    groupConsecutive = settings.groupConsecutiveCalls
                )
                val rMap = DialerRepository.getAllCustomRingtoneTitles(context, cList)
                Triple(cList, lList, rMap)
            }
            contacts = loadedContacts
            callLogs = loadedLogs
            customRingtoneMap = loadedRingtones
        }
    }

    fun refreshPermissionStates() {
        isDefaultDialer = checkIsDefaultDialer(context)
        hasContactsPermission = hasPermission(context, Manifest.permission.READ_CONTACTS)
        hasCallLogPermission = hasPermission(context, Manifest.permission.READ_CALL_LOG)
        hasPhonePermission = hasPermission(context, Manifest.permission.CALL_PHONE)
        canUseFullScreenPopup = checkCanUseFullScreenIntent(context)
        reloadDeviceDataCoalesced()
        if (isDefaultDialer && hasContactsPermission && hasCallLogPermission && currentScreen == "permissions") {
            currentScreen = "recents"
        }
    }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionStates()
    }

    val dialerRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionStates()
        if (checkIsDefaultDialer(context)) {
            runtimePermissionsLauncher.launch(runtimePermissions)
            val queued = pendingCallNumberAfterRole
            if (!queued.isNullOrBlank()) {
                pendingCallNumberAfterRole = null
                DialerRepository.placeCall(context, queued)
            }
        }
    }

    // Native Android System Ringtone Picker Launcher
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = ringtoneTargetContact
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            val data = result.data
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (pickedUri != null) {
                val title = DialerRepository.setContactCustomRingtone(context, target, pickedUri)
                customRingtoneMap = DialerRepository.getAllCustomRingtoneTitles(context, contacts)
                toastMessage = "♪ ${target.displayName}: $title"
            } else {
                DialerRepository.clearContactCustomRingtone(context, target)
                customRingtoneMap = DialerRepository.getAllCustomRingtoneTitles(context, contacts)
                toastMessage = "Reset to Default Ringtone"
            }
        }
    }

    fun launchSystemRingtonePicker(contact: ContactItem) {
        ringtoneTargetContact = contact
        val (existingUriStr, _) = DialerRepository.getContactCustomRingtoneInfo(context, contact)
        val currentUri = if (!existingUriStr.isNullOrBlank()) {
            try {
                Uri.parse(existingUriStr)
            } catch (_: Throwable) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        try {
            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for ${contact.displayName}")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                )
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            }
            ringtonePickerLauncher.launch(pickerIntent)
        } catch (_: Throwable) {
            toastMessage = "Unable to open system ringtone picker"
        }
    }

    fun triggerPermissionFlow() {
        refreshPermissionStates()
        if (!isDefaultDialer) {
            val roleIntent = createDefaultDialerIntent(context)
            if (roleIntent != null) {
                dialerRoleLauncher.launch(roleIntent)
            } else {
                runtimePermissionsLauncher.launch(runtimePermissions)
            }
        } else {
            runtimePermissionsLauncher.launch(runtimePermissions)
        }
    }

    fun placeCallEnsuringDefaultDialer(rawNumber: String) {
        val clean = rawNumber.trim()
        if (clean.isEmpty()) return
        if (!checkIsDefaultDialer(context)) {
            pendingCallNumberAfterRole = clean
            val roleIntent = createDefaultDialerIntent(context)
            if (roleIntent != null) {
                dialerRoleLauncher.launch(roleIntent)
                return
            }
        }
        DialerRepository.placeCall(context, clean)
    }

    LaunchedEffect(Unit) {
        reloadDeviceDataCoalesced()
        triggerPermissionFlow()
    }

    LaunchedEffect(activityResumeTick) {
        if (activityResumeTick > 0L) {
            isDefaultDialer = checkIsDefaultDialer(context)
            canUseFullScreenPopup = checkCanUseFullScreenIntent(context)
            reloadDeviceDataCoalesced()
        }
    }

    LaunchedEffect(callLogUpdateTick, liveCallState.stage) {
        reloadDeviceDataCoalesced(delayMs = if (liveCallState.stage == UiCallStage.ENDED) 450L else 0L)
    }

    DisposableEffect(context, hasCallLogPermission) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                reloadDeviceDataCoalesced(delayMs = 150L)
            }
        }
        if (hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            try {
                context.contentResolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    true,
                    observer
                )
            } catch (_: Exception) {
            }
        }
        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {
            }
        }
    }

    var recentsFilter by remember { mutableStateOf("ALL") }
    var expandedLogId by remember { mutableStateOf<String?>(null) }
    var isDialSheetOpen by remember { mutableStateOf(initialDialNumber.isNotEmpty()) }
    var dialNumber by remember { mutableStateOf(initialDialNumber) }
    var activeActionCardContact by remember { mutableStateOf<ContactItem?>(null) }

    LaunchedEffect(initialDialNumber, openDialpadRequestTick) {
        if (initialDialNumber.isNotEmpty()) {
            dialNumber = initialDialNumber
            isDialSheetOpen = true
        } else if (openDialpadRequestTick > 0L) {
            isDialSheetOpen = true
        }
    }

    // Native BackHandler so pressing Back closes overlays or returns to Recents smoothly
    val shouldInterceptBack = activeActionCardContact != null ||
            isDialSheetOpen ||
            (currentScreen != "recents" && currentScreen != "permissions")

    BackHandler(enabled = shouldInterceptBack) {
        when {
            activeActionCardContact != null -> activeActionCardContact = null
            isDialSheetOpen -> isDialSheetOpen = false
            currentScreen == "contact_details" -> currentScreen = "contacts"
            currentScreen == "contact_ringtones" ||
                    currentScreen == "appearance" ||
                    currentScreen == "edge_states" -> currentScreen = "settings"
            else -> currentScreen = "recents"
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2200)
            toastMessage = null
        }
    }

    DotMatrixBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isDefaultDialer && currentScreen != "permissions") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(MistShapes.Radius18)
                        .background(NothingTokens.NothingRed)
                        .clickable { triggerPermissionFlow() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set 'Mist Phone' as Default Dialer",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "ENABLE →",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (liveCallState.stage != UiCallStage.ENDED) {
                InAppCallPopupBanner(
                    state = liveCallState,
                    onOpenFullCall = {
                        val intent = Intent(context, CallActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(intent)
                    },
                    onAnswer = {
                        if (liveCallState.hasWaitingCall) {
                            CallStateController.answerWaitingCall()
                        } else {
                            CallStateController.answerCall(openCallScreen = true)
                        }
                    },
                    onDeclineOrEnd = {
                        if (liveCallState.hasWaitingCall) {
                            CallStateController.declineWaitingCall()
                        } else {
                            CallStateController.endOrDeclineCall()
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (currentScreen) {
                    "recents" -> RecentsScreen(
                        callLogs = callLogs,
                        contacts = contacts,
                        selectedFilter = recentsFilter,
                        onFilterChange = { recentsFilter = it },
                        expandedLogId = expandedLogId,
                        onToggleExpand = { id ->
                            expandedLogId = if (expandedLogId == id) null else id
                        },
                        onOpenDialPad = { isDialSheetOpen = true },
                        onOpenActionCard = { activeActionCardContact = it },
                        onPlaceCall = { number ->
                            placeCallEnsuringDefaultDialer(number)
                        },
                        onSendSms = { number ->
                            DialerRepository.openSmsComposer(context, number)
                        },
                        onCopyNumber = { number ->
                            DialerRepository.copyToClipboard(context, "Phone Number", number)
                            toastMessage = "Copied $number"
                        },
                        onSaveNumber = { number ->
                            DialerRepository.saveNumberToContacts(context, number)
                        },
                        onOpenSearch = { currentScreen = "search" },
                        onOpenSettings = { currentScreen = "settings" },
                        onOpenCallDetails = { log ->
                            selectedCallLog = log
                            val matched = contacts.find {
                                it.id == log.contactId ||
                                        (it.normalizedPhoneKey.isNotEmpty() && it.normalizedPhoneKey == log.normalizedPhoneKey)
                            }
                            selectedContact = matched ?: ContactItem(
                                id = log.contactId,
                                name = log.name,
                                displayName = log.name,
                                role = log.codec,
                                tag = log.simLabel,
                                number = log.number,
                                isSavedContact = log.isSaved
                            )
                            currentScreen = "call_details"
                        }
                    )

                    "contacts" -> ContactsScreen(
                        contacts = contacts,
                        sortBy = settings.sortBy,
                        onPlaceCall = { number ->
                            placeCallEnsuringDefaultDialer(number)
                        },
                        onOpenActionCard = { contact ->
                            selectedContact = contact
                            activeActionCardContact = contact
                        },
                        onAddNewContact = { prefill ->
                            DialerRepository.saveNumberToContacts(context, prefill)
                        },
                        onOpenSettings = { currentScreen = "settings" }
                    )

                    "contact_details" -> ContactDetailsScreen(
                        contact = selectedContact,
                        callLogs = callLogs,
                        customRingtoneTitle = customRingtoneMap[selectedContact.id] ?: "Default Ringtone",
                        onBack = { currentScreen = "contacts" },
                        onToggleFavourite = { c ->
                            val nextFav = !c.isFavourite
                            contacts = contacts.map {
                                if (it.id == c.id) it.copy(isFavourite = nextFav) else it
                            }
                            selectedContact = selectedContact.copy(isFavourite = nextFav)
                            DialerRepository.toggleContactFavouriteInDb(context, c, nextFav)
                        },
                        onPlaceCall = { placeCallEnsuringDefaultDialer(it) },
                        onSendSms = { DialerRepository.openSmsComposer(context, it) },
                        onSaveContact = { c ->
                            DialerRepository.saveNumberToContacts(context, c.number, c.displayName)
                        },
                        onPickRingtone = { c ->
                            launchSystemRingtonePicker(c)
                        },
                        onCopyNumber = {
                            DialerRepository.copyToClipboard(context, "Phone", it)
                            toastMessage = "Copied $it"
                        },
                        onOpenActionCard = { activeActionCardContact = it }
                    )

                    "call_details" -> {
                        var isBlockedState by remember(selectedCallLog.number) {
                            mutableStateOf(DialerRepository.isNumberBlocked(context, selectedCallLog.number))
                        }
                        CallDetailsScreen(
                            log = selectedCallLog,
                            contact = selectedContact,
                            showCodec = settings.showCallCodec,
                            isBlocked = isBlockedState,
                            onBack = { currentScreen = "recents" },
                            onPlaceCall = { placeCallEnsuringDefaultDialer(it) },
                            onSendSms = { DialerRepository.openSmsComposer(context, it) },
                            onSaveNumber = { num ->
                                DialerRepository.saveNumberToContacts(context, num)
                            },
                            onEditBeforeCall = { num ->
                                dialNumber = num
                                isDialSheetOpen = true
                            },
                            onCopyNumber = {
                                DialerRepository.copyToClipboard(context, "Phone", it)
                                toastMessage = "Copied $it"
                            },
                            onOpenContactDetails = {
                                selectedContact = it
                                currentScreen = "contact_details"
                            },
                            onOpenActionCard = { activeActionCardContact = it },
                            onBlockNumber = { num ->
                                isBlockedState = DialerRepository.toggleBlockNumber(context, num)
                                toastMessage = if (isBlockedState) {
                                    "Number blocked from incoming calls"
                                } else {
                                    "Number unblocked"
                                }
                            }
                        )
                    }

                    "search" -> UnifiedSearchScreen(
                        contacts = contacts,
                        callLogs = callLogs,
                        onBack = { currentScreen = "recents" },
                        onPlaceCall = { placeCallEnsuringDefaultDialer(it) },
                        onSaveNumber = { num ->
                            DialerRepository.saveNumberToContacts(context, num)
                        },
                        onOpenContactDetails = {
                            selectedContact = it
                            currentScreen = "contact_details"
                        },
                        onOpenCallDetails = {
                            selectedCallLog = it
                            currentScreen = "call_details"
                        }
                    )

                    "favourites" -> FavouritesScreen(
                        contacts = contacts,
                        onToggleFavourite = { c ->
                            val nextFav = !c.isFavourite
                            contacts = contacts.map {
                                if (it.id == c.id) it.copy(isFavourite = nextFav) else it
                            }
                            if (selectedContact.id == c.id) {
                                selectedContact = selectedContact.copy(isFavourite = nextFav)
                            }
                            DialerRepository.toggleContactFavouriteInDb(context, c, nextFav)
                            toastMessage = if (nextFav) "★ Added to Favourites" else "Removed from Favourites"
                        },
                        onPlaceCall = { placeCallEnsuringDefaultDialer(it) },
                        onSendSms = { DialerRepository.openSmsComposer(context, it) },
                        onOpenActionCard = { activeActionCardContact = it },
                        onOpenSettings = { currentScreen = "settings" }
                    )

                    "settings" -> SettingsScreen(
                        settings = settings,
                        customRingtoneCount = customRingtoneMap.size,
                        onUpdateSettings = { updated ->
                            settings = updated
                            DialerRepository.saveSettings(context, updated)
                            reloadDeviceDataCoalesced()
                        },
                        onNavigateScreen = { currentScreen = it },
                        onTestIncomingCallPopup = {
                            val testIntent = Intent(context, CallActivity::class.java).apply {
                                putExtra(CallActivity.EXTRA_PHONE_NUMBER, "+91 98455 67890")
                                putExtra(CallActivity.EXTRA_INITIAL_STAGE, "INCOMING")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            context.startActivity(testIntent)
                        }
                    )

                    "contact_ringtones" -> ContactRingtoneScreen(
                        contacts = contacts,
                        customRingtoneMap = customRingtoneMap,
                        onBack = { currentScreen = "settings" },
                        onPickRingtoneForContact = { contact ->
                            launchSystemRingtonePicker(contact)
                        },
                        onResetContactRingtone = { contact ->
                            DialerRepository.clearContactCustomRingtone(context, contact)
                            customRingtoneMap = DialerRepository.getAllCustomRingtoneTitles(context, contacts)
                            toastMessage = "Reset ringtone for ${contact.displayName}"
                        }
                    )

                    "appearance" -> AppearanceScreen(
                        settings = settings,
                        onUpdateSettings = { updated ->
                            settings = updated
                            DialerRepository.saveSettings(context, updated)
                        },
                        onBack = { currentScreen = "settings" }
                    )

                    "edge_states" -> EdgeStatesScreen(
                        onOpenDialPad = { isDialSheetOpen = true },
                        onNavigateScreen = { currentScreen = it },
                        onTriggerCall = { placeCallEnsuringDefaultDialer(it) }
                    )

                    else -> PermissionsOnboardingContent(
                        isDefaultDialer = isDefaultDialer,
                        hasContactsPermission = hasContactsPermission,
                        hasCallLogPermission = hasCallLogPermission,
                        hasPhonePermission = hasPhonePermission,
                        canUseFullScreenPopup = canUseFullScreenPopup,
                        onGrantClick = { triggerPermissionFlow() },
                        onGrantFullScreenPopup = { openFullScreenIntentSettings(context) },
                        onContinueToDialer = { currentScreen = "recents" }
                    )
                }
            }

            BottomNavigationDock(
                currentTab = currentScreen,
                onSelectTab = { currentScreen = it },
                onOpenDialPad = { isDialSheetOpen = true }
            )
        }

        if (isDialSheetOpen) {
            DialPadSheetOverlay(
                dialNumber = dialNumber,
                onDigitPress = { digit ->
                    if (dialNumber.length < 22) dialNumber += digit
                },
                onBackspace = {
                    if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1)
                },
                onClearAll = { dialNumber = "" },
                contacts = contacts,
                onPlaceCall = { number ->
                    isDialSheetOpen = false
                    placeCallEnsuringDefaultDialer(number)
                },
                onSaveNumber = { number ->
                    isDialSheetOpen = false
                    DialerRepository.saveNumberToContacts(context, number)
                },
                onOpenContactCard = { contact ->
                    isDialSheetOpen = false
                    selectedContact = contact
                    currentScreen = "contact_details"
                },
                onDismiss = { isDialSheetOpen = false }
            )
        }

        val cardContact = activeActionCardContact
        if (cardContact != null) {
            ContactActionCardOverlay(
                contact = cardContact,
                onDismiss = { activeActionCardContact = null },
                onCall = { number ->
                    placeCallEnsuringDefaultDialer(number)
                },
                onText = { number ->
                    DialerRepository.openSmsComposer(context, number)
                },
                onCopy = { text ->
                    DialerRepository.copyToClipboard(context, "Contact", text)
                    toastMessage = "Copied: $text"
                },
                onShare = { contact ->
                    DialerRepository.shareContactText(context, contact)
                },
                onSaveContact = { contact ->
                    DialerRepository.saveNumberToContacts(context, contact.number, contact.displayName)
                },
                onToggleFavourite = { contact ->
                    val nextFav = !contact.isFavourite
                    val updated = contact.copy(isFavourite = nextFav)
                    contacts = contacts.map { if (it.id == contact.id) updated else it }
                    activeActionCardContact = updated
                    DialerRepository.toggleContactFavouriteInDb(context, contact, nextFav)
                },
                onOpenFullDetails = { contact ->
                    selectedContact = contact
                    currentScreen = "contact_details"
                }
            )
        }

        if (toastMessage != null) {
            FocalGlassBox(
                shape = MistShapes.Pill,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(NothingTokens.NothingRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toastMessage ?: "",
                        color = NothingTokens.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun InAppCallPopupBanner(
    state: ActiveCallUiState,
    onOpenFullCall: () -> Unit,
    onAnswer: () -> Unit,
    onDeclineOrEnd: () -> Unit
) {
    val isRinging = state.stage == UiCallStage.INCOMING || state.hasWaitingCall
    val title = if (state.hasWaitingCall) state.waitingCallerName.ifBlank { state.waitingCallerNumber }
    else state.displayName
    val sub = when {
        state.hasWaitingCall -> "Call waiting · ${state.waitingCallerNumber}"
        isRinging -> "Incoming call · ${state.number}"
        else -> "Ongoing call · Tap to open"
    }

    FocalGlassBox(
        shape = MistShapes.Radius24,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onOpenFullCall() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRinging) NothingTokens.NothingRed else NothingTokens.EmeraldActive
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    NdotText(
                        text = title,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                    MonoNumText(
                        text = sub,
                        fontSize = 10.sp,
                        color = NothingTokens.TextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRinging) {
                    Box(
                        modifier = Modifier
                            .clip(MistShapes.Pill)
                            .background(Color.White)
                            .clickable { onAnswer() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Accept",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(MistShapes.Pill)
                        .background(NothingTokens.NothingRed)
                        .clickable { onDeclineOrEnd() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isRinging) "Decline" else "End",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationDock(
    currentTab: String,
    onSelectTab: (String) -> Unit,
    onOpenDialPad: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FrostedMistBox(
            shape = MistShapes.Pill,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BottomNavTabItem(
                    iconContent = { tint ->
                        VectorSpeedDialIcon(tint = tint, size = 18.dp)
                    },
                    label = "Recents",
                    isSelected = currentTab == "recents" || currentTab == "call_details",
                    onClick = { onSelectTab("recents") },
                    modifier = Modifier.weight(1f)
                )

                BottomNavTabItem(
                    iconContent = { tint ->
                        Text(text = "☷", color = tint, fontSize = 14.sp)
                    },
                    label = "Contacts",
                    isSelected = currentTab == "contacts" || currentTab == "contact_details",
                    onClick = { onSelectTab("contacts") },
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onOpenDialPad() },
                    contentAlignment = Alignment.Center
                ) {
                    VectorKeypadIcon(tint = Color.Black, size = 20.dp)
                }

                BottomNavTabItem(
                    iconContent = { tint ->
                        VectorVoicemailTapeIcon(tint = tint, size = 18.dp)
                    },
                    label = "Favourites",
                    isSelected = currentTab == "favourites",
                    onClick = { onSelectTab("favourites") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomNavTabItem(
    iconContent: @Composable (Color) -> Unit,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Color(0x26FFFFFF) else Color.Transparent
    val tintColor = if (isSelected) Color.White else NothingTokens.TextSecondary

    Column(
        modifier = modifier
            .clip(MistShapes.Pill)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            iconContent(tintColor)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = tintColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PermissionsOnboardingContent(
    isDefaultDialer: Boolean,
    hasContactsPermission: Boolean,
    hasCallLogPermission: Boolean,
    hasPhonePermission: Boolean,
    canUseFullScreenPopup: Boolean = true,
    onGrantClick: () -> Unit,
    onGrantFullScreenPopup: () -> Unit = {},
    onContinueToDialer: () -> Unit
) {
    val allReady = isDefaultDialer && hasContactsPermission && hasCallLogPermission && hasPhonePermission

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            NdotText(text = "PERMISSIONS", fontSize = 22.sp)
            Spacer(modifier = Modifier.height(18.dp))

            PermissionStatusCard(
                title = "Default Phone App Role",
                apiLabel = "android.app.role.ROLE_DIALER",
                isGranted = isDefaultDialer,
                onClick = onGrantClick
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionStatusCard(
                title = "Contacts Identity & Portraits",
                apiLabel = "android.permission.READ_CONTACTS",
                isGranted = hasContactsPermission,
                onClick = onGrantClick
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionStatusCard(
                title = "Call History Timeline",
                apiLabel = "android.permission.READ_CALL_LOG",
                isGranted = hasCallLogPermission,
                onClick = onGrantClick
            )
            Spacer(modifier = Modifier.height(10.dp))
            PermissionStatusCard(
                title = "Place & Manage Calls + Popup",
                apiLabel = "CALL_PHONE · POST_NOTIFICATIONS · FULL_SCREEN_INTENT",
                isGranted = hasPhonePermission && canUseFullScreenPopup,
                onClick = {
                    if (!canUseFullScreenPopup) onGrantFullScreenPopup() else onGrantClick()
                }
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { if (allReady) onContinueToDialer() else onGrantClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MistShapes.Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allReady) Color.White else NothingTokens.NothingRed,
                    contentColor = if (allReady) Color.Black else Color.White
                )
            ) {
                Text(
                    text = if (allReady) "CONTINUE TO DIALER →" else "GRANT ALL & CONTINUE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (!allReady) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MistShapes.Pill)
                        .clickable { onContinueToDialer() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoNumText(
                        text = "Skip to Dialer →",
                        fontSize = 11.sp,
                        color = NothingTokens.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    apiLabel: String,
    isGranted: Boolean,
    onClick: () -> Unit = {}
) {
    val borderColor = if (isGranted) NothingTokens.GlassBBorderTop else NothingTokens.NothingRedBorder
    val statusDotColor = if (isGranted) NothingTokens.EmeraldActive else NothingTokens.NothingRed

    FrostedMistBox(
        shape = MistShapes.Radius20,
        borderColor = borderColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        color = NothingTokens.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                MonoNumText(text = apiLabel, fontSize = 10.sp)
            }

            Box(
                modifier = Modifier
                    .clip(MistShapes.Pill)
                    .background(if (isGranted) Color(0x1A34D399) else NothingTokens.NothingRedSoft)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                MonoNumText(
                    text = if (isGranted) "GRANTED ✓" else "GRANT",
                    color = if (isGranted) NothingTokens.EmeraldActive else NothingTokens.NothingRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        permission
    ) == PackageManager.PERMISSION_GRANTED
}

private fun checkCanUseFullScreenIntent(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.canUseFullScreenIntent() ?: true
    } else {
        true
    }
}

private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

private fun checkIsDefaultDialer(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    } else {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecomManager?.defaultDialerPackage == context.packageName
    }
}

private fun createDefaultDialerIntent(context: Context): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            null
        }
    } else {
        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }
}
