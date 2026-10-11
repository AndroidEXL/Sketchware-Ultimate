package com.example.ubuntuterminal

import pro.sketchware.R

import android.content.Context
import com.example.ubuntuterminal.setup.Installer
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns ALL live PRoot + Ubuntu sessions so they survive activity
 * re-creation and keep running while the app is in the background
 * (kept alive by [TerminalService]).
 *
 * Every session is wrapped in an [Entry] that carries a display title
 * ("Session 1", "Session 2", …). Exactly one entry is [active] – the one
 * currently attached to the TerminalView. All other sessions keep running
 * untouched in the background.
 */
object TerminalManager {

    class Entry(val session: TerminalSession, val title: String)

    private val counter = AtomicInteger(0)

    private val _entries = mutableListOf<Entry>()

    /** Live sessions, oldest first. */
    val entries: List<Entry> get() = _entries.toList()

    /** The entry currently shown in the TerminalView (null when none). */
    var active: Entry? = null
        private set

    /** The session keystrokes should go to (null = none usable). */
    val activeSession: TerminalSession?
        get() = active?.session?.takeIf { it.isRunning || it.emulator == null }

    private var autostartLockCleared = false

    @Synchronized
    fun createSession(context: Context, client: TerminalSessionClient): Entry {
        if (!autostartLockCleared) {
            autostartLockCleared = true
            // fresh app launch: allow the autostart list to run in the first tab
            runCatching {
                File(Installer.rootfsDir(context.applicationContext), "tmp/.dapi-autostart.lock").delete()
            }
        }
        ensureHistoryConfig(context.applicationContext)
        ensureApiScript(context.applicationContext)
        ensureTermResetScript(context.applicationContext)
        ensureCmdNotifyScript(context.applicationContext)
        ensureUsbLibs(context.applicationContext)
        val n = counter.incrementAndGet()
        val entry = Entry(
            session = buildSession(context.applicationContext, client),
            title = context.getString(R.string.ut_session_name_fmt, n)
        )
        _entries += entry
        // apply the selected color theme to the fresh session
        runCatching {
            val theme = TerminalTheme.fromId(
                context.getSharedPreferences("ut_settings", Context.MODE_PRIVATE)
                    .getString("theme", "dark") ?: "dark"
            )
            theme.applyTo(entry.session)
        }
        return entry
    }

    /**
     * Installs the `dapi` command inside the container - a tiny bash tool that
     * talks to [AndroidApiBridge] over 127.0.0.1:8037 using only /dev/tcp, so
     * it needs no extra packages inside Ubuntu:
     *
     *   dapi vibrate [ms] | notify TITLE MSG | notify-done DURms RC CMD |
     *   toast TEXT | battery | usb |
     *   dapi usbp VID:PID | torch on|off | info
     */
    private fun ensureApiScript(app: Context) {
        runCatching {
            val script = File(Installer.rootfsDir(app), "usr/local/bin/dapi")
            script.parentFile?.mkdirs()
            val t = "\t"
            script.writeText(
                "#!/bin/bash\n" +
                "# dapi - control the Android device from inside Ubuntu (Ubuntu Terminal app)\n" +
                "PORT=8037\n" +
                "tab=${'$'}(printf '\\t')\n" +
                "send() {\n" +
                "    local req=\"${'$'}1\"\n" +
                "    if ! exec 3<>/dev/tcp/127.0.0.1/\"${'$'}PORT\"; then\n" +
                "        echo 'dapi: cannot reach the Android bridge (open the Ubuntu Terminal app first)' >&2\n" +
                "        return 1\n" +
                "    fi\n" +
                "    printf '%s\\n' \"${'$'}req\" >&3\n" +
                "    cat <&3\n" +
                "    exec 3<&- 3>&-\n" +
                "}\n" +
                "cmd=\"${'$'}1\"; shift\n" +
                "case \"${'$'}cmd\" in\n" +
                "    vibrate) send \"vibrate$t${'$'}{1:-300}\" ;;\n" +
                "    notify)   send \"notify$t${'$'}1$t${'$'}2\" ;;\n" +
                "    notify-done) send \"notify-done$t${'$'}1$t${'$'}2$t${'$'}3\" ;;\n" +
                "    toast)    send \"toast$t${'$'}*\" ;;\n" +
                "    ping)     send \"ping\" ;;\n" +
                "    battery|usb|info) send \"${'$'}cmd\" ;;\n" +
                "    usbp)     send \"usbp$t${'$'}1\" ;;\n" +
                "    torch)    send \"torch$t${'$'}{1:-on}\" ;;\n" +
                "    clip)    if [ ${'$'}# -gt 0 ]; then send \"clip$t${'$'}*\"; else send \"clip\"; fi ;;\n" +
                "    usbser)   q=\"usbser\"; for a in \"${'$'}@\"; do q=\"${'$'}q${'$'}tab${'$'}a\"; done; send \"${'$'}q\" ;;\n" +
                "    usbsh)    if ! exec 3<>/dev/tcp/127.0.0.1/8039; then\n" +
                "                  echo 'usbsh: serial relay not running - first: dapi usbser open VID:PID [baud]' >&2\n" +
                "                  exit 1\n" +
                "              fi\n" +
                "              cat <&3 &\n" +
                "              UPID=${'$'}!\n" +
                "              trap 'kill ${'$'}UPID 2>/dev/null; exec 3<&- 3>&-' INT TERM EXIT\n" +
                "              cat >&3\n" +
                "              kill ${'$'}UPID 2>/dev/null\n" +
                "              exec 3<&- 3>&- ;;\n" +
                "    usbraw)   q=\"usbraw\"; for a in \"${'$'}@\"; do q=\"${'$'}q${'$'}tab${'$'}a\"; done; send \"${'$'}q\" ;;\n" +
                "    flash)    case \"${'$'}1\" in\n" +
                "                  get)\n" +
                "                      if [ -z \"${'$'}2\" ]; then echo 'usage: dapi flash get REMOTE [LOCAL]' >&2; exit 1; fi\n" +
                "                      if ! exec 3<>/dev/tcp/127.0.0.1/8041; then echo 'flash: not attached - first: dapi flash open VID:PID' >&2; exit 1; fi\n" +
                "                      printf 'get%s%s\\n' \"${'$'}tab\" \"${'$'}2\" >&3\n" +
                "                      if [ -n \"${'$'}3\" ]; then cat <&3 >\"${'$'}3\"; else cat <&3; fi\n" +
                "                      exec 3<&- 3>&- ;;\n" +
                "                  put)\n" +
                "                      if [ -z \"${'$'}2\" ] || [ -z \"${'$'}3\" ]; then echo 'usage: dapi flash put LOCAL REMOTE' >&2; exit 1; fi\n" +
                "                      if [ ! -f \"${'$'}2\" ]; then echo \"flash: ${'$'}2: no such file\" >&2; exit 1; fi\n" +
                "                      fsz=$(stat -c%s \"${'$'}2\")\n" +
                "                      if ! exec 3<>/dev/tcp/127.0.0.1/8041; then echo 'flash: not attached - first: dapi flash open VID:PID' >&2; exit 1; fi\n" +
                "                      printf 'put%s%s%s%s\\n' \"${'$'}tab\" \"${'$'}3\" \"${'$'}tab\" \"${'$'}fsz\" >&3\n" +
                "                      cat \"${'$'}2\" >&3\n" +
                "                      cat <&3\n" +
                "                      exec 3<&- 3>&- ;;\n" +
                "                  *)\n" +
                "                      q=\"flash\"; for a in \"${'$'}@\"; do q=\"${'$'}q${'$'}tab${'$'}a\"; done; send \"${'$'}q\" ;;\n" +
                "              esac ;;\n" +
                "    share)    case \"${'$'}1\" in\n" +
                "                  stop)\n" +
                "                      tmate -S /tmp/utmate.sock kill-session 2>/dev/null\n" +
                "                      rm -f /tmp/utmate.sock\n" +
                "                      echo 'remote access stopped' ;;\n" +
                "                  *)\n" +
                "                      if ! command -v tmate >/dev/null 2>&1; then\n" +
                "                          echo 'one-time setup: apt install -y tmate' >&2; exit 1\n" +
                "                      fi\n" +
                "                      if ! tmate -S /tmp/utmate.sock has-session 2>/dev/null; then\n" +
                "                          tmate -S /tmp/utmate.sock new-session -d\n" +
                "                          i=0\n" +
                "                          while [ \"${'$'}i\" -lt 30 ]; do\n" +
                "                              ssh_line=\"${'$'}(tmate -S /tmp/utmate.sock display -p '#{tmate_ssh}')\"\n" +
                "                              case \"${'$'}ssh_line\" in *tmate.io*) break ;; esac\n" +
                "                              sleep 1; i=${'$'}((i+1))\n" +
                "                          done\n" +
                "                      fi\n" +
                "                      echo 'from ANY pc on the internet, run:'\n" +
                "                      echo \"  ${'$'}(tmate -S /tmp/utmate.sock display -p '#{tmate_ssh}')\"\n" +
                "                      echo \"web terminal: ${'$'}(tmate -S /tmp/utmate.sock display -p '#{tmate_web}')\"\n" +
                "                      echo 'stop: dapi share stop (keep the app open on the phone!)' ;;\n" +
                "              esac ;;\n" +
                "    tg)       if [ ! -x /usr/local/bin/dapi-tg ]; then\n" +
                "                  echo 'missing dapi-tg - reopen the app once, then retry' >&2; exit 1\n" +
                "              fi\n" +
                "              if ! command -v python3 >/dev/null 2>&1; then\n" +
                "                  echo 'one-time setup: apt install -y python3' >&2; exit 1\n" +
                "              fi\n" +
                "              exec python3 /usr/local/bin/dapi-tg \"${'$'}@\" ;;\n" +
                "    pkglist)  mkdir -p /var/lib\n" +
                "              apt-cache pkgnames 2>/dev/null | sort > /var/lib/dapi-pkgs.txt\n" +
                "              echo \"OK ${'$'}(wc -l < /var/lib/dapi-pkgs.txt) package names cached for the completion bar\" ;;\n" +
                "    backup)    if ! mkdir -p /sdcard/UbuntuTerminal 2>/dev/null; then\n" +
                "                   echo 'cannot write to /sdcard - allow storage access to the app' >&2\n" +
                "                   exit 1\n" +
                "               fi\n" +
                "               MODE=-czf\n" +
                "               EXT=.tar.gz\n" +
                "               if [ \"${'$'}1\" = \"fast\" ]; then MODE=-cf; EXT=.tar; fi\n" +
                "               OUT=\"/sdcard/UbuntuTerminal/backup-${'$'}(date +%Y%m%d-%H%M%S)${'$'}EXT\"\n" +
                "               echo 'backing up the WHOLE Ubuntu (few minutes - keep the app open)...'\n" +
                "               echo \"-> ${'$'}OUT\"\n" +
                "               if tar \"${'$'}MODE\" \"${'$'}OUT\" --one-file-system \\\n" +
                "                       --exclude=./proc --exclude=./sys --exclude=./dev \\\n" +
                "                       --exclude=./tmp --exclude=./run --exclude=./sdcard \\\n" +
                "                       --exclude=./storage --exclude=./mnt \\\n" +
                "                       --checkpoint=5000 --checkpoint-action=echo='  %u files archived' \\\n" +
                "                       -C / . ; then\n" +
                "                   echo \"OK backup saved: ${'$'}OUT (${'$'}(du -h \"${'$'}OUT\" | cut -f1))\"\n" +
                "                   echo \"restore it later with: dapi restore ${'$'}OUT CONFIRM\"\n" +
                "               else\n" +
                "                   rm -f \"${'$'}OUT\"\n" +
                "                   echo 'backup FAILED - check free space on /sdcard' >&2\n" +
                "                   exit 1\n" +
                "               fi ;;\n" +
                "    restore)   if [ -z \"${'$'}1\" ] || [ \"${'$'}2\" != \"CONFIRM\" ]; then\n" +
                "                   echo 'usage: dapi restore FILE CONFIRM'\n" +
                "                   echo 'this REPLACES the current Ubuntu with the backup!'\n" +
                "                   exit 1\n" +
                "               fi\n" +
                "               if [ ! -f \"${'$'}1\" ]; then echo \"no such file: ${'$'}1\" >&2; exit 1; fi\n" +
                "               MODE=-xzf\n" +
                "               case \"${'$'}1\" in\n" +
                "                   *.tar) MODE=-xf ;;\n" +
                "                   *.tar.gz|*.tgz) MODE=-xzf ;;\n" +
                "                   *) echo 'unsupported file (need .tar or .tar.gz)' >&2; exit 1 ;;\n" +
                "               esac\n" +
                "               echo \"restoring ${'$'}1 (few minutes)... keep the app open\"\n" +
                "               if tar \"${'$'}MODE\" \"${'$'}1\" -C / ; then\n" +
                "                   mkdir -p /proc /sys /dev /tmp /run /sdcard\n" +
                "                   echo 'OK restored - now FULLY close the app (from recents) and reopen it'\n" +
                "               else\n" +
                "                   echo 'restore FAILED' >&2\n" +
                "                   exit 1\n" +
                "               fi ;;\n" +
                "    backups)   ls -lh /sdcard/UbuntuTerminal/backup-*.tar* 2>/dev/null || echo 'no backups yet (try: dapi backup)' ;;\n" +
                "    keepalive) if [ \"${'$'}1\" = \"on\" ] || [ \"${'$'}1\" = \"off\" ] || [ \"${'$'}1\" = \"status\" ]; then\n" +
                "                   q=\"keepalive\"; for a in \"${'$'}@\"; do q=\"${'$'}q${'$'}tab${'$'}a\"; done; send \"${'$'}q\"\n" +
                "               else\n" +
                "                   echo 'usage: dapi keepalive on|off|status'\n" +
                "                   echo 'keeps the CPU awake with the screen off (bot/tmate/VNC keep running)'\n" +
                "               fi ;;\n" +
                "    keepalive) if [ \"${'$'}1\" = \"on\" ] || [ \"${'$'}1\" = \"off\" ] || [ \"${'$'}1\" = \"status\" ]; then\n" +
                "                   q=\"keepalive\"; for a in \"${'$'}@\"; do q=\"${'$'}q${'$'}tab${'$'}a\"; done; send \"${'$'}q\"\n" +
                "               else\n" +
                "                   echo 'usage: dapi keepalive on|off|status'\n" +
                "                   echo 'keeps the CPU awake with the screen off (bot/tmate/VNC keep running)'\n" +
                "               fi ;;\n" +
                "    autostart) AS=\"${'$'}HOME/.dapi-autostart\"\n" +
                "               case \"${'$'}1\" in\n" +
                "                   add)\n" +
                "                       shift\n" +
                "                       [ -n \"${'$'}1\" ] || { echo 'usage: dapi autostart add COMMAND' >&2; exit 1; }\n" +
                "                       echo \"${'$'}*\" >> \"${'$'}AS\"\n" +
                "                       echo \"OK added: ${'$'}*\"\n" +
                "                       echo '(runs automatically every time you open the app)' ;;\n" +
                "                   rm)\n" +
                "                       case \"${'$'}2\" in [0-9]*) ;; *) echo 'usage: dapi autostart rm NUMBER' >&2; exit 1 ;; esac\n" +
                "                       if [ -f \"${'$'}AS\" ]; then sed -i \"${'$'}{2}d\" \"${'$'}AS\" && echo \"OK removed #${'$'}2\"; else echo 'list is empty' >&2; exit 1; fi ;;\n" +
                "                   clear)\n" +
                "                       : > \"${'$'}AS\"\n" +
                "                       echo 'OK autostart cleared' ;;\n" +
                "                   run)\n" +
                "                       if [ -s \"${'$'}AS\" ]; then\n" +
                "                           n=0\n" +
                "                           while IFS= read -r c; do\n" +
                "                               case \"${'$'}c\" in ''|'#'*) continue ;; esac\n" +
                "                               nohup bash -c \"${'$'}c\" >/dev/null 2>&1 &\n" +
                "                               n=${'$'}((n+1))\n" +
                "                           done < \"${'$'}AS\"\n" +
                "                           echo \"OK started ${'$'}n command(s)\"\n" +
                "                       else\n" +
                "                           echo 'nothing to run' >&2; exit 1\n" +
                "                       fi ;;\n" +
                "                   *)\n" +
                "                       echo 'autostart - commands that run when the app opens:'\n" +
                "                       if [ -s \"${'$'}AS\" ]; then nl -ba \"${'$'}AS\"; else echo '  (empty)'; fi\n" +
                "                       echo ''\n" +
                "                       echo 'add:    dapi autostart add COMMAND'\n" +
                "                       echo 'remove: dapi autostart rm NUMBER    clear: dapi autostart clear'\n" +
                "                       echo 'examples:'\n" +
                "                       echo \"  dapi autostart add 'dapi keepalive on'\"\n" +
                "                       echo \"  dapi autostart add 'python3 /usr/local/bin/dapi-tg TOKEN'\"\n" +
                "                       echo \"  dapi autostart add 'tmate -S /tmp/utmate.sock new-session -d'\" ;;\n" +
                "               esac ;;\n" +
                "    vnc)      case \"${'$'}1\" in\n" +
                "                  start)\n" +
                "                      VS=''\n" +
                "                      if command -v tigervncserver >/dev/null 2>&1; then VS=tigervncserver; fi\n" +
                "                      if [ -z \"${'$'}VS\" ] && command -v vncserver >/dev/null 2>&1; then VS=vncserver; fi\n" +
                "                      if [ -z \"${'$'}VS\" ]; then\n" +
                "                          echo 'one-time setup - pick a desktop:'\n" +
                "                          echo '  full: apt install -y tigervnc-standalone-server xfce4 xfce4-terminal dbus-x11'\n" +
                "                          echo '  lite: apt install -y tigervnc-standalone-server openbox xterm'\n" +
                "                          exit 1\n" +
                "                      fi\n" +
                "                      if ! (exec 3<>/dev/tcp/127.0.0.1/5901) 2>/dev/null; then\n" +
                "                          mkdir -p \"${'$'}HOME/.vnc\" \"${'$'}HOME/.config/tigervnc\"\n" +
                "                          if [ ! -f \"${'$'}HOME/.vnc/xstartup\" ] && [ ! -f \"${'$'}HOME/.config/tigervnc/xstartup\" ]; then\n" +
                "                              printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'command -v startxfce4 >/dev/null 2>&1 && exec startxfce4' 'command -v openbox-session >/dev/null 2>&1 && exec openbox-session' 'exec xterm' > \"${'$'}HOME/.vnc/xstartup\"\n" +
                "                              cp \"${'$'}HOME/.vnc/xstartup\" \"${'$'}HOME/.config/tigervnc/xstartup\"\n" +
                "                          fi\n" +
                "                          chmod +x \"${'$'}HOME/.vnc/xstartup\" \"${'$'}HOME/.config/tigervnc/xstartup\" 2>/dev/null\n" +
                "                          ${'$'}VS :1 -localhost yes -SecurityTypes None -geometry 1280x720 -depth 24 >/dev/null 2>&1\n" +
                "                          i=0\n" +
                "                          while [ \"${'$'}i\" -lt 25 ]; do\n" +
                "                              if (exec 3<>/dev/tcp/127.0.0.1/5901) 2>/dev/null; then break; fi\n" +
                "                              sleep 1; i=${'$'}((i+1))\n" +
                "                          done\n" +
                "                      fi\n" +
                "                      if (exec 3<>/dev/tcp/127.0.0.1/5901) 2>/dev/null; then\n" +
                "                          exec 3<&- 3>&-\n" +
                "                          echo 'VNC desktop ready on 127.0.0.1:5901 - opening view'\n" +
                "                          send \"vnc${'$'}{tab}open\"\n" +
                "                      else\n" +
                "                          echo \"failed - try manually: ${'$'}VS :1 -localhost yes -SecurityTypes None\"\n" +
                "                          exit 1\n" +
                "                      fi ;;\n" +
                "                  stop)\n" +
                "                      tigervncserver -kill :1 >/dev/null 2>&1\n" +
                "                      vncserver -kill :1 >/dev/null 2>&1\n" +
                "                      echo 'VNC stopped' ;;\n" +
                "                  *) echo 'usage: dapi vnc start|stop  (desktop GUI via VNC)' ;;\n" +
                "              esac ;;\n" +
                "    *) cat <<'HELP'\n" +
                "dapi - control the Android device from Ubuntu\n" +
                "\n" +
                "Usage:\n" +
                "  dapi vibrate [ms]      vibrate the phone (default 300 ms)\n" +
                "  dapi notify TITLE MSG  send a system notification\n" +
                "  dapi toast TEXT        show a short toast\n" +
                "  dapi battery           battery level, status and temperature\n" +
                "  dapi usb               list connected USB devices\n" +
                "  dapi usbp VID:PID      request permission for a USB device\n" +
                "  dapi torch on|off      camera flashlight\n" +
                "  dapi info              device model and Android version\n" +
                "  dapi ping              check the bridge connection (version/uptime)\n" +
                "  dapi clip [TEXT]       get or set the Android clipboard\n" +
                "  dapi usbser list       list USB serial adapters (CH340/CP210x/FTDI/PL2303/CDC)\n" +
                "  dapi usbser open VID:PID [baud]\n" +
                "                        open serial port + start TCP relay (default 115200)\n" +
                "  dapi usbser send TEXT  send a line to the device (adds CRLF)\n" +
                "  dapi usbser sendraw T  send exact bytes, e.g. $'\\x03' for Ctrl+C\n" +
                "  dapi usbser read       print and clear received data\n" +
                "  dapi usbser status     connection state and byte counters\n" +
                "  dapi usbser close      close the serial port\n" +
                "  dapi usbsh             interactive serial console (Ctrl+D exits)\n" +
                "  dapi usbser dtr|rts on|off   serial control lines (Arduino reset)\n" +
                "  dapi flash list       list USB flash drives\n" +
                "  dapi flash open VID:PID  attach a FAT32 stick (auto after allow)\n" +
                "  dapi flash ls [path]  list files on the stick\n" +
                "  dapi flash cat FILE   print a small file\n" +
                "  dapi flash get F [L]  download a file (streamed)\n" +
                "  dapi flash put L R    upload a file to the stick\n" +
                "  dapi flash mkdir PATH | rm PATH   create / delete\n" +
                "  dapi flash info       filesystem, capacity, free space\n" +
                "  dapi flash format CONFIRM [LABEL]  quick-format FAT32 (ERASES!)\n" +
                "  dapi flash format TYPE CONFIRM [LABEL]  real mkfs: fat16|exfat|ntfs|ext2|ext3|ext4\n" +
                "        (runs in background inside Ubuntu - notification when done)\n" +
                "  dapi flash eject      release the stick\n" +
                "  dapi usbraw open VID:PID  raw USB for code on 127.0.0.1:8040\n" +
                "Python: import utusb | Node: require('utusb')  - USB libraries!\n" +
                "  dapi share            remote SSH from any pc on the internet (tmate)\n" +
                "  dapi share stop       stop remote access\n" +
                "  dapi tg BOT_TOKEN     run terminal commands from Telegram chat\n" +
                "  dapi vnc start|stop   desktop GUI (VNC) - opens the app view\n" +
                "  dapi pkglist          rebuild the package list for the completion bar\n" +
                "  dapi backup [fast]    full Ubuntu backup -> /sdcard (tar.gz)\n" +
                "  dapi backups          list saved backups\n" +
                "  dapi restore F CONFIRM  restore a backup over the current Ubuntu\n" +
                "  dapi keepalive on|off  keep the CPU awake with screen off\n" +
                "  dapi autostart         auto-run commands when the app opens\n" +
                "HELP\n" +
                "    ;;\n" +
                "esac\n"
            )
            script.setExecutable(true, false)
            script.setReadable(true, false)
        }
    }

    /**
     * Installs a profile.d script that heals the terminal state at every
     * bash prompt: a TUI killed without cleanup (opencode/vim killed with
     * Ctrl+C or a crash) can leave the terminal in the alternate screen with
     * mouse tracking still enabled - which turns every touch into garbage
     * echoed at the prompt. Emitting these resets at each prompt is a no-op
     * in a healthy shell.
     */
    private fun ensureTermResetScript(app: Context) {
        runCatching {
            val script = File(Installer.rootfsDir(app), "etc/profile.d/51-terminal-reset.sh")
            script.parentFile?.mkdirs()
            script.writeText(
                "# Ubuntu Terminal: heal the terminal state at every bash prompt.\n" +
                "# A TUI that dies without cleanup (killed opencode, vim, ...) can leave\n" +
                "# the terminal in the alternate screen with mouse tracking still on,\n" +
                "# which turns every touch into garbage. These resets are no-ops in a\n" +
                "# healthy shell.\n" +
                "__ut_reset_cmd=\": ut_term_reset; printf '\\033[?1000l\\033[?1002l\\033[?1003l\\033[?1006l\\033[?1015l\\033[?25h\\033[?2004l\\033[?1049l'\"\n" +
                "case \"${'$'}PROMPT_COMMAND\" in\n" +
                "    *\"ut_term_reset\"*) ;;\n" +
                "    \"\") PROMPT_COMMAND=\"${'$'}__ut_reset_cmd\" ;;\n" +
                "    *) PROMPT_COMMAND=\"${'$'}__ut_reset_cmd; ${'$'}PROMPT_COMMAND\" ;;\n" +
                "esac\n" +
                "unset __ut_reset_cmd\n"
            )
            script.setReadable(true, false)
        }
    }

    /**
     * Installs the utusb programming libraries (Python + Node.js) into the
     * rootfs, so code running inside the container can drive USB devices
     * directly: raw control/bulk/interrupt transfers, serial adapters and
     * FAT32 flash drives - all through the app's TCP bridges, no root.
     */
    private fun ensureUsbLibs(app: Context) {
        runCatching {
            val pyDir = File(Installer.rootfsDir(app), "usr/local/lib/utusb")
            pyDir.mkdirs()
            app.assets.open("utusb.py").use { input ->
                File(pyDir, "utusb.py").outputStream().use { input.copyTo(it) }
            }
            val nodeDir = File(Installer.rootfsDir(app), "usr/local/lib/node_modules/utusb")
            nodeDir.mkdirs()
            app.assets.open("utusb.js").use { input ->
                File(nodeDir, "index.js").outputStream().use { input.copyTo(it) }
            }
            val tg = File(Installer.rootfsDir(app), "usr/local/bin/dapi-tg")
            tg.parentFile?.mkdirs()
            app.assets.open("dapi-tg.py").use { input ->
                tg.outputStream().use { input.copyTo(it) }
            }
            tg.setReadable(true, false)
            tg.setExecutable(true, false)
            // pkg: Termux-style apt wrapper
            val pkg = File(Installer.rootfsDir(app), "usr/local/bin/pkg")
            pkg.parentFile?.mkdirs()
            app.assets.open("pkg.sh").use { input ->
                pkg.outputStream().use { input.copyTo(it) }
            }
            pkg.setReadable(true, false)
            pkg.setExecutable(true, false)
            // autostart runner: runs ~/.dapi-autostart commands once per app launch
            val auto = File(Installer.rootfsDir(app), "etc/profile.d/53-dapi-autostart.sh")
            auto.writeText(
                "# dapi autostart - runs ~/.dapi-autostart commands once per app launch\n" +
                "# (the app removes /tmp/.dapi-autostart.lock when it starts)\n" +
                "if [ -f /tmp/.dapi-autostart.lock ]; then return 0 2>/dev/null || exit 0; fi\n" +
                ": > /tmp/.dapi-autostart.lock\n" +
                "if [ -f \"${'$'}HOME/.dapi-autostart\" ]; then\n" +
                "    while IFS= read -r _dacmd; do\n" +
                "        case \"${'$'}_dacmd\" in ''|'#'*) continue ;; esac\n" +
                "        nohup bash -c \"${'$'}_dacmd\" >/dev/null 2>&1 &\n" +
                "    done < \"${'$'}HOME/.dapi-autostart\"\n" +
                "    unset _dacmd\n" +
                "fi\n"
            )
            auto.setReadable(true, false)
            // refresh the completion-bar package list after every apt update
            val aptHook = File(Installer.rootfsDir(app), "etc/apt/apt.conf.d/99-dapi-complete")
            aptHook.parentFile?.mkdirs()
            aptHook.writeText(
                "APT::Update::Post-Invoke { " +
                    "\"apt-cache pkgnames 2>/dev/null | sort > /var/lib/dapi-pkgs.txt || true\" };" +
                "\n"
            )
            aptHook.setReadable(true, false)
            val profile = File(Installer.rootfsDir(app), "etc/profile.d/52-utusb-paths.sh")
            profile.parentFile?.mkdirs()
            profile.writeText(
                "# Ubuntu Terminal: make the utusb USB libraries importable\n" +
                "export PYTHONPATH=/usr/local/lib/utusb" + "${'$'}{PYTHONPATH:+:" + "${'$'}PYTHONPATH}\n" +
                "export NODE_PATH=/usr/local/lib/node_modules" + "${'$'}{NODE_PATH:+:" + "${'$'}NODE_PATH}\n"
            )
            profile.setReadable(true, false)
        }
    }

    /**
     * Installs a crash-safe, persistent bash history:
     *  - every command is appended to ~/.bash_history IMMEDIATELY (so it
     *    survives an app kill / crash – bash normally only writes on exit),
     *  - commands typed in other sessions are pulled in live,
     *  - ~ is the persistent home bind, so history survives app restarts.
     * Written into the rootfs as a profile.d script – runs for every login
     * shell, and re-applying it on existing installs is a no-op.
     */
    private fun ensureHistoryConfig(app: Context) {
        runCatching {
            val script = File(Installer.rootfsDir(app), "etc/profile.d/50-history.sh")
            script.parentFile?.mkdirs()
            script.writeText(
                """
                # Ubuntu Terminal: persistent, crash-safe, multi-session command history
                export HISTFILE=/root/.bash_history
                export HISTSIZE=10000
                export HISTFILESIZE=20000
                export HISTCONTROL=ignoreboth:erasedups
                shopt -s histappend 2>/dev/null
                # append each command right away + merge commands from other sessions
                if [ -z "${'$'}PROMPT_COMMAND" ]; then
                    PROMPT_COMMAND='history -a; history -n'
                else
                    case "${'$'}PROMPT_COMMAND" in
                        *"history -a"*) ;;
                        *) PROMPT_COMMAND="history -a; history -n; ${'$'}PROMPT_COMMAND" ;;
                    esac
                fi
                """.trimIndent()
            )
            script.setReadable(true, false)
        }
    }

    /**
     * Installs the command-finished notifier: PS0 records the start time of
     * every command, and when the prompt returns (PROMPT_COMMAND) the hook
     * computes the duration; anything >= 30s calls `dapi notify-done`, which
     * posts a local notification + a Telegram message from the Android side.
     */
    private fun ensureCmdNotifyScript(app: Context) {
        runCatching {
            val script = File(Installer.rootfsDir(app), "etc/profile.d/55-cmd-notify.sh")
            script.parentFile?.mkdirs()
            script.writeText(
                """
                #!/bin/bash
                # Ubuntu Terminal: notify when a long-running command finishes (>= 30s)
                __ut_cn_start() {
                    date +%s%3N > "/tmp/.utcmd-${'$'}$.t" 2>/dev/null
                    return 0
                }
                __ut_cn_end() {
                    local __rc=${'$'}?
                    local __f="/tmp/.utcmd-${'$'}$.t"
                    [ -f "${'$'}__f" ] || return 0
                    local __now __t0
                    __now=${'$'}(date +%s%3N 2>/dev/null) || return 0
                    __t0=${'$'}(cat "${'$'}__f" 2>/dev/null) || __t0="${'$'}__now"
                    rm -f "${'$'}__f" 2>/dev/null
                    case "${'$'}__t0${'$'}__now" in ''|*[!0-9]*) return 0 ;; esac
                    local __dur=$(( __now - __t0 ))
                    [ "${'$'}__dur" -ge 30000 ] || return 0
                    local __cmd
                    __cmd=${'$'}(HISTTIMEFORMAT= history 1 2>/dev/null | sed 's/^ *[0-9]* *//; s/ *$//' | head -c 200 | tr '\n\r\t' '   ')
                    (dapi notify-done "${'$'}__dur" "${'$'}__rc" "${'$'}__cmd" >/dev/null 2>&1 &)
                    return 0
                }
                # stale start-files from dead sessions must not fire false alerts
                rm -f /tmp/.utcmd-*.t 2>/dev/null
                PS0='${'$'}(__ut_cn_start)'
                if [ -z "${'$'}PROMPT_COMMAND" ]; then
                    PROMPT_COMMAND='__ut_cn_end'
                else
                    case "${'$'}PROMPT_COMMAND" in
                        *__ut_cn_end*) ;;
                        *) PROMPT_COMMAND="__ut_cn_end; ${'$'}PROMPT_COMMAND" ;;
                    esac
                fi
                """.trimIndent()
            )
            script.setReadable(true, false)
        }
    }

    /**
     * Runs a ONE-SHOT command inside the Ubuntu container and waits for it.
     * Used by the multi-filesystem flash formatter (mkfs on an image file).
     * Returns (exitCode, combined stdout+stderr).
     */
    fun execInUbuntu(
        app: Context, command: String, timeoutSec: Long = 600L,
        extraBind: Pair<String, String>? = null
    ): Pair<Int, String> {
        return try {
            val rootfs = Installer.rootfsDir(app)
            val home = Installer.homeDir(app)
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val proot = File(nativeDir, "libproot.so")

            val args = mutableListOf<String>()
            args += proot.absolutePath
            args += "--kill-on-exit"
            args += "-0"
            args += "--link2symlink"
            args += "-r"; args += rootfs.absolutePath
            args += "-b"; args += "/dev"
            args += "-b"; args += "/proc"
            args += "-b"; args += "/sys"
            if (extraBind != null) {   // v1.29: e.g. format images via /var/tmp
                args += "-b"; args += "${extraBind.first}:${extraBind.second}"
            }
            args += "-b"; args += "${home.absolutePath}:/root"
            args += "-w"; args += "/root"
            val envVars = listOf(
                "HOME=/root", "USER=root", "LOGNAME=root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=dumb", "LANG=C.UTF-8"
            )
            if (File(rootfs, "usr/bin/env").canExecute()) {
                args += "/usr/bin/env"; args += "-i"; args += envVars
            } else {
                args += "/bin/bash"
            }
            args += "/bin/bash"
            args += "-c"
            args += command

            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.environment()["PROOT_TMP_DIR"] = app.cacheDir.absolutePath
            pb.environment()["PROOT_NO_SECCOMP"] = "1"
            pb.environment()["PROOT_LOADER"] = File(nativeDir, "libproot-loader.so").absolutePath
            File(nativeDir, "libproot-loader32.so").takeIf { it.isFile }?.let {
                pb.environment()["PROOT_LOADER_32"] = it.absolutePath
            }
            // without env -i the variables above must reach bash through proot
            for (v in envVars) {
                val k = v.substringBefore('='); val v2 = v.substringAfter('=')
                pb.environment().putIfAbsent(k, v2)
            }

            val proc = pb.start()
            val sb = StringBuilder()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                }
            }.apply { isDaemon = true; name = "ut-exec-reader"; start() }
            val done = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                proc.destroyForcibly()
                -1 to (sb.toString() + "\n(timeout after ${timeoutSec}s)")
            } else {
                reader.join(3000)
                proc.exitValue() to sb.toString()
            }
        } catch (e: Exception) {
            -2 to ("exec failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Marks [entry] as the one shown in the view. */
    @Synchronized
    fun activate(entry: Entry) {
        if (_entries.contains(entry)) active = entry
    }

    /** Detaches and forgets a session (does NOT kill it). Returns the removed entry. */
    @Synchronized
    fun remove(session: TerminalSession): Entry? {
        val i = _entries.indexOfFirst { it.session === session }
        if (i < 0) return null
        val e = _entries.removeAt(i)
        if (active === e) active = _entries.firstOrNull()
        return e
    }

    /** Kills every session and forgets them all. */
    @Synchronized
    fun reset() {
        _entries.forEach { it.session.finishIfRunning() }
        _entries.clear()
        active = null
        counter.set(0)
    }

    /** Points every live session's callbacks at a fresh client after the activity was recreated. */
    @Synchronized
    fun rebind(client: TerminalSessionClient) {
        _entries.forEach { it.session.updateTerminalSessionClient(client) }
    }

    // ---------------------------------------------------------------- session build

    private fun buildSession(app: Context, client: TerminalSessionClient): TerminalSession {
        val rootfs = Installer.rootfsDir(app)
        val home = Installer.homeDir(app).apply { mkdirs() }
        val nativeDir = app.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")

        val args = mutableListOf<String>()
        args += proot.absolutePath // argv[0]
        args += "--kill-on-exit"   // kill all tracees when proot itself exits
        args += "-0"               // appear as root inside the container (required by apt)
        args += "--link2symlink"   // Termux PRoot extension: hardlinks become symlinks
        args += "-r"; args += rootfs.absolutePath     // guest root filesystem
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        args += "-b"; args += "/sys"
        args += "-b"; args += "${home.absolutePath}:/root" // persistent home
        // Device shared storage → /sdcard (and its real path) inside the guest.
        // The bind is always created; whether the files are readable/writable
        // depends on the runtime storage permission (see MainActivity).
        val external = File("/storage/emulated/0")
        if (external.isDirectory) {
            args += "-b"; args += "${external.absolutePath}:/sdcard"
            args += "-b"; args += "${external.absolutePath}:${external.absolutePath}"
        }
        // Removable SD cards, if any (labels like "XXXX-XXXX").
        runCatching {
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
                ?.forEach {
                    args += "-b"; args += "${it.absolutePath}:${it.absolutePath}"
                }
        }
        args += "-w"; args += "/root" // initial working directory

        // The guest entry command. `env -i` gives the guest a clean, predictable
        // environment; if /usr/bin/env is unusable we launch bash directly and
        // the variables travel through PRoot's own environment instead (see below).
        val envUsable = File(rootfs, "usr/bin/env").canExecute()
        args += if (envUsable) listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
            "/bin/bash", "--login"
        ) else listOf(
            "/bin/bash", "--login"
        )

        // Environment for the PRoot process itself (outside the container).
        // In the normal path the guest env is set by `env -i`; in the fallback
        // path (no /usr/bin/env) the variables below reach bash directly.
        val env = mutableListOf(
            "LD_LIBRARY_PATH=$nativeDir", // resolves libtalloc.so & libandroid-shmem.so
            "PROOT_TMP_DIR=${app.cacheDir.absolutePath}",
            "PROOT_NO_SECCOMP=1",         // some kernels restrict seccomp for apps
            "PROOT_LOADER=${File(nativeDir, "libproot-loader.so").absolutePath}"
        )
        File(nativeDir, "libproot-loader32.so").takeIf { it.isFile }?.let {
            env += "PROOT_LOADER_32=${it.absolutePath}"
        }
        if (!envUsable) {
            // no `env -i` available – carry the guest environment ourselves
            env += listOf(
                "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8"
            )
        }

        return TerminalSession(
            proot.absolutePath,        // executable
            app.filesDir.absolutePath, // working directory
            args.toTypedArray(),
            env.toTypedArray(),
            null,                      // default transcript size
            client
        )
    }
}
