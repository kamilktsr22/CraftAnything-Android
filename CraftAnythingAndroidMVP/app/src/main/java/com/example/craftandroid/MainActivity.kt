package com.example.craftandroid

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val MAX_APK_BYTES = 128L * 1024L * 1024L

private sealed interface ScanState {
    data object Idle : ScanState
    data object Loading : ScanState
    data class Ready(val name: String, val report: JSONObject) : ScanState
    data class Failed(val message: String) : ScanState
}

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf<ScanState>(ScanState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AnalyzerScreen(state = state, onPick = ::scanUri)
                }
            }
        }
    }

    private fun scanUri(uri: Uri) {
        if (state == ScanState.Loading) return
        state = ScanState.Loading
        lifecycleScope.launch {
            state = withContext(Dispatchers.IO) {
                val selectedName = displayName(uri)
                val privateFile = File(cacheDir, "selected-${UUID.randomUUID()}.apk")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        privateFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_APK_BYTES) {
                                    "APK terlalu besar. Batas pemindaian 128 MiB."
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("Berkas tidak dapat dibuka oleh Android.")
                    val reply = NativeBridge.analyzeApk(privateFile.absolutePath)
                    val parsed = JSONObject(reply)
                    if (parsed.optBoolean("ok", false)) {
                        ScanState.Ready(selectedName, parsed)
                    } else {
                        ScanState.Failed(parsed.optString("error", "Analisis gagal."))
                    }
                } catch (t: Throwable) {
                    ScanState.Failed(t.message ?: "Terjadi kesalahan saat analisis APK.")
                } finally {
                    privateFile.delete()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.take(140) ?: "Berkas APK"
        } catch (_: Exception) {
            "Berkas APK"
        }
    }
}

@Composable
private fun AnalyzerScreen(state: ScanState, onPick: (Uri) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Default.Android, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Craft Anything", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text("Native APK Analyzer · MVP 0.1", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pilih APK untuk melihat manifest, permission, komponen, DEX, dan library native. Pemindaian dilakukan lokal menggunakan Rust.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state != ScanState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state is ScanState.Ready) "Pilih APK lain" else "Pilih berkas APK")
        }
        when (state) {
            ScanState.Idle -> InfoCard("Belum ada hasil", "Buka berkas .apk dari penyimpanan perangkat. Root tidak diperlukan.")
            ScanState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator()
                Text("Menganalisis APK secara lokal...")
            }
            is ScanState.Failed -> InfoCard("Analisis tidak berhasil", state.message)
            is ScanState.Ready -> ReportPanel(state.name, state.report)
        }
        HorizontalDivider()
        Text("AI dan cloud build: belum diaktifkan pada MVP ini.", style = MaterialTheme.typography.bodySmall)
        Text("APK tidak diunggah. File sementara dihapus setelah dianalisis.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReportPanel(name: String, report: JSONObject) {
    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    InfoCard("Identitas aplikasi") {
        Detail("Package", report.safeText("package_name"))
        Detail("Versi", report.safeText("version_name"))
        Detail("Version code", report.safeText("version_code"))
        Detail("Min SDK", report.safeText("min_sdk"))
        Detail("Target SDK", report.safeText("target_sdk"))
        Detail("Ukuran APK", "${report.optLong("file_bytes") / 1024} KiB")
        Detail("Entri arsip", report.optInt("entry_count").toString())
    }

    val perms = report.optJSONArray("permissions") ?: JSONArray()
    ExpandableSection("Permission (${perms.length()})", defaultExpanded = true) {
        Strings(perms, 120)
    }

    val components = report.optJSONArray("components") ?: JSONArray()
    ExpandableSection("Komponen (${report.optInt("components_total")})") {
        for (i in 0 until minOf(components.length(), 60)) {
            val item = components.optJSONObject(i) ?: continue
            val visibility = if (item.optBoolean("exported")) "exported" else "internal"
            Detail("${item.safeText("kind")} [$visibility]", item.safeText("name"))
        }
        if (components.length() > 60) Text("Hanya 60 komponen pertama ditampilkan.")
    }

    val dex = report.optJSONArray("dex_files") ?: JSONArray()
    ExpandableSection("DEX (${report.optInt("dex_count")})", defaultExpanded = true) {
        for (i in 0 until dex.length()) {
            val item = dex.optJSONObject(i) ?: continue
            Text(item.safeText("name"), fontWeight = FontWeight.SemiBold)
            Text("Class: ${item.optInt("classes")} | Method: ${item.optInt("methods")} | Field: ${item.optInt("fields")}")
            Text("Checksum valid: ${item.optBoolean("checksum_valid")}")
            Spacer(Modifier.height(6.dp))
        }
        if (dex.length() == 0) Text("Tidak ada DEX yang dapat diurai.")
    }

    val classes = report.optJSONArray("classes_preview") ?: JSONArray()
    ExpandableSection("Pratinjau class (${classes.length()})") { Strings(classes, 120) }

    val libraries = report.optJSONArray("native_libraries") ?: JSONArray()
    ExpandableSection("Library native (${libraries.length()})") {
        for (i in 0 until minOf(libraries.length(), 60)) {
            val item = libraries.optJSONObject(i) ?: continue
            Detail(item.safeText("abi"), item.safeText("name"))
        }
        if (libraries.length() > 60) Text("Hanya 60 library pertama ditampilkan.")
    }

    val warnings = report.optJSONArray("warnings") ?: JSONArray()
    if (warnings.length() > 0) {
        ExpandableSection("Peringatan (${warnings.length()})") { Strings(warnings, 60) }
    }
}

private fun JSONObject.safeText(key: String): String =
    if (isNull(key)) "-" else optString(key).takeUnless { it.isBlank() } ?: "-"

@Composable
private fun Strings(items: JSONArray, limit: Int) {
    if (items.length() == 0) Text("Tidak ada data.")
    for (i in 0 until minOf(items.length(), limit)) {
        Text("• ${items.optString(i)}", style = MaterialTheme.typography.bodySmall)
    }
    if (items.length() > limit) Text("Daftar dipotong untuk tampilan.")
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ExpandableSection(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember(title) { mutableStateOf(defaultExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Tutup" else "Buka")
                }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun InfoCard(title: String, description: String) {
    InfoCard(title) { Text(description) }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
