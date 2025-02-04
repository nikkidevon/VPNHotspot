package be.mygod.vpnhotspot.util

import android.annotation.SuppressLint
import android.content.*
import android.net.InetAddresses
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.room.macToString
import be.mygod.vpnhotspot.widget.SmartSnackbar
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

val Throwable.readableMessage get() = localizedMessage ?: javaClass.name

/**
 * This is a hack: we wrap longs around in 1 billion and such. Hopefully every language counts in base 10 and this works
 * marvelously for everybody.
 */
fun Long.toPluralInt(): Int {
    check(this >= 0)    // please don't mess with me
    if (this <= Int.MAX_VALUE) return toInt()
    return (this % 1000000000).toInt() + 1000000000
}

@SuppressLint("Recycle")
fun <T> useParcel(block: (Parcel) -> T) = Parcel.obtain().run {
    try {
        block(this)
    } finally {
        recycle()
    }
}

fun Parcelable.toByteArray(parcelableFlags: Int = 0) = useParcel { p ->
    p.writeParcelable(this, parcelableFlags)
    p.marshall()
}
fun <T : Parcelable> ByteArray.toParcelable() = useParcel { p ->
    p.unmarshall(this, 0, size)
    p.setDataPosition(0)
    p.readParcelable<T>(javaClass.classLoader)
}

fun broadcastReceiver(receiver: (Context, Intent) -> Unit) = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = receiver(context, intent)
}

fun intentFilter(vararg actions: String): IntentFilter {
    val result = IntentFilter()
    actions.forEach { result.addAction(it) }
    return result
}

@BindingAdapter("android:src")
fun setImageResource(imageView: ImageView, @DrawableRes resource: Int) = imageView.setImageResource(resource)

@BindingAdapter("android:visibility")
fun setVisibility(view: View, value: Boolean) {
    view.isVisible = value
}

fun makeIpSpan(ip: InetAddress) = ip.hostAddress.let {
    // exclude all bogon IP addresses supported by Android APIs
    if (app.hasTouch && !(ip.isMulticastAddress || ip.isAnyLocalAddress || ip.isLoopbackAddress ||
                    ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMCGlobal || ip.isMCNodeLocal ||
                    ip.isMCLinkLocal || ip.isMCSiteLocal || ip.isMCOrgLocal)) SpannableString(it).apply {
        setSpan(CustomTabsUrlSpan("https://ipinfo.io/$it"), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    } else it
}
fun makeMacSpan(mac: String) = if (app.hasTouch) SpannableString(mac).apply {
    setSpan(CustomTabsUrlSpan("https://macvendors.co/results/$mac"), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
} else mac

fun NetworkInterface.formatAddresses(macOnly: Boolean = false) = SpannableStringBuilder().apply {
    try {
        hardwareAddress?.apply { appendln(makeMacSpan(asIterable().macToString())) }
    } catch (_: SocketException) { }
    if (!macOnly) for (address in interfaceAddresses) {
        append(makeIpSpan(address.address))
        appendln("/${address.networkPrefixLength}")
    }
}.trimEnd()

private val parseNumericAddress by lazy @SuppressLint("SoonBlockedPrivateApi") {
    InetAddress::class.java.getDeclaredMethod("parseNumericAddress", String::class.java).apply {
        isAccessible = true
    }
}
fun parseNumericAddress(address: String) = if (Build.VERSION.SDK_INT >= 29)
    InetAddresses.parseNumericAddress(address) else parseNumericAddress.invoke(null, address) as InetAddress

fun Context.launchUrl(url: String) {
    if (app.hasTouch) try {
        app.customTabsIntent.launchUrl(this, url.toUri())
        return
    } catch (_: RuntimeException) { }
    SmartSnackbar.make(url).show()
}

fun Context.stopAndUnbind(connection: ServiceConnection) {
    connection.onServiceDisconnected(null)
    unbindService(connection)
}

var MenuItem.isNotGone: Boolean
    get() = isVisible || isEnabled
    set(value) {
        isVisible = value
        isEnabled = value
    }

fun <K, V> MutableMap<K, V>.computeIfAbsentCompat(key: K, value: () -> V) = if (Build.VERSION.SDK_INT >= 24)
    computeIfAbsent(key) { value() } else this[key] ?: value().also { put(key, it) }
fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) = if (Build.VERSION.SDK_INT >= 24)
    putIfAbsent(key, value) else this[key] ?: put(key, value)
fun <K, V> MutableMap<K, V>.removeCompat(key: K, value: V) = if (Build.VERSION.SDK_INT >= 24) remove(key, value) else {
    val curValue = get(key)
    if (curValue === value && (curValue != null || containsKey(key))) {
        remove(key)
        true
    } else false
}
