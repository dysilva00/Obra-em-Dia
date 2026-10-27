package pt.obraemdia.app.fiscal

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import pt.obraemdia.domain.fiscalqr.AtQrDocument
import pt.obraemdia.domain.fiscalqr.AtQrParser
import pt.obraemdia.domain.fiscalqr.AtQrResult
import java.util.concurrent.Executors

/**
 * Leitor de QR da AT pela câmara (CameraX + ML Kit, no aparelho). A permissão de câmara só é
 * pedida aqui; se for recusada, fica a alternativa de importar o PDF ou introduzir à mão.
 */
@Composable
fun QrScannerScreen(onResult: (AtQrDocument) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    when {
        granted -> CameraPreview(onResult)
        denied -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sem acesso à câmara.", style = MaterialTheme.typography.titleMedium)
            Text("Pode importar o PDF da fatura (a app lê o QR do documento) ou introduzir os valores à mão.")
            Button(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Voltar") }
        }
        else -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun CameraPreview(onResult: (AtQrDocument) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf("Aponte para o código QR da fatura") }
    var done by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media == null || done) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                val text = codes.firstNotNullOfOrNull { it.rawValue } ?: return@addOnSuccessListener
                                when (val r = AtQrParser.parse(text)) {
                                    is AtQrResult.Ok -> if (!done) {
                                        done = true
                                        onResult(r.document)
                                    }
                                    is AtQrResult.Invalid -> hint = r.message
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
        Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White, MaterialTheme.shapes.large))
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth(),
        ) {
            Text(hint, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
