package `is`.xyz.mpv

import kotlinx.android.synthetic.main.player.*

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager.getDefaultSharedPreferences
import androidx.core.content.ContextCompat
import android.view.*
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import android.widget.Toast.makeText
import kotlinx.android.synthetic.main.player.view.*

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : Activity(), MPVLib.EventObserver, TouchGesturesObserver {
    private lateinit var fadeHandler: Handler
    private lateinit var fadeRunnable: FadeOutControlsRunnable

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false

    private var userIsOperatingSeekbar = false

    private var audioFocusRestore: () -> Unit = {}

    private lateinit var toast: Toast
    private lateinit var gestures: TouchGestures
    private lateinit var audioManager: AudioManager

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            player.timePos = progress
            updatePlaybackPos(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { type ->
        Log.v(TAG, "Audio focus changed: $type")
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    private var statsEnabled = false
    private var statsOnlyFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var gesturesEnabled = true

    private var backgroundPlayMode = ""

    private var shouldSavePosition = false

    private var autoRotationMode = ""

    private fun initListeners() {
        controls.cycleAudioBtn.setOnClickListener { cycleAudio() }
        controls.cycleAudioBtn.setOnLongClickListener { pickAudio(); true }

        controls.cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }

        controls.cycleSubsBtn.setOnClickListener { cycleSub() }
        controls.cycleSubsBtn.setOnLongClickListener { pickSub(); true }

        controls.prevBtn.setOnLongClickListener { pickPlaylist(); true }
        controls.nextBtn.setOnLongClickListener { pickPlaylist(); true }
    }

    @SuppressLint("ShowToast")
    private fun initMessageToast() {
        toast = makeText(applicationContext, "This totally shouldn't be seen", LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
    }

    private var playbackHasStarted = false
    private var onloadCommands = ArrayList<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        copyAssets()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.pref_background_play_title)
            val channel = NotificationChannel(
                    BackgroundPlaybackService.NOTIFICATION_CHANNEL_ID,
                    name, NotificationManager.IMPORTANCE_MIN)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        setContentView(R.layout.player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Init controls to be hidden and view fullscreen
        initControls()

        // Initialize listeners for the player view
        initListeners()

        // Initialize toast used for short messages
        initMessageToast()

        // set up a callback handler and a runnable for fading the controls out
        fadeHandler = Handler()
        fadeRunnable = FadeOutControlsRunnable(this, controls)

        syncSettings()

        // set initial screen orientation (depending on settings)
        updateOrientation(true)

        val filepath = parsePathFromIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finish()
            return
        }

        player.initialize(applicationContext.filesDir.path)
        player.addObserver(this)
        player.playFile(filepath)

        playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)

        if (this.gesturesEnabled) {
            val dm = resources.displayMetrics
            gestures = TouchGestures(dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), this)
            player.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        volumeControlStream = AudioManager.STREAM_MUSIC

        val res = audioManager.requestAudioFocus(
                audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
        if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Then who pressed the play button?
            Log.w(TAG, "Audio focus not granted, continuing anyway")
        }
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        audioManager.abandonAudioFocus(audioFocusChangeListener)

        // take the background service with us
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)

        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    private fun copyAssets() {
        val assetManager = applicationContext.assets
        val files = arrayOf("subfont.ttf", "cacert.pem")
        val configDir = applicationContext.filesDir.path
        for (filename in files) {
            var ins: InputStream? = null
            var out: OutputStream? = null
            try {
                ins = assetManager.open(filename, AssetManager.ACCESS_STREAMING)
                val outFile = File("$configDir/$filename")
                // Note that .available() officially returns an *estimated* number of bytes available
                // this is only true for generic streams, asset streams return the full file size
                if (outFile.length() == ins.available().toLong()) {
                    Log.w(TAG, "Skipping copy of asset file (exists same size): $filename")
                    continue
                }
                out = FileOutputStream(outFile)
                ins.copyTo(out)
                Log.w(TAG, "Copied asset file: $filename")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy asset file: $filename", e)
            } finally {
                ins?.close()
                out?.close()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            MPVLib.command(arrayOf("loadfile", filepath, "append"))
            showToast(getString(R.string.notice_file_appended))
            moveTaskToBack(true)
        } else {
            MPVLib.command(arrayOf("loadfile", filepath))
        }
    }

    private fun isPlayingAudioOnly(): Boolean {
        val fmt = MPVLib.getPropertyString("video-format")
        return fmt.isNullOrEmpty() || arrayOf("mjpeg", "png", "bmp").indexOf(fmt) != -1
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        if (player.paused ?: true)
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        val multiWindowMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false
        if (multiWindowMode) {
            Log.v(TAG, "Going into multi-window mode")
            super.onPause()
            return
        }

        val shouldBackground = shouldBackground()
        if (shouldBackground && !MPVLib.getPropertyString("video-format").isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null

        // player.onPause() modifies the playback state, so save stuff beforehand
        if (isFinishing)
            savePosition()

        player.onPause(!shouldBackground)
        super.onPause()

        activityIsForeground = false
        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            // start background playback service
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            applicationContext.startService(serviceIntent)
        }
    }

    private fun syncSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(this.applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        val statsMode = prefs.getString("stats_mode", "")
        if (statsMode.isNullOrBlank()) {
            this.statsEnabled = false
            this.statsLuaMode = 0
        } else if (statsMode == "native" || statsMode == "native_fps") {
            this.statsEnabled = true
            this.statsLuaMode = 0
            this.statsOnlyFPS = statsMode == "native_fps"
        } else if (statsMode.startsWith("lua")) {
            this.statsEnabled = false
            this.statsLuaMode = statsMode.removePrefix("lua").toInt()
        }
        this.gesturesEnabled = prefs.getBoolean("touch_gestures", true)
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)

        if (this.statsOnlyFPS)
            statsTextView.setTextColor((0xFF00FF00).toInt()) // green
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        // Init controls to be hidden and view fullscreen
        initControls()
        syncSettings()

        activityIsForeground = true
        // stop background playback if still running
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)

        player.onResume()
        refreshUi()

        super.onResume()
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    // UI

    private fun pauseForDialog(): StateRestoreCallback {
        val wasPlayerPaused = player.paused ?: true // default to not changing state
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (this.statsOnlyFPS) {
            statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
            return
        }

        val text = "File: ${player.filename}\n\n" +
                "Video: ${player.videoCodec} hwdec: ${player.hwdecActive}\n" +
                "\tA-V: ${player.avsync}\n" +
                "\tDropped: decoder: ${player.decoderFrameDropCount}, VO: ${player.frameDropCount}\n" +
                "\tFPS: ${player.containerFps} (specified) ${player.estimatedVfFps} (estimated)\n" +
                "\tResolution: ${player.videoW}x${player.videoH}\n\n" +
                "Audio: ${player.audioCodec}\n" +
                "\tSample rate: ${player.audioSampleRate} Hz\n" +
                "\tChannels: ${player.audioChannels}"
        statsTextView.text = text
    }

    private var useAudioUI = false

    private fun showControls() {
        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)

        // set the main controls as 75%, actual seek bar|buttons as 100%
        controls.alpha = 1f

        // Open, Sesame!
        controls.visibility = View.VISIBLE
        top_controls.visibility = View.VISIBLE

        if (this.statsEnabled) {
            updateStats()
            statsTextView.visibility = View.VISIBLE
        }

        window.decorView.systemUiVisibility = if (useAudioUI) View.SYSTEM_UI_FLAG_LAYOUT_STABLE else 0

        // add a new callback to hide the controls once again
        if (!useAudioUI)
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    fun initControls() {
        if (useAudioUI)
            return
        /* Init controls to be hidden */
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        controls.visibility = View.GONE
        top_controls.visibility = View.GONE
        statsTextView.visibility = View.GONE

        val flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE
        window.decorView.systemUiVisibility = flags
    }

    private fun hideControls() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    private fun toggleControls(): Boolean {
        if (useAudioUI)
            return true
        return if (controls.visibility == View.VISIBLE) {
            hideControls()
            false
        } else {
            showControls()
            true
        }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        showControls()
        // try built-in event handler first, forward all other events to libmpv
        if (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) {
            return true
        } else if (player.onKey(ev)) {
            return true
        }

        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                if (controls.visibility != View.VISIBLE)
                    showControls()
            }

        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private var mightWantToToggleControls = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            if (controls.visibility == View.VISIBLE)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls)
            toggleControls()
        return true
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandeled = 0

        when (event.unicodeChar.toChar()) {
            // overrides a default binding:
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandeled++
        }
        when (event.keyCode) {
            // no default binding:
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_HEADSETHOOK -> player.cyclePause()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()

            // overrides a default binding:
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.paused = true
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.paused = false

            else -> unhandeled++
        }

        return unhandeled < 2
    }

    override fun onBackPressed() {
        val pos = MPVLib.getPropertyInt("playlist-pos") ?: 0
        val count = MPVLib.getPropertyInt("playlist-count") ?: 1
        val notYetPlayed = count - pos - 1
        if (notYetPlayed <= 0) {
            super.onBackPressed()
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                super.finish()
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig?.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Move top controls so they don't overlap with System UI
        if (Utils.hasSoftwareKeys(this)) {
            val lp = RelativeLayout.LayoutParams(top_controls.layoutParams as RelativeLayout.LayoutParams)
            lp.marginEnd = if (isLandscape) Utils.convertDp(this, 48f) else 0
            top_controls.layoutParams = lp
        }

        // Change margin of controls (for the same reason, but unconditionally)
        run {
            val lp = RelativeLayout.LayoutParams(controls.layoutParams as RelativeLayout.LayoutParams)
            val pad = Utils.convertDp(this, if (isLandscape) 60f else 24f)
            lp.leftMargin = pad
            lp.rightMargin = pad
            controls.layoutParams = lp
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun playPause(view: View) = player.cyclePause()
    @Suppress("UNUSED_PARAMETER")
    fun playlistPrev(view: View) = MPVLib.command(arrayOf("playlist-prev"))
    @Suppress("UNUSED_PARAMETER")
    fun playlistNext(view: View) = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String) {
        toast.setText(msg)
        toast.show()
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        val filepath: String?
        if (intent.action == Intent.ACTION_VIEW) {
            filepath = intent.data?.let { resolveUri(it) }
        } else if (intent.action == Intent.ACTION_SEND) {
            filepath = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                val uri = Uri.parse(it.trim())
                if (uri.isHierarchical && !uri.isRelative) resolveUri(uri) else null
            }
        } else {
            filepath = intent.getStringExtra("filepath")
        }
        return filepath
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> openContentFd(data)
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh", "tcp", "udp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun openContentFd(uri: Uri): String? {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        val fd = try {
            val desc = resolver.openFileDescriptor(uri, "r")
            desc!!.detachFd()
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
            return null
        }
        // Find out real file path and see if we can read it directly
        try {
            val path = File("/proc/self/fd/${fd}").canonicalPath
            if (!path.startsWith("/proc") && File(path).canRead()) {
                Log.v(TAG, "Found real file path: ${path}")
                ParcelFileDescriptor.adoptFd(fd).close() // we don't need that anymore
                return path
            }
        } catch(e: Exception) { }
        // Else, pass the fd to mpv
        return "fdclose://${fd}"
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        // API reference: http://mx.j2inter.com/api (partially implemented)
        if (extras.getByte("decode_mode") == 2.toByte())
            onloadCommands.add(arrayOf("set", "file-local-options/hwdec", "no"))
        if (extras.containsKey("subs")) {
            val subList = extras.getParcelableArray("subs")?.mapNotNull { it as? Uri } ?: emptyList()
            val subsToEnable = extras.getParcelableArray("subs.enable")?.mapNotNull { it as? Uri } ?: emptyList()

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.filter { it.compareTo(suburi) == 0 }.any()) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        if (extras.getInt("position", 0) > 0) {
            val pos = extras.getInt("position", 0) / 1000f
            onloadCommands.add(arrayOf("set", "start", pos.toString()))
        }
    }

    // UI (Part 2)

    data class TrackData(val track_id: Int, val track_type: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> "Audio"
            "sub"   -> "Subs"
            "video" -> "Video"
            else    -> "Unknown"
        }

        if (track_id == -1) {
            showToast("$trackPrefix Off")
            return
        }

        val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
        showToast("$trackPrefix $trackName")
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }

    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() = selectTrack("sub", { player.sid }, { player.sid = it })

    private fun pickPlaylist() {
        val playlist = player.loadPlaylist() // load on demand
        val selectedIndex = MPVLib.getPropertyInt("playlist-pos") ?: 0
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(playlist.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val itemIndex = playlist[item].index

                MPVLib.setPropertyInt("playlist-pos", itemIndex)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun switchDecoder(view: View) {
        player.cycleHwdec()
        updateDecoderButton()
    }

    @Suppress("UNUSED_PARAMETER")
    fun cycleSpeed(view: View) {
        player.cycleSpeed()
        updateSpeedButton()
    }

    private fun pickSpeed() {
        val view = SpeedPickerDialog.buildView(layoutInflater, player.playbackSpeed ?: 1.0)

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setTitle(R.string.title_speed_dialog)
            setView(view)
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                player.playbackSpeed = SpeedPickerDialog.readResult(view)
                updateSpeedButton()
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    data class MenuItem(val textResource: Int, val handler: () -> Boolean)
    @Suppress("UNUSED_PARAMETER")
    fun openTopMenu(view: View) {
        val restoreState = pauseForDialog()

        /******/
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.string.open_external_audio) {
                    openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                        if (result == RESULT_OK)
                            MPVLib.command(arrayOf("audio-add", data!!.getStringExtra("path"), "cached"))
                        restoreState()
                    }; false
                },
                MenuItem(R.string.open_external_sub) {
                    openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                        if (result == RESULT_OK)
                            MPVLib.command(arrayOf("sub-add", data!!.getStringExtra("path"), "cached"))
                        restoreState()
                    }; false
                },
                MenuItem(R.string.playlist_append) {
                    openFilePickerFor(RCODE_LOAD_FILE, R.string.playlist_append) { result, data ->
                        if (result == RESULT_OK)
                            MPVLib.command(arrayOf("loadfile", data!!.getStringExtra("path"), "append"))
                        restoreState()
                    }; false
                },
                MenuItem(R.string.resume_bg_playback) {
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                }
        )
        if (autoRotationMode == "landscape" || autoRotationMode == "portrait")
            buttons.add(MenuItem(R.string.switch_orientation) { this.cycleOrientation(); true })
        /******/

        with (AlertDialog.Builder(this)) {
            setItems(buttons.map { getString(it.textResource) }.toTypedArray()) { dialog, item ->
                val ret = buttons[item].handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
            setOnCancelListener { restoreState() }
            create().show()
        }
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, titleRes: Int, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", getString(titleRes))
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path")
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        val paused = player.paused ?: return
        updatePlaybackStatus(paused)
        player.timePos?.let { updatePlaybackPos(it) }
        player.duration?.let { updatePlaybackDuration(it) }
        updateAudioUI()
        if (useAudioUI)
            updateAudioMetadata("", "")
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.playBtn, R.id.cycleAudioBtn, R.id.cycleSubsBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.w(TAG, "Audio UI: $useAudioUI")

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(controls_seekbar_group, R.id.prevBtn, controls_button_group, 0)
            Utils.viewGroupMove(controls_seekbar_group, R.id.nextBtn, controls_button_group, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(controls_button_group, audioButtons)

            // Show song title and more metadata
            controls_title_group.visibility = View.VISIBLE
            updateAudioMetadata("", "")

            showControls()
        } else {
            Utils.viewGroupMove(controls_button_group, R.id.prevBtn, controls_seekbar_group, 0)
            Utils.viewGroupMove(controls_button_group, R.id.nextBtn, controls_seekbar_group, -1)

            Utils.viewGroupReorder(controls_button_group, videoButtons)

            controls_title_group.visibility = View.GONE

            hideControls()
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private var cachedAudioMeta = Utils.AudioMetadata()

    private fun updateAudioMetadata(property: String, value: String) {
        if (!useAudioUI)
            return
        if (property.isEmpty()) {
            cachedAudioMeta.readAll()
        } else {
            if (!cachedAudioMeta.update(property, value))
                return
        }

        titleTextView.text = cachedAudioMeta.formatTitle()
        minorTitleTextView.text = cachedAudioMeta.formatArtistAlbum()
    }

    fun updatePlaybackPos(position: Int) {
        playbackPositionTxt.text = Utils.prettyTime(position)
        if (!userIsOperatingSeekbar)
            playbackSeekbar.progress = position
        updateDecoderButton()
        updateSpeedButton()
    }

    private fun updatePlaybackDuration(duration: Int) {
        playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            playbackSeekbar.max = duration
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        playBtn.setImageResource(r)
    }

    private fun updateDecoderButton() {
        cycleDecoderBtn.text = if (player.hwdecActive!!) "HW" else "SW"
    }

    private fun updateSpeedButton() {
        cycleSpeedBtn.text = getString(R.string.ui_speed, player.playbackSpeed)
    }

    private fun updatePlaylistButtons() {
        val plCount = MPVLib.getPropertyInt("playlist-count") ?: 1
        val plPos = MPVLib.getPropertyInt("playlist-pos") ?: 0

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            prevBtn.visibility = View.GONE
            nextBtn.visibility = View.GONE
            return
        }
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(applicationContext, R.color.tint_disabled)
        val w = ContextCompat.getColor(applicationContext, R.color.tint_normal)
        prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateOrientation(initial: Boolean = false) {
        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        var ratio = (player.videoW ?: 0) / (player.videoH ?: 1).toFloat()
        if (ratio != 0f) {
            if ((player.videoRotation ?: 0) % 180 == 90)
                ratio = 1f / ratio
        }
        Log.v(TAG, "auto rotation: aspect ratio = ${ratio}")

        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // mpv events

    private fun eventPropertyUi(property: String) {
        when (property) {
            "track-list" -> player.loadTracks()
            "video-params" -> updateOrientation()
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
            "video-format" -> updateAudioUI()
        }
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        when (property) {
            "pause" -> updatePlaybackStatus(value)
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        when (property) {
            "time-pos" -> updatePlaybackPos(value.toInt())
            "duration" -> updatePlaybackDuration(value.toInt())
        }
    }

    private fun eventPropertyUi(property: String, value: String) {
        updateAudioMetadata(property, value)
    }

    private fun eventUi(eventId: Int) {
        when (eventId) {
            MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> updatePlaybackStatus(player.paused!!)
        }
    }

    override fun eventProperty(property: String) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun event(eventId: Int) {
        // exit properly even when in background
        if (playbackHasStarted && eventId == MPVLib.mpvEventId.MPV_EVENT_IDLE)
            finish()
        else if(eventId == MPVLib.mpvEventId.MPV_EVENT_SHUTDOWN)
            finish()

        if (!activityIsForeground) return

        // deliberately not on the UI thread
        if (eventId == MPVLib.mpvEventId.MPV_EVENT_START_FILE) {
            for (c in onloadCommands)
                MPVLib.command(c)
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
                MPVLib.command(arrayOf("script-binding", "stats/${this.statsLuaMode}"))
            }

            playbackHasStarted = true
        }

        runOnUiThread { eventUi(eventId) }
    }

    // Gesture handler

    private var initialSeek = 0
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        when (p) {
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = player.timePos ?: -1
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when timePos is not available
                val duration = player.duration ?: 0
                if (duration == 0 || initialSeek < 0)
                    return
                val newPos = Math.min(Math.max(0, initialSeek + diff.toInt()), duration)
                val newDiff = newPos - initialSeek
                // seek faster than assigning to timePos but less precise
                MPVLib.command(arrayOf("seek", newPos.toString(), "absolute", "keyframes"))
                updatePlaybackPos(newPos)

                val diffText = (if (newDiff >= 0) "+" else "-") + Utils.prettyTime(Math.abs(newDiff))
                gestureTextView.text = "${Utils.prettyTime(newPos)}\n[$diffText]"
            }
            PropertyChange.Volume -> {
                val newVolume = Math.min(Math.max(0, initialVolume + (diff * maxVolume).toInt()), maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = Math.min(Math.max(0f, initialBright + diff), 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = getString(R.string.ui_brightness, Math.round(newBright * 100))
            }
            PropertyChange.Finalize -> gestureTextView.visibility = View.GONE
        }
    }

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 2000L
        // size (px) of the thumbnail displayed with background play notification
        private const val THUMB_SIZE = 192
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
    }
}

internal class FadeOutControlsRunnable(private val activity: MPVActivity, private val controls: View) : Runnable {

    override fun run() {
        controls.animate().alpha(0f).setDuration(500).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activity.initControls()
            }
        })
    }
}
