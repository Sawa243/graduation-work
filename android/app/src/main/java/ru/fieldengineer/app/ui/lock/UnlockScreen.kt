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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.fieldengineer.app.AppContainer
import ru.fieldengineer.app.BuildConfig

/**
 * Э0 поверх любого экрана. Показывается отдельным полноэкранным окном (Dialog): так он
 * закрывает и открытые диалоги экрана (например, ввод PIN на Э7), а экран под ним со всеми
 * введёнными данными остаётся как был — после разблокировки инженер продолжает с того же места.
 */
@Composable
fun AppLockOverlay(container: AppContainer) {
    val lock = container.appLockManager
    val locked by lock.locked.collectAsStateWithLifecycle()
    val loggedIn by container.authRepository.loggedIn.collectAsStateWithLifecycle()
    val pinRequired by container.authRepository.pinRequired.collectAsStateWithLifecycle()
    // вошли, вышли, задали PIN — пересчитать блокировку (на Э1 и на шаге «Задайте PIN» её нет,
    // после задания PIN отсчёт с нуля)
    LaunchedEffect(loggedIn, pinRequired) { lock.refresh() }

    if (locked && loggedIn && !pinRequired) {
        val vm = viewModel { UnlockViewModel(container.authRepository, container.pinStore, lock) }
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                securePolicy = if (BuildConfig.SECURE_SCREENS) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            UnlockScreen(vm)
        }
    }
}

/** Э0 «Разблокировка»: PIN инженера или отпечаток; новый PIN здесь не задаётся. */
@Composable
private fun UnlockScreen(vm: UnlockViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val biometric = remember(activity) { activity != null && BiometricUnlock.isAvailable(activity) }
    // после неверного PIN поле очищается
    var pin by remember(state.error) { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.start() }
    // отпечаток предлагаем сразу, если он есть и PIN уже задан
    LaunchedEffect(state.offerBiometric) {
        if (state.offerBiometric && biometric && activity != null) {
            vm.biometricOffered()
            BiometricUnlock.show(activity, vm::onBiometricSuccess)
        }
    }
    // «Назад» не снимает блокировку, а сворачивает приложение
    BackHandler { activity?.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Приложение заблокировано", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
            Text("Не было действий 5 минут. Данные защищены до подтверждения, что телефон у вас.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Text("Введите PIN", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
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
                else Text("Разблокировать")
            }
            if (biometric && activity != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { BiometricUnlock.show(activity, vm::onBiometricSuccess) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Отпечаток пальца") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = vm::loginWithPassword, enabled = !state.busy) { Text("Войти по паролю") }
        }
    }
}
