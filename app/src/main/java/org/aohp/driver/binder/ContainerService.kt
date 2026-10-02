package org.aohp.driver.binder

import android.os.ParcelFileDescriptor
import android.util.Log
import com.android.internal.aohp.IAohpContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}

data class ServiceInfo(
    val serviceId: String,
    val pid: Int,
    val alive: Boolean,
    val startTime: Long,
    val uptimeSec: Long,
    val command: String,
)

data class CgroupDiag(
    val v2Detected: Boolean,
    val enabled: Boolean,
    val path: String,
    val memoryMaxConfigured: String,
)

data class Diagnose(
    val container: String,
    val template: String,
    val rootfsExists: Boolean,
    val npmCacheHostDir: Boolean,
    val openclawDevHostDir: Boolean,
    val cgroup: CgroupDiag?,
    val raw: String,
)

data class Usage(
    val cgroupEnabled: Boolean,
    val cgroupPath: String,
    val memoryCurrent: Long,
    val memoryMax: String,
    val memoryPeak: Long,
    val cpuUsageUsec: Long,
    val pidsCurrent: Int,
)

/**
 * Typed Kotlin wrapper over IAohpContainer. All calls are suspend and run on
 * Dispatchers.IO; JSON field names mirror the stock AOHPAgentDriver parsers
 * (AohpContainerClient / AohpServiceInfo / CgroupUsage) and containerd.
 */
class ContainerService {
    companion object {
        const val TAG = "AohpDriver"
        const val SERVICE_NAME = "aohp_container"
        const val TEMPLATE_DIR = "/system/etc/aohp/rootfs-templates"
    }

    @Volatile private var svc: IAohpContainer? = null

    @Synchronized
    fun service(): IAohpContainer? {
        svc?.let { if (it.asBinder().isBinderAlive) return it else svc = null }
        val b = ServiceManagerCompat.getService(SERVICE_NAME) ?: return null
        return IAohpContainer.Stub.asInterface(b).also { svc = it }
    }

    val available: Boolean get() = service() != null

    private suspend inline fun <T> io(crossinline block: (IAohpContainer) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val s = service() ?: return@withContext Result.failure(
                IllegalStateException(ServiceManagerCompat.lastError ?: "aohp_container not available"))
            runCatching { block(s) }.onFailure { Log.e(TAG, "binder call failed", it) }
        }

    suspend fun listContainers(): Result<List<String>> = io { it.listContainers()?.toList() ?: emptyList() }

    /** Empty string on success, else daemon error message. */
    suspend fun createContainer(name: String, template: String): Result<String> =
        io { it.createContainer(name, template) ?: "" }

    suspend fun destroyContainer(name: String): Result<Boolean> = io { it.destroyContainer(name) }
    suspend fun resetContainer(name: String): Result<Boolean> = io { it.resetContainer(name) }

    suspend fun execSync(name: String, cmd: String, timeoutMs: Int): Result<ExecResult> = io {
        val json = it.execSync(name, cmd, timeoutMs)
        val o = JSONObject(json ?: "{}")
        ExecResult(o.optInt("exitCode", -1), o.optString("stdout", ""), o.optString("stderr", ""))
    }

    suspend fun openShell(name: String): Result<ParcelFileDescriptor> =
        io { it.openShell(name) ?: throw IllegalStateException("openShell returned null") }

    suspend fun templateInfo(name: String): Result<String> = io { it.templateInfo(name) ?: "" }

    suspend fun startService(name: String, id: String, cmd: String): Result<Long> = io { it.startService(name, id, cmd) }
    suspend fun stopService(name: String, id: String): Result<Boolean> = io { it.stopService(name, id) }

    suspend fun listServices(name: String): Result<List<ServiceInfo>> = io {
        val arr = JSONArray(it.listServices(name) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ServiceInfo(
                o.optString("serviceId", ""), o.optInt("pid", -1), o.optBoolean("alive", false),
                o.optLong("startTime", 0), o.optLong("uptimeSec", 0), o.optString("command", ""))
        }
    }

    suspend fun serviceLog(name: String, id: String, tailBytes: Int): Result<String> =
        io { it.serviceLog(name, id, tailBytes) ?: "" }

    suspend fun getUsage(name: String): Result<Usage> = io {
        val o = JSONObject(it.getUsage(name) ?: "{}")
        Usage(o.optBoolean("cgroupEnabled", false), o.optString("cgroupPath", ""),
            o.optLong("memoryCurrent", 0), o.optString("memoryMax", ""), o.optLong("memoryPeak", 0),
            o.optLong("cpuUsageUsec", 0), o.optInt("pidsCurrent", 0))
    }

    suspend fun diagnose(name: String): Result<Diagnose> = io {
        val raw = it.diagnose(name) ?: "{}"
        val o = JSONObject(raw)
        val cg = o.optJSONObject("cgroup")?.let { c ->
            CgroupDiag(c.optBoolean("cgroupV2Detected", false), c.optBoolean("cgroupEnabled", false),
                c.optString("cgroupPath", ""), c.optString("memoryMaxConfigured", ""))
        }
        Diagnose(o.optString("container", name), o.optString("template", ""), o.optBoolean("rootfsExists", false),
            o.optBoolean("npmCacheHostDir", false), o.optBoolean("openclawDevHostDir", false), cg, raw)
    }

    /** Template names = *.tar.gz in the (world-readable) system template dir. */
    fun listTemplates(): List<String> =
        File(TEMPLATE_DIR).list()?.filter { it.endsWith(".tar.gz") }?.map { it.removeSuffix(".tar.gz") }?.sorted()
            ?: emptyList()
}
