package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.AudioHistoryEntity
import com.example.data.AudioHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "bangla_tts_prefs"
        private const val KEY_LANG_EN = "is_english_ui"
        private const val KEY_EXPORT_FORMAT = "export_format_id"

        // Exact Part 1: Copyable AI Prompt for News Script Preparation
        val AI_NEWS_SCRIPT_PROMPT = """
তুমি একজন বাংলা নিউজ স্ক্রিপ্ট প্রস্তুতকারক। নিচের কাঁচা নিউজ লেখাকে BanglaTTS দিয়ে পড়ার উপযোগী পরিষ্কার স্ক্রিপ্টে বদলে দাও। অর্থ, তথ্য, নাম, সংখ্যার মান একটুও বদলাবে না — শুধু পড়ার রূপ বদলাবে।
নিয়ম:
সব সংখ্যা কথায় লেখো: ১২৫০০ → বারো হাজার পাঁচশো, ২৫০০ → আড়াই হাজার, ১৯৭৭ → উনিশশো সাতাত্তর, ৮০ → আশি, ১৬ → ষোলো। শতাংশ, সময়, সাল সব কথায় লেখো। (মডেল অঙ্কের স্ট্রিং আটকে আটকে পড়ে।)
ইংরেজি শব্দ বাংলা হরফে লেখো: GDP → জিডিপি, Facebook → ফেসবুক, School → স্কুল। লাতিন সংখ্যাও কথায় লেখো। (মডেল ইংরেজি হরফ পড়তে পারে না — ওগুলো থাকলে আওয়াজ ভাঙবে।)
আওয়াজ নয় এমন চিহ্ন তুলে দাও: *, ', /, @, #, URL, ব্র্যাকেট, ইমোজি। শব্দের ভেতরের ' তুলে শব্দ জোড়া দাও: চো'র → চোর, র'ক্তের → রক্তের।
বাক্য ছোট রাখো: এক লাইনে এক বাক্য। খুব লম্বা বাক্য অর্থ না বদলে দুই-তিন ভাগে ভাঙো।
বিরামচিহ্ন ঠিক রাখো: বাক্যের শেষে ।, ছোট থামার জায়গায় ,, প্রশ্নে ?, বিস্ময়ে !। এক লাইনে একটাই ভাব রাখো। (অ্যাপ চিহ্নের পরেই ভাগ করে পড়ে আর চিহ্নে হালকা থামে — তাই চিহ্ন ঠিক থাকলে পড়া মসৃণ হবে।)
নতুন তথ্য যোগ করো না, কিছু বাদ দিও না, সারাংশ করো না। কথ্য ঢঙ থাকলে সেটা রেখো (যেমন চাইসে, এইডা) — শুধু উপরের নিয়মগুলো প্রয়োগ করো।
আউটপুটে শুধু পরিষ্কার স্ক্রিপ্ট দেবে — কোনো ব্যাখ্যা, হেডিং, বাড়তি কথা নয়।
কাঁচা লেখা:
[এখানে নিউজ লেখা পেস্ট করুন]
        """.trimIndent()

        // Exact Part 2: App Text Input Instructions Summary
        val TEXT_INPUT_GUIDE_SUMMARY = """
অ্যাপে টেক্সট ইনপুট কেমন দেবেন (সংক্ষেপ)
• শুধু সাদা বাংলা লেখা: টাইপ করে, পেস্ট করে, বা .txt ফাইল থেকে।
• এক লাইনে এক বাক্য হলে সবচেয়ে ভালো।
• সংখ্যা কথায়, ইংরেজি শব্দ বাংলা হরফে, কোনো *, ', /, URL, ইমোজি নয়।
• PDF/ছবি থেকে লেখা অ্যাপ পড়তে পারবে না — আগে লেখা কপি করে .txt বানিয়ে নিন। উপরের প্রম্পট দিয়ে AI-কে দিলে এই সবকিছুই সে নিজে করে দেবে — আপনাকেও হাতে পরিষ্কার করতে হবে না।
        """.trimIndent()
    }

    private enum class SelectedVoiceMode {
        ELEVENLABS,
        KIKI,
        CUSTOM
    }

    private var isEnglishUi: Boolean = false
    private var selectedVoiceMode: SelectedVoiceMode = SelectedVoiceMode.ELEVENLABS
    private var selectedSpeed: Float = 1.0f
    private var selectedExportFormat: AudioExportFormat = AudioExportFormat.MP3
    private var volumeGain: Float = 1.0f

    // Approved VITS parameters
    private var noiseScale: Float = 0.667f
    private var noiseScaleW: Float = 0.8f
    private var lengthScale: Float = 1.0f

    private var customOnnxFile: File? = null
    private var customTokensFile: File? = null

    private var activeDownloadJob: Job? = null
    private var lastFailedDownloadSpec: VoiceModelSpec? = null
    private var hasAskedNotificationPermission: Boolean = false

    private lateinit var historyRepository: AudioHistoryRepository
    private var cachedHistoryItems: List<AudioHistoryEntity> = emptyList()

    // Plain Android Views
    private lateinit var rootContainer: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var btnLanguageToggle: Button
    private lateinit var tvLoadedRamBadge: TextView
    private lateinit var btnNavStudio: Button
    private lateinit var btnNavHistory: Button
    private lateinit var btnNavModels: Button
    private lateinit var btnNavSettings: Button

    private lateinit var sectionStudio: LinearLayout
    private lateinit var sectionHistory: LinearLayout
    private lateinit var sectionModels: LinearLayout
    private lateinit var sectionSettings: LinearLayout

    private lateinit var layoutErrorBanner: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetryDownload: Button
    private lateinit var btnDismissError: Button

    private lateinit var layoutDownloadBanner: LinearLayout
    private lateinit var tvDownloadTitle: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvDownloadDetails: TextView

    private lateinit var tvLabelStatChars: TextView
    private lateinit var tvStatCharsWords: TextView
    private lateinit var tvLabelStatChunks: TextView
    private lateinit var tvStatChunks: TextView
    private lateinit var tvLabelStatVoice: TextView
    private lateinit var tvStatActiveVoice: TextView

    private lateinit var tvLabelVoiceSelector: TextView
    private lateinit var layoutQuickModelSwitch: LinearLayout
    private lateinit var tvQuickSwitchHint: TextView
    private lateinit var btnQuickSwitchEleven: Button
    private lateinit var btnQuickSwitchKiki: Button

    private lateinit var rbVoiceEleven: RadioButton
    private lateinit var tvElevenStatusBadge: TextView
    private lateinit var rbVoiceKiki: RadioButton
    private lateinit var btnDownloadKiki: Button
    private lateinit var tvKikiStatusBadge: TextView

    private lateinit var tvLabelSpeedSelector: TextView
    private lateinit var rgSpeed: RadioGroup
    private lateinit var rbSpeed075: RadioButton
    private lateinit var rbSpeed100: RadioButton
    private lateinit var rbSpeed125: RadioButton
    private lateinit var rbSpeed150: RadioButton

    private lateinit var tvLabelFormatSelector: TextView
    private lateinit var rgAudioFormat: RadioGroup
    private lateinit var rbFormatMp3: RadioButton
    private lateinit var rbFormatWav: RadioButton
    private lateinit var rbFormatM4a: RadioButton
    private lateinit var rbFormatPcm: RadioButton

    private lateinit var tvLabelScriptInput: TextView
    private lateinit var etInputText: EditText
    private lateinit var btnSampleNews: Button
    private lateinit var btnClearText: Button
    private lateinit var btnPaste: Button
    private lateinit var btnPickTxt: Button

    private lateinit var tvStatusMessage: TextView
    private lateinit var tvProgressPercentBn: TextView
    private lateinit var progressSynthesis: ProgressBar
    private lateinit var tvPieceCounterBn: TextView
    private lateinit var tvAudioSpecInfo: TextView
    private lateinit var tvActivePiecePreview: TextView

    private lateinit var btnGenerate: Button
    private lateinit var btnPlayStop: Button
    private lateinit var btnSaveWav: Button
    private lateinit var btnQuickShare: Button

    // Studio Recent & History Tab Views
    private lateinit var tvLabelRecentHistory: TextView
    private lateinit var btnViewAllHistory: Button
    private lateinit var tvStudioRecentEmpty: TextView
    private lateinit var containerStudioRecentItems: LinearLayout
    private lateinit var tvHistoryHeaderTitle: TextView
    private lateinit var tvHistorySummaryCount: TextView
    private lateinit var tvHistoryHeaderDesc: TextView
    private lateinit var btnClearAllHistory: Button
    private lateinit var layoutHistoryEmptyState: LinearLayout
    private lateinit var tvHistoryEmptyTitle: TextView
    private lateinit var tvHistoryEmptySubtitle: TextView
    private lateinit var containerHistoryList: LinearLayout

    // Models Tab Views
    private lateinit var tvModelCardElevenStatus: TextView
    private lateinit var btnModelCardDownloadEleven: Button
    private lateinit var tvModelCardKikiStatus: TextView
    private lateinit var btnModelCardDownloadKiki: Button
    private lateinit var tvEngineMemoryInfo: TextView
    private lateinit var btnUnloadRamModel: Button
    private lateinit var btnPickCustomOnnx: Button
    private lateinit var btnPickCustomTokens: Button
    private lateinit var tvCustomModelStatus: TextView

    // Settings, Two-Part Guide & Cloud Export Tab Views
    private lateinit var tvGuidePart1Header: TextView
    private lateinit var tvGuidePart1Intro: TextView
    private lateinit var tvAiPromptBox: TextView
    private lateinit var btnCopyAiPrompt: Button
    private lateinit var tvGuidePart2Header: TextView
    private lateinit var tvGuidePart2Body: TextView
    private lateinit var btnCopyInputGuide: Button

    private lateinit var tvSettingsVitsHeader: TextView
    private lateinit var tvVolumeGainLabel: TextView
    private lateinit var seekVolumeGain: SeekBar
    private lateinit var btnResetApprovedVits: Button
    private lateinit var tvSettingsExportHeader: TextView
    private lateinit var btnSettingsSaveDownloads: Button
    private lateinit var btnSettingsExportCustomLocation: Button
    private lateinit var btnSettingsShareTranscript: Button
    private lateinit var etCloudWebhookUrl: EditText
    private lateinit var btnCloudSyncUpload: Button

    // Activity Result Launchers
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // User granted or declined POST_NOTIFICATIONS; foreground service still works
        }

    private val pickTxtFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                loadTextFromUri(uri)
            }
        }

    private val pickCustomOnnxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                copyUriToCustomModelFile(uri, "custom_model.onnx") { file ->
                    customOnnxFile = file
                    updateCustomModelStatusUi()
                }
            }
        }

    private val pickCustomTokensLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                copyUriToCustomModelFile(uri, "custom_tokens.txt") { file ->
                    customTokensFile = file
                    updateCustomModelStatusUi()
                }
            }
        }

    private val exportCustomFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            if (uri != null) {
                exportCurrentFormatToCustomUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnglishUi = prefs.getBoolean(KEY_LANG_EN, false)
        selectedExportFormat = AudioExportFormat.fromId(
            prefs.getString(KEY_EXPORT_FORMAT, AudioExportFormat.MP3.id)
        )

        historyRepository = AudioHistoryRepository.getInstance(applicationContext)

        bindViews()
        setupWindowInsets()
        populateTwoPartGuideContent()
        restoreExportFormatSelection()
        setupListeners()
        applyLanguageToUi()
        observeServiceState()
        observeRoomHistory()

        // Default clean Bengali sample text (numbers written in words as recommended)
        if (etInputText.text.isNullOrBlank()) {
            etInputText.setText(
                "স্বাগতম বাংলা ভয়েস স্টুডিওতে। আজকের আবহাওয়া চমৎকার! আপনি কি জানেন, বারো হাজার পাঁচশো টাকার মধ্যে সাড়ে বারো শতাংশ ছাড় পাওয়া যাচ্ছে? অডিও তৈরি করুন বাটনে ক্লিক করলে অটো-প্লে ছাড়াই ব্যাকগ্রাউন্ডে জেনারেট হয়ে হিস্টরিতে সেভ হবে।"
            )
        }
        updateTextAnalytics()
        refreshVoiceSelectorUi()

        // Ask POST_NOTIFICATIONS runtime permission once on Android 13+
        requestPostNotificationsPermissionOnce()

        // MANDATORY FIRST-RUN CHECK: Automatically download ElevenLabs model if not present
        // NOTE: Never download Kiki automatically on first run, and never load any model into RAM at app open.
        if (!VoiceModelSpec.ELEVENLABS.isVerifiedPresent(this)) {
            triggerModelDownload(VoiceModelSpec.ELEVENLABS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVoiceSelectorUi()
        refreshRamStatusUi()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvAppSubtitle = findViewById(R.id.tvAppSubtitle)
        btnLanguageToggle = findViewById(R.id.btnLanguageToggle)
        tvLoadedRamBadge = findViewById(R.id.tvLoadedRamBadge)
        btnNavStudio = findViewById(R.id.btnNavStudio)
        btnNavHistory = findViewById(R.id.btnNavHistory)
        btnNavModels = findViewById(R.id.btnNavModels)
        btnNavSettings = findViewById(R.id.btnNavSettings)

        sectionStudio = findViewById(R.id.sectionStudio)
        sectionHistory = findViewById(R.id.sectionHistory)
        sectionModels = findViewById(R.id.sectionModels)
        sectionSettings = findViewById(R.id.sectionSettings)

        layoutErrorBanner = findViewById(R.id.layoutErrorBanner)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetryDownload = findViewById(R.id.btnRetryDownload)
        btnDismissError = findViewById(R.id.btnDismissError)

        layoutDownloadBanner = findViewById(R.id.layoutDownloadBanner)
        tvDownloadTitle = findViewById(R.id.tvDownloadTitle)
        progressDownload = findViewById(R.id.progressDownload)
        tvDownloadDetails = findViewById(R.id.tvDownloadDetails)

        tvLabelStatChars = findViewById(R.id.tvLabelStatChars)
        tvStatCharsWords = findViewById(R.id.tvStatCharsWords)
        tvLabelStatChunks = findViewById(R.id.tvLabelStatChunks)
        tvStatChunks = findViewById(R.id.tvStatChunks)
        tvLabelStatVoice = findViewById(R.id.tvLabelStatVoice)
        tvStatActiveVoice = findViewById(R.id.tvStatActiveVoice)

        tvLabelVoiceSelector = findViewById(R.id.tvLabelVoiceSelector)
        layoutQuickModelSwitch = findViewById(R.id.layoutQuickModelSwitch)
        tvQuickSwitchHint = findViewById(R.id.tvQuickSwitchHint)
        btnQuickSwitchEleven = findViewById(R.id.btnQuickSwitchEleven)
        btnQuickSwitchKiki = findViewById(R.id.btnQuickSwitchKiki)

        rbVoiceEleven = findViewById(R.id.rbVoiceEleven)
        tvElevenStatusBadge = findViewById(R.id.tvElevenStatusBadge)
        rbVoiceKiki = findViewById(R.id.rbVoiceKiki)
        btnDownloadKiki = findViewById(R.id.btnDownloadKiki)
        tvKikiStatusBadge = findViewById(R.id.tvKikiStatusBadge)

        tvLabelSpeedSelector = findViewById(R.id.tvLabelSpeedSelector)
        rgSpeed = findViewById(R.id.rgSpeed)
        rbSpeed075 = findViewById(R.id.rbSpeed075)
        rbSpeed100 = findViewById(R.id.rbSpeed100)
        rbSpeed125 = findViewById(R.id.rbSpeed125)
        rbSpeed150 = findViewById(R.id.rbSpeed150)

        tvLabelFormatSelector = findViewById(R.id.tvLabelFormatSelector)
        rgAudioFormat = findViewById(R.id.rgAudioFormat)
        rbFormatMp3 = findViewById(R.id.rbFormatMp3)
        rbFormatWav = findViewById(R.id.rbFormatWav)
        rbFormatM4a = findViewById(R.id.rbFormatM4a)
        rbFormatPcm = findViewById(R.id.rbFormatPcm)

        tvLabelScriptInput = findViewById(R.id.tvLabelScriptInput)
        etInputText = findViewById(R.id.etInputText)
        btnSampleNews = findViewById(R.id.btnSampleNews)
        btnClearText = findViewById(R.id.btnClearText)
        btnPaste = findViewById(R.id.btnPaste)
        btnPickTxt = findViewById(R.id.btnPickTxt)

        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        tvProgressPercentBn = findViewById(R.id.tvProgressPercentBn)
        progressSynthesis = findViewById(R.id.progressSynthesis)
        tvPieceCounterBn = findViewById(R.id.tvPieceCounterBn)
        tvAudioSpecInfo = findViewById(R.id.tvAudioSpecInfo)
        tvActivePiecePreview = findViewById(R.id.tvActivePiecePreview)

        btnGenerate = findViewById(R.id.btnGenerate)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        btnSaveWav = findViewById(R.id.btnSaveWav)
        btnQuickShare = findViewById(R.id.btnQuickShare)

        tvLabelRecentHistory = findViewById(R.id.tvLabelRecentHistory)
        btnViewAllHistory = findViewById(R.id.btnViewAllHistory)
        tvStudioRecentEmpty = findViewById(R.id.tvStudioRecentEmpty)
        containerStudioRecentItems = findViewById(R.id.containerStudioRecentItems)
        tvHistoryHeaderTitle = findViewById(R.id.tvHistoryHeaderTitle)
        tvHistorySummaryCount = findViewById(R.id.tvHistorySummaryCount)
        tvHistoryHeaderDesc = findViewById(R.id.tvHistoryHeaderDesc)
        btnClearAllHistory = findViewById(R.id.btnClearAllHistory)
        layoutHistoryEmptyState = findViewById(R.id.layoutHistoryEmptyState)
        tvHistoryEmptyTitle = findViewById(R.id.tvHistoryEmptyTitle)
        tvHistoryEmptySubtitle = findViewById(R.id.tvHistoryEmptySubtitle)
        containerHistoryList = findViewById(R.id.containerHistoryList)

        tvModelCardElevenStatus = findViewById(R.id.tvModelCardElevenStatus)
        btnModelCardDownloadEleven = findViewById(R.id.btnModelCardDownloadEleven)
        tvModelCardKikiStatus = findViewById(R.id.tvModelCardKikiStatus)
        btnModelCardDownloadKiki = findViewById(R.id.btnModelCardDownloadKiki)
        tvEngineMemoryInfo = findViewById(R.id.tvEngineMemoryInfo)
        btnUnloadRamModel = findViewById(R.id.btnUnloadRamModel)
        btnPickCustomOnnx = findViewById(R.id.btnPickCustomOnnx)
        btnPickCustomTokens = findViewById(R.id.btnPickCustomTokens)
        tvCustomModelStatus = findViewById(R.id.tvCustomModelStatus)

        tvGuidePart1Header = findViewById(R.id.tvGuidePart1Header)
        tvGuidePart1Intro = findViewById(R.id.tvGuidePart1Intro)
        tvAiPromptBox = findViewById(R.id.tvAiPromptBox)
        btnCopyAiPrompt = findViewById(R.id.btnCopyAiPrompt)
        tvGuidePart2Header = findViewById(R.id.tvGuidePart2Header)
        tvGuidePart2Body = findViewById(R.id.tvGuidePart2Body)
        btnCopyInputGuide = findViewById(R.id.btnCopyInputGuide)

        tvSettingsVitsHeader = findViewById(R.id.tvSettingsVitsHeader)
        tvVolumeGainLabel = findViewById(R.id.tvVolumeGainLabel)
        seekVolumeGain = findViewById(R.id.seekVolumeGain)
        btnResetApprovedVits = findViewById(R.id.btnResetApprovedVits)
        tvSettingsExportHeader = findViewById(R.id.tvSettingsExportHeader)
        btnSettingsSaveDownloads = findViewById(R.id.btnSettingsSaveDownloads)
        btnSettingsExportCustomLocation = findViewById(R.id.btnSettingsExportCustomLocation)
        btnSettingsShareTranscript = findViewById(R.id.btnSettingsShareTranscript)
        etCloudWebhookUrl = findViewById(R.id.etCloudWebhookUrl)
        btnCloudSyncUpload = findViewById(R.id.btnCloudSyncUpload)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun populateTwoPartGuideContent() {
        tvAiPromptBox.text = AI_NEWS_SCRIPT_PROMPT
        tvGuidePart2Body.text = TEXT_INPUT_GUIDE_SUMMARY
    }

    private fun restoreExportFormatSelection() {
        when (selectedExportFormat) {
            AudioExportFormat.MP3 -> rbFormatMp3.isChecked = true
            AudioExportFormat.WAV -> rbFormatWav.isChecked = true
            AudioExportFormat.M4A -> rbFormatM4a.isChecked = true
            AudioExportFormat.PCM -> rbFormatPcm.isChecked = true
        }
    }

    private fun setupListeners() {
        // Language Toggle (বাংলা <-> English)
        btnLanguageToggle.setOnClickListener {
            isEnglishUi = !isEnglishUi
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LANG_EN, isEnglishUi)
                .apply()
            applyLanguageToUi()
            updateTextAnalytics()
            refreshVoiceSelectorUi()
            refreshRamStatusUi()
            renderHistoryViews(cachedHistoryItems)
        }

        // 4-Section Navigation Tabs
        btnNavStudio.setOnClickListener { switchMenuTab(0) }
        btnNavHistory.setOnClickListener { switchMenuTab(1) }
        btnNavModels.setOnClickListener { switchMenuTab(2) }
        btnNavSettings.setOnClickListener { switchMenuTab(3) }

        btnViewAllHistory.setOnClickListener { switchMenuTab(1) }

        btnClearAllHistory.setOnClickListener {
            lifecycleScope.launch {
                historyRepository.clearAll(applicationContext)
                TtsForegroundService.updateStatusExternal(
                    statusBn = "সমস্ত অডিও ইতিহাস মুছে ফেলা হয়েছে।",
                    statusEn = "All audio history has been cleared."
                )
            }
        }

        // Error Banner Actions
        btnDismissError.setOnClickListener {
            layoutErrorBanner.visibility = View.GONE
        }
        btnRetryDownload.setOnClickListener {
            val spec = lastFailedDownloadSpec ?: VoiceModelSpec.ELEVENLABS
            layoutErrorBanner.visibility = View.GONE
            triggerModelDownload(spec)
        }

        // Quick Model Switch Buttons (when both ElevenLabs & Kiki are downloaded)
        btnQuickSwitchEleven.setOnClickListener {
            selectVoiceMode(SelectedVoiceMode.ELEVENLABS)
            val msgBn = "সক্রিয় মডেল পরিবর্তন করা হয়েছে: ElevenLabs"
            val msgEn = "Switched active model to: ElevenLabs"
            TtsForegroundService.updateStatusExternal(msgBn, msgEn)
        }
        btnQuickSwitchKiki.setOnClickListener {
            if (VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)) {
                selectVoiceMode(SelectedVoiceMode.KIKI)
                val msgBn = "সক্রিয় মডেল পরিবর্তন করা হয়েছে: সংবাদ (Kiki)"
                val msgEn = "Switched active model to: সংবাদ (Kiki)"
                TtsForegroundService.updateStatusExternal(msgBn, msgEn)
            }
        }

        // Voice Selector RadioButtons (ElevenLabs vs সংবাদ Kiki)
        rbVoiceEleven.setOnClickListener {
            selectVoiceMode(SelectedVoiceMode.ELEVENLABS)
        }
        rbVoiceKiki.setOnClickListener {
            if (VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)) {
                selectVoiceMode(SelectedVoiceMode.KIKI)
            } else {
                rbVoiceKiki.isChecked = false
                rbVoiceEleven.isChecked = (selectedVoiceMode == SelectedVoiceMode.ELEVENLABS)
                showBengaliError(
                    if (isEnglishUi) {
                        "Please tap 'Download' first to use the সংবাদ (Kiki) voice model."
                    } else {
                        "সংবাদ (Kiki) কণ্ঠ ব্যবহারের আগে 'ডাউনলোড' বাটনে চাপুন।"
                    },
                    failedSpec = VoiceModelSpec.KIKI_NEWS
                )
            }
        }

        // Optional Kiki Download Button
        btnDownloadKiki.setOnClickListener {
            triggerModelDownload(VoiceModelSpec.KIKI_NEWS)
        }
        btnModelCardDownloadEleven.setOnClickListener {
            triggerModelDownload(VoiceModelSpec.ELEVENLABS)
        }
        btnModelCardDownloadKiki.setOnClickListener {
            triggerModelDownload(VoiceModelSpec.KIKI_NEWS)
        }

        // Speed Selector: 0.75 / 1.0 / 1.25 / 1.5
        rgSpeed.setOnCheckedChangeListener { _, checkedId ->
            selectedSpeed = when (checkedId) {
                R.id.rbSpeed075 -> 0.75f
                R.id.rbSpeed100 -> 1.0f
                R.id.rbSpeed125 -> 1.25f
                R.id.rbSpeed150 -> 1.5f
                else -> 1.0f
            }
        }

        // Audio Export Format Selector: MP3 / WAV / M4A / PCM
        rgAudioFormat.setOnCheckedChangeListener { _, checkedId ->
            selectedExportFormat = when (checkedId) {
                R.id.rbFormatMp3 -> AudioExportFormat.MP3
                R.id.rbFormatWav -> AudioExportFormat.WAV
                R.id.rbFormatM4a -> AudioExportFormat.M4A
                R.id.rbFormatPcm -> AudioExportFormat.PCM
                else -> AudioExportFormat.MP3
            }
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EXPORT_FORMAT, selectedExportFormat.id)
                .apply()
            updateFormatButtonsUi()
            renderHistoryViews(cachedHistoryItems)
        }

        // Text Input Watcher for Real-Time Bengali Stats
        etInputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateTextAnalytics()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSampleNews.setOnClickListener {
            val newsText =
                "আজকের প্রধান সংবাদ। রাজধানী ঢাকাসহ সারাদেশে আগামীকাল আবহাওয়া স্বাভাবিক থাকবে! দেশের অর্থনৈতিক প্রবৃদ্ধি সাড়ে সাত শতাংশে পৌঁছেছে, এবং কৃষি খাতে বারো হাজার পাঁচশো মেট্রিক টন অতিরিক্ত ফসল উৎপাদিত হয়েছে। আপনারা শুনছেন বাংলা ভয়েস স্টুডিও।"
            etInputText.setText(newsText)
            etInputText.setSelection(newsText.length)
        }

        btnClearText.setOnClickListener {
            etInputText.setText("")
        }

        // Paste Button
        btnPaste.setOnClickListener {
            pasteFromClipboard()
        }

        // Pick .txt File Button
        btnPickTxt.setOnClickListener {
            try {
                pickTxtFileLauncher.launch(arrayOf("text/plain", "text/*"))
            } catch (e: Exception) {
                showBengaliError("ফাইল পিকার খোলা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }

        // Generate Button (Starts Foreground Service in Background — NEVER Auto-Plays)
        btnGenerate.setOnClickListener {
            handleGenerateClicked()
        }

        // Play / Stop Button (Plays completed file only when user clicks Play)
        btnPlayStop.setOnClickListener {
            handlePlayStopClicked()
        }

        // Save Button (Saves to Downloads in selected format: MP3 / WAV / M4A / PCM)
        btnSaveWav.setOnClickListener {
            saveAudioToDownloads(shareAfterSave = false)
        }
        btnSettingsSaveDownloads.setOnClickListener {
            saveAudioToDownloads(shareAfterSave = false)
        }

        // Quick Share / Social Media Share Button
        btnQuickShare.setOnClickListener {
            saveAudioToDownloads(shareAfterSave = true)
        }

        // Copy Buttons for the Two-Part Script Guide in Settings
        btnCopyAiPrompt.setOnClickListener {
            copyTextToClipboard(
                label = "BanglaTTS AI News Script Prompt",
                text = AI_NEWS_SCRIPT_PROMPT,
                toastBn = "AI স্ক্রিপ্ট প্রম্পট ক্লিপবোর্ডে কপি হয়েছে! এখন ChatGPT বা যেকোনো AI-তে পেস্ট করুন।",
                toastEn = "AI Script Prompt copied to clipboard!"
            )
        }
        btnCopyInputGuide.setOnClickListener {
            copyTextToClipboard(
                label = "BanglaTTS Text Input Guide",
                text = TEXT_INPUT_GUIDE_SUMMARY,
                toastBn = "টেক্সট ইনপুট নির্দেশনা ক্লিপবোর্ডে কপি হয়েছে!",
                toastEn = "Text input instructions copied to clipboard!"
            )
        }

        // Unload RAM Model Button
        btnUnloadRamModel.setOnClickListener {
            TtsEngineHolder.unloadCurrentVoice()
            refreshRamStatusUi()
            TtsForegroundService.updateStatusExternal(
                statusBn = "মেমোরি (RAM) থেকে ভয়েস মডেল আনলোড করা হয়েছে।",
                statusEn = "Unloaded voice model from memory (RAM)."
            )
        }

        // Custom ONNX / Tokens File Pickers
        btnPickCustomOnnx.setOnClickListener {
            try {
                pickCustomOnnxLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                showBengaliError("ফাইল নির্বাচন করা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }
        btnPickCustomTokens.setOnClickListener {
            try {
                pickCustomTokensLauncher.launch(arrayOf("text/plain", "*/*"))
            } catch (e: Exception) {
                showBengaliError("টোকেন ফাইল নির্বাচন করা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }

        // Custom Audio Settings: Volume Gain & VITS Reset
        seekVolumeGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(50, 150)
                volumeGain = clamped / 100.0f
                val pctBn = BengaliTextChunker.toBengaliDigits(clamped)
                tvVolumeGainLabel.text = if (isEnglishUi) {
                    "Audio Volume Boost (Gain): $clamped%"
                } else {
                    "অডিও ভলিউম বুস্ট (Gain): $pctBn%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetApprovedVits.setOnClickListener {
            noiseScale = 0.667f
            noiseScaleW = 0.8f
            lengthScale = 1.0f
            volumeGain = 1.0f
            seekVolumeGain.progress = 100
            tvVolumeGainLabel.text = if (isEnglishUi) {
                "Audio Volume Boost (Gain): 100%"
            } else {
                "অডিও ভলিউম বুস্ট (Gain): ১০০%"
            }
            TtsForegroundService.updateStatusExternal(
                statusBn = "অনুমোদিত VITS সেটিংস (০.৬৬৭ / ০.৮ / ১.০) পুনরায় সেট করা হয়েছে।",
                statusEn = "Reset to approved VITS settings (0.667 / 0.8 / 1.0)."
            )
        }

        btnSettingsExportCustomLocation.setOnClickListener {
            val pcmPair = getAvailablePcmAndSampleRate()
            if (pcmPair == null) {
                showBengaliError(
                    if (isEnglishUi) "Please generate audio first before exporting."
                    else "এক্সপোর্ট করার জন্য প্রথমে অডিও তৈরি করুন (Generate)।"
                )
                return@setOnClickListener
            }
            val ext = selectedExportFormat.extension
            val fileName = "BanglaTTS_${System.currentTimeMillis()}.$ext"
            try {
                exportCustomFileLauncher.launch(fileName)
            } catch (e: Exception) {
                showBengaliError("ফাইল এক্সপোর্ট মেনু খোলা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }

        btnSettingsShareTranscript.setOnClickListener {
            shareBanglaTranscriptText()
        }

        btnCloudSyncUpload.setOnClickListener {
            uploadAudioToCloudWebhook()
        }
    }

    private fun copyTextToClipboard(
        label: String,
        text: String,
        toastBn: String,
        toastEn: String
    ) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            val msg = if (isEnglishUi) toastEn else toastBn
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            TtsForegroundService.updateStatusExternal(toastBn, toastEn)
        } catch (e: Exception) {
            showBengaliError("কপি করতে সমস্যা হয়েছে: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun updateFormatButtonsUi() {
        val fmtUpper = selectedExportFormat.extension.uppercase(Locale.US)
        if (isEnglishUi) {
            btnSaveWav.text = "💾 Save ($fmtUpper)"
            btnSettingsSaveDownloads.text = "💾 Save $fmtUpper to Downloads Folder"
            btnSettingsExportCustomLocation.text = "📂 Export $fmtUpper to Custom Folder / Cloud Drive"
            tvAudioSpecInfo.text = "22050 Hz • Format: $fmtUpper • Silent Background Gen"
        } else {
            btnSaveWav.text = "💾 সেভ করুন ($fmtUpper)"
            btnSettingsSaveDownloads.text = "💾 Downloads ফোল্ডারে $fmtUpper সেভ করুন"
            btnSettingsExportCustomLocation.text = "📂 কাস্টম ফোল্ডার / ক্লাউড ড্রাইভে $fmtUpper এক্সপোর্ট করুন"
            tvAudioSpecInfo.text = "২২০৫০ Hz • ফরম্যাট: $fmtUpper • নীরব জেনারেট"
        }
    }

    /**
     * Updates all UI labels between Bangla (বাংলা) and English dynamically.
     */
    private fun applyLanguageToUi() {
        val count = cachedHistoryItems.size
        val countBn = BengaliTextChunker.toBengaliDigits(count)

        if (isEnglishUi) {
            tvAppTitle.text = "Bangla Voice Studio"
            tvAppSubtitle.text = "Offline Bengali TTS & Background Audio Generator"
            btnLanguageToggle.text = "🌐 বাংলা"

            btnNavStudio.text = "Studio"
            btnNavHistory.text = "History ($count)"
            btnNavModels.text = "Models"
            btnNavSettings.text = "Settings & Guide"

            btnRetryDownload.text = "Retry"
            btnDismissError.text = "Dismiss"

            tvLabelStatChars.text = "Chars & Words"
            tvLabelStatChunks.text = "Chunks (Pieces)"
            tvLabelStatVoice.text = "Active Voice"

            tvLabelVoiceSelector.text = "Voice Model Selector & Switcher"
            tvQuickSwitchHint.text =
                "Both voice models are downloaded — tap below to switch which model generates audio:"
            tvLabelSpeedSelector.text = "Speech Speed"
            rbSpeed075.text = "0.75x"
            rbSpeed100.text = "1.0x"
            rbSpeed125.text = "1.25x"
            rbSpeed150.text = "1.5x"

            tvLabelFormatSelector.text = "Export Audio Format (MP3 / WAV / M4A / PCM)"

            tvLabelScriptInput.text = "Bengali Text Script"
            btnSampleNews.text = "News Sample"
            btnClearText.text = "Clear"
            etInputText.hint = "Type or paste clean Bengali script here…"
            btnPaste.text = "📋 Paste Text"
            btnPickTxt.text = "📄 Open .txt File"

            btnGenerate.text = "⚙️ Generate Audio (Silent Background • Auto-Saves File)"
            btnQuickShare.text = "📤 Share Audio File to Social Media & Cloud"

            tvLabelRecentHistory.text = "Recent Auto-Saved Audio Files (Room History)"
            btnViewAllHistory.text = "View All"
            tvStudioRecentEmpty.text =
                "No audio generated yet. Completed generations are automatically saved here without auto-playing."

            tvHistoryHeaderTitle.text = "Audio Generation History (Room Database)"
            tvHistorySummaryCount.text = "Total saved audio files: $count"
            btnClearAllHistory.text = "Clear All"
            tvHistoryHeaderDesc.text =
                "Every generated audio is automatically saved here when finished. Tap Play File to listen, or Save in MP3/WAV/M4A/PCM format."
            tvHistoryEmptyTitle.text = "🗂️ No Audio History Yet"
            tvHistoryEmptySubtitle.text =
                "Go to the Studio tab, enter Bengali text, and tap Generate. It will run silently in the background and auto-save here."

            btnCopyAiPrompt.text = "📋 Copy Part 1: AI News Script Prompt"
            btnCopyInputGuide.text = "📋 Copy Part 2: Text Input Instructions"
            tvSettingsVitsHeader.text = "Custom Audio & VITS Engine Settings"
            tvSettingsExportHeader.text = "Export Formats (MP3 / WAV / M4A / PCM) & Share"
            btnSettingsShareTranscript.text = "📲 Share Bengali Script to Social Media"
            btnCloudSyncUpload.text = "☁️ Sync Audio File to Cloud Webhook API"
        } else {
            tvAppTitle.text = "বাংলা ভয়েস স্টুডিও"
            tvAppSubtitle.text = "অফলাইন বাংলা টিটিএস ও অডিও জেনারেশন স্টুডিও"
            btnLanguageToggle.text = "🌐 English"

            btnNavStudio.text = "স্টুডিও"
            btnNavHistory.text = "ইতিহাস ($countBn)"
            btnNavModels.text = "মডেল"
            btnNavSettings.text = "সেটিংস ও গাইড"

            btnRetryDownload.text = "আবার চেষ্টা করুন"
            btnDismissError.text = "বন্ধ করুন"

            tvLabelStatChars.text = "অক্ষর ও শব্দ"
            tvLabelStatChunks.text = "বাক্য খণ্ড (Pieces)"
            tvLabelStatVoice.text = "সক্রিয় কণ্ঠ"

            tvLabelVoiceSelector.text = "কণ্ঠ নির্বাচন ও সুইচ (Voice Model Switcher)"
            tvQuickSwitchHint.text =
                "দুইটি মডেলই ডাউনলোড করা আছে — কোনটি দিয়ে অডিও জেনারেট করবেন তা ট্যাপ করে সুইচ করুন:"
            tvLabelSpeedSelector.text = "কণ্ঠের গতি (Speed Selector)"
            rbSpeed075.text = "০.৭৫x"
            rbSpeed100.text = "১.০x"
            rbSpeed125.text = "১.২৫x"
            rbSpeed150.text = "১.৫x"

            tvLabelFormatSelector.text = "অডিও ফাইল ফরম্যাট (MP3 / WAV / M4A / PCM)"

            tvLabelScriptInput.text = "বাংলা টেক্সট স্ক্রিপ্ট"
            btnSampleNews.text = "সংবাদ নমুনা"
            btnClearText.text = "মুছুন"
            etInputText.hint = "এখানে পরিষ্কার বাংলা টেক্সট লিখুন বা পেস্ট করুন…"
            btnPaste.text = "📋 পেস্ট করুন (Paste)"
            btnPickTxt.text = "📄 .txt ফাইল খুলুন"

            btnGenerate.text = "⚙️ অডিও তৈরি করুন (অটো-প্লে ছাড়া ব্যাকগ্রাউন্ডে জেনারেট)"
            btnQuickShare.text = "📤 সোশ্যাল মিডিয়া ও ক্লাউডে অডিও ফাইল শেয়ার করুন"

            tvLabelRecentHistory.text = "সাম্প্রতিক অটো-সেভড অডিও ফাইল (Room History)"
            btnViewAllHistory.text = "সব দেখুন"
            tvStudioRecentEmpty.text =
                "এখনো কোনো অডিও তৈরি করা হয়নি। অডিও জেনারেট শেষ হলে অটো-প্লে ছাড়াই স্বয়ংক্রিয়ভাবে এখানে ফাইল হিসেবে সেভ হবে।"

            tvHistoryHeaderTitle.text = "অডিও জেনারেশন ইতিহাস (Room Database)"
            tvHistorySummaryCount.text = "মোট সংরক্ষিত অডিও: $countBn টি"
            btnClearAllHistory.text = "সব মুছুন"
            tvHistoryHeaderDesc.text =
                "জেনারেট করা শেষ হলে প্রতিটি অডিও ফাইল স্বয়ংক্রিয়ভাবে এখানে সেভ হয়। আপনি যেকোনো সময় ফাইলটি প্লে করতে কিংবা MP3/WAV/M4A/PCM ফরম্যাটে সেভ করতে পারবেন।"
            tvHistoryEmptyTitle.text = "🗂️ কোনো পূর্ববর্তী অডিও ইতিহাস নেই"
            tvHistoryEmptySubtitle.text =
                "স্টুডিও ট্যাবে গিয়ে বাংলা টেক্সট লিখে 'Generate' চাপলে অডিও তৈরি হবে এবং স্বয়ংক্রিয়ভাবে এখানে সংরক্ষিত হবে।"

            btnCopyAiPrompt.text = "📋 ১ম ভাগ কপি করুন: AI স্ক্রিপ্ট প্রম্পট"
            btnCopyInputGuide.text = "📋 ২য় ভাগ কপি করুন: ইনপুট নির্দেশনা"
            tvSettingsVitsHeader.text = "কাস্টম অডিও ও VITS ইঞ্জিন সেটিংস"
            tvSettingsExportHeader.text = "এক্সপোর্ট ফরম্যাট (MP3 / WAV / M4A / PCM) ও শেয়ার"
            btnSettingsShareTranscript.text = "📲 বাংলা স্ক্রিপ্ট ও অডিও তথ্য সোশ্যাল মিডিয়ায় শেয়ার করুন"
            btnCloudSyncUpload.text = "☁️ ক্লাউড এপিআই-তে অডিও সিঙ্ক করুন"
        }

        updateFormatButtonsUi()
        val state = TtsForegroundService.sessionState.value
        tvStatusMessage.text = if (isEnglishUi) state.statusEn else state.statusBn
    }

    private fun switchMenuTab(index: Int) {
        sectionStudio.visibility = if (index == 0) View.VISIBLE else View.GONE
        sectionHistory.visibility = if (index == 1) View.VISIBLE else View.GONE
        sectionModels.visibility = if (index == 2) View.VISIBLE else View.GONE
        sectionSettings.visibility = if (index == 3) View.VISIBLE else View.GONE

        val tabs = listOf(btnNavStudio, btnNavHistory, btnNavModels, btnNavSettings)
        for (i in tabs.indices) {
            val btn = tabs[i]
            if (i == index) {
                btn.setBackgroundResource(R.drawable.bg_tab_selected)
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent))
            } else {
                btn.setBackgroundResource(R.drawable.bg_tab_unselected)
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
        refreshVoiceSelectorUi()
        refreshRamStatusUi()
    }

    /**
     * Observes the Room Database Flow<List<AudioHistoryEntity>> reactively and renders
     * both the full History tab and the Studio quick-access list.
     */
    private fun observeRoomHistory() {
        lifecycleScope.launch {
            historyRepository.allHistory.collectLatest { items ->
                cachedHistoryItems = items
                renderHistoryViews(items)
            }
        }
    }

    private fun renderHistoryViews(items: List<AudioHistoryEntity>) {
        val count = items.size
        val countBn = BengaliTextChunker.toBengaliDigits(count)
        if (isEnglishUi) {
            btnNavHistory.text = "History ($count)"
            tvHistorySummaryCount.text = "Total saved audio files: $count"
        } else {
            btnNavHistory.text = "ইতিহাস ($countBn)"
            tvHistorySummaryCount.text = "মোট সংরক্ষিত অডিও: $countBn টি"
        }
        btnClearAllHistory.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        val activePlayingId = TtsForegroundService.sessionState.value.playingHistoryId

        // 1. Render full History Tab list
        containerHistoryList.removeAllViews()
        if (items.isEmpty()) {
            layoutHistoryEmptyState.visibility = View.VISIBLE
        } else {
            layoutHistoryEmptyState.visibility = View.GONE
            for (entity in items) {
                val card = buildHistoryItemCard(
                    entity = entity,
                    isCurrentlyPlaying = (activePlayingId == entity.id),
                    compact = false
                )
                containerHistoryList.addView(card)
            }
        }

        // 2. Render Studio Recent Creations (top 3 items)
        containerStudioRecentItems.removeAllViews()
        if (items.isEmpty()) {
            tvStudioRecentEmpty.visibility = View.VISIBLE
        } else {
            tvStudioRecentEmpty.visibility = View.GONE
            for (entity in items.take(3)) {
                val compactCard = buildHistoryItemCard(
                    entity = entity,
                    isCurrentlyPlaying = (activePlayingId == entity.id),
                    compact = true
                )
                containerStudioRecentItems.addView(compactCard)
            }
        }
    }

    private fun buildHistoryItemCard(
        entity: AudioHistoryEntity,
        isCurrentlyPlaying: Boolean,
        compact: Boolean
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(
                if (compact) R.drawable.bg_card_elevated else R.drawable.bg_card_rounded
            )
            val pad = dpToPx(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        }

        // Top Row: Voice Model Badge + Formatted Timestamp & Duration
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val speedFormatted = String.format("%.2f", entity.speed).trimEnd('0').trimEnd('.')
        val speedDisplay = if (isEnglishUi) speedFormatted else BengaliTextChunker.toBengaliDigits(speedFormatted)
        val voiceBadge = TextView(this).apply {
            text = "🎙️ ${entity.voiceModel} • ${speedDisplay}x"
            setBackgroundResource(R.drawable.bg_badge_teal)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_dark))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
        }

        val durRaw = String.format("%.1f", entity.durationSeconds)
        val durDisplay = if (isEnglishUi) "${durRaw}s" else "${BengaliTextChunker.toBengaliDigits(durRaw)} সে."
        val timeFormatted = formatTimestamp(entity.timestamp)
        val timeView = TextView(this).apply {
            text = "$timeFormatted • $durDisplay"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dpToPx(8)
            }
        }

        topRow.addView(voiceBadge)
        topRow.addView(timeView)
        card.addView(topRow)

        // Snippet Text
        val snippetView = TextView(this).apply {
            text = entity.textSnippet
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = if (compact) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }
        card.addView(snippetView)

        // Action Buttons Row: Play/Stop, Load into Studio, Save in Selected Format, Delete
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
        }

        val playLabel = if (isCurrentlyPlaying) {
            if (isEnglishUi) "⏹️ Stop" else "⏹️ থামান"
        } else {
            if (isEnglishUi) "▶️ Play File" else "▶️ ফাইল বাজান"
        }

        val playBtn = Button(this).apply {
            text = playLabel
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundResource(
                if (isCurrentlyPlaying) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary
            )
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_on_accent))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1.2f)
            setOnClickListener {
                if (isCurrentlyPlaying) {
                    stopServicePlayback()
                } else {
                    playHistoryItem(entity)
                }
            }
        }

        val loadTextBtn = Button(this).apply {
            text = if (isEnglishUi) "📝 Use Script" else "📝 এডিটরে নিন"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundResource(R.drawable.bg_btn_secondary)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1.1f).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener {
                loadHistoryCreationIntoStudio(entity)
            }
        }

        actionRow.addView(playBtn)
        actionRow.addView(loadTextBtn)

        val fmtUpper = selectedExportFormat.extension.uppercase(Locale.US)
        val saveBtn = Button(this).apply {
            text = "💾 $fmtUpper"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundResource(R.drawable.bg_btn_secondary)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 0.9f).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener {
                saveHistoryItemToDownloads(entity)
            }
        }
        actionRow.addView(saveBtn)

        if (!compact) {
            val deleteBtn = Button(this).apply {
                text = "🗑️"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundResource(R.drawable.bg_btn_danger)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_on_accent))
                layoutParams = LinearLayout.LayoutParams(dpToPx(46), dpToPx(42)).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener {
                    lifecycleScope.launch {
                        historyRepository.deleteCreation(entity)
                        TtsForegroundService.updateStatusExternal(
                            statusBn = "ইতিহাস থেকে অডিওটি মুছে ফেলা হয়েছে।",
                            statusEn = "Deleted audio item from history."
                        )
                    }
                }
            }
            actionRow.addView(deleteBtn)
        }

        card.addView(actionRow)
        return card
    }

    private fun playHistoryItem(entity: AudioHistoryEntity) {
        val file = File(entity.audioFilePath)
        if (!file.exists()) {
            showBengaliError(
                if (isEnglishUi) "Saved audio file was not found on device."
                else "সংরক্ষিত অডিও ফাইলটি ডিভাইসে পাওয়া যায়নি।"
            )
            return
        }
        val playIntent = Intent(this, TtsForegroundService::class.java).apply {
            action = TtsForegroundService.ACTION_PLAY_HISTORY_FILE
            putExtra(TtsForegroundService.EXTRA_HISTORY_ID, entity.id)
            putExtra(TtsForegroundService.EXTRA_HISTORY_FILE_PATH, entity.audioFilePath)
            putExtra(TtsForegroundService.EXTRA_HISTORY_TITLE, entity.textSnippet)
            putExtra(TtsForegroundService.EXTRA_IS_ENGLISH_UI, isEnglishUi)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(playIntent)
            } else {
                startService(playIntent)
            }
        } catch (e: Exception) {
            showBengaliError("ইতিহাসের অডিও চালানো যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun stopServicePlayback() {
        val stopIntent = Intent(this, TtsForegroundService::class.java).apply {
            action = TtsForegroundService.ACTION_STOP
        }
        try {
            startService(stopIntent)
        } catch (_: Exception) {
        }
    }

    private fun loadHistoryCreationIntoStudio(entity: AudioHistoryEntity) {
        etInputText.setText(entity.fullText)
        etInputText.setSelection(entity.fullText.length)

        // Restore voice selection if applicable
        when {
            entity.voiceModel.contains("Kiki", ignoreCase = true) ||
                entity.voiceModel.contains("সংবাদ") -> {
                if (VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)) {
                    selectVoiceMode(SelectedVoiceMode.KIKI)
                }
            }
            entity.voiceModel.contains("ElevenLabs", ignoreCase = true) -> {
                selectVoiceMode(SelectedVoiceMode.ELEVENLABS)
            }
        }

        // Restore speed selection
        when (entity.speed) {
            0.75f -> rbSpeed075.isChecked = true
            1.25f -> rbSpeed125.isChecked = true
            1.5f -> rbSpeed150.isChecked = true
            else -> rbSpeed100.isChecked = true
        }

        switchMenuTab(0)
        TtsForegroundService.updateStatusExternal(
            statusBn = "ইতিহাস থেকে টেক্সট ও ভয়েস মডেল (${entity.voiceModel}) স্টুডিওতে লোড করা হয়েছে।",
            statusEn = "Loaded script & voice model (${entity.voiceModel}) into Studio."
        )
    }

    private fun saveHistoryItemToDownloads(entity: AudioHistoryEntity) {
        val file = File(entity.audioFilePath)
        if (!file.exists()) {
            showBengaliError("সংরক্ষিত অডিও ফাইলটি খুঁজে পাওয়া যায়নি।")
            return
        }
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    BengaliTextChunker.decodeWavFileToPcm16(file.readBytes())
                } catch (_: Exception) {
                    null
                }
            }
            if (decoded == null || decoded.first.isEmpty()) {
                showBengaliError("অডিও ফাইলটি পড়া যায়নি।")
                return@launch
            }
            val (pcm, sampleRate) = decoded
            encodeAndWriteToPublicDownloads(
                pcmSamples = pcm,
                sampleRate = sampleRate,
                format = selectedExportFormat,
                timestamp = entity.timestamp,
                shareAfterSave = false
            )
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.US)
        val raw = sdf.format(Date(timestampMs))
        return if (isEnglishUi) raw else BengaliTextChunker.toBengaliDigits(raw)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun selectVoiceMode(newMode: SelectedVoiceMode) {
        val previousMode = selectedVoiceMode
        selectedVoiceMode = newMode

        rbVoiceEleven.isChecked = (newMode == SelectedVoiceMode.ELEVENLABS)
        rbVoiceKiki.isChecked = (newMode == SelectedVoiceMode.KIKI)

        val activeDisplayName = when (newMode) {
            SelectedVoiceMode.ELEVENLABS -> VoiceModelSpec.ELEVENLABS.displayName
            SelectedVoiceMode.KIKI -> VoiceModelSpec.KIKI_NEWS.displayName
            SelectedVoiceMode.CUSTOM -> if (isEnglishUi) "Custom VITS" else "কাস্টম VITS"
        }
        tvStatActiveVoice.text = activeDisplayName

        // Highlight active quick-switch button when both models are present
        updateQuickSwitchBarVisuals()

        // Unload previous voice from RAM when switching voice to save RAM
        if (previousMode != newMode) {
            val loadedKey = TtsEngineHolder.getLoadedVoiceKey()
            val targetKey = when (newMode) {
                SelectedVoiceMode.ELEVENLABS -> VoiceModelSpec.ELEVENLABS.id
                SelectedVoiceMode.KIKI -> VoiceModelSpec.KIKI_NEWS.id
                SelectedVoiceMode.CUSTOM -> "custom"
            }
            if (loadedKey != null && loadedKey != targetKey) {
                TtsEngineHolder.unloadCurrentVoice()
            }
        }
        refreshRamStatusUi()
    }

    private fun updateQuickSwitchBarVisuals() {
        val bothDownloaded =
            VoiceModelSpec.ELEVENLABS.isVerifiedPresent(this) &&
                VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)

        layoutQuickModelSwitch.visibility = if (bothDownloaded) View.VISIBLE else View.GONE
        if (!bothDownloaded) return

        val isElevenActive = (selectedVoiceMode == SelectedVoiceMode.ELEVENLABS)
        val isKikiActive = (selectedVoiceMode == SelectedVoiceMode.KIKI)

        if (isElevenActive) {
            btnQuickSwitchEleven.setBackgroundResource(R.drawable.bg_tab_selected)
            btnQuickSwitchEleven.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent))
            btnQuickSwitchEleven.text = if (isEnglishUi) "✓ ElevenLabs (Active)" else "✓ ElevenLabs (সক্রিয়)"
        } else {
            btnQuickSwitchEleven.setBackgroundResource(R.drawable.bg_tab_unselected)
            btnQuickSwitchEleven.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnQuickSwitchEleven.text = "🎙️ ElevenLabs"
        }

        if (isKikiActive) {
            btnQuickSwitchKiki.setBackgroundResource(R.drawable.bg_tab_selected)
            btnQuickSwitchKiki.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent))
            btnQuickSwitchKiki.text = if (isEnglishUi) "✓ সংবাদ (Kiki) (Active)" else "✓ সংবাদ (Kiki) (সক্রিয়)"
        } else {
            btnQuickSwitchKiki.setBackgroundResource(R.drawable.bg_tab_unselected)
            btnQuickSwitchKiki.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnQuickSwitchKiki.text = "📰 সংবাদ (Kiki)"
        }
    }

    private fun refreshVoiceSelectorUi() {
        val elevenReady = VoiceModelSpec.ELEVENLABS.isVerifiedPresent(this)
        val kikiReady = VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)

        if (elevenReady) {
            tvElevenStatusBadge.text = if (isEnglishUi) "Downloaded ✓" else "ডাউনলোডকৃত ✓"
            tvElevenStatusBadge.setBackgroundResource(R.drawable.bg_badge_teal)
            tvElevenStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_primary_dark))
            tvModelCardElevenStatus.text = if (isEnglishUi) "Ready (100%)" else "প্রস্তুত (১০০%)"
            btnModelCardDownloadEleven.text =
                if (isEnglishUi) "Verified ✓ (Re-check)" else "যাচাইকৃত ✓ (পুনরায় পরীক্ষা করুন)"
        } else {
            tvElevenStatusBadge.text = if (isEnglishUi) "Download Required" else "ডাউনলোড প্রয়োজন"
            tvElevenStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvElevenStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            tvModelCardElevenStatus.text = if (isEnglishUi) "Not Downloaded" else "ডাউনলোড বাকি"
            btnModelCardDownloadEleven.text =
                if (isEnglishUi) "Download (ElevenLabs)" else "ডাউনলোড করুন (ElevenLabs)"
        }

        if (kikiReady) {
            rbVoiceKiki.isEnabled = true
            btnDownloadKiki.visibility = View.GONE
            tvKikiStatusBadge.visibility = View.VISIBLE
            tvKikiStatusBadge.text = if (isEnglishUi) "Downloaded ✓" else "ডাউনলোডকৃত ✓"
            tvModelCardKikiStatus.text = if (isEnglishUi) "Ready (100%)" else "প্রস্তুত (১০০%)"
            tvModelCardKikiStatus.setBackgroundResource(R.drawable.bg_badge_teal)
            tvModelCardKikiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_primary_dark))
            btnModelCardDownloadKiki.text =
                if (isEnglishUi) "Verified ✓ (সংবাদ Kiki)" else "যাচাইকৃত ✓ (সংবাদ Kiki)"
        } else {
            rbVoiceKiki.isEnabled = false
            btnDownloadKiki.visibility = View.VISIBLE
            btnDownloadKiki.text = if (isEnglishUi) "Download" else "ডাউনলোড"
            tvKikiStatusBadge.visibility = View.GONE
            tvModelCardKikiStatus.text = if (isEnglishUi) "Optional Voice" else "ঐচ্ছিক কণ্ঠ"
            tvModelCardKikiStatus.setBackgroundResource(R.drawable.bg_badge_amber)
            tvModelCardKikiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnModelCardDownloadKiki.text =
                if (isEnglishUi) "Download (সংবাদ Kiki)" else "ডাউনলোড (সংবাদ Kiki)"
            if (selectedVoiceMode == SelectedVoiceMode.KIKI) {
                selectVoiceMode(SelectedVoiceMode.ELEVENLABS)
            }
        }

        updateQuickSwitchBarVisuals()
    }

    private fun refreshRamStatusUi() {
        val loadedKey = TtsEngineHolder.getLoadedVoiceKey()
        if (loadedKey == null) {
            tvLoadedRamBadge.text = if (isEnglishUi) "RAM: Idle" else "RAM: খালি"
            tvEngineMemoryInfo.text = if (isEnglishUi) {
                "No model currently loaded in RAM. The selected model loads lazily on first Generate."
            } else {
                "বর্তমানে মেমোরিতে কোনো মডেল লোড নেই। প্রথমবার Generate চাপলে নির্বাচিত কণ্ঠ স্বয়ংক্রিয়ভাবে লোড হবে।"
            }
        } else {
            val name = when (loadedKey) {
                VoiceModelSpec.ELEVENLABS.id -> "ElevenLabs"
                VoiceModelSpec.KIKI_NEWS.id -> "সংবাদ (Kiki)"
                else -> if (isEnglishUi) "Custom" else "কাস্টম"
            }
            tvLoadedRamBadge.text = "RAM: $name"
            tvEngineMemoryInfo.text = if (isEnglishUi) {
                "'$name' is currently loaded in RAM for fast consecutive generations."
            } else {
                "বর্তমানে '$name' মডেলটি মেমোরিতে (RAM) সক্রিয় আছে যাতে দ্রুত পরবর্তী অডিও তৈরি করা যায়।"
            }
        }
    }

    private fun triggerModelDownload(spec: VoiceModelSpec) {
        if (activeDownloadJob?.isActive == true) {
            TtsForegroundService.updateStatusExternal(
                statusBn = "একটি ডাউনলোড ইতিমধ্যে চলছে, অনুগ্রহ করে অপেক্ষা করুন…",
                statusEn = "A download is already in progress, please wait…"
            )
            return
        }

        layoutErrorBanner.visibility = View.GONE
        layoutDownloadBanner.visibility = View.VISIBLE
        progressDownload.progress = 0
        tvDownloadTitle.text = if (isEnglishUi) {
            "Downloading ${spec.displayName} voice model…"
        } else {
            "${spec.displayName} ভয়েস মডেল ডাউনলোড হচ্ছে…"
        }
        tvDownloadDetails.text = if (isEnglishUi) "Connecting… (0%)" else "সংযোগ স্থাপন করা হচ্ছে… (০%)"
        btnDownloadKiki.isEnabled = false

        activeDownloadJob = lifecycleScope.launch {
            val result = VoiceModelDownloader.downloadVoiceModel(
                context = applicationContext,
                spec = spec,
                onProgress = { pct, _, _, statusBn ->
                    runOnUiThread {
                        layoutDownloadBanner.visibility = View.VISIBLE
                        progressDownload.progress = pct
                        tvDownloadDetails.text = statusBn
                        tvStatusMessage.text = statusBn
                        tvProgressPercentBn.text = if (isEnglishUi) {
                            "$pct%"
                        } else {
                            "${BengaliTextChunker.toBengaliDigits(pct)}%"
                        }
                    }
                }
            )

            btnDownloadKiki.isEnabled = true
            refreshVoiceSelectorUi()

            if (result.isSuccess) {
                layoutDownloadBanner.visibility = View.GONE
                val readyMsgBn = "${spec.displayName} মডেল প্রস্তুত — এখন বাংলা টেক্সট থেকে অডিও তৈরি করতে পারেন।"
                val readyMsgEn = "${spec.displayName} model is ready — you can now generate Bengali speech."
                TtsForegroundService.updateStatusExternal(readyMsgBn, readyMsgEn)
                tvProgressPercentBn.text = if (isEnglishUi) "100%" else "১০০%"
                if (spec == VoiceModelSpec.KIKI_NEWS) {
                    selectVoiceMode(SelectedVoiceMode.KIKI)
                }
            } else {
                layoutDownloadBanner.visibility = View.GONE
                val errMsg = result.exceptionOrNull()?.message
                    ?: "${spec.displayName} মডেল ডাউনলোড ব্যর্থ হয়েছে।"
                showBengaliError(errMsg, failedSpec = spec)
            }
        }
    }

    private fun handleGenerateClicked() {
        layoutErrorBanner.visibility = View.GONE
        val input = etInputText.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            showBengaliError(
                if (isEnglishUi) "Please enter, paste, or open a .txt file with Bengali text first."
                else "অনুগ্রহ করে প্রথমে বাংলা টেক্সট লিখুন, পেস্ট করুন বা ফাইল থেকে খুলুন।"
            )
            return
        }

        val voiceKey: String
        val voiceName: String
        val modelPath: String
        val tokensPath: String

        when (selectedVoiceMode) {
            SelectedVoiceMode.ELEVENLABS -> {
                val spec = VoiceModelSpec.ELEVENLABS
                if (!spec.isVerifiedPresent(this)) {
                    showBengaliError(
                        if (isEnglishUi) {
                            "ElevenLabs model is not yet downloaded. Starting automatic download…"
                        } else {
                            "ElevenLabs ভয়েস মডেলটি এখনো সম্পূর্ণ ডাউনলোড হয়নি। স্বয়ংক্রিয় ডাউনলোড শুরু হচ্ছে…"
                        },
                        failedSpec = spec
                    )
                    triggerModelDownload(spec)
                    return
                }
                voiceKey = spec.id
                voiceName = spec.displayName
                modelPath = spec.getModelFile(this).absolutePath
                tokensPath = spec.getTokensFile(this).absolutePath
            }
            SelectedVoiceMode.KIKI -> {
                val spec = VoiceModelSpec.KIKI_NEWS
                if (!spec.isVerifiedPresent(this)) {
                    showBengaliError(
                        if (isEnglishUi) {
                            "সংবাদ (Kiki) model is not downloaded. Please tap 'Download' first."
                        } else {
                            "সংবাদ (Kiki) মডেলটি ডাউনলোড করা নেই। অনুগ্রহ করে 'ডাউনলোড' বাটনে চাপুন।"
                        },
                        failedSpec = spec
                    )
                    return
                }
                voiceKey = spec.id
                voiceName = spec.displayName
                modelPath = spec.getModelFile(this).absolutePath
                tokensPath = spec.getTokensFile(this).absolutePath
            }
            SelectedVoiceMode.CUSTOM -> {
                val onnx = customOnnxFile
                val tok = customTokensFile
                if (onnx == null || !onnx.exists() || tok == null || !tok.exists()) {
                    showBengaliError("কাস্টম model.onnx এবং tokens.txt উভয় ফাইল নির্বাচন করুন।")
                    return
                }
                voiceKey = "custom"
                voiceName = "কাস্টম VITS"
                modelPath = onnx.absolutePath
                tokensPath = tok.absolutePath
            }
        }

        // Start foreground service ONLY when Generate is tapped (strictly silent background generation)
        val serviceIntent = Intent(this, TtsForegroundService::class.java).apply {
            action = TtsForegroundService.ACTION_START_GENERATE
            putExtra(TtsForegroundService.EXTRA_TEXT, input)
            putExtra(TtsForegroundService.EXTRA_VOICE_KEY, voiceKey)
            putExtra(TtsForegroundService.EXTRA_VOICE_NAME, voiceName)
            putExtra(TtsForegroundService.EXTRA_MODEL_PATH, modelPath)
            putExtra(TtsForegroundService.EXTRA_TOKENS_PATH, tokensPath)
            putExtra(TtsForegroundService.EXTRA_SPEED, selectedSpeed)
            putExtra(TtsForegroundService.EXTRA_NOISE_SCALE, noiseScale)
            putExtra(TtsForegroundService.EXTRA_NOISE_SCALE_W, noiseScaleW)
            putExtra(TtsForegroundService.EXTRA_LENGTH_SCALE, lengthScale)
            putExtra(TtsForegroundService.EXTRA_VOLUME_GAIN, volumeGain)
            putExtra(TtsForegroundService.EXTRA_IS_ENGLISH_UI, isEnglishUi)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            showBengaliError("ব্যাকগ্রাউন্ড সার্ভিস চালু করতে সমস্যা হয়েছে: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun handlePlayStopClicked() {
        val currentState = TtsForegroundService.sessionState.value
        if (currentState.isRunning) {
            stopServicePlayback()
        } else {
            // Play last generated audio file/buffer if present, or most recent Room history item
            if (!currentState.hasCompletedWav && TtsForegroundService.getLastWavBytes() == null) {
                val latestHistory = cachedHistoryItems.firstOrNull()
                if (latestHistory != null) {
                    playHistoryItem(latestHistory)
                    return
                }
                showBengaliError(
                    if (isEnglishUi) "No generated audio file to play yet. Tap 'Generate' first."
                    else "চালানোর মতো কোনো অডিও ফাইল নেই। প্রথমে 'Generate' বাটনে চাপুন।"
                )
                return
            }
            val playIntent = Intent(this, TtsForegroundService::class.java).apply {
                action = TtsForegroundService.ACTION_PLAY_SAVED_BUFFER
                putExtra(TtsForegroundService.EXTRA_IS_ENGLISH_UI, isEnglishUi)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(playIntent)
                } else {
                    startService(playIntent)
                }
            } catch (e: Exception) {
                showBengaliError("অডিও প্লেব্যাক শুরু করা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }
    }

    private fun observeServiceState() {
        var lastPlayingId: Long? = null
        lifecycleScope.launch {
            TtsForegroundService.sessionState.collectLatest { state ->
                tvStatusMessage.text = if (isEnglishUi) state.statusEn else state.statusBn
                val pctBn = BengaliTextChunker.toBengaliDigits(state.progressPercent)
                tvProgressPercentBn.text = if (isEnglishUi) "${state.progressPercent}%" else "$pctBn%"
                progressSynthesis.progress = state.progressPercent

                val curBn = BengaliTextChunker.toBengaliDigits(state.currentPiece)
                val totBn = BengaliTextChunker.toBengaliDigits(state.totalPieces)
                if (state.totalPieces > 0) {
                    tvPieceCounterBn.text = if (isEnglishUi) {
                        "Piece ${state.currentPiece} of ${state.totalPieces} (${state.progressPercent}%)"
                    } else {
                        "খণ্ড $curBn এর $totBn ($pctBn%)"
                    }
                } else {
                    tvPieceCounterBn.text = if (isEnglishUi) "Piece 0 of 0" else "খণ্ড ০ এর ০"
                }

                if (state.activePieceText.isNotBlank()) {
                    tvActivePiecePreview.visibility = View.VISIBLE
                    tvActivePiecePreview.text = if (isEnglishUi) {
                        "Current chunk: “${state.activePieceText}”"
                    } else {
                        "বর্তমান বাক্য: “${state.activePieceText}”"
                    }
                } else {
                    tvActivePiecePreview.visibility = View.GONE
                }

                if (state.isRunning) {
                    btnPlayStop.text = if (isEnglishUi) "⏹️ Stop" else "⏹️ থামান (Stop)"
                    btnPlayStop.setBackgroundResource(R.drawable.bg_btn_danger)
                    btnPlayStop.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_on_accent))
                } else {
                    btnPlayStop.text = if (isEnglishUi) "▶️ Play File" else "▶️ ফাইল চালান (Play)"
                    btnPlayStop.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnPlayStop.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                }

                val err = if (isEnglishUi) (state.errorEn ?: state.errorBn) else state.errorBn
                if (err != null) {
                    showBengaliError(err)
                }

                if (lastPlayingId != state.playingHistoryId) {
                    lastPlayingId = state.playingHistoryId
                    renderHistoryViews(cachedHistoryItems)
                }

                refreshRamStatusUi()
            }
        }
    }

    private fun updateTextAnalytics() {
        val text = etInputText.text?.toString().orEmpty()
        val charCount = text.length
        val wordCount = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
        val chunks = BengaliTextChunker.chunkText(text)

        if (isEnglishUi) {
            tvStatCharsWords.text = "$charCount chars • $wordCount words"
            tvStatChunks.text = "${chunks.size} pieces"
        } else {
            val charBn = BengaliTextChunker.toBengaliDigits(charCount)
            val wordBn = BengaliTextChunker.toBengaliDigits(wordCount)
            val chunkBn = BengaliTextChunker.toBengaliDigits(chunks.size)
            tvStatCharsWords.text = "$charBn অক্ষর • $wordBn শব্দ"
            tvStatChunks.text = "$chunkBn খণ্ড"
        }
    }

    private fun pasteFromClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasted = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
                if (pasted.isNotBlank()) {
                    etInputText.setText(pasted)
                    etInputText.setSelection(pasted.length)
                    TtsForegroundService.updateStatusExternal(
                        statusBn = "ক্লিপবোর্ড থেকে বাংলা টেক্সট পেস্ট করা হয়েছে।",
                        statusEn = "Pasted Bengali text from clipboard."
                    )
                } else {
                    showBengaliError(
                        if (isEnglishUi) "No text found in clipboard." else "ক্লিপবোর্ডে কোনো টেক্সট পাওয়া যায়নি।"
                    )
                }
            } else {
                showBengaliError(
                    if (isEnglishUi) "Clipboard is empty." else "ক্লিপবোর্ড খালি রয়েছে।"
                )
            }
        } catch (e: Exception) {
            showBengaliError("পেস্ট করতে সমস্যা হয়েছে: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun loadTextFromUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    } ?: throw Exception("ফাইল পড়া যায়নি।")
                } catch (e: Exception) {
                    null to (e.localizedMessage ?: "ফাইল পড়ার ত্রুটি")
                }
            }
            when (result) {
                is String -> {
                    if (result.isBlank()) {
                        showBengaliError(
                            if (isEnglishUi) "Selected .txt file is empty." else "নির্বাচিত .txt ফাইলটি খালি।"
                        )
                    } else {
                        etInputText.setText(result)
                        etInputText.setSelection(result.length)
                        TtsForegroundService.updateStatusExternal(
                            statusBn = ".txt ফাইল থেকে টেক্সট সফলভাবে লোড হয়েছে। অডিও তৈরি করতে Generate চাপুন।",
                            statusEn = "Loaded text from .txt file. Tap Generate to create audio."
                        )
                    }
                }
                is Pair<*, *> -> {
                    showBengaliError("ফাইল লোড ব্যর্থ হয়েছে: ${result.second}")
                }
            }
        }
    }

    private fun getAvailablePcmAndSampleRate(): Pair<ShortArray, Int>? {
        val inMemory = TtsForegroundService.getLastPcmAndSampleRate()
        if (inMemory != null) return inMemory

        val latestHistory = cachedHistoryItems.firstOrNull() ?: return null
        val file = File(latestHistory.audioFilePath)
        if (!file.exists()) return null
        return try {
            BengaliTextChunker.decodeWavFileToPcm16(file.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Saves the complete generated audio to public Downloads as BanglaTTS_<timestamp>.<mp3|wav|m4a|pcm>
     * and optionally launches Android Share sheet for social media / cloud drive sharing.
     */
    private fun saveAudioToDownloads(shareAfterSave: Boolean) {
        val pcmPair = getAvailablePcmAndSampleRate()
        if (pcmPair == null) {
            showBengaliError(
                if (isEnglishUi) "No audio available to save. Tap 'Generate' first."
                else "সংরক্ষণ করার মতো কোনো অডিও নেই। প্রথমে 'Generate' চাপুন।"
            )
            return
        }

        val (pcm, sampleRate) = pcmPair
        encodeAndWriteToPublicDownloads(
            pcmSamples = pcm,
            sampleRate = sampleRate,
            format = selectedExportFormat,
            timestamp = System.currentTimeMillis(),
            shareAfterSave = shareAfterSave
        )
    }

    private fun encodeAndWriteToPublicDownloads(
        pcmSamples: ShortArray,
        sampleRate: Int,
        format: AudioExportFormat,
        timestamp: Long,
        shareAfterSave: Boolean
    ) {
        val fileName = "BanglaTTS_$timestamp.${format.extension}"

        lifecycleScope.launch {
            val savedResult = withContext(Dispatchers.IO) {
                try {
                    val encodedBytes = AudioFormatExporter.encodeAudioToBytes(
                        pcmSamples = pcmSamples,
                        sampleRate = sampleRate,
                        format = format,
                        cacheDir = cacheDir
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = applicationContext.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("Downloads ফোল্ডারে ফাইল তৈরি করা যায়নি।")
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(encodedBytes)
                            out.flush()
                        } ?: throw Exception("আউটপুট স্ট্রিম খোলা যায়নি।")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        Result.success(uri to "Downloads/$fileName")
                    } else {
                        @Suppress("DEPRECATION")
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val outFile = File(downloadsDir, fileName)
                        FileOutputStream(outFile).use { out ->
                            out.write(encodedBytes)
                            out.flush()
                        }
                        Result.success((null as Uri?) to outFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (savedResult.isSuccess) {
                val (mediaUri, pathDisplay) = savedResult.getOrThrow()
                val fmtUpper = format.extension.uppercase(Locale.US)
                TtsForegroundService.updateStatusExternal(
                    statusBn = "$fmtUpper ফরম্যাটে সেভ হয়েছে: $pathDisplay",
                    statusEn = "Saved as $fmtUpper: $pathDisplay"
                )
                Toast.makeText(
                    this@MainActivity,
                    if (isEnglishUi) "Saved: $pathDisplay" else "সেভ হয়েছে: $pathDisplay",
                    Toast.LENGTH_SHORT
                ).show()
                if (shareAfterSave) {
                    launchShareSheetForAudio(mediaUri, fileName, format.mimeType)
                }
            } else {
                val err = savedResult.exceptionOrNull()?.localizedMessage ?: "অজানা ত্রুটি"
                showBengaliError("ফাইল সেভ করতে ব্যর্থ হয়েছে: $err")
            }
        }
    }

    private fun launchShareSheetForAudio(mediaStoreUri: Uri?, fileName: String, mimeType: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                if (mediaStoreUri != null) {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, mediaStoreUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
                putExtra(
                    Intent.EXTRA_TEXT,
                    "বাংলা ভয়েস স্টুডিও দিয়ে তৈরি অডিও ফাইল ($fileName) — স্ক্রিপ্ট:\n${etInputText.text}"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "অডিও শেয়ার করুন"))
        } catch (e: Exception) {
            showBengaliError("শেয়ার মেনু খোলা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun exportCurrentFormatToCustomUri(uri: Uri) {
        val pcmPair = getAvailablePcmAndSampleRate()
        if (pcmPair == null) {
            showBengaliError("এক্সপোর্ট করার মতো কোনো অডিও নেই।")
            return
        }
        val (pcm, sampleRate) = pcmPair
        val fmt = selectedExportFormat
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val bytes = AudioFormatExporter.encodeAudioToBytes(
                        pcmSamples = pcm,
                        sampleRate = sampleRate,
                        format = fmt,
                        cacheDir = cacheDir
                    )
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw Exception("ফাইলে লেখা যায়নি।")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (res.isSuccess) {
                val fmtUpper = fmt.extension.uppercase(Locale.US)
                TtsForegroundService.updateStatusExternal(
                    statusBn = "নির্বাচিত ফোল্ডারে $fmtUpper অডিও সফলভাবে এক্সপোর্ট হয়েছে।",
                    statusEn = "Exported $fmtUpper audio to selected folder."
                )
            } else {
                showBengaliError("এক্সপোর্ট ব্যর্থ হয়েছে: ${res.exceptionOrNull()?.localizedMessage ?: "ত্রুটি"}")
            }
        }
    }

    private fun shareBanglaTranscriptText() {
        val script = etInputText.text?.toString()?.trim().orEmpty()
        if (script.isEmpty()) {
            showBengaliError("শেয়ার করার জন্য প্রথমে বাংলা টেক্সট লিখুন।")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "বাংলা ভয়েস স্টুডিও স্ক্রিপ্ট")
                putExtra(Intent.EXTRA_TEXT, script)
            }
            startActivity(Intent.createChooser(intent, "স্ক্রিপ্ট শেয়ার করুন"))
        } catch (e: Exception) {
            showBengaliError("শেয়ার করা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun uploadAudioToCloudWebhook() {
        val url = etCloudWebhookUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            showBengaliError("অনুগ্রহ করে একটি বৈধ ক্লাউড এপিআই URL (https://...) লিখুন।")
            return
        }
        val pcmPair = getAvailablePcmAndSampleRate()
        if (pcmPair == null) {
            showBengaliError("ক্লাউডে আপলোড করার আগে অডিও তৈরি করুন (Generate)।")
            return
        }

        val (pcm, sampleRate) = pcmPair
        val fmt = selectedExportFormat
        TtsForegroundService.updateStatusExternal(
            statusBn = "ক্লাউড সার্ভারে ${fmt.extension.uppercase(Locale.US)} অডিও আপলোড হচ্ছে…",
            statusEn = "Uploading ${fmt.extension.uppercase(Locale.US)} audio to cloud webhook…"
        )
        lifecycleScope.launch {
            val uploadResult = withContext(Dispatchers.IO) {
                try {
                    val encodedBytes = AudioFormatExporter.encodeAudioToBytes(
                        pcmSamples = pcm,
                        sampleRate = sampleRate,
                        format = fmt,
                        cacheDir = cacheDir
                    )
                    val client = OkHttpClient()
                    val body = encodedBytes.toRequestBody(fmt.mimeType.toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-File-Name", "BanglaTTS_${System.currentTimeMillis()}.${fmt.extension}")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Result.success(resp.code)
                        } else {
                            Result.failure(Exception("সার্ভার স্ট্যাটাস কোড: ${BengaliTextChunker.toBengaliDigits(resp.code)}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (uploadResult.isSuccess) {
                val code = uploadResult.getOrThrow()
                val codeBn = BengaliTextChunker.toBengaliDigits(code)
                TtsForegroundService.updateStatusExternal(
                    statusBn = "ক্লাউড সিঙ্ক সফল হয়েছে (কোড $codeBn) ✓",
                    statusEn = "Cloud sync succeeded (HTTP $code) ✓"
                )
            } else {
                showBengaliError(
                    "ক্লাউড সিঙ্ক ব্যর্থ হয়েছে: ${uploadResult.exceptionOrNull()?.localizedMessage ?: "নেটওয়ার্ক ত্রুটি"}"
                )
            }
        }
    }

    private fun copyUriToCustomModelFile(uri: Uri, targetName: String, onCopied: (File) -> Unit) {
        lifecycleScope.launch {
            val customDir = File(filesDir, "model_custom")
            val targetFile = File(customDir, targetName)
            val result = withContext(Dispatchers.IO) {
                try {
                    if (!customDir.exists()) customDir.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("ফাইল খোলা যায়নি।")
                    Result.success(targetFile)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (result.isSuccess) {
                onCopied(result.getOrThrow())
            } else {
                showBengaliError("কাস্টম ফাইল কপি করা যায়নি: ${result.exceptionOrNull()?.localizedMessage ?: "ত্রুটি"}")
            }
        }
    }

    private fun updateCustomModelStatusUi() {
        val onnx = customOnnxFile
        val tok = customTokensFile
        if (onnx != null && onnx.exists() && tok != null && tok.exists()) {
            val sizeMb = String.format("%.1f", onnx.length() / (1024.0 * 1024.0))
            val sizeMbBn = BengaliTextChunker.toBengaliDigits(sizeMb)
            tvCustomModelStatus.text =
                "কাস্টম মডেল প্রস্তুত ($sizeMbBn মেগাবাইট) ও সক্রিয় করা হয়েছে ✓"
            selectVoiceMode(SelectedVoiceMode.CUSTOM)
            switchMenuTab(0)
            TtsForegroundService.updateStatusExternal(
                statusBn = "কাস্টম VITS মডেল সক্রিয় করা হয়েছে। এখন Generate চাপুন।",
                statusEn = "Custom VITS model activated. Tap Generate to synthesize."
            )
        } else {
            val onnxStatus = if (onnx != null) "model.onnx ✓" else "model.onnx বাকি"
            val tokStatus = if (tok != null) "tokens.txt ✓" else "tokens.txt বাকি"
            tvCustomModelStatus.text = "কাস্টম মডেল স্থিতি: $onnxStatus • $tokStatus"
        }
    }

    private fun showBengaliError(messageBn: String, failedSpec: VoiceModelSpec? = null) {
        lastFailedDownloadSpec = failedSpec
        layoutErrorBanner.visibility = View.VISIBLE
        tvErrorMessage.text = messageBn
        btnRetryDownload.visibility = if (failedSpec != null) View.VISIBLE else View.GONE
    }

    private fun requestPostNotificationsPermissionOnce() {
        if (hasAskedNotificationPermission) return
        hasAskedNotificationPermission = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
