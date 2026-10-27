package pt.obraemdia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import pt.obraemdia.app.ui.navigation.AppNavigation
import pt.obraemdia.app.ui.theme.ObraEmDiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A app tem só tema claro: ícones escuros nas barras do sistema, mesmo com o telemóvel em modo escuro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            ObraEmDiaTheme {
                AppNavigation()
            }
        }
    }
}
