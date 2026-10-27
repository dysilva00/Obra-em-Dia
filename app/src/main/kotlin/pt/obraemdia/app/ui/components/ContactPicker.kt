package pt.obraemdia.app.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Nome e telefone escolhidos; só isto é copiado do contacto. */
data class PickedContact(val name: String, val phone: String?)

/**
 * Seletor de número de telefone do sistema. Não precisa da permissão READ_CONTACTS: o Android dá
 * acesso só à entrada escolhida, e a app não vê o resto da agenda.
 */
private object PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

private fun read(context: Context, uri: Uri): PickedContact? = runCatching {
    val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val name = c.getString(0)?.trim().orEmpty()
        val phone = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (name.isEmpty() && phone == null) null else PickedContact(name.ifEmpty { phone!! }, phone)
    }
}.getOrNull()

/**
 * Botão "Escolher dos contactos". Se o telemóvel não tiver seletor ou a leitura falhar,
 * [onUnavailable] explica e os campos continuam a poder ser preenchidos à mão.
 */
@Composable
fun ContactPickerButton(onPicked: (PickedContact) -> Unit, onUnavailable: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickPhone) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = read(context, uri)
        if (picked != null) onPicked(picked) else onUnavailable("Não foi possível ler o contacto. Escreva o nome e o telefone.")
    }
    OutlinedButton(
        onClick = {
            try {
                launcher.launch(Unit)
            } catch (_: ActivityNotFoundException) {
                onUnavailable("Este telemóvel não tem seletor de contactos. Escreva o nome e o telefone.")
            }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.Contacts, contentDescription = null)
        Text("  Escolher dos contactos")
    }
}
