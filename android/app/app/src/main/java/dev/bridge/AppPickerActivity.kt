package dev.bridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Lists launchable apps; the chosen one is started on the hidden display and streamed to the TV. */
class AppPickerActivity : Activity() {
    data class Entry(val label: String, val component: ComponentName) { override fun toString() = label }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pm = packageManager
        val entries = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { Entry(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
            .sortedBy { it.label.lowercase() }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        list.setOnItemClickListener { _, _, i, _ ->
            if (!BridgeService.adbReady) { Toast.makeText(this, "Connect to Wireless debugging first", Toast.LENGTH_LONG).show(); return@setOnItemClickListener }
            ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java)
                .putExtra(BridgeService.EXTRA_MODE, BridgeService.MODE_VIRTUAL).putExtra(BridgeService.EXTRA_COMPONENT, entries[i].component.flattenToString()))
            Toast.makeText(this, "Open http://" + BridgeService.localIp() + ":" + BridgeService.PORT + " in the TV browser", Toast.LENGTH_LONG).show()
            finish()
        }
        setContentView(list)
    }
}
