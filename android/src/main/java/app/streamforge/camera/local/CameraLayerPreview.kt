package app.streamforge.camera.local

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Local CameraX source; remote sources are kept separate under camera/link. */
@Composable
fun CameraLayerPreview(
    lensFacing: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (!granted) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Text("CAMERA PERMISSION", color = Color.White, fontSize = 10.sp)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera", fontSize = 10.sp)
                }
            }
        } else {
            var previewView by remember { mutableStateOf<PreviewView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewView = this
                    }
                },
                update = { previewView = it }
            )
            DisposableEffect(previewView, lensFacing, lifecycleOwner) {
                val view = previewView
                var disposed = false
                var provider: ProcessCameraProvider? = null
                var useCase: Preview? = null
                if (view != null) {
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        if (!disposed) {
                            runCatching {
                                val cameraProvider = future.get()
                                provider = cameraProvider
                                val selector = CameraSelector.Builder()
                                    .requireLensFacing(if (lensFacing == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                                    .build()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                                useCase = preview
                                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
                            }
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
                onDispose {
                    disposed = true
                    val activeProvider = provider
                    val activeUseCase = useCase
                    if (activeProvider != null && activeUseCase != null) {
                        runCatching { activeProvider.unbind(activeUseCase) }
                    }
                }
            }
        }
    }
}
