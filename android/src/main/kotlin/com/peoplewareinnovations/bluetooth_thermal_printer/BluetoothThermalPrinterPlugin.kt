package com.peoplewareinnovations.bluetooth_thermal_printer

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.*
import androidx.annotation.NonNull

private const val TAG = "====> mio: "
private var outputStream: OutputStream? = null
private lateinit var mac: String

class BluetoothThermalPrinterPlugin : FlutterPlugin, MethodCallHandler {
  private lateinit var mContext: Context
  private lateinit var channel: MethodChannel
  private lateinit var state: String

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "bluetooth_thermal_printer")
    channel.setMethodCallHandler(this)
    this.mContext = flutterPluginBinding.applicationContext
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${android.os.Build.VERSION.RELEASE}")
      }
      "getBatteryLevel" -> {
        val batteryLevel = getBatteryLevel()
        if (batteryLevel != -1) {
          result.success(batteryLevel)
        } else {
          result.error("UNAVAILABLE", "Battery level not available.", null)
        }
      }
      "BluetoothStatus" -> {
        val state = if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) "true" else "false"
        result.success(state)
      }
      "connectionStatus" -> {
        if (outputStream != null) {
          try {
            outputStream?.write(" ".toByteArray())
            result.success("true")
          } catch (e: Exception) {
            result.success("false")
            outputStream = null
            showToast("Device was disconnected, reconnect")
          }
        } else {
          result.success("false")
        }
      }
      "connectPrinter" -> {
        val printerMAC = call.arguments.toString()
        if (printerMAC.isNotEmpty()) {
          mac = printerMAC
          GlobalScope.launch(Dispatchers.Main) {
            if (outputStream == null) {
              outputStream = connect()?.also {
                result.success(state)
              }
            }
          }
        } else {
          result.success("false")
        }
      }
      "disconnectPrinter" -> {
        GlobalScope.launch(Dispatchers.Main) {
          if (outputStream != null) {
            outputStream = disconnect()?.also {
              result.success("true")
            }
          }
        }
      }
      "writeBytes" -> {
        val lista: List<Int> = call.arguments as List<Int>
        var bytes: ByteArray = "\n".toByteArray()
        lista.forEach { bytes += it.toByte() }

        if (outputStream != null) {
          try {
            outputStream?.write(bytes)
            result.success("true")
          } catch (e: Exception) {
            result.success("false")
            outputStream = null
            showToast("Device was disconnected, reconnect")
          }
        } else {
          result.success("false")
        }
      }
      "printText" -> {
        val stringArrived: String = call.arguments.toString()
        val line = stringArrived.split("//")
        val size = if (line.size > 1) line[0].toInt().coerceIn(1, 5) else 2
        val texto = if (line.size > 1) line[1] else stringArrived

        if (outputStream != null) {
          try {
            outputStream?.run {
              write(setBytes.size[0])
              write(setBytes.cancelar_chino)
              write(setBytes.caracteres_escape)
              write(setBytes.size[size])
              write(texto.toByteArray(charset("iso-8859-1")))
              result.success("true")
            }
          } catch (e: Exception) {
            result.success("false")
            outputStream = null
            showToast("Device was disconnected, reconnect")
          }
        } else {
          result.success("false")
        }
      }
      "bluetothLinked" -> {
        val list = getLinkedDevices()
        result.success(list)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  private fun getBatteryLevel(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      val batteryManager = mContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
      batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } else {
      val intent = ContextWrapper(mContext.applicationContext).registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.times(100)?.div(intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) ?: -1
    }
  }

  private suspend fun connect(): OutputStream? {
    state = "false"
    return withContext(Dispatchers.IO) {
      var outputStream: OutputStream? = null
      val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
      if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
        try {
          val bluetoothDevice = bluetoothAdapter.getRemoteDevice(mac)
          val bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
          )
          bluetoothAdapter.cancelDiscovery()
          bluetoothSocket.connect()
          if (bluetoothSocket.isConnected) {
            outputStream = bluetoothSocket.outputStream
            state = "true"
          } else {
            state = "false"
            Log.d(TAG, "Disconnected")
          }
        } catch (e: Exception) {
          state = "false"
          Log.d(TAG, "connect: ${e.message}")
          outputStream?.close()
        }
      } else {
        state = "false"
        Log.d(TAG, "Adapter problem")
      }
      outputStream
    }
  }

  private suspend fun disconnect(): OutputStream? {
    state = "false"
    return withContext(Dispatchers.IO) {
      val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
      if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
        try {
          if (mac.isNotEmpty()) {
            val bluetoothDevice = bluetoothAdapter.getRemoteDevice(mac)
            val bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(
              UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            )
            bluetoothSocket.close()
          }
          Log.d(TAG, "Disconnected")
          outputStream?.close()
          outputStream = null
        } catch (e: Exception) {
          state = "false"
          Log.d(TAG, "disconnect: ${e.message}")
          outputStream?.close()
        }
      } else {
        state = "false"
        outputStream = null
        Log.d(TAG, "Adapter problem")
      }
      outputStream
    }
  }

  private fun getLinkedDevices(): List<String> {
    val listItems = mutableListOf<String>()
    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    if (bluetoothAdapter?.isEnabled == true) {
      bluetoothAdapter.bondedDevices?.forEach { device ->
        listItems.add("${device.name}#${device.address}")
      }
    }
    return listItems
  }

  private fun showToast(message: String) {
    Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  companion object {
    val setBytes = SetBytes()

    class SetBytes {
      companion object {
        val enter = "\n".toByteArray()
        val resetear_impresora = byteArrayOf(0x1b, 0x40, 0x0a)
        val cancelar_chino = byteArrayOf(0x1C, 0x2E)
        val caracteres_escape = byteArrayOf(0x1B, 0x74, 0x10)

        val size = arrayOf(
          byteArrayOf(0x1d, 0x21, 0x00), // La fuente no se agranda 0
          byteArrayOf(0x1b, 0x4d, 0x01), // Fuente ASCII comprimida 1
          byteArrayOf(0x1b, 0x4d, 0x00), // Fuente estándar ASCII 2
          byteArrayOf(0x1d, 0x21, 0x11), // Altura doblada 3
          byteArrayOf(0x1d, 0x21, 0x22), // Altura doblada 4
          byteArrayOf(0x1d, 0x21, 0x33) // Altura doblada 5
        )
      }
    }
  }
}
