package com.aesphome

import android.content.Context


/*

  Start At Boot
    switch.start_at_boot — BootReceiver (MainActivity.kt) already starts AESPHomeService on
    BOOT_COMPLETED/MY_PACKAGE_REPLACED; this just makes that conditional and controllable from
    HA rather than permanently on. Defaults to on, matching the app's behavior before this
    switch existed (BootReceiver had no condition at all).

*/


object StartAtBootSwitch : SwitchEntity {
  override val id                  = "start_at_boot"
  override val label               = "Start At Boot"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = true // no real "disabled" behavior beyond hiding from HA — see BluetoothSwitch/IdentifyButton for the same pattern
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:power-settings"

  override fun isOn(context: Context): Boolean = getFlag(context, "start_at_boot_state", true)
  override fun setOn(context: Context, on: Boolean) = setFlag(context, "start_at_boot_state", on)

  override fun start(context: Context) {
    AESPHomeService.instance?.reportSwitch(this, isOn(context))
  }

  override fun stop(context: Context) {}
}
