package com.wiperspace.maas.model

data class ContainerProcess(
    val pid: Int,
    val ppid: Int = 0,
    val name: String,
    val command : String,
    val port : Int? = null,
    val cpuTime : String = "",
    val isKillable : Boolean = true
)