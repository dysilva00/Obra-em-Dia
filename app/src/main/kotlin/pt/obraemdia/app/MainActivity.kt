package pt.obraemdia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pt.obraemdia.app.ui.navigation.AppNavigation
import pt.obraemdia.app.ui.theme.ObraEmDiaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObraEmDiaTheme {
                AppNavigation()
            }
        }
    }
}
