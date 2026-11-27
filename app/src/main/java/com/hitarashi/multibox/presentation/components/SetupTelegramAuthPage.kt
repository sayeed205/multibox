package com.hitarashi.multibox.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hitarashi.multibox.R
import com.hitarashi.multibox.core.model.telegram.TelegramAuthState
import com.hitarashi.multibox.presentation.viewmodel.SetupUiState
import com.hitarashi.multibox.ui.theme.RoundedSans

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupTelegramAuthPage(
    uiState: SetupUiState,
    onApiIdChanged: (String) -> Unit,
    onApiHashChanged: (String) -> Unit,
    onSubmitApiCredentials: () -> Unit,
    onPhoneChanged: (String) -> Unit,
    onSubmitPhone: () -> Unit,
    onCodeChanged: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmitPassword: () -> Unit,
    onEditPhone: () -> Unit,
    onLogout: () -> Unit,
    onRetryInit: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.setup_telegram_title),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = RoundedSans,
                    fontSize = 32.sp
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.setup_telegram_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            if (uiState.telegramIsBusy) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            uiState.telegramInlineError?.let { errorMessage ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissError) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_close),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            val visualState = when {
                uiState.telegramEditingPhone &&
                    (uiState.telegramAuthState is TelegramAuthState.WaitingForCode ||
                        uiState.telegramAuthState is TelegramAuthState.WaitingForPassword) ->
                    TelegramAuthState.WaitingForPhoneNumber
                else -> uiState.telegramAuthState
            }

            AnimatedContent(
                targetState = visualState,
                transitionSpec = {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                },
                label = "SetupTelegramAuthStep"
            ) { state ->
                when (state) {
                    TelegramAuthState.Initializing,
                    TelegramAuthState.LoggingOut -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                LoadingIndicator()
                                Text(
                                    text = stringResource(R.string.setup_telegram_initializing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is TelegramAuthState.WaitingForApiCredentials -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = state.message ?: stringResource(R.string.setup_telegram_api_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = uiState.telegramApiIdInput,
                                    onValueChange = onApiIdChanged,
                                    label = { Text(stringResource(R.string.setup_telegram_api_id_label)) },
                                    singleLine = true,
                                    enabled = !uiState.telegramIsBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = uiState.telegramApiHashInput,
                                    onValueChange = onApiHashChanged,
                                    label = { Text(stringResource(R.string.setup_telegram_api_hash_label)) },
                                    singleLine = true,
                                    enabled = !uiState.telegramIsBusy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { onSubmitApiCredentials() }),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = onSubmitApiCredentials,
                                    enabled = !uiState.telegramIsBusy &&
                                        uiState.telegramApiIdInput.isNotBlank() &&
                                        uiState.telegramApiHashInput.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.setup_telegram_save_credentials))
                                }
                            }
                        }
                    }

                    TelegramAuthState.WaitingForPhoneNumber -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.telegramPhoneInput,
                                    onValueChange = onPhoneChanged,
                                    label = { Text(stringResource(R.string.setup_telegram_phone_label)) },
                                    placeholder = { Text(stringResource(R.string.setup_telegram_phone_placeholder)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                                    },
                                    singleLine = true,
                                    enabled = !uiState.telegramIsBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { onSubmitPhone() }),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = onSubmitPhone,
                                    enabled = !uiState.telegramIsBusy && uiState.telegramPhoneInput.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.setup_telegram_send_code))
                                }
                            }
                        }
                    }

                    is TelegramAuthState.WaitingForCode -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.telegramCodeInput,
                                    onValueChange = onCodeChanged,
                                    label = { Text(stringResource(R.string.setup_telegram_code_label)) },
                                    singleLine = true,
                                    enabled = !uiState.telegramIsBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { onSubmitCode() }),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = onSubmitCode,
                                    enabled = !uiState.telegramIsBusy && uiState.telegramCodeInput.length >= 4,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.setup_telegram_verify_code))
                                }
                                TextButton(
                                    onClick = onEditPhone,
                                    enabled = !uiState.telegramIsBusy,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.setup_telegram_change_phone))
                                }
                            }
                        }
                    }

                    is TelegramAuthState.WaitingForPassword -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.passwordHint.isNotBlank()) {
                                    Text(
                                        text = state.passwordHint,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedTextField(
                                    value = uiState.telegramPasswordInput,
                                    onValueChange = onPasswordChanged,
                                    label = { Text(stringResource(R.string.setup_telegram_password_label)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Lock, contentDescription = null)
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    enabled = !uiState.telegramIsBusy,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { onSubmitPassword() }),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = onSubmitPassword,
                                    enabled = !uiState.telegramIsBusy && uiState.telegramPasswordInput.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.setup_telegram_verify_password))
                                }
                                TextButton(
                                    onClick = onEditPhone,
                                    enabled = !uiState.telegramIsBusy,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.setup_telegram_change_phone))
                                }
                            }
                        }
                    }

                    TelegramAuthState.Ready -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column {
                                        Text(
                                            text = uiState.telegramUser?.displayName
                                                ?: stringResource(R.string.setup_telegram_connected_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = stringResource(R.string.setup_telegram_connected_subtitle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = onLogout,
                                    enabled = !uiState.telegramIsBusy,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.setup_telegram_sign_out))
                                }
                            }
                        }
                    }

                    TelegramAuthState.Closed -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.setup_telegram_initializing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(onClick = onRetryInit) {
                                    Text(stringResource(R.string.setup_telegram_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
