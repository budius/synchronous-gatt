package com.sensorberg.synchronousgatt.sample

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattDescriptor
import android.os.Bundle
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sensorberg.permissionbitte.BitteBitte
import com.sensorberg.permissionbitte.PermissionBitte
import com.sensorberg.synchronousgatt.GattResult
import com.sensorberg.synchronousgatt.SynchronousGatt
import no.nordicsemi.android.support.v18.scanner.*
import timber.log.Timber
import java.util.*

class MainActivity : AppCompatActivity(), BitteBitte {

	private var isStarted = false
	private var isScanning = false
	private var hasPermission = false

	private val settings = ScanSettings.Builder()
		.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
		.setUseHardwareFilteringIfSupported(false)
		.build()

	private val serviceUuid = UUID.fromString(BuildConfig.SERVICE_UUID)
	private val characteristicWrite = UUID.fromString(BuildConfig.CHARACTERISTIC_WRITE_UUID)
	private val characteristicRead = UUID.fromString(BuildConfig.CHARACTERISTIC_READ_UUID)
	private val descriptorNotify = UUID.fromString(BuildConfig.DESCRIPTOR_NOTIFY_UUID)
	private val enableNotify = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

	private val scanner = BluetoothLeScannerCompat.getScanner()
	private val results = mutableMapOf<String, ScanResult>()
	private val filters = listOf<ScanFilter>(ScanFilter
												 .Builder()
												 .setServiceUuid(ParcelUuid(serviceUuid))
												 .build())

	private fun SynchronousGatt.execute() {
		val startMillis = SystemClock.elapsedRealtime()

		// connect
		val connection: GattResult.OnConnectionStateChange = connectGatt(this@MainActivity, false, BluetoothDevice.TRANSPORT_LE, 10000)
		Timber.d(connection.toString())

		// discover
		val services = discoverServices(4000)
		Timber.d(services.toString())

		// enable notify
		val notify = services
			.getService(serviceUuid)
			.getCharacteristic(characteristicRead)
			.getDescriptor(descriptorNotify)
		notify.value = enableNotify
		val writeNotify = writeDescriptor(notify, 3000)
		Timber.d(writeNotify.toString())

		// write something
		val characteristic = services
			.getService(serviceUuid)
			.getCharacteristic(characteristicWrite)
		characteristic.setValue("hello world\n")
		val writeChar = writeCharacteristic(characteristic, 3000)
		Timber.d(writeChar.toString())

		// await read
		val changed = awaitCharacteristicChange(5000)
		Timber.d("Changed (${changed.characteristic.uuid} -> $changed")

		// disconnect
		val disconnection = disconnect(1000)
		Timber.d(disconnection.toString())

		val time = SystemClock.elapsedRealtime() - startMillis
		Timber.d("Success! after $time ms")
	}

	private val adapter = Adapter { scanResult ->
		Thread {
			Timber.i("Starting Gatt Connection")

			val syncGatt = SynchronousGatt(scanResult.device)
			try {
				syncGatt.execute()
			} catch (e: Exception) {
				Timber.e(e, "Something went wrong")
			} finally {
				syncGatt.close()
			}

			Timber.i("Gatt Connection complete")
		}.start()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		Timber.uprootAll()
		Timber.plant(Timber.DebugTree())
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		val rv = findViewById<RecyclerView>(R.id.recycler)
		rv.layoutManager = LinearLayoutManager(this)
		rv.adapter = adapter
		PermissionBitte.ask(this, this)
	}

	override fun onStart() {
		super.onStart()
		isStarted = true
		startIfPossible()
	}

	private fun startIfPossible() {
		if (isStarted && hasPermission && !isScanning) {
			isScanning = true
			scanner.startScan(filters, settings, scanCallback)
		}
	}

	private val scanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			results[result.device.address] = result
			val data = results.values.filter { it.rssi > -55 }.sortedBy { it.rssi }
			Timber.v("(${data.size}) onScanResult $result")
			adapter.submitList(data)
		}
	}

	override fun onStop() {
		isStarted = false
		if (isScanning) {
			isScanning = false
			scanner.stopScan(scanCallback)
		}
		super.onStop()
	}

	override fun askNicer() {
		PermissionBitte.ask(this, this)
	}

	override fun noYouCant() {
		PermissionBitte.goToSettings(this)
	}

	override fun yesYouCan() {
		hasPermission = true
		startIfPossible()
	}

}
