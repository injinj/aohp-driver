package org.aohp.driver.binder

import android.util.Log
import com.android.internal.aohp.IAohpVirtualDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VirtualDisplayInfo(val displayId: Int, val name: String, val width: Int, val height: Int, val raw: String)

/** Read-only status over IAohpVirtualDisplay (v1: list only). */
class VirtualDisplayService {
    companion object { const val SERVICE_NAME = "aohp_virtual_display" }

    @Volatile private var svc: IAohpVirtualDisplay? = null

    @Synchronized
    fun service(): IAohpVirtualDisplay? {
        svc?.let { if (it.asBinder().isBinderAlive) return it else svc = null }
        val b = ServiceManagerCompat.getService(SERVICE_NAME) ?: return null
        return IAohpVirtualDisplay.Stub.asInterface(b).also { svc = it }
    }

    suspend fun snapshotJson(): Result<String> = withContext(Dispatchers.IO) {
        val s = service() ?: return@withContext Result.failure(IllegalStateException("aohp_virtual_display not available"))
        runCatching { s.getDisplayRuntimeSnapshotJson(null) ?: "{}" }
            .onFailure { Log.w(ContainerService.TAG, "vd snapshot failed", it) }
    }

    /** Best-effort parse: looks for an array of display objects anywhere at top level. */
    suspend fun listDisplays(): Result<List<VirtualDisplayInfo>> = snapshotJson().map { raw ->
        val out = mutableListOf<VirtualDisplayInfo>()
        fun add(o: JSONObject) {
            val id = o.optInt("displayId", o.optInt("id", -1))
            out += VirtualDisplayInfo(id, o.optString("name", ""), o.optInt("width", o.optInt("w", 0)),
                o.optInt("height", o.optInt("h", 0)), o.toString())
        }
        runCatching {
            val top = JSONObject(raw)
            val keys = top.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = top.opt(k)
                if (v is JSONArray) for (i in 0 until v.length()) v.optJSONObject(i)?.let(::add)
            }
        }.recoverCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
        }
        out
    }
}
