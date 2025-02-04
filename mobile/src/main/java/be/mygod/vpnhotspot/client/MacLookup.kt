package be.mygod.vpnhotspot.client

import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.room.AppDatabase
import be.mygod.vpnhotspot.room.macToString
import be.mygod.vpnhotspot.widget.SmartSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * This class generates a default nickname for new clients.
 */
object MacLookup {
    class UnexpectedError(val mac: Long, val error: String) : JSONException("") {
        private fun formatMessage(context: Context) =
                context.getString(R.string.clients_mac_lookup_unexpected_error, mac.macToString(), error)
        override val message get() = formatMessage(app.english)
        override fun getLocalizedMessage() = formatMessage(app)
    }

    private val macLookupBusy = mutableMapOf<Long, Pair<HttpURLConnection, Job>>()
    private val countryCodeRegex = "([A-Z]{2})\\s*\$".toRegex() // http://en.wikipedia.org/wiki/ISO_3166-1

    @MainThread
    fun abort(mac: Long) = macLookupBusy.remove(mac)?.let { (conn, job) ->
        job.cancel()
        if (Build.VERSION.SDK_INT >= 26) conn.disconnect() else GlobalScope.launch(Dispatchers.IO) { conn.disconnect() }
    }

    @MainThread
    fun perform(mac: Long, explicit: Boolean = false) {
        abort(mac)
        val conn = URL("https://macvendors.co/api/" + mac.macToString()).openConnection() as HttpURLConnection
        macLookupBusy[mac] = conn to GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(response).getJSONObject("result")
                obj.opt("error")?.also { throw UnexpectedError(mac, it.toString()) }
                val company = obj.getString("company")
                val match = extractCountry(mac, response, obj)
                val result = if (match != null) {
                    String(match.groupValues[1].flatMap { listOf('\uD83C', it + 0xDDA5) }.toCharArray()) + ' ' + company
                } else company
                AppDatabase.instance.clientRecordDao.upsert(mac) {
                    nickname = result
                    macLookupPending = false
                }
            } catch (e: IOException) {
                Timber.d(e)
                if (explicit) SmartSnackbar.make(e).show()
            } catch (e: JSONException) {
                if ((e as? UnexpectedError)?.error == "no result") {
                    // no vendor found, we should not retry in the future
                    AppDatabase.instance.clientRecordDao.upsert(mac) { macLookupPending = false }
                } else Timber.w(e)
                if (explicit) SmartSnackbar.make(e).show()
            }
        }
    }

    private fun extractCountry(mac: Long, response: String, obj: JSONObject): MatchResult? {
        countryCodeRegex.matchEntire(obj.optString("country"))?.also { return it }
        val address = obj.optString("address")
        if (address.isBlank()) return null
        countryCodeRegex.find(address)?.also { return it }
        Timber.w(UnexpectedError(mac, response))
        return null
    }
}
