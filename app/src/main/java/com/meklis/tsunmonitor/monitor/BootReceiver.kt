package com.meklis.tsunmonitor.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meklis.tsunmonitor.data.InverterStore

/** Re-arms the monitor alarm after a reboot or app update if monitoring is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (InverterStore(context).monitorEnabled) Monitor.enable(context)
    }
}
