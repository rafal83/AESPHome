package com.aesphome

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections



// Regex pattern for DNS sanitization
private val DNS_LABEL_PATTERN = Regex("-+")



fun bytesToFloat(bytes: ByteArray): Float = Float.fromBits(bytesToInt(bytes))



fun bytesToInt(bytes: ByteArray):   Int =(bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)



fun getDeviceName(context: Context): String {
  val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
  return name ?: Build.MODEL // falls back to the hardware model if no user-set device name exists
}



fun sanitizeForDns(text: String): String {
  val cleaned = text.map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '-' }.joinToString("")
  val collapsed = cleaned.replace(DNS_LABEL_PATTERN, "-").trim('-')
  val truncated = collapsed.take(63) // DNS label length limit
  return if (truncated.isEmpty()) "android-device" else truncated
}



fun getDeviceHostname(context: Context): String {
  try {
    // net.hostname is the actual network hostname Android's own stack uses (e.g. for DHCP),
    // distinct from the user-facing device name. Not a public API, so this is best-effort.
    val systemProperties = Class.forName("android.os.SystemProperties")
    val get = systemProperties.getMethod("get", String::class.java)
    val hostname = get.invoke(null, "net.hostname") as? String
    if (!hostname.isNullOrBlank()) return hostname
  } catch (e: Exception) {
    // net.hostname isn't readable on this device/Android version — fall through below
  }
  // Fall back to the display device name, made DNS-safe (it can contain spaces, e.g. "LGE LM-T600").
  return sanitizeForDns(getDeviceName(context))
}



// Android ID formatted as a MAC
fun getDeviceMac(context: Context): String {
  val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "000000000000"
  return id.padStart(12, '0').takeLast(12).chunked(2).joinToString(":").uppercase()
}



// Packs a "AA:BB:CC:DD:EE:FF"-style MAC string into the uint64 layout ESPHome's own firmware
// uses for BluetoothLERawAdvertisement.address (esphome/components/esp32_ble/ble.h,
// ble_addr_to_uint64: byte 0 in the most-significant position) — matching this exactly is
// what lets Home Assistant's Bluetooth integration show the right address for a proxied
// advertisement. Returns null for anything that isn't a well-formed 6-byte MAC.
fun macStringToLong(mac: String): Long? {
  val parts = mac.split(":")
  if (parts.size != 6) return null
  var result = 0L
  for (part in parts) {
    val byte = part.toIntOrNull(16)?.takeIf { it in 0..255 } ?: return null
    result = (result shl 8) or byte.toLong()
  }
  return result
}

// Usage Access ("PACKAGE_USAGE_STATS") is a special app-op permission with no runtime
// prompt — this is the standard way to check whether it's actually been granted via its own
// Settings screen. Shared by ForegroundAppSensor, the app launcher's foreground-app lookup,
// and the Permissions screen's status row for it.
fun hasUsageAccess(context: Context): Boolean {
  val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
  val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
  } else {
    @Suppress("DEPRECATION")
    appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
  }
  return mode == AppOpsManager.MODE_ALLOWED
}

// Same wlan0-scoped lookup as getDeviceMac, just returning the IPv4 address instead.
fun getWifiIpAddress(): String? {
  try {
    for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!iface.name.equals("wlan0", ignoreCase = true)) continue
      for (addr in Collections.list(iface.inetAddresses)) {
        if (addr is Inet4Address) return addr.hostAddress
      }
    }
  } catch (e: Exception) {
    // fall through — no wlan0 address available (e.g. Wi-Fi is off)
  }
  return null
}
