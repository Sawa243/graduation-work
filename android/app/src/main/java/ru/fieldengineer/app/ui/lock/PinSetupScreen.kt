package ru.fieldengineer.app.ui.lock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.fieldengineer.app.domain.PinSetup

/** Шаг после Э1 «Задайте PIN»: ввести PIN и повторить его; затем — список заявок. */
@Composable
fun PinSetupScreen(vm: PinSetupViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // поле очищается при переходе к повтору и после ошибки
    var pin by remember(state.step, state.error) { mutableStateOf("") }

    LaunchedEffect(state.done) { if (state.done) onDone() }
    // без PIN в приложение не пускаем: «Назад» сворачивает приложение
    BackHandler { activity?.moveTaskToBack(true) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (state.step == PinSetup.Step.CREATE) "Задайте PIN" else "Повторите PIN",
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "4–6 цифр. PIN снимает блокировку приложения и подтверждает вашу подпись в актах. " +
                    "Если забудете — войдите по паролю и задайте новый.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.onPin(pin) }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.onPin(pin) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(if (state.step == PinSetup.Step.CREATE) "Далее" else "Сохранить PIN")
            }
        }
    }
}
