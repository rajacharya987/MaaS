package com.wiperspace.maas.model

import com.termux.terminal.TerminalSession

data class TerminalTab (
    val id: String,
    val title: String,
    var session : TerminalSession? = null,
    var sessionKey : Int = 0
)
