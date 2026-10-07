package com.os4.musiccover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.screen.features.TransitDemo
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 高德's bus and subway trip - its island (AmapTransitIsland) and its lock screen page
 * (AmapTransitScene) - as a screen of its own, with the one switch for both.
 *
 * See [CoverActivity] for why this is an Activity rather than a page inside the features tab.
 */
class TransitActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings.load(this)
        val theme = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        setContent {
            AppTheme(themeMode = theme) { TransitPage(settings.isBlurEnabled, resumes, ::finish) }
        }
    }

    // See CoverActivity: asked again each time the screen comes back to the front.
    private var resumes by mutableIntStateOf(0)
    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        if (resumedOnce) resumes++ else resumedOnce = true
    }
}

@Composable
private fun TransitPage(blur: Boolean, refreshKey: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    var asked by remember { mutableStateOf(false) }
    // See ShadePageView: a setting the module did not take greys the page until it answers again.
    val lost by ModuleBridge.lost.collectAsState()
    LaunchedEffect(lost) {
        if (lost > 0) {
            module = module.copy(alive = false)
            asked = false
        }
    }
    LaunchedEffect(refreshKey, lost) {
        module = ModuleBridge.queryAlive(context)
        asked = true
    }

    PageScaffold(title = stringResource(R.string.features_transit_title), isBlurEnabled = blur,
        onBack = onBack) {
        item {
            // What the trip's island does, played on a drawn phone, before the switch (as the
            // islands' own page has it).
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                TransitDemo(Modifier.padding(top = 16.dp))
            }
        }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                // Only an answer can put this on (ShadePageView says why).
                SwitchPreference(title = stringResource(R.string.transit_enabled),
                    summary = if (asked && !module.alive) {
                        stringResource(R.string.home_status_inactive_hint)
                    } else {
                        null
                    },
                    checked = module.alive && module.transit, enabled = module.alive,
                    onCheckedChange = {
                        module = module.copy(transit = it)
                        ModuleBridge.setTransit(context, it)
                    })
            }
        }
    }
}
