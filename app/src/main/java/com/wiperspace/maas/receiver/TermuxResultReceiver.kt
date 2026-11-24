package com.wiperspace.maas.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.wiperspace.maas.engine.TermuxBridge

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val bundle: Bundle? = intent?.getBundleExtra("com.termux.result")
            ?: intent?.getBundleExtra(("result"))
            ?: intent?.extras

        val stdout = bundle?.getString("stdout")
            ?: bundle?.getString("com.termux.RUN_COMMAND_STDOUT")
            ?: ""
        val stderr = bundle?.getString("stderr")
            ?: bundle?.getString("com.termux.RUN.COMMAND.STDERR")
            ?: ""
        val exitCode = bundle?.getInt("exitcode",-1)?: -1
        val errmsg = bundle?.getString("errmsg")?: ""

        TermuxBridge.onCommandResultReceived(stdout, stderr, exitCode, errmsg)

    }

    companion object{
        const val ACTION_TERMUX_RESULT = "com.wiperspace.maas.TERMUX_RESULT"

    }

}