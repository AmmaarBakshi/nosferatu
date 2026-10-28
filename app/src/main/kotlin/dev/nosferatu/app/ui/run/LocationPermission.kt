package dev.nosferatu.app.ui.run

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.nosferatu.app.container

/** Location (and, on Android 13+, notification) permission state with a launcher to ask for it. */
class PermissionState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberTrackingPermissions(): PermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.container.gps.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || context.container.gps.hasPermission()
    }
    val wanted = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return PermissionState(granted) { launcher.launch(wanted.toTypedArray()) }
}
