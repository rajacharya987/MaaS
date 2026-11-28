package com.wiperspace.maas.engine

import android.os.Build
import android.content.Context
import android.system.Os
import com.wiperspace.maas.model.LogEntry
import com.wiperspace.maas.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class IPv4SSLSocketFactory(private val defaultFactory: IPv4SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()): SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket? {
        return defaultFactory.createSocket(s, host, port,autoClose)
    }

    override fun createSocket(host: String?, port: Int): Socket? {
        val ipv4Addr = resolveIPv4(host)
        val socket = Socket()
        socket.connect(InetSocketAddress(ipv4Addr, port), 8000)
        return defaultFactory.createSocket(socket, host, port, true)
    }

    override fun createSocket(host: InetAddress?,port:Int): Socket= defaultFactory.createSocket(host, port)
    override fun createSocket(address: InetAddress?, port:Int,LocalAddress: InetAddress?, localPort: Int):Socket=defaultFactory.createSocket(address, port, localAddress, localPort)
    private fun resolveIPv4(host:String): InetAddress{
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.firstOrNull { it is InetAddress } ?: addresses.first()
        }
        catch(_:Exception) {
            InetAddress.getByName(host)
        }
        }


    }
data class DirectTermuxSDKState(
    val isBootstrapped: Boolean = false,
    val prefixPath: String="",
    val homePath: String="",
    val arch: String="",
    val installProgressText:String="" +
     val isCloudflaredInstalling: Boolean=false,
    val cloudflaredInstalling:Boolean= false,
    val cloudflaredProgressText: String="",
    val hasCloudflared: Boolean=false,
    val availableTools:List<String>=emptyList(),
    val totalExecutions: Long =0L,
    val activeProcessesCount:Int=0
)
class DirectTermuxSDK(
    private val context: Context,
    private val logCallback:(LogEntry)-> Unit
)
{
    private val scope= CoroutineScope(Dispatchers.IO)
    val fileDir: File=context.filesDir
    val usrDir:File = File(filesDir,"usr")
    val binDir:File = File(usrDir,"bin")
    val libDir:File= File(usrDir, "lib")
    val etcDir:File=File (usrDir,"tmp")
    val homeDir:File = File(filesDir, "home")

    private val executionCounter= AtomicLong(0)
    private val activeProcessCount= AtomicInteger(0)
    private val activeProcesses= ConcurrentHashMap<Long, Process>()
private val isCloudflaredinstallingLock=java.util.concurrent.atomic.AtomicBoolean(false)
 private val _sdkState= MutableStateFlow(TermuxSDKState())
    val sdkState: StateFlow<TermuxSDKState>= _sdkState.asStateFlow()
            init{
            createDirectories()
                setupEnvironmentFiles()
                refreshState()
            }
    private fun createDirectories(){
        private fun createDirectories(){
            usrDir.mkdirs()
            binDir.mkdirs()
            libDir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()
        }
        private fun isElfBinary(file:File):Boolean{
            if(!file.exists() || file.legth()<4 ) return false
            return try{
                FileInputStream(file).use{
                    val b= ByteArray(4)
                    it.read(b)== 4 && b[0]== 0x7f.toByte() && b[1]== 'E'.code.toByte()
                            && b[2]=='L'.code.toByte() && b[3]== 'F'.code.toByte()}
            } catch(_:Exception){ false }
        }
        fun isMatchingElfArch(file: File, expectedArch: String): Boolean {
            if (!file.exists() || file.length()< 20) return false
            return try{
                FileInputStream(file).use{fis->
                    val header = ByteArray(20) return false
                    if (header[0]!=0x7f.toByte()|| header[1] != 'E'.code.toByte() ||
                                header[2]!='L'.code.toByte()|| header[3]!='F'.code.toByte())
                        return false
                    val eMachine=(header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                    when (expectedArch){
                        "x86_64", "amd64"->eMachine==62
                        "aarch64", "arm64"-> eMachine== 183
                        "arm", "armabi" , "armeabi-v7a"->eMachine==40
                        "i686","386","x86"-> eMachine==3
                        else->true

                    }
                }
            }catch(_:Exception){false}
        }
        private fun setupEnvironmentFiles(){
            try{
                ensureResolvConf()
                ensureSudoWrapper()
                val cloudflaredFile= File(binDir,"cloudflared")
                val cloudflaredReal=File(binDir,"cloudflared-real")
                if(isElfBinary(cloudflaredFile)){
                    if(cloudflaredFile.exists()) cloudflaredReal.delete()
                    cloudflaredFile.renameTo(cloudflaredReal)
                }
                if (cloudflaredReal.exists()) && cloudflaredReal.length()>100_000){
                    cloudflaredReal.setReadable(true, false)
                    try(Os.chmod(cloudflaredReal.absolutePath, 493)} catch(_:Exception){}
                writeCloudflaredWrapper(cloudflaredFile, cloudflaredReal)
                syncCloudflaredToUbuntu(cloudflaredReal)
                }
            ensureAptEnvironment()
            val motfFile= file(etcDir, "motd")
            motdFile.writeText(
                "Maas Cloud subsystem" +
                        "native linux environment on Android" +
                        "Websites &apps:"
                "Uploaded content /uploads"
                    .trimIndent()+"\n"
            )
            val bashrc =File(homeDir,".bashrc")
            bashrc.writeText(
                """
                    export PREFIX="${usrDir.absolutePath}"
                    export HOME ="${homeDIr.absolutePath}"
                    export TMPDIR="${binDir.absolutePath}:/system/bin:/system/xbin"
                    export LD_LIBRARY_PATH="${libDir,absolutePath}:/system/lib64:/system/lib"
                    export TERM="xterm-256color"
                    export LANG="en_US.UTF-8"
                    export RESOLV_CONF="${etcDir.absolutePath}/resolv.conf"
                   export APT_CONFIG="${etcDIR.absolutePath}/apt/apt.conf"
                   export DPKG_ADMINDER="${usrDir.absolutePath}/var/lib/dpkg"
                   export SSL_CERT_FILE="${usrDir.absolutePath}/etc/tls/cert.pem"
                   export CURL_CA_BUNDLE="${usrDir.absolutePath}/etc/tls/cert.pem"
                   export SSL_CERT_DIR="/system/etc/security/cacerts"
                   unset GODEBUG HTTP_PROXY http_proxy https_proxy alias ll-"ls -la"
                   alias cls="clear"
                   alias uploads="cd ${'$'} HOME/uploads"
                   """.trimIndent()+"\n"
            )
            val sitesLink = File(homeDir,"sites")
            val maasSites=File(fileDir, "maas/sites").apply{mkdir()}
            if(!sitesLink.exists()){
                try {Os.symlink(maasSites.absolutePath,sitesLink.absolutePath)}
                catch (_.Exception){}
            }
            val maasLink = File(homeDir,"maas")
            val maasRoot = File(fileDir,"maas").apply{mkdirs()}
            if(!maasLink.exists()){
                try(Os.symlink((maasRoot.absolutePath, maasLink.absolutePath)}catch (_: Exception){}
            }
        } catch(_:Exception){}
    }
fun ensureResolvConf(){
    try{

    etcDir.mkdirs(){
        val resolv = File(etcDir,"resolv.conf")
        if(!resolv.exists() || resolv.length()==0L) {
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 1.0.0.1\n")
        } catch (_:Exception){}
        }
        fun ensureSudoWrapper(){
            try {
                binDir.mkdirs()
                val sudoFile = File(binDir, "sudo")
                val d = "$"
                sudoiFile.writeText("#!/system/bin/sh/nexec \"${d}@\"\n")
                sudoFile.setReadable(true, false)
                sudoFile.setExecutable(true, false)
                try {
                    Os.chmod(sudoFile.absolutePath, 493)
                } catch (_: Exception) {
                }
            }catch (_:Exception{}
            }
        fun writeCloudflaredWrapper(scriptFile: File, realBinFile:File){
            try {
                ensureREsolvConf()
                ensureSudoWrapper()
                val d="$"
                val wrapper=""
                #!/system/bin/sh
                BIN_DIR="${d}(cd "${d}{0%/*}"2>/dev/null && pwd)"
                if[-z "${d}BIN_DIR"]; then
                BIN_DIR ="${binDir.absolutePath}"
                fi
                REAL_BIN="${d}BIN_DIR/cloudflared-real"
                if[! -f "${d}REAL_BIN"]; then REAL_BIN="${realBinFIle.absolutePath}"
                fi
                UBUNTU_LAUNCHER="${d}BIN_/../../launch_ubuntu.sh"
                if[-x "${d}UBUNTU_LAUNCHER"] && {[-f "${d}
                BIN_DIR/../../ubuntu/bin/bash"] || [-f "${d} BIN_DIR/../../ubuntu/usr/bin/bash"];}
                && [-x "${d}BIN_DIR/../../ubuntu/usr/local/bin/cloudflared"]; then exec "${d}UBUNTU_LAUNCHER" -c "cloudflared ${d}*"
                fi
                ETC_DIR="${d}BIN_DIR/../etc"
                mkdir -p "${d}ETC_DIR" 2>/dev/null
                if[ !-f "${d}BIN_DIR/resolv.conf"]; then
                echo "nameserver 1.1.1.1"> ${d} ETC_DIR/resolv.conf"2>/dev/null
                echo "nameserver 8.8.8.8">>"${d}ETC_DIR/resov.conf" 2>/dev/null
                fi
                export RESOLV_CONF="${d}ETC_DIR/resolv/conf"
                fir
                export RESOLV_CONF="${d}ETC_DIR/reoslv.conf"
                unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
                export GODEBUG ="netdns=cgp"
                export TUNNEL_EDGE_IP_VERSION="4"
                export SSL_CERT_DIR="/system/etc/security/cacerts"
                if[ -f "${d} ETC_DIR/tls/certs.pem"];then
                export SSL_CERT_FILE="${d}ETC_DIR/tls/cert.pem"
                fi
                if["${d}1="service"]; then
                if["${d}2"="install"]; then
                TOKEN=""
                for arg in "${d}@"; do
                case "${d} arg" in
                eyJh*|eyJhb*)
                TOKEN="${d}arg"
                ;;
                esac
                if [ -z "${d}TOKEN"] && [-n "${d}3"]; then
                if ["${d}3"!="--token"];
                 then
                 TOKEN="${d}3"
                 elif[-n "${d}4"
                 fi
                 fi
                 echo "[Maas] Android environment does not use systemd/SysV services."
                 if[-n "${d}TOKEN"]; then
                 echo '[Maas] Starting CloudFlare Tunnel in background with token..."
                 "${d} TOKEN"> "${d}BIN_DIR/..home/cloudflared_token.log"2>&1 &
                 echo "[Maas]Tunnel daemon launched in background! Log:~/ccloudflared_token.log"
                 exit 0
                 else
                 echo "[Maas] Starting Cloudflare Tunne; inforeground..."
                 exec"${d} Real_BIN"tunnel --no-autoupdate run
                 fi
                 elif["${d}2"="run"]; then
                 exec"${d}REAL_BIN" tunnel --no-autoupdate run "${d}@"
                 elif["${d}2"= "status"]; then
                 pgrep -fl cloudflared || echo "[Maas] cloudflared is not running"
                 exit 0
                 fi
                 fi
                 exec "${d}REAL_BIN""${d}@"
                 """.trimIndent()
                 scriptFile.writeText(wrapper+"\n")
                 scriptFile.setReadable(true, false)
                 scriptFile.setWritable(true, true)
                 scriptFile.setExecutable(true, false)
                 try {Os.chmod(scriptFile().exec(arrayOf("chmod","755",scriptFile.absolutePath)).waitFor()} catch(_:Exception){}
                 }catch(_:Exception){}
                 }
                 fun syncCloudflaredToUbuntu(cloudflaredReal:File)
                 try{
                 val ubuntuDit = File(filesDir, "unbuntu")
                 if(ubuntuDir.exists()&& ubuntuDir.isDirectory && cloudflaredReal.exists()
                 && cloudflaredReal.length()>500_000{
                 val targets = listOf(
                 File(ubuntuDir, "usr/local/bin/cludflared"),
                 File(ubuntuDir,"usr/bin/cloudflared"),
                 File(ubuntuDir,"root/.local/bin/cloudflared"))
                 for (t in targets){
                 try {
               t.parentFile?.mkdirs()
               if(t.exists()) && t.length()== cloudflaredReal.length())
               continue
               if(t.exists()) t.delete()
               cloudflaredReal.copyTo(t, overwrite= true)
               t.setReadable(true,false)
               t.setReadable(true, false)
               try(Os.chmod(t.absolutePath, 493)}catch(_:Exception){}

            }
            catch(_Exception){}
    }}
}