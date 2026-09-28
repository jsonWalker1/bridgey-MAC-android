package dev.bridgey.android

import android.util.Log
import java.io.File
import java.net.InetAddress
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.filesys.debug.DebugConfigSection
import org.filesys.netbios.server.NetBIOSNameServer
import org.filesys.server.auth.LocalAuthenticator
import org.filesys.server.auth.UserAccount
import org.filesys.server.auth.UserAccountList
import org.filesys.server.auth.acl.DefaultAccessControlManager
import org.filesys.server.auth.ISMBAuthenticator
import org.filesys.server.config.CoreServerConfigSection
import org.filesys.server.config.GlobalConfigSection
import org.filesys.server.config.SecurityConfigSection
import org.filesys.server.config.ServerConfiguration
import org.filesys.server.filesys.DiskDeviceContext
import org.filesys.server.filesys.DiskSharedDevice
import org.filesys.server.filesys.FilesystemsConfigSection
import org.filesys.smb.DialectSelector
import org.filesys.smb.server.SMBConfigSection
import org.filesys.smb.server.SMBServer
import org.filesys.smb.server.SMBSrvSession
import java.util.EnumSet
import org.filesys.smb.server.disk.JavaNIODiskDriver
import org.springframework.extensions.config.element.GenericConfigElement

private const val TAG = "SmbPoc"

/**
 * BRIDGEY SMB SERVER FEASIBILITY POC (isolated, throwaway - see SmbPocActivity/SmbPocService).
 * NOT wired into any production code path. Delete this file + SmbPocService.kt + SmbPocActivity.kt,
 * the four `implementation(...)` lines and the JitPack repo block to fully remove.
 *
 * Wraps org.filesys/jfileserver (buttercookie42's Android-friendly fork of the old JLAN/JFileServer
 * project - see the audit note that led to this POC: the free/LGPL core is SMB1-only, there is no
 * free pure-Java SMB2/3 server implementation anywhere). Exposes exactly ONE directory
 * (the app's own `getExternalFilesDir(null)` - never any broader filesystem path) as a single SMB
 * share, authenticated, bound to the device's current Wi-Fi address only.
 *
 * CANNOT bind the standard SMB ports (139/445) or NetBIOS ports (137/138) - those are < 1024 and
 * require CAP_NET_BIND_SERVICE, which a normal (non-root) Android app does not have. This POC shifts
 * every port well above 1024, matching the same workaround real rootless Android SMB-server apps use
 * (confirmed against SimbaDroid's own source, which does the same thing and additionally uses a
 * ROOTED iptables NAT redirect to reach the standard ports - deliberately NOT replicated here, since
 * requiring root defeats the point of this feasibility question).
 */
internal object SmbPocServer {
    const val SHARE_NAME = "Bridgey"
    const val SERVER_NAME = "BRIDGEY-S23"
    const val USERNAME = "bridgey"
    const val PASSWORD = "bridgey123"

    // Non-privileged ports only - see class doc comment. Chosen to avoid colliding with the
    // already-frozen KVM ports (42458 control, plus whatever ephemeral ports video/input pick) and
    // with SimbaDroid's own defaults, in case both are ever run side by side during testing.
    const val SMB_PORT = 44445
    const val NETBIOS_NAME_PORT = 44137
    const val NETBIOS_DATAGRAM_PORT = 44138
    const val NETBIOS_SESSION_PORT = 44139

    @Volatile private var config: ServerConfiguration? = null

    val isRunning: Boolean get() = config != null

    fun shareDirectory(context: android.content.Context): File {
        // App-private external storage - world-readable path but owned by this app, requires no
        // MediaStore ceremony and no READ/WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE
        // permission on any supported Android version (unaffected by scoped storage - it was never
        // covered by it). This directly satisfies "expose a dedicated Bridgey-controlled directory,
        // never the general filesystem."
        val dir = File(context.getExternalFilesDir(null), "SmbPocShare")
        if (!dir.exists()) dir.mkdirs()
        for (sub in listOf("Movies", "Photos", "Shared")) File(dir, sub).mkdirs()
        return dir
    }

    @Synchronized
    fun start(context: android.content.Context, bindAddress: InetAddress): Result<Unit> {
        if (config != null) return Result.success(Unit)
        return runCatching {
            val shareDir = shareDirectory(context)
            val cfg = ServerConfiguration(SERVER_NAME)

            // TEMPORARY DIAGNOSTIC (POC only): route jfileserver's own Debug interface to
            // java.util.logging, which Android forwards to logcat, so a failed negotiate/session-setup
            // is actually visible instead of silently returning an opaque NTSTATUS.
            val debugSection = DebugConfigSection(cfg)
            // Android redirects stdout/stderr to logcat (tag System.out/System.err) by default -
            // simpler and, unlike JDKLoggingDebug in this fork, doesn't NPE on a missing config child.
            debugSection.setDebug("org.filesys.debug.ConsoleDebug", GenericConfigElement("debug"))

            val core = CoreServerConfigSection(cfg)
            core.setThreadPool(4, 20)
            core.setMemoryPool(
                intArrayOf(256, 4096, 16384, 65536),
                intArrayOf(20, 20, 5, 5),
                intArrayOf(100, 100, 50, 50),
            )

            GlobalConfigSection(cfg)

            // BRIDGEY SMB POC FINDING: NTLM password validation silently failed ("access denied" for
            // the correct password) until BouncyCastle is actually registered as a JCE Security
            // provider - Android's default javax.crypto providers don't cover everything jfileserver's
            // PasswordEncryptor needs (DES/MD4-based LM/NTLM hashing).
            // BRIDGEY SMB POC FINDING: Android ships its OWN stripped-down provider already
            // registered under the name "BC" (missing MD4, among other algorithms, for
            // security-policy reasons) - Security.addProvider(BouncyCastleProvider()) is a silent
            // no-op when a provider with that name already exists. Must explicitly remove Android's
            // stub and insert the real upstream BC at the front of the search order.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            runCatching { java.security.MessageDigest.getInstance("MD4") }
                .onSuccess { Log.i(TAG, "MD4 digest available via provider ${it.provider.name}") }
                .onFailure { Log.e(TAG, "MD4 digest NOT available - NTLM auth will silently fail", it) }

            val security = SecurityConfigSection(cfg)
            security.setJCEProvider("org.bouncycastle.jce.provider.BouncyCastleProvider")
            val users = UserAccountList()
            users.addUser(UserAccount(USERNAME, PASSWORD))
            security.setUserAccounts(users)
            security.setAccessControlManager(DefaultAccessControlManager())

            val filesystems = FilesystemsConfigSection(cfg)
            val diskDriver = JavaNIODiskDriver()
            val driverConfig = GenericConfigElement("driver")
            val localPath = GenericConfigElement("LocalPath")
            localPath.setValue(shareDir.absolutePath)
            driverConfig.addChild(localPath)
            val diskContext = diskDriver.createContext(SHARE_NAME, driverConfig) as DiskDeviceContext
            diskContext.setFilesystemAttributes(0)
            val share = DiskSharedDevice(SHARE_NAME, diskDriver, diskContext)
            filesystems.addShare(share)

            val smb = SMBConfigSection(cfg)
            smb.setServerName(SERVER_NAME)
            smb.setDomainName("WORKGROUP")
            // BRIDGEY SMB POC FINDING: DialectSelector.enableAll() advertises SMB2/3 dialect STRINGS
            // that this free/LGPL build has no actual protocol handler for - any modern client (macOS,
            // Windows, pysmb) negotiates SMB 2.002 successfully, then the server logs "No protocol
            // handler for dialect - Unknown" and hard-closes the connection with STATUS_INVALID_PARAMETER
            // (confirmed live via ConsoleDebug NEGOTIATE tracing). Restricting to the SMBv1 dialect
            // group is what actually makes any client succeed at all.
            val dialects = DialectSelector()
            dialects.enableGroup(DialectSelector.DialectGroup.SMBv1)
            smb.setEnabledDialects(dialects)
            val authenticator = LocalAuthenticator()
            authenticator.setConfig(cfg)
            authenticator.setAccessMode(ISMBAuthenticator.AuthMode.USER)
            authenticator.setAllowGuest(false)
            smb.setAuthenticator(authenticator)
            smb.setTcpipSMB(true)
            smb.setNetBIOSSMB(true)
            smb.setTcpipSMBPort(SMB_PORT)
            smb.setNameServerPort(NETBIOS_NAME_PORT)
            smb.setDatagramPort(NETBIOS_DATAGRAM_PORT)
            smb.setSessionPort(NETBIOS_SESSION_PORT)
            smb.setSMBBindAddress(bindAddress)
            smb.setNetBIOSBindAddress(bindAddress)
            smb.setSessionDebugFlags(EnumSet.of(SMBSrvSession.Dbg.NEGOTIATE, SMBSrvSession.Dbg.ERROR, SMBSrvSession.Dbg.STATE, SMBSrvSession.Dbg.TREE))

            cfg.addServer(NetBIOSNameServer(cfg))
            cfg.addServer(SMBServer(cfg))
            for (i in 0 until cfg.numberOfServers()) cfg.getServer(i).startServer()

            config = cfg
            Log.i(TAG, "SMB POC server started: \\\\$SERVER_NAME:$SMB_PORT\\$SHARE_NAME bound to ${bindAddress.hostAddress}, share dir=${shareDir.absolutePath}")
            Unit
        }.onFailure { Log.e(TAG, "SMB POC server failed to start", it) }
    }

    @Synchronized
    fun stop() {
        val cfg = config ?: return
        for (i in 0 until cfg.numberOfServers()) {
            runCatching { cfg.getServer(i).shutdownServer(true) }
                .onFailure { Log.w(TAG, "SMB POC server shutdown error", it) }
        }
        config = null
        Log.i(TAG, "SMB POC server stopped")
    }
}
