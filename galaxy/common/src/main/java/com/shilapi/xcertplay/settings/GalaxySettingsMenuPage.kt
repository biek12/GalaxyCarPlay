package com.shilapi.xcertplay.settings

import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.L7Components
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.ItemGalaxySettingsEntryBinding
import com.shilapi.xcertplay.host.databinding.PageGalaxySettingsMenuBinding

/**
 * 设置根页的七个分类。
 *
 * 顺序、图标和文案保持原列表。每一项只注册一个点击，点击一次只回调一次路由。
 */
internal class GalaxySettingsMenuPage(
    private val activity: ComponentActivity,
    private val onSelect: (String) -> Unit,
) {
    /** 把说明和七张分类卡片装进正文。 */
    fun bind(parent: LinearLayout) {
        val binding = PageGalaxySettingsMenuBinding.inflate(activity.layoutInflater, parent, false)
        entries.forEach { entry ->
            val item = ItemGalaxySettingsEntryBinding.inflate(activity.layoutInflater, binding.settingsEntries, false)
            val row = item.settingsEntryRow
            row.setTitle(activity.getString(entry.title))
            row.setDescription(activity.getString(entry.hint))
            row.setLeadingIcon(entry.icon)
            L7Components.bindActionRow(row) { onSelect(entry.destination) }
            binding.settingsEntries.addView(item.root)
        }
        parent.addView(binding.root)
    }

    private val entries =
        listOf(
            Entry(
                "settings-vehicle",
                R.string.config_page_title,
                R.string.config_page_entry_hint,
                R.drawable.ic_l7_preferences,
            ),
            Entry(
                "settings-connection",
                R.string.connection_setup,
                R.string.l7_connection_row_hint,
                R.drawable.ic_l7_connection,
            ),
            Entry(
                "settings-general",
                R.string.application_settings_title,
                R.string.application_settings_entry_hint,
                R.drawable.ic_l7_settings,
            ),
            Entry(
                "settings-permissions",
                R.string.permissions_and_connection_help,
                R.string.l7_permissions_row_hint,
                R.drawable.ic_l7_permissions,
            ),
            Entry("settings-debug", R.string.l7_probe_title, R.string.l7_probe_entry_hint, R.drawable.ic_l7_debug),
            Entry("settings-logs", R.string.l7_logs_title, R.string.l7_logs_entry_hint, R.drawable.ic_l7_agreement),
            Entry("settings-about", R.string.about, R.string.l7_about_row_hint, R.drawable.ic_dp_about),
        )

    private data class Entry(
        val destination: String,
        val title: Int,
        val hint: Int,
        val icon: Int,
    )
}
