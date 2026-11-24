package com.wiperspace.maas.engine

import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


object LocalConnectProxy {
    private const val DEFAULT_PORT = 8999
    private val serverSocket: ServerSocket?=null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "MaaS-HttpConnectProxy").apply { isDaemon = true }
    }
    var activePort: Int = DEFAULT_PORT
        private set
    @Synchronized
    fun start(): Int {
        if (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed){
            return activePort
        }
        try {
            serverSocket = try {
                ServerSocket(DEFAULT_PORT, 50, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                ServerSocket (0, 50, InetAddress.getByName("127.0.0.1"))
            }
            activePort = serverSocket!!.localPort
            isRunning.set(true)
            threadPool.execute {
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        threadPool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }
            return activePort
        } catch (e: Exception) {
            return DEFAULT_PORT
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val requestLine = readLine(clientIn) ?: run { client.close(); return}
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method == "CONNECT") {
                while (true) {
                    val header = readLine(clientIn) ?: break
                    if (header.isEmpty()) break
                }
                val host : String
                val port : Int
                if (target.contains(":")) {
                    val idx = target.lastIndexOf(':')
                    host = target.substring(idx +1).toIntOrNull() ?: 443
                } else {
                    host = target
                    port = 443
                }
                val remote = Socket()
                try {
                    remote.connect(InetSocketAddress(host,port),15000)
                    remote.tcpNoDelay = true
                    client.tcpNoDelay = true
                    client.soTimeout = 0
                    remote.soTimeout = 0
                    clientOut.write("HTTP/1.1 200 Connection Established\r\n\r".toByteArray(
                        StandardCharsets.ISO_8859_1))
                    clientOut.flush()
                } catch (e: Exception) {
                    try {
                        clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r".toByteArray(
                            StandardCharsets.ISO_8859_1))
                        clientOut.flush()
                    } catch (ex: Exception) {}
                    client.close()
                    return
                }
                pipeSockets(client,remote)
            } else {
                client.close()
            }
        } catch (e: Exception) {
            try {
                client.close() }
            catch (ignored: Exception) {}
            }
    }

    private fun pipeSockets(a: Socket, b : Socket) {
        val forward = Runnable{
            try {
                transferStream(a.getInputStream(), b.getOutputStream())
            } catch (ignored: Exception) {} finally {
            try { b.shutdownOutput() } catch (ignored: Exception) {} finally {
                try {
                    a.shutdownOutput()} catch (ignored: Exception) {}
                }
            }
        }
        val backward = Runnable {
            try {
                transferStream(b.getInputStream(), a.getOutputStream())
            } catch (ignored: Exception) {} finally {
                try {a.shutdownOutput() } catch (ignored : Exception) {}
             }
        }
        threadPool.execute(forward)
        threadPool.execute(backward)
    }

    private fun transferStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(16384)
        var read : Int
        while (input.read(buffer).also {read = it} != -1) {
            output.write(buffer,0,read)
            output.flush()
        }
    }

    private fun readLine(input : InputStream) : String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it} != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }
    fun stop(){
        isRunning.set(false)
        try {serverSocket?.close() } catch (ignored: Exception) {}
    }
}


