package com.mcai.ubuntudsu

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.PopupWindow
import android.content.Intent
import android.widget.EditText
import android.text.InputType
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.ChrootRunner
import com.mcai.ubuntudsu.core.AudioBridge
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.R
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {
    companion object {
        const val EXTRA_DESKTOP = "extra_desktop"
    }

    private data class DesktopOption(
        val id: String,
        val name: String,
        val packages: String,
        val packagesDebian: String? = null,
        val commandCheck: String,
        val startup: String,
    )

    private val desktopOptions = listOf(
        DesktopOption(id = "xfce", name = "XFCE4", packages = "xfce4 xfce4-goodies xfce4-terminal", commandCheck = "startxfce4", startup = "exec startxfce4"),
        DesktopOption(id = "kde", name = "KDE Plasma", packages = "kde-full konsole plasma-session-x11", packagesDebian = "kde-full konsole plasma-workspace plasma-desktop", commandCheck = "startplasma-x11", startup = "exec dbus-launch startplasma-x11"),
    )
    private var session: TerminalSession? = null
    private lateinit var terminalView: TerminalView
    private var audioBridge: AudioBridge? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TerminalSessionStore.setTarget(this)
        title = "Linux - Dsu Terminal"
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        if (!Env.ubuntuInstalled(this)) {
            Toast.makeText(this, "Install the Ubuntu rootfs first, then return to the Linux tab on the home page.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        terminalView = TerminalView(this, null)
        terminalView.setTextSize(Ui.dp(12, resources.displayMetrics.density))
        terminalView.setTerminalViewClient(this)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        val terminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        terminalContainer.addView(
            terminalView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        terminalContainer.addView(createShortcutBar())
        frame.addView(terminalContainer)
        setContentView(frame)

        terminalView.post {
            startSession()
        }
        // 从 Linux 页"Desktop Environment"入口进入时：Done装桌面则直接弹启动菜单，未装才弹Install菜单
        if (intent.getBooleanExtra(EXTRA_DESKTOP, false)) {
            terminalView.postDelayed({
                Thread {
                    val installed = runCatching {
                        val rootfsDir = Env.rootfs(this)
                        val candidates = listOf(
                            File(rootfsDir, "usr/bin/startplasma-x11"),
                            File(rootfsDir, "usr/bin/startxfce4"),
                            File(rootfsDir, "usr/bin/xsession"),
                        )
                        candidates.any { it.isFile }
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (installed) startVnc() else installVncDesktop()
                    }
                }.start()
            }, 600)
        }
    }

    private fun createShortcutBar(): View {
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 拟态深色玻璃工具条：深海军蓝渐变 + 顶部高光边（与终端深色场景协调）
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.rgb(22, 31, 53), Color.rgb(14, 21, 38)),
                    ).apply { cornerRadius = 0f },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((1 * density).toInt(), Color.argb(56, 168, 214, 255))
                    },
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val keys = listOf(
            listOf("Esc" to byteArrayOf(27), "Tab" to byteArrayOf(9), "PgUp" to byteArrayOf(27, 91, 53, 126), "Home" to byteArrayOf(27, 91, 72), "↑" to byteArrayOf(27, 91, 65), "End" to byteArrayOf(27, 91, 70), "Ctrl" to null),
            listOf("Alt" to null, "PgDn" to byteArrayOf(27, 91, 54, 126), "←" to byteArrayOf(27, 91, 68), "↓" to byteArrayOf(27, 91, 66), "→" to byteArrayOf(27, 91, 67), "Enter" to byteArrayOf(13)),
        )
        var altActive = false
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        keys.forEach { keyRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            keyRow.forEach { (label, bytes) ->
                val key = TextView(this).apply {
                    text = label
                    textSize = 8f
                    setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    isSingleLine = true
                    setHorizontallyScrolling(false)
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                    background = Ui.lightGlassButton(this@TerminalActivity, 8f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = (2 * density).toInt()
                    }
                    setOnClickListener {
                        if (label == "Ctrl") {
                            ctrlKeyActive = !ctrlKeyActive
                            background = Ui.lightGlassButton(this@TerminalActivity, 8f, if (ctrlKeyActive) Color.rgb(80, 220, 140) else null)
                        } else if (label == "Alt") {
                            altActive = !altActive
                            background = Ui.lightGlassButton(this@TerminalActivity, 8f, if (altActive) Color.rgb(80, 220, 140) else null)
                        } else {
                            val output = if (ctrlKeyActive && bytes != null && bytes.size == 1) {
                                byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
                            } else if (altActive && bytes != null) {
                                byteArrayOf(27) + bytes
                            } else bytes
                            output?.let { sendShortcut(it) }
                            if (ctrlKeyActive) {
                                ctrlKeyActive = false
                                background = Ui.lightGlassButton(this@TerminalActivity, 8f)
                            }
                            if (altActive) altActive = false
                        }
                        terminalView.requestFocus()
                    }
                    Ui.pressAnimation(this)
                }
                row.addView(key)
            }
            rows.addView(row)
        }
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setOnClickListener { showTerminalMenu(this) }
        }
        val vncInstall = ImageView(this).apply {
            setImageResource(R.drawable.ic_desktop_install)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setClickable(true)
            isFocusable = true
            background = null
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                marginStart = (2 * density).toInt()
            }
            setOnClickListener { installVncDesktop() }
        }
        val vncOpen = ImageView(this).apply {
            setImageResource(R.drawable.ic_desktop_start)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setClickable(true)
            isFocusable = true
            background = null
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                marginStart = (2 * density).toInt()
            }
            setOnClickListener { startVnc() }
        }
        bar.addView(rows)
        bar.addView(vncInstall)
        bar.addView(vncOpen)
        bar.addView(menu, LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        return bar
    }

    private fun installVncDesktop() {
        Ui.showGlassChoiceDialog(
            this,
            "Install Desktop Environment",
            "Choose a desktop to install. VNC can be started after installation.",
            desktopOptions.map { it.name },
            onPick = { which -> installVncDesktop(desktopOptions[which]) },
        )
    }

    private fun installVncDesktop(option: DesktopOption) {
                   val script = "export DEBIAN_FRONTEND=noninteractive; export LANG=C.UTF-8; export LC_ALL=C.UTF-8; . /etc/os-release 2>/dev/null; result=0; if [ \"${'$'}ID\" = ubuntu ]; then DESKTOP_EXTRA='language-pack-en-base'; PKGS='${option.packages}'; elif [ \"${'$'}ID\" = debian ]; then DESKTOP_EXTRA=''; PKGS='${option.packagesDebian ?: option.packages}'; else echo \"[Error] Unsupported system: ${'$'}ID\"; result=1; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[1/8] Repair dpkg status'; dpkg --configure -a || true; echo '[2/8] Update package index'; apt-get update || result=1; echo '[3/8] Repair dpkg dependencies'; apt-get install -y --fix-broken || result=1; echo '[3b/8] Install desktop, VNC, and audio components'; apt-get install -y ${'$'}PKGS dbus-x11 dbus-user-session locales ${'$'}DESKTOP_EXTRA fonts-noto-cjk fonts-wqy-microhei tigervnc-standalone-server tigervnc-common pulseaudio pulseaudio-utils || result=1; echo '[4/8] Finish dpkg configuration'; dpkg --configure -a || result=1; echo '[5/8] 生成Chinese locale'; sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen || result=1; grep -q '^en_US.UTF-8 UTF-8' /etc/locale.gen || printf '%s\\n' 'en_US.UTF-8 UTF-8' >> /etc/locale.gen; locale-gen en_US.UTF-8 || result=1; echo '[5b/8] 固化全Chinese环境'; printf '%s\\n' 'LANG=en_US.UTF-8' 'LANGUAGE=en_US:en' 'LC_ALL=' > /etc/environment; update-locale LANG=en_US.UTF-8 || true; echo '[5c/8] 设 KDE 默认语言为Chinese'; printf '%s\\n' '[KDE]' 'Language=en_US' > /root/.config/kdeglobals; sed -i 's/^LANG=.*/LANG=en_US.UTF-8/' /root/.bashrc 2>/dev/null || true; grep -q '^export LANG=en_US.UTF-8' /root/.bashrc || printf '%s\\n' 'export LANG=en_US.UTF-8' 'export LANGUAGE=en_US:en' 'export LC_ALL=' >> /root/.bashrc; echo '[7/8] Configure VNC startup script'; mkdir -p /root/.vnc /root/.config/tigervnc || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export LANG=en_US.UTF-8' 'export LANGUAGE=en_US:en' 'export LC_ALL=' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.vnc/xstartup || result=1; cp -f /root/.vnc/xstartup /root/.config/tigervnc/ 2>/dev/null || true; chmod +x /root/.vnc/xstartup || result=1; echo '[8/8] Configure passwordless VNC'; mkdir -p /root/.config/tigervnc; printf 'securitytypes=none\\n' > /root/.config/tigervnc/config 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.vnc/config 2>/dev/null || true; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[Success] ${option.name} 桌面、VNC 和Audio支持Install完成'; else echo '[Failed] ${option.name} 桌面、VNC 或AudioInstall'; fi"
         sendVisibleCommand(script)
        Toast.makeText(this, "Sent ${option.name}  and language environment installation script", Toast.LENGTH_SHORT).show()
        focusTerminalAndShowKeyboard()
    }

    private fun startVnc() {
        Ui.showGlassChoiceDialog(
            this,
            "Start VNC Desktop",
            "Select Desktop Environment",
            desktopOptions.map { it.name },
            onPick = { which -> chooseVncResolution(desktopOptions[which]) },
        )
    }

    private fun chooseVncResolution(option: DesktopOption) {
        Ui.showGlassChoiceDialog(
            this,
            "VNC Resolution",
            option.name,
            listOf("Portrait: 720x1584", "Landscape: 1584x720"),
            onPick = { which ->
                if (which == 0) startVnc(720, 1584, "portrait", option)
                else startVnc(1584, 720, "landscape", option)
            },
        )
    }

    private fun startVnc(width: Int, height: Int, orientation: String, option: DesktopOption) {
        terminalView.postDelayed({
         audioBridge?.stop()
          audioBridge = AudioBridge(Env.audioFifo(this)).also { it.start() }
                   val startupCommand = "result=0; audio=0; command -v ${option.commandCheck} >/dev/null 2>&1 || { echo \"[Warning] Desktop startup command not found ${option.commandCheck}，will still try to start VNC\"; }; command -v vncserver >/dev/null 2>&1 || { echo '[Error] vncserver not found'; result=1; }; if [ \"${'$'}result\" -eq 0 ]; then export XDG_RUNTIME_DIR=/run/user/0; export VNCUSERCONFIGDIR=/root/.vnc; export PULSE_SERVER=unix:/run/pulse/native; mkdir -p \"${'$'}XDG_RUNTIME_DIR\" /run/pulse; echo '[Audio] Check Android PCM FIFO'; if [ ! -p /run/android-audio.pcm ]; then echo '[AudioError] /run/android-audio.pcm is not a FIFO'; audio=1; fi; echo '[Audio] Start PulseAudio user mode'; command -v pulseaudio >/dev/null 2>&1 || { echo '[AudioError] pulseaudio not found'; audio=1; }; if [ \"${'$'}audio\" -eq 0 ]; then pulseaudio --check >/dev/null 2>&1 || pulseaudio --daemonize=true --exit-idle-time=-1 --load='module-native-protocol-unix socket=/run/pulse/native auth-anonymous=1' 2>&1 || { echo '[AudioError] PulseAudio 启动Failed'; audio=1; }; echo '[Audio] Wait for PulseAudio socket'; ready=0; for attempt in 1 2 3 4 5; do pactl --server=unix:/run/pulse/native info >/dev/null 2>&1 && ready=1 && break; sleep 0.2; done; if [ \"${'$'}ready\" -eq 0 ]; then echo '[AudioError] pactl cannot connect to PulseAudio'; audio=1; else echo '[Audio] Create android_audio sink'; pactl --server=unix:/run/pulse/native list short sinks; pactl --server=unix:/run/pulse/native load-module module-pipe-sink sink_name=android_audio format=s16le rate=44100 channels=2 file=/run/android-audio.pcm 2>&1 || true; pactl --server=unix:/run/pulse/native set-default-sink android_audio 2>&1 || true; echo '[Audio] Current sink'; pactl --server=unix:/run/pulse/native list short sinks; fi; fi; echo '[VNC] Clean old :1 display'; vncserver -kill :1 2>/dev/null || true; rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /root/.vnc/*:1.log /root/.vnc/*:1.pid; mkdir -p /root/.config/tigervnc /root/.vnc || result=1; cp -f /root/.vnc/xstartup /root/.config/tigervnc/ 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.config/tigervnc/config 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.vnc/config 2>/dev/null || true; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export PULSE_SERVER=unix:/run/pulse/native' 'export PULSE_SINK=android_audio' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.config/tigervnc/xstartup || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export PULSE_SERVER=unix:/run/pulse/native' 'export PULSE_SINK=android_audio' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.vnc/xstartup || result=1; chmod +x /root/.vnc/xstartup || result=1; echo '[VNC] Start display :1'; nohup vncserver :1 -SecurityTypes none -geometry ${width}x${height} -depth 24 -xstartup /root/.vnc/xstartup </dev/null >/tmp/vnc-start.log 2>&1 & echo \"[VNC] started in background, log /tmp/vnc-start.log\"; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[Success] ${option.name} Desktop started'; [ \"${'$'}audio\" -eq 0 ] && echo '[Success] Audio桥接Done启用' || echo '[Warning] Desktop started，但 PulseAudio Audio桥接Failed'; else echo '[Failed] ${option.name} Desktop startup'; fi"
            sendHiddenCommand(startupCommand)
            Toast.makeText(this, "Starting ${option.name} VNC", Toast.LENGTH_SHORT).show()
            return@postDelayed

         }, 200)
        Thread {
            var ready = false
            Thread.sleep(1500)
            for (attempt in 0 until 30) {
                if (runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", 5901), 500)
                    }
                    true
                }.getOrDefault(false)) {
                    ready = true
                    break
                }
                if (attempt < 29) Thread.sleep(500)
            }
            runOnUiThread {
                 if (!ready) {
                    Toast.makeText(this, "VNC service is not listening on port 5901. Check terminal errors.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                 }
                 val profile = com.gaurav.avnc.model.ServerProfile(
                    name = "Ubuntu DSU",
                    host = "127.0.0.1",
                     port = 5901,
                     password = "",
                     securityType = 0,
                     useRawEncoding = false,
                    gestureStyle = "touchpad",
                     resizeRemoteDesktop = false,
                     screenOrientation = orientation,
                )
                com.gaurav.avnc.ui.vnc.startVncActivity(this, profile)
            }
        }.start()
    }

    private fun sendHiddenCommand(command: String) {
        val disableEcho = "stty -echo 2>/dev/null\n"
        session?.write(disableEcho.toByteArray(), 0, disableEcho.toByteArray().size)
        terminalView.postDelayed({
            val clearPreviousLine = "\u001b[2J\u001b[H"
            val commandBytes = (clearPreviousLine + command + " ; stty echo 2>/dev/null\n").toByteArray()
            session?.write(commandBytes, 0, commandBytes.size)
        }, 150)
    }

    private fun sendVisibleCommand(command: String) {
        val commandBytes = (command + "\n").toByteArray()
        session?.write(commandBytes, 0, commandBytes.size)
    }

    private var ctrlKeyActive = false

    private fun sendShortcut(bytes: ByteArray) {
        session?.takeIf { it.isRunning }?.write(bytes, 0, bytes.size)
    }

    private fun showTerminalMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            // 拟态深色玻璃菜单：渐变底 + 霓虹高光边
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.rgb(24, 33, 55), Color.rgb(16, 23, 41)),
                    ).apply { cornerRadius = (14 * density) },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = (14 * density)
                        setStroke((1 * density).toInt(), Color.argb(60, 111, 168, 255))
                    },
                ),
            )
            // 圆角 outline：PopupWindow 投影随 content view outline 走，否则是方形影子
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14 * density)
                }
            }
        }
        fun item(label: String, action: () -> Unit) {
            menu.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (12 * density).toInt(), (42 * density).toInt(), (12 * density).toInt())
                setOnClickListener { action() }
            })
        }
        var popup: PopupWindow? = null
        item("Keep-awake lock") { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); popup?.dismiss() }
        item("End") {
            session?.write("exit\n".toByteArray(), 0, 5)
            terminalView.postDelayed({ session?.finishIfRunning() }, 3000)
            popup?.dismiss()
        }
        item("Reset") { session?.reset(); terminalView.onScreenUpdated(); popup?.dismiss() }
        item("Paste") { onPasteTextFromClipboard(session); popup?.dismiss() }
        item("Run in Background") {
            popup?.dismiss()
            finish()
        }
        popup = PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = (8 * density)
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, 0, anchor.height + Ui.dp(8, density))
        }
    }

    private fun focusTerminalAndShowKeyboard() {
        terminalView.requestFocus()
        terminalView.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun startSession() {
        val existing = TerminalSessionStore.takeRunning()
        if (existing != null) {
            session = existing
            existing.updateTerminalSessionClient(TerminalSessionStore.client)
        } else {
            val rootfs = Env.rootfs(this)
            val (executable, args) = ChrootRunner.terminalLaunchSpec(this, rootfs)
            session = TerminalSession(executable, "/", args, ChrootRunner.terminalEnvironment(), 2000, TerminalSessionStore.client)
            TerminalSessionStore.session = session
        }
        terminalView.attachSession(session)
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        audioBridge?.stop()
        TerminalSessionStore.setTarget(null)
        super.onDestroy()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (TerminalSessionStore.session === finishedSession) {
            TerminalSessionStore.session = null
        }
        val code = finishedSession.exitStatus
        runOnUiThread {
            Toast.makeText(this, "Process ended (code $code)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int =
        com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {}

    override fun logWarn(tag: String, message: String) {}

    override fun logInfo(tag: String, message: String) {}

    override fun logDebug(tag: String, message: String) {}

    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}

    override fun logStackTrace(tag: String, e: Exception) {}

    override fun onSingleTapUp(e: MotionEvent?) {
        focusTerminalAndShowKeyboard()
    }

    override fun onScale(scale: Float): Float = scale

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = ctrlKeyActive

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        terminalView.onScreenUpdated()
    }
}
