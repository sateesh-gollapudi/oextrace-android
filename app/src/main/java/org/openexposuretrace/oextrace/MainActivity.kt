package org.openexposuretrace.oextrace

import android.Manifest.permission
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.openexposuretrace.oextrace.OnboardingActivity.Extra.STAGE_EXTRA
import org.openexposuretrace.oextrace.data.ContactRequest
import org.openexposuretrace.oextrace.data.Enums
import org.openexposuretrace.oextrace.data.Enums.*
import org.openexposuretrace.oextrace.di.BluetoothManagerProvider
import org.openexposuretrace.oextrace.di.api.ContactsApiClientProvider
import org.openexposuretrace.oextrace.ext.access.withPermissions
import org.openexposuretrace.oextrace.ext.ifAllNotNull
import org.openexposuretrace.oextrace.ext.ui.showError
import org.openexposuretrace.oextrace.ext.ui.showInfo
import org.openexposuretrace.oextrace.location.LocationAccessManager
import org.openexposuretrace.oextrace.service.BleUpdatesService
import org.openexposuretrace.oextrace.service.TrackingService
import org.openexposuretrace.oextrace.storage.*
import org.openexposuretrace.oextrace.utils.CryptoUtil
import org.openexposuretrace.oextrace.utils.CryptoUtil.base64DecodeByteArray
import org.openexposuretrace.oextrace.utils.CryptoUtil.base64EncodedString
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_NONE = 0
        const val REQUEST_LOCATION = 1
        const val REQUEST_CHECK_TRACKING_SETTINGS = 2
        private const val REQUEST_BLUETOOTH = 3
    }

    // Tracks the bound state of the service.
    private val deviceManager by BluetoothManagerProvider()
    private var bluetoothAlert: AlertDialog.Builder? = null
    private val contactsApiClient by ContactsApiClientProvider()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val navController = findNavController(R.id.nav_host_fragment)
        navView.setupWithNavController(navController)

        if (!OnboardingManager.isComplete()) {
            val intent = Intent(this, OnboardingActivity::class.java)

            intent.putExtra(STAGE_EXTRA, OnboardingStage.WELCOME)

            startActivity(intent)

            return
        }

        handleDeepLink()
    }

    private fun handleDeepLink() {
        val data: Uri? = intent.data
        if (data != null && data.isHierarchical) {
            val uri = Uri.parse(intent.dataString)
            val rpi = uri.getQueryParameter("r")
            val key = uri.getQueryParameter("k")
            val token = uri.getQueryParameter("d")
            val platform = uri.getQueryParameter("p")
            val tst = uri.getQueryParameter("t")?.toLongOrNull()
            ifAllNotNull(rpi, key, token, platform, tst, ::makeContact)
        }
    }

    override fun onResume() {
        super.onResume()

        if (OnboardingManager.isComplete()) {
            requestEnableTracking()

            Log.i("DATA", "Cleaning old data...")

            BtContactsManager.removeOldContacts()
            QrContactsManager.removeOldContacts()
            TracksManager.removeOldTracks()
            TrackingManager.removeOldPoints()
            LocationBordersManager.removeOldLocationBorders()
            EncryptionKeysManager.removeOldKeys()

            if (UserSettingsManager.sick()) {
                KeysManager.uploadNewKeys()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        stopBleService()
        stopTrackingService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startOrUpdateTrackingService()
                    startSearchDevices()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_BLUETOOTH)
                startBleService()
        }
    }

    private fun enableTracking() {
        if (LocationAccessManager.authorized(this)) {
            startOrUpdateTrackingService()
            startBleService()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_LOCATION
            )
        }
    }

    private fun requestEnableTracking() {
        checkLocationSettings(
            TrackingService.TRACKING_LOCATION_REQUEST_BUILDER,
            Runnable { this.enableTracking() },
            Runnable {
                Toast.makeText(this, R.string.location_disabled, LENGTH_LONG).show()
            }
        )
    }

    /**
     * This method is about location usage device-wide, not permission of the app!
     */
    private fun checkLocationSettings(
        requestBuilder: LocationSettingsRequest.Builder,
        onSuccess: Runnable,
        onFailure: Runnable?
    ) {
        val client = LocationServices.getSettingsClient(this)
        val task =
            client.checkLocationSettings(requestBuilder.build())
        task.addOnSuccessListener(this) { onSuccess.run() }
        task.addOnFailureListener(this) { e ->
            if (e is ResolvableApiException) {
                // StaticLocation settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    e.startResolutionForResult(this@MainActivity, REQUEST_CHECK_TRACKING_SETTINGS)
                } catch (sendEx: SendIntentException) {
                    // Ignore the error.
                }
            } else {
                onFailure?.run()
            }
        }
    }

    private fun stopBleService() {
        stopService(Intent(this, BleUpdatesService::class.java))
    }

    private fun startOrUpdateTrackingService() {
        startService(Intent(this, TrackingService::class.java))
    }

    private fun stopTrackingService() {
        stopService(Intent(this, TrackingService::class.java))
    }

    private fun startSearchDevices() =
        withPermissions(arrayOf(permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION) {
            val locationManager =
                getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsEnabled =
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ?: false
            if (gpsEnabled) {
                when (checkBluetooth()) {
                    ENABLED -> startService(Intent(this, BleUpdatesService::class.java))
                    DISABLED -> showBluetoothDisabledError()
                    NOT_FOUND -> showBluetoothNotFoundError()
                }
            }
        }

    private fun checkBluetooth(): Enums = deviceManager.checkBluetooth()

    private fun startBleService() {
        when (checkBluetooth()) {
            ENABLED -> startSearchDevices()
            DISABLED -> showBluetoothDisabledError()
            NOT_FOUND -> showBluetoothNotFoundError()
        }
    }

    private fun showBluetoothDisabledError() {
        if (bluetoothAlert == null)
            bluetoothAlert = AlertDialog.Builder(this).apply {
                setTitle(R.string.bluetooth_turn_off)
                setMessage(R.string.bluetooth_turn_off_description)
                setPositiveButton(R.string.enable) { _, _ ->
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH)
                    bluetoothAlert = null
                }
                setOnCancelListener { bluetoothAlert = null }
                show()
            }
    }

    private fun showBluetoothNotFoundError() {
        AlertDialog.Builder(this).apply {
            setTitle(R.string.bluetooth_do_not_support)
            setMessage(R.string.bluetooth_do_not_support_description)
            setCancelable(false)
            setNegativeButton(R.string.done) { _, _ -> }
            show()
        }
    }

    private fun makeContact(rpi: String, key: String, token: String, platform: String, tst: Long) {
        if (abs(System.currentTimeMillis() - tst) > (60 * 1000)) {
            // QR contact should be valid for 1 minute only
            showError(R.string.code_has_expired)

            return
        }

        val (rollingId, meta) = CryptoUtil.getCurrentRollingIdAndMeta()

        val keyData = key.base64DecodeByteArray()
        var secretData = CryptoUtil.encodeAES(rollingId, keyData)
        secretData += CryptoUtil.encodeAES(meta, keyData)

        val contactRequest = ContactRequest(token, platform, secretData.base64EncodedString(), tst)

        contactsApiClient.sendContactRequest(contactRequest)
            .enqueue(object : Callback<Void> {

                override fun onResponse(call: Call<Void>, response: Response<Void>) {
                    val contact = QrContact.create(rpi)

                    QrContactsManager.addContact(contact)
                    showInfo(R.string.recorded_contact)
                }

                override fun onFailure(call: Call<Void>, t: Throwable) {
                    t.message?.let { showError(it) }
                }

            })
    }

}
