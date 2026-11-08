package id.art.tracker.playground.scenario

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Text fields a scenario can type into by id. */
object ScenarioFields {
    private val fields = ConcurrentHashMap<String, MutableState<String>>()

    fun register(id: String, state: MutableState<String>) {
        fields[id] = state
    }

    fun unregister(id: String) {
        fields.remove(id)
    }

    /** False when no such field is on screen. */
    fun set(id: String, text: String): Boolean {
        val state = fields[id] ?: return false
        state.value = text
        return true
    }
}

@Composable
fun ScenarioTextField(id: String, label: String, modifier: Modifier = Modifier) {
    val state = remember(id) { mutableStateOf("") }
    DisposableEffect(id) {
        ScenarioFields.register(id, state)
        onDispose { ScenarioFields.unregister(id) }
    }
    OutlinedTextField(
        value = state.value,
        onValueChange = { state.value = it },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}
