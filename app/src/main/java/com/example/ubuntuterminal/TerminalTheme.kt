package com.example.ubuntuterminal

import android.graphics.Color
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle

/**
 * Terminal color themes: a background, a default foreground and the 16 ANSI
 * colors, applied to the emulator color table of every session (and to new
 * sessions as they are created). Selected in SettingsActivity, stored in the
 * "settings" preferences under the key "theme".
 */
class TerminalTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val fg: Int,
    val ansi: IntArray
) {

    fun applyTo(session: TerminalSession?) {
        val emu = session?.emulator ?: return
        val c = emu.mColors.mCurrentColors
        c[TextStyle.COLOR_INDEX_FOREGROUND] = fg
        c[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        for (i in 0..15) c[i] = ansi[i]
    }

    companion object {
        private fun c(hex: String) = Color.parseColor(hex)

        fun fromId(id: String): TerminalTheme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

        val THEMES = listOf(
            TerminalTheme("dark", "Dark (افتراضي)", c("#121212"), c("#EEEEEE"), intArrayOf(
                c("#000000"), c("#CD0000"), c("#00CD00"), c("#CDCD00"),
                c("#0000EE"), c("#CD00CD"), c("#00CDCD"), c("#E5E5E5"),
                c("#7F7F7F"), c("#FF0000"), c("#00FF00"), c("#FFFF00"),
                c("#5C5CFF"), c("#FF00FF"), c("#00FFFF"), c("#FFFFFF"))),
            TerminalTheme("ubuntu", "Ubuntu 🟠", c("#300A24"), c("#FFFFFF"), intArrayOf(
                c("#000000"), c("#CC0000"), c("#4E9A06"), c("#C4A000"),
                c("#3465A4"), c("#75507B"), c("#06989A"), c("#D3D7CF"),
                c("#555753"), c("#EF2929"), c("#8AE234"), c("#FCE94F"),
                c("#729FCF"), c("#AD7FA8"), c("#34E2E2"), c("#EEEEEC"))),
            TerminalTheme("dracula", "Dracula 🧛", c("#282A36"), c("#F8F8F2"), intArrayOf(
                c("#000000"), c("#FF5555"), c("#50FA7B"), c("#F1FA8C"),
                c("#BD93F9"), c("#FF79C6"), c("#8BE9FD"), c("#BFBFBF"),
                c("#4D4D4D"), c("#FF6E67"), c("#5AF78E"), c("#F4F99D"),
                c("#CAA9FA"), c("#FF92D0"), c("#9AEDFE"), c("#E6E6E6"))),
            TerminalTheme("nord", "Nord ❄️", c("#2E3440"), c("#D8DEE9"), intArrayOf(
                c("#3B4252"), c("#BF616A"), c("#A3BE8C"), c("#EBCB8B"),
                c("#81A1C1"), c("#B48EAD"), c("#88C0D0"), c("#E5E9F0"),
                c("#4C566A"), c("#BF616A"), c("#A3BE8C"), c("#EBCB8B"),
                c("#81A1C1"), c("#B48EAD"), c("#8FBCBB"), c("#ECEFF4"))),
            TerminalTheme("monokai", "Monokai 🍌", c("#272822"), c("#F8F8F2"), intArrayOf(
                c("#272822"), c("#F92672"), c("#A6E22E"), c("#F4BF75"),
                c("#66D9EF"), c("#AE81FF"), c("#A1EFE4"), c("#F8F8F2"),
                c("#75715E"), c("#F92672"), c("#A6E22E"), c("#F4BF75"),
                c("#66D9EF"), c("#AE81FF"), c("#A1EFE4"), c("#F9F8F5"))),
            TerminalTheme("solarized_dark", "Solarized Dark 🌚", c("#002B36"), c("#839496"), intArrayOf(
                c("#073642"), c("#DC322F"), c("#859900"), c("#B58900"),
                c("#268BD2"), c("#D33682"), c("#2AA198"), c("#EEE8D5"),
                c("#002B36"), c("#CB4B16"), c("#586E75"), c("#657B83"),
                c("#839496"), c("#6C71C4"), c("#93A1A1"), c("#FDF6E3"))),
            TerminalTheme("solarized_light", "Solarized Light 🌝", c("#FDF6E3"), c("#657B83"), intArrayOf(
                c("#EEE8D5"), c("#DC322F"), c("#859900"), c("#B58900"),
                c("#268BD2"), c("#D33682"), c("#2AA198"), c("#073642"),
                c("#FDF6E3"), c("#CB4B16"), c("#586E75"), c("#586E75"),
                c("#839496"), c("#6C71C4"), c("#93A1A1"), c("#002B36"))),
            TerminalTheme("light", "Light 📄", c("#FAFAFA"), c("#212121"), intArrayOf(
                c("#212121"), c("#D32F2F"), c("#388E3C"), c("#F57F17"),
                c("#1976D2"), c("#7B1FA2"), c("#0097A7"), c("#757575"),
                c("#9E9E9E"), c("#E57373"), c("#81C784"), c("#FFD54F"),
                c("#64B5F6"), c("#BA68C8"), c("#4DD0E1"), c("#616161")))
        )
    }
}
