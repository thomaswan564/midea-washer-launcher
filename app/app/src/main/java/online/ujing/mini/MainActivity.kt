package online.ujing.mini

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import online.ujing.mini.ui.AppRoot
import online.ujing.mini.ui.MainViewModel
import online.ujing.mini.ui.UjingMiniTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            vm.log("权限结果 " + granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent {
            UjingMiniTheme {
                AppRoot(vm)
            }
        }
    }

    /**
     * Android 12+ 用 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；
     * 11 及以下 BLE 扫描必须拿 ACCESS_FINE_LOCATION。
     */
    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permLauncher.launch(perms)
    }
}
