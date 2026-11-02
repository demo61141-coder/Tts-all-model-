package com.example

import android.Manifest
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

    private enum class SelectedVoiceMode {
        ELEVENLABS,
        KIKI,
        CUSTOM
    }

    private var selectedVoiceMode: SelectedVoiceMode = SelectedVoiceMode.ELEVENLABS
    private var selectedSpeed: Float = 1.0f
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

    private lateinit var tvStatCharsWords: TextView
    private lateinit var tvStatChunks: TextView
    private lateinit var tvStatActiveVoice: TextView

    private lateinit var rbVoiceEleven: RadioButton
    private lateinit var tvElevenStatusBadge: TextView
    private lateinit var rbVoiceKiki: RadioButton
    private lateinit var btnDownloadKiki: Button
    private lateinit var tvKikiStatusBadge: TextView

    private lateinit var rgSpeed: RadioGroup
    private lateinit var rbSpeed075: RadioButton
    private lateinit var rbSpeed100: RadioButton
    private lateinit var rbSpeed125: RadioButton
    private lateinit var rbSpeed150: RadioButton

    private lateinit var etInputText: EditText
    private lateinit var btnSampleNews: Button
    private lateinit var btnClearText: Button
    private lateinit var btnPaste: Button
    private lateinit var btnPickTxt: Button

    private lateinit var tvStatusMessage: TextView
    private lateinit var tvProgressPercentBn: TextView
    private lateinit var progressSynthesis: ProgressBar
    private lateinit var tvPieceCounterBn: TextView
    private lateinit var tvActivePiecePreview: TextView

    private lateinit var btnGenerate: Button
    private lateinit var btnPlayStop: Button
    private lateinit var btnSaveWav: Button
    private lateinit var btnQuickShare: Button

    // Studio Recent & History Tab Views
    private lateinit var btnViewAllHistory: Button
    private lateinit var tvStudioRecentEmpty: TextView
    private lateinit var containerStudioRecentItems: LinearLayout
    private lateinit var tvHistorySummaryCount: TextView
    private lateinit var btnClearAllHistory: Button
    private lateinit var layoutHistoryEmptyState: LinearLayout
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

    // Settings & Cloud Export Tab Views
    private lateinit var tvVolumeGainLabel: TextView
    private lateinit var seekVolumeGain: SeekBar
    private lateinit var btnResetApprovedVits: Button
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

    private val exportCustomWavLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
            if (uri != null) {
                exportWavToCustomUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyRepository = AudioHistoryRepository.getInstance(applicationContext)

        bindViews()
        setupWindowInsets()
        setupListeners()
        observeServiceState()
        observeRoomHistory()

        // Default sample Bengali text so user can test immediately after ElevenLabs download
        if (etInputText.text.isNullOrBlank()) {
            etInputText.setText(
                "স্বাগতম বাংলা ভয়েস স্টুডিওতে। আজকের আবহাওয়া চমৎকার! আপনি কি জানেন, ১২,৫০০ টাকার মধ্যে ১২.৫ শতাংশ ছাড় পাওয়া যাচ্ছে? চো'র ও র'ক্তের মতো শব্দ কখনোই মাঝপথে ভাঙবে না।"
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

        tvStatCharsWords = findViewById(R.id.tvStatCharsWords)
        tvStatChunks = findViewById(R.id.tvStatChunks)
        tvStatActiveVoice = findViewById(R.id.tvStatActiveVoice)

        rbVoiceEleven = findViewById(R.id.rbVoiceEleven)
        tvElevenStatusBadge = findViewById(R.id.tvElevenStatusBadge)
        rbVoiceKiki = findViewById(R.id.rbVoiceKiki)
        btnDownloadKiki = findViewById(R.id.btnDownloadKiki)
        tvKikiStatusBadge = findViewById(R.id.tvKikiStatusBadge)

        rgSpeed = findViewById(R.id.rgSpeed)
        rbSpeed075 = findViewById(R.id.rbSpeed075)
        rbSpeed100 = findViewById(R.id.rbSpeed100)
        rbSpeed125 = findViewById(R.id.rbSpeed125)
        rbSpeed150 = findViewById(R.id.rbSpeed150)

        etInputText = findViewById(R.id.etInputText)
        btnSampleNews = findViewById(R.id.btnSampleNews)
        btnClearText = findViewById(R.id.btnClearText)
        btnPaste = findViewById(R.id.btnPaste)
        btnPickTxt = findViewById(R.id.btnPickTxt)

        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        tvProgressPercentBn = findViewById(R.id.tvProgressPercentBn)
        progressSynthesis = findViewById(R.id.progressSynthesis)
        tvPieceCounterBn = findViewById(R.id.tvPieceCounterBn)
        tvActivePiecePreview = findViewById(R.id.tvActivePiecePreview)

        btnGenerate = findViewById(R.id.btnGenerate)
        btnPlayStop = findViewById(R.id.btnPlayStop)
        btnSaveWav = findViewById(R.id.btnSaveWav)
        btnQuickShare = findViewById(R.id.btnQuickShare)

        btnViewAllHistory = findViewById(R.id.btnViewAllHistory)
        tvStudioRecentEmpty = findViewById(R.id.tvStudioRecentEmpty)
        containerStudioRecentItems = findViewById(R.id.containerStudioRecentItems)
        tvHistorySummaryCount = findViewById(R.id.tvHistorySummaryCount)
        btnClearAllHistory = findViewById(R.id.btnClearAllHistory)
        layoutHistoryEmptyState = findViewById(R.id.layoutHistoryEmptyState)
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

        tvVolumeGainLabel = findViewById(R.id.tvVolumeGainLabel)
        seekVolumeGain = findViewById(R.id.seekVolumeGain)
        btnResetApprovedVits = findViewById(R.id.btnResetApprovedVits)
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

    private fun setupListeners() {
        // 4-Section Navigation Tabs
        btnNavStudio.setOnClickListener { switchMenuTab(0) }
        btnNavHistory.setOnClickListener { switchMenuTab(1) }
        btnNavModels.setOnClickListener { switchMenuTab(2) }
        btnNavSettings.setOnClickListener { switchMenuTab(3) }

        btnViewAllHistory.setOnClickListener { switchMenuTab(1) }

        btnClearAllHistory.setOnClickListener {
            lifecycleScope.launch {
                historyRepository.clearAll(applicationContext)
                TtsForegroundService.updateStatusExternal("সমস্ত অডিও ইতিহাস মুছে ফেলা হয়েছে।")
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

        // Voice Selector (ElevenLabs vs সংবাদ Kiki)
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
                    "সংবাদ (Kiki) কণ্ঠ ব্যবহারের আগে 'ডাউনলোড' বাটনে চাপুন।",
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
                "আজকের প্রধান সংবাদ। রাজধানী ঢাকাসহ সারাদেশে আগামীকাল আবহাওয়া স্বাভাবিক থাকবে! দেশের অর্থনৈতিক প্রবৃদ্ধি ৭.৫ শতাংশে পৌঁছেছে, এবং কৃষি খাতে ১২,৫০০ মেট্রিক টন অতিরিক্ত ফসল উৎপাদিত হয়েছে। আপনারা শুনছেন বাংলা ভয়েস স্টুডিও।"
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

        // Generate Button (Starts Foreground Service + Immediate Streaming)
        btnGenerate.setOnClickListener {
            handleGenerateClicked()
        }

        // Play / Stop Button
        btnPlayStop.setOnClickListener {
            handlePlayStopClicked()
        }

        // Save WAV Button (Saves to Downloads as BanglaTTS_<timestamp>.wav)
        btnSaveWav.setOnClickListener {
            saveWavToDownloads(shareAfterSave = false)
        }
        btnSettingsSaveDownloads.setOnClickListener {
            saveWavToDownloads(shareAfterSave = false)
        }

        // Quick Share / Social Media Share Button
        btnQuickShare.setOnClickListener {
            saveWavToDownloads(shareAfterSave = true)
        }

        // Unload RAM Model Button
        btnUnloadRamModel.setOnClickListener {
            TtsEngineHolder.unloadCurrentVoice()
            refreshRamStatusUi()
            TtsForegroundService.updateStatusExternal("মেমোরি (RAM) থেকে ভয়েস মডেল আনলোড করা হয়েছে।")
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
                tvVolumeGainLabel.text = "অডিও ভলিউম বুস্ট (Gain): $pctBn%"
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
            tvVolumeGainLabel.text = "অডিও ভলিউম বুস্ট (Gain): ১০০%"
            TtsForegroundService.updateStatusExternal(
                "অনুমোদিত VITS সেটিংস (০.৬৬৭ / ০.৮ / ১.০) পুনরায় সেট করা হয়েছে।"
            )
        }

        btnSettingsExportCustomLocation.setOnClickListener {
            val wavBytes = TtsForegroundService.getLastWavBytes()
            if (wavBytes == null) {
                showBengaliError("এক্সপোর্ট করার জন্য প্রথমে অডিও তৈরি করুন (Generate)।")
                return@setOnClickListener
            }
            val fileName = "BanglaTTS_${System.currentTimeMillis()}.wav"
            try {
                exportCustomWavLauncher.launch(fileName)
            } catch (e: Exception) {
                showBengaliError("ফাইল এক্সপোর্ট মেনু খোলা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
            }
        }

        btnSettingsShareTranscript.setOnClickListener {
            shareBanglaTranscriptText()
        }

        btnCloudSyncUpload.setOnClickListener {
            uploadWavToCloudWebhook()
        }
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
        val countBn = BengaliTextChunker.toBengaliDigits(items.size)
        btnNavHistory.text = "ইতিহাস ($countBn)"
        tvHistorySummaryCount.text = "মোট সংরক্ষিত অডিও: $countBn টি"
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

        val speedBn = BengaliTextChunker.toBengaliDigits(String.format("%.2f", entity.speed).trimEnd('0').trimEnd('.'))
        val voiceBadge = TextView(this).apply {
            text = "🎙️ ${entity.voiceModel} • ${speedBn}x"
            setBackgroundResource(R.drawable.bg_badge_teal)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary_dark))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
        }

        val durBn = BengaliTextChunker.toBengaliDigits(String.format("%.1f", entity.durationSeconds))
        val timeFormatted = formatTimestampBn(entity.timestamp)
        val timeView = TextView(this).apply {
            text = "$timeFormatted • $durBn সে."
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

        // Action Buttons Row: Play/Stop, Load into Studio, Save to Downloads, Delete
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

        val playBtn = Button(this).apply {
            text = if (isCurrentlyPlaying) "⏹️ থামান" else "▶️ বাজান (Play)"
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
            text = "📝 এডিটরে নিন"
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

        if (!compact) {
            val saveBtn = Button(this).apply {
                text = "💾 সেভ"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundResource(R.drawable.bg_btn_secondary)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 0.8f).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener {
                    saveHistoryItemToDownloads(entity)
                }
            }

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
                        TtsForegroundService.updateStatusExternal("ইতিহাস থেকে অডিওটি মুছে ফেলা হয়েছে।")
                    }
                }
            }

            actionRow.addView(saveBtn)
            actionRow.addView(deleteBtn)
        }

        card.addView(actionRow)
        return card
    }

    private fun playHistoryItem(entity: AudioHistoryEntity) {
        val file = File(entity.audioFilePath)
        if (!file.exists()) {
            showBengaliError("সংরক্ষিত অডিও ফাইলটি ডিভাইসে পাওয়া যায়নি।")
            return
        }
        val playIntent = Intent(this, TtsForegroundService::class.java).apply {
            action = TtsForegroundService.ACTION_PLAY_HISTORY_FILE
            putExtra(TtsForegroundService.EXTRA_HISTORY_ID, entity.id)
            putExtra(TtsForegroundService.EXTRA_HISTORY_FILE_PATH, entity.audioFilePath)
            putExtra(TtsForegroundService.EXTRA_HISTORY_TITLE, entity.textSnippet)
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
            "ইতিহাস থেকে টেক্সট ও ভয়েস মডেল (${entity.voiceModel}) স্টুডিওতে লোড করা হয়েছে।"
        )
    }

    private fun saveHistoryItemToDownloads(entity: AudioHistoryEntity) {
        val file = File(entity.audioFilePath)
        if (!file.exists()) {
            showBengaliError("সংরক্ষিত অডিও ফাইলটি খুঁজে পাওয়া যায়নি।")
            return
        }
        lifecycleScope.launch {
            val wavBytes = withContext(Dispatchers.IO) {
                try {
                    file.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
            if (wavBytes == null || wavBytes.isEmpty()) {
                showBengaliError("অডিও ফাইলটি পড়া যায়নি।")
                return@launch
            }
            writeWavBytesToPublicDownloads(wavBytes, entity.timestamp, shareAfterSave = false)
        }
    }

    private fun formatTimestampBn(timestampMs: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.US)
        val raw = sdf.format(Date(timestampMs))
        return BengaliTextChunker.toBengaliDigits(raw)
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
            SelectedVoiceMode.CUSTOM -> "কাস্টম VITS"
        }
        tvStatActiveVoice.text = activeDisplayName

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

    private fun refreshVoiceSelectorUi() {
        val elevenReady = VoiceModelSpec.ELEVENLABS.isVerifiedPresent(this)
        val kikiReady = VoiceModelSpec.KIKI_NEWS.isVerifiedPresent(this)

        if (elevenReady) {
            tvElevenStatusBadge.text = "ডাউনলোডকৃত ✓"
            tvElevenStatusBadge.setBackgroundResource(R.drawable.bg_badge_teal)
            tvElevenStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_primary_dark))
            tvModelCardElevenStatus.text = "প্রস্তুত (১০০%)"
            btnModelCardDownloadEleven.text = "যাচাইকৃত ✓ (পুনরায় পরীক্ষা করুন)"
        } else {
            tvElevenStatusBadge.text = "ডাউনলোড প্রয়োজন"
            tvElevenStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvElevenStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            tvModelCardElevenStatus.text = "ডাউনলোড বাকি"
            btnModelCardDownloadEleven.text = "ডাউনলোড করুন (ElevenLabs)"
        }

        if (kikiReady) {
            rbVoiceKiki.isEnabled = true
            btnDownloadKiki.visibility = View.GONE
            tvKikiStatusBadge.visibility = View.VISIBLE
            tvKikiStatusBadge.text = "ডাউনলোডকৃত ✓"
            tvModelCardKikiStatus.text = "প্রস্তুত (১০০%)"
            tvModelCardKikiStatus.setBackgroundResource(R.drawable.bg_badge_teal)
            tvModelCardKikiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_primary_dark))
            btnModelCardDownloadKiki.text = "যাচাইকৃত ✓ (সংবাদ Kiki)"
        } else {
            rbVoiceKiki.isEnabled = false
            btnDownloadKiki.visibility = View.VISIBLE
            tvKikiStatusBadge.visibility = View.GONE
            tvModelCardKikiStatus.text = "ঐচ্ছিক কণ্ঠ"
            tvModelCardKikiStatus.setBackgroundResource(R.drawable.bg_badge_amber)
            tvModelCardKikiStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnModelCardDownloadKiki.text = "ডাউনলোড (সংবাদ Kiki)"
            if (selectedVoiceMode == SelectedVoiceMode.KIKI) {
                selectVoiceMode(SelectedVoiceMode.ELEVENLABS)
            }
        }
    }

    private fun refreshRamStatusUi() {
        val loadedKey = TtsEngineHolder.getLoadedVoiceKey()
        if (loadedKey == null) {
            tvLoadedRamBadge.text = "RAM: খালি (Lazy)"
            tvEngineMemoryInfo.text =
                "বর্তমানে মেমোরিতে কোনো মডেল লোড নেই। প্রথমবার Generate চাপলে নির্বাচিত কণ্ঠ স্বয়ংক্রিয়ভাবে লোড হবে।"
        } else {
            val name = when (loadedKey) {
                VoiceModelSpec.ELEVENLABS.id -> "ElevenLabs"
                VoiceModelSpec.KIKI_NEWS.id -> "সংবাদ (Kiki)"
                else -> "কাস্টম মডেল"
            }
            tvLoadedRamBadge.text = "RAM: $name"
            tvEngineMemoryInfo.text =
                "বর্তমানে '$name' মডেলটি মেমোরিতে (RAM) সক্রিয় আছে যাতে দ্রুত পরবর্তী অডিও তৈরি করা যায়।"
        }
    }

    private fun triggerModelDownload(spec: VoiceModelSpec) {
        if (activeDownloadJob?.isActive == true) {
            TtsForegroundService.updateStatusExternal("একটি ডাউনলোড ইতিমধ্যে চলছে, অনুগ্রহ করে অপেক্ষা করুন…")
            return
        }

        layoutErrorBanner.visibility = View.GONE
        layoutDownloadBanner.visibility = View.VISIBLE
        progressDownload.progress = 0
        tvDownloadTitle.text = "${spec.displayName} ভয়েস মডেল ডাউনলোড হচ্ছে…"
        tvDownloadDetails.text = "সংযোগ স্থাপন করা হচ্ছে… (০%)"
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
                        tvProgressPercentBn.text = "${BengaliTextChunker.toBengaliDigits(pct)}%"
                    }
                }
            )

            btnDownloadKiki.isEnabled = true
            refreshVoiceSelectorUi()

            if (result.isSuccess) {
                layoutDownloadBanner.visibility = View.GONE
                val readyMsg = "${spec.displayName} মডেল প্রস্তুত — এখন বাংলা টেক্সট থেকে অডিও তৈরি করতে পারেন।"
                tvStatusMessage.text = readyMsg
                tvProgressPercentBn.text = "১০০%"
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
            showBengaliError("অনুগ্রহ করে প্রথমে বাংলা টেক্সট লিখুন, পেস্ট করুন বা ফাইল থেকে খুলুন।")
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
                        "ElevenLabs ভয়েস মডেলটি এখনো সম্পূর্ণ ডাউনলোড হয়নি। স্বয়ংক্রিয় ডাউনলোড শুরু হচ্ছে…",
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
                        "সংবাদ (Kiki) মডেলটি ডাউনলোড করা নেই। অনুগ্রহ করে 'ডাউনলোড' বাটনে চাপুন।",
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

        // Start foreground service ONLY when Generate is tapped
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
            // Play last generated audio buffer if present, or most recent Room history item
            if (!currentState.hasCompletedWav && TtsForegroundService.getLastWavBytes() == null) {
                val latestHistory = cachedHistoryItems.firstOrNull()
                if (latestHistory != null) {
                    playHistoryItem(latestHistory)
                    return
                }
                showBengaliError("চালানোর মতো কোনো অডিও নেই। প্রথমে 'Generate' বাটনে চাপুন।")
                return
            }
            val playIntent = Intent(this, TtsForegroundService::class.java).apply {
                action = TtsForegroundService.ACTION_PLAY_SAVED_BUFFER
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
                tvStatusMessage.text = state.statusBn
                val pctBn = BengaliTextChunker.toBengaliDigits(state.progressPercent)
                tvProgressPercentBn.text = "$pctBn%"
                progressSynthesis.progress = state.progressPercent

                val curBn = BengaliTextChunker.toBengaliDigits(state.currentPiece)
                val totBn = BengaliTextChunker.toBengaliDigits(state.totalPieces)
                if (state.totalPieces > 0) {
                    tvPieceCounterBn.text = "খণ্ড $curBn এর $totBn ($pctBn%)"
                } else {
                    tvPieceCounterBn.text = "খণ্ড ০ এর ০"
                }

                if (state.activePieceText.isNotBlank()) {
                    tvActivePiecePreview.visibility = View.VISIBLE
                    tvActivePiecePreview.text = "বর্তমান বাক্য: “${state.activePieceText}”"
                } else {
                    tvActivePiecePreview.visibility = View.GONE
                }

                if (state.isRunning) {
                    btnPlayStop.text = "⏹️ থামান (Stop)"
                    btnPlayStop.setBackgroundResource(R.drawable.bg_btn_danger)
                    btnPlayStop.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_on_accent))
                } else {
                    btnPlayStop.text = "▶️ চালান (Play)"
                    btnPlayStop.setBackgroundResource(R.drawable.bg_btn_secondary)
                    btnPlayStop.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                }

                if (state.errorBn != null) {
                    showBengaliError(state.errorBn)
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

        val charBn = BengaliTextChunker.toBengaliDigits(charCount)
        val wordBn = BengaliTextChunker.toBengaliDigits(wordCount)
        val chunkBn = BengaliTextChunker.toBengaliDigits(chunks.size)

        tvStatCharsWords.text = "$charBn অক্ষর • $wordBn শব্দ"
        tvStatChunks.text = "$chunkBn খণ্ড"
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
                    TtsForegroundService.updateStatusExternal("ক্লিপবোর্ড থেকে বাংলা টেক্সট পেস্ট করা হয়েছে।")
                } else {
                    showBengaliError("ক্লিপবোর্ডে কোনো টেক্সট পাওয়া যায়নি।")
                }
            } else {
                showBengaliError("ক্লিপবোর্ড খালি রয়েছে।")
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
                        showBengaliError("নির্বাচিত .txt ফাইলটি খালি।")
                    } else {
                        etInputText.setText(result)
                        etInputText.setSelection(result.length)
                        TtsForegroundService.updateStatusExternal(".txt ফাইল থেকে টেক্সট সফলভাবে লোড হয়েছে।")
                    }
                }
                is Pair<*, *> -> {
                    showBengaliError("ফাইল লোড ব্যর্থ হয়েছে: ${result.second}")
                }
            }
        }
    }

    /**
     * Saves the complete accumulated WAV audio to public Downloads as BanglaTTS_<timestamp>.wav
     * and optionally launches Android Share sheet for social media / cloud drive sharing.
     */
    private fun saveWavToDownloads(shareAfterSave: Boolean) {
        val wavBytes = TtsForegroundService.getLastWavBytes()
        if (wavBytes == null || wavBytes.isEmpty()) {
            val latestHistory = cachedHistoryItems.firstOrNull()
            if (latestHistory != null && File(latestHistory.audioFilePath).exists()) {
                saveHistoryItemToDownloads(latestHistory)
                return
            }
            showBengaliError("সংরক্ষণ করার মতো কোনো অডিও নেই। প্রথমে 'Generate' চাপুন।")
            return
        }

        writeWavBytesToPublicDownloads(wavBytes, System.currentTimeMillis(), shareAfterSave)
    }

    private fun writeWavBytesToPublicDownloads(
        wavBytes: ByteArray,
        timestamp: Long,
        shareAfterSave: Boolean
    ) {
        val fileName = "BanglaTTS_$timestamp.wav"

        lifecycleScope.launch {
            val savedResult = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = applicationContext.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("Downloads ফোল্ডারে ফাইল তৈরি করা যায়নি।")
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(wavBytes)
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
                            out.write(wavBytes)
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
                val msg = "সফলভাবে সেভ হয়েছে: $pathDisplay"
                TtsForegroundService.updateStatusExternal(msg)
                if (shareAfterSave) {
                    launchShareSheetForAudio(mediaUri, fileName)
                }
            } else {
                val err = savedResult.exceptionOrNull()?.localizedMessage ?: "অজানা ত্রুটি"
                showBengaliError("WAV ফাইল সেভ করতে ব্যর্থ হয়েছে: $err")
            }
        }
    }

    private fun launchShareSheetForAudio(mediaStoreUri: Uri?, fileName: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                if (mediaStoreUri != null) {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, mediaStoreUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
                putExtra(
                    Intent.EXTRA_TEXT,
                    "বাংলা ভয়েস স্টুডিও দিয়ে তৈরি অডিও ফাইল ($fileName) — মেকানিক্যাল স্ক্রিপ্ট:\n${etInputText.text}"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "অডিও শেয়ার করুন"))
        } catch (e: Exception) {
            showBengaliError("শেয়ার মেনু খোলা যায়নি: ${e.localizedMessage ?: "ত্রুটি"}")
        }
    }

    private fun exportWavToCustomUri(uri: Uri) {
        val wavBytes = TtsForegroundService.getLastWavBytes()
        if (wavBytes == null) {
            showBengaliError("এক্সপোর্ট করার মতো কোনো অডিও নেই।")
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(wavBytes)
                        out.flush()
                    } ?: throw Exception("ফাইলে লেখা যায়নি।")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (res.isSuccess) {
                TtsForegroundService.updateStatusExternal("নির্বাচিত ফোল্ডারে WAV অডিও সফলভাবে এক্সপোর্ট হয়েছে।")
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

    private fun uploadWavToCloudWebhook() {
        val url = etCloudWebhookUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            showBengaliError("অনুগ্রহ করে একটি বৈধ ক্লাউড এপিআই URL (https://...) লিখুন।")
            return
        }
        val wavBytes = TtsForegroundService.getLastWavBytes()
        if (wavBytes == null) {
            showBengaliError("ক্লাউডে আপলোড করার আগে অডিও তৈরি করুন (Generate)।")
            return
        }

        TtsForegroundService.updateStatusExternal("ক্লাউড সার্ভারে WAV অডিও আপলোড হচ্ছে…")
        lifecycleScope.launch {
            val uploadResult = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val body = wavBytes.toRequestBody("audio/wav".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .header("X-File-Name", "BanglaTTS_${System.currentTimeMillis()}.wav")
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
                val codeBn = BengaliTextChunker.toBengaliDigits(uploadResult.getOrThrow())
                TtsForegroundService.updateStatusExternal("ক্লাউড সিঙ্ক সফল হয়েছে (কোড $codeBn) ✓")
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
            TtsForegroundService.updateStatusExternal("কাস্টম VITS মডেল সক্রিয় করা হয়েছে। এখন Generate চাপুন।")
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
