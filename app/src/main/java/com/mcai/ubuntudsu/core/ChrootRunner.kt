package com.mcai.ubuntudsu.core

import android.content.Context
import java.io.File

object ChrootRunner {
    private const val SU = "/system/bin/su"
    private const val SH = "/system/bin/sh"

    // root chroot 启动规格（已验证可用）：
    // /system/bin/su 0 提权，toybox 挂载 /dev /proc /sys /tmp，伪造音频 FIFO，
    // toybox chroot 进 rootfs 起 bash。依赖 root（Magisk/su）。
    // 返回 (executable, args)，供 TerminalSession 直接 execve
    fun terminalLaunchSpec(ctx: Context, rootfs: File): Pair<String, Array<String>> {
        val script = File(rootfs.parentFile, ".linux-dsu-chroot.sh")
        script.writeText(rootCommand(rootfs, Env.audioPipeHost(ctx)), Charsets.UTF_8)
        script.setReadable(true, false)
        script.setExecutable(true, false)
        val scriptRef = script.path.replace("'", "'\\''")
        return SU to arrayOf(SU, "0", "-c", "sh '$scriptRef'")
    }

    fun terminalEnvironment(): Array<String> = arrayOf(
        "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
        "TERM=xterm-256color",
        "HOME=/root",
    )

    fun terminalSetupCommand(rootfs: File): ByteArray = ByteArray(0)

    private fun rootCommand(rootfs: File, audioHost: File): String {
        val hostAudio = audioHost.absolutePath.replace("'", "'\\''")
        return """
            export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
            set +x
            ROOTFS=${q(rootfs.absolutePath)}
            HOST_FILES=${q(File(rootfs.absolutePath, "..").absolutePath)}

            cleanup() {
              /system/bin/toybox umount "${'$'}ROOTFS/dev/pts" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/dev" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/proc" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/sys" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/tmp" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null
            }
            trap cleanup EXIT INT TERM HUP

            if [ ! -x "${'$'}ROOTFS/bin/bash" ] && [ ! -x "${'$'}ROOTFS/usr/bin/bash" ]; then
              echo "Linux-Dsu: rootfs 中没有可执行的 /bin/bash" >&2
              exit 127
            fi

            # 挂载 /dev /dev/pts /proc /sys /tmp（toybox）
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/root"
            /system/bin/toybox mount --bind /dev "${'$'}ROOTFS/dev" || { echo "挂载 /dev 失败" >&2; exit 125; }
            /system/bin/toybox mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || { echo "挂载 /dev/pts 失败" >&2; exit 125; }
            /system/bin/toybox mount -t proc proc "${'$'}ROOTFS/proc" || { echo "挂载 /proc 失败" >&2; exit 125; }
            /system/bin/toybox mount -t sysfs sysfs "${'$'}ROOTFS/sys" || { echo "挂载 /sys 失败" >&2; exit 125; }
            /system/bin/toybox mount -t tmpfs tmpfs "${'$'}ROOTFS/tmp" || { echo "挂载 /tmp 失败" >&2; exit 125; }
            # resolv.conf：bind host 的，缺失则写国内 DNS
            /system/bin/toybox rm -f "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox touch "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox mount --bind /etc/resolv.conf "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null || true
            if ! /system/bin/toybox test -s "${'$'}ROOTFS/etc/resolv.conf"; then
              /system/bin/toybox printf '%s\n' 'nameserver 114.114.114.114' 'nameserver 223.5.5.5' > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            # 音频 FIFO：host 侧 audioPipeHost 映射到 rootfs /run/android-audio.pcm
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/run"
            # 确保 host 侧路径是真正的 FIFO：先删再 mkfifo（AudioBridge 会读它）
            /system/bin/toybox rm -f "$hostAudio" 2>/dev/null
            /system/bin/toybox mkfifo "$hostAudio" 2>/dev/null || { /system/bin/toybox touch "$hostAudio" 2>/dev/null; }
            /system/bin/toybox rm -f "${'$'}ROOTFS/run/android-audio.pcm"
            /system/bin/toybox ln -s "$hostAudio" "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null || true

            # bashrc：pulse 音频 + VNC 桌面环境变量
            /system/bin/toybox printf '%s\n' \
              'if [ -r /usr/lib/os-release ]; then . /usr/lib/os-release; else . /etc/os-release 2>/dev/null; fi' \
              'export PS1="root@${'$'}{PRETTY_NAME:-Linux}:\\w# "' \
              'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
              'export XDG_RUNTIME_DIR=/run/user/0; mkdir -p /run/user/0 /run/pulse; export PULSE_SERVER=unix:/run/pulse/native; export PULSE_SINK=android_audio; export LANG=zh_CN.UTF-8; export LANGUAGE=zh_CN:en; export LC_ALL=' \
              > "${'$'}ROOTFS/root/.bashrc"
            /system/bin/toybox printf '%s\n' \
              '. /root/.bashrc' \
              > "${'$'}ROOTFS/root/.bash_profile"

            # 修复 Tigervnc 默认 session wrapper：它引用 host 的 /system/bin/sh，chroot 里不存在
            /system/bin/toybox printf '%s\n' \
              '#!/bin/sh' \
              'unset SESSION_MANAGER' \
              'unset DBUS_SESSION_BUS_ADDRESS' \
              'export HOME=/root' \
              'export XDG_VNC_SESSION=1' \
              'export DISPLAY=${'$'}{DISPLAY:-:1}' \
              'export PULSE_SERVER=unix:/run/pulse/native' \
              'export PULSE_SINK=android_audio' \
              'export LANG=zh_CN.UTF-8' \
              'export LANGUAGE=zh_CN:en' \
              'export LC_ALL=' \
              'if [ -x /root/.vnc/xstartup ]; then exec /root/.vnc/xstartup; else for c in startxfce4 startplasma-x11 gnome-shell; do command -v "${'$'}c" >/dev/null 2>&1 && { exec "${'$'}c"; }; done; echo "no desktop" >&2; exit 1; fi' \
              > "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true
            /system/bin/toybox chmod +x "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true

            echo -e "\033[?25l"
            cd "${'$'}ROOTFS" || exit 1
            /system/bin/toybox chroot "${'$'}ROOTFS" /usr/bin/env \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              HOME=/root \
              TERM="${'$'}TERM" \
              LANG=zh_CN.UTF-8 \
              LANGUAGE=zh_CN:en \
              PULSE_SERVER=unix:/run/pulse/native \
              PULSE_SINK=android_audio \
              /bin/bash --login
            status=${'$'}?
            exit "${'$'}status"
        """.trimIndent()
    }

    private fun q(value: String): String = "'${value.replace("'", "'\\''")}'"
}
