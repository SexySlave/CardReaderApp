package com.sexysalve.cardreaderapp

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sexysalve.cardreaderapp.CardReaderBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.HexFormat

// Состояние приложения
data class AppState(
    val terminals: List<String> = emptyList(),
    val selectedTerminal: String? = null,
    val isLoadingTerminals: Boolean = false,
    val isProcessingCard: Boolean = false, // Общий флаг для подключения, отключения, чтения данных
    val cardStatusMessage: String = "Ожидание действий...", // Единое поле для статуса и данных
    val isCardConnected: Boolean = false,
    val cardInfo: String? = null // Для протокола и имени терминала
)

// Вспомогательная функция для преобразования ByteArray в HEX-строку
fun ByteArray?.toHexString(): String {
    return this?.let { HexFormat.of().formatHex(it).uppercase() } ?: "NULL_RESPONSE"
}

// Форматирует ответ APDU для отображения: только данные (если есть) и статус.
fun formatApduResponseForDisplay(response: ByteArray?): String {
    if (response == null || response.size < 2) {
        return "Ошибка: неверный ответ от карты (${response.toHexString()})"
    }
    val dataBytes = response.copyOfRange(0, response.size - 2)
    val sw1 = response[response.size - 2]
    val sw2 = response[response.size - 1]
    val status = String.format("%02X%02X", sw1, sw2).uppercase()

    return if (dataBytes.isNotEmpty()) {
        "Данные: ${dataBytes.toHexString()}\nСтатус: $status"
    } else {
        "Статус: $status"
    }
}


@Composable
@Preview
fun App(cardReaderBackend: CardReaderBackend) {
    var appState by remember { mutableStateOf(AppState()) }
    val coroutineScope = rememberCoroutineScope()

    fun updateState(block: AppState.() -> AppState) {
        appState = appState.block()
    }

    MaterialTheme(
        colors = MaterialTheme.colors.copy(primary = Color(0xFF6200EE), secondary = Color(0xFF03DAC5))
    ) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Card Reader Demo") })
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Секция управления ридером
                Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Управление ридером", style = MaterialTheme.typography.h6)

                        // Кнопка поиска ридеров
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    // Запуск поиска ридеров
                                    updateState { copy(isLoadingTerminals = true, terminals = emptyList(), selectedTerminal = null, cardStatusMessage = "Поиск ридеров...") }
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val names = cardReaderBackend.listTerminalNames()
                                        withContext(Dispatchers.Main) {
                                            // Обновление состояния после получения списка ридеров
                                            updateState {
                                                copy(
                                                    terminals = names,
                                                    isLoadingTerminals = false,
                                                    cardStatusMessage = if (names.isEmpty()) "Ридеры не найдены." else "Выберите ридер из списка."
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = !appState.isLoadingTerminals && !appState.isProcessingCard,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Найти ридеры", modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Найти ридеры")
                            }
                            if (appState.isLoadingTerminals) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }

                        if (appState.terminals.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expanded = true },
                                    enabled = !appState.isProcessingCard && !appState.isCardConnected,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(appState.selectedTerminal ?: "Выберите ридер")
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.List, contentDescription = "Список ридеров")
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.fillMaxWidth(0.8f) // Ограничиваем ширину меню
                                ) {
                                    appState.terminals.forEach { terminalName ->
                                        DropdownMenuItem(onClick = {
                                            updateState { copy(selectedTerminal = terminalName, cardStatusMessage = "$terminalName выбран.") }
                                            expanded = false
                                        }) {
                                            Text(terminalName)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    appState.selectedTerminal?.let {
                                        updateState { copy(isProcessingCard = true, cardStatusMessage = "Подключение к $it...") }
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val success = cardReaderBackend.connectToTerminalByName(it)
                                            val protocol = if (success) cardReaderBackend.getConnectedCardProtocol() else null
                                            val connectedName = if (success) cardReaderBackend.getConnectedTerminalName() else null
                                            withContext(Dispatchers.Main) {
                                                updateState {
                                                    copy(
                                                        isProcessingCard = false,
                                                        isCardConnected = success,
                                                        cardStatusMessage = if (success) "Карта подключена." else "Не удалось подключиться к $it.",
                                                        cardInfo = if (success) "Ридер: $connectedName\nПротокол: $protocol" else null
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = appState.selectedTerminal != null && !appState.isCardConnected && !appState.isProcessingCard,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Подключить")
                            }

                            Button(
                                onClick = {
                                    updateState { copy(isProcessingCard = true, cardStatusMessage = "Отключение карты...") }
                                    coroutineScope.launch(Dispatchers.IO) {
                                        cardReaderBackend.disconnectCard()
                                        withContext(Dispatchers.Main) {
                                            updateState {
                                                copy(
                                                    isProcessingCard = false,
                                                    isCardConnected = false,
                                                    cardStatusMessage = "Карта отключена. Выберите ридер для нового подключения.",
                                                    cardInfo = null
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = appState.isCardConnected && !appState.isProcessingCard,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                            ) {
                                Text("Отключить", color = Color.White)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Секция информации и действий с картой
                Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Статус и данные карты", style = MaterialTheme.typography.h6)

                        if (appState.isProcessingCard && !appState.isLoadingTerminals) { // Не показываем этот индикатор при поиске терминалов
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appState.cardStatusMessage, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
                            }
                        } else {
                            Text(
                                appState.cardStatusMessage,
                                style = if (appState.cardStatusMessage.startsWith("Ошибка") || appState.cardStatusMessage.startsWith("Не удалось"))
                                    MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.error, fontWeight = FontWeight.Bold)
                                else
                                    MaterialTheme.typography.body1,
                                fontFamily = FontFamily.Monospace // Для HEX данных
                            )
                        }


                        appState.cardInfo?.let {
                            Text(it, style = MaterialTheme.typography.caption)
                        }

                        if (appState.isCardConnected && !appState.isProcessingCard) {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Button(
                                onClick = {
                                    updateState { copy(isProcessingCard = true, cardStatusMessage = "Чтение EF.SUME...") }
                                    coroutineScope.launch(Dispatchers.IO) {
                                        var resultMessage = "Не удалось прочитать EF.SUME." // Default error
                                        try {
                                            // Команды APDU (можно вынести в константы или helper object)
                                            val selectUsimAdfCmd = HexFormat.of().parseHex("00A4040008A0000000871002FF")
                                            val selectEfSumeCmd = HexFormat.of().parseHex("00A4020C026F18")
                                            val readBinaryEfSumeCmd = HexFormat.of().parseHex("00B0000000")

                                            val responseUsimAdf = cardReaderBackend.sendApdu(selectUsimAdfCmd)
                                            if (responseUsimAdf == null || !(responseUsimAdf.last() == 0x00.toByte() && responseUsimAdf[responseUsimAdf.size - 2] == 0x90.toByte())) {
                                                throw Exception("Ошибка выбора USIM ADF: ${formatApduResponseForDisplay(responseUsimAdf)}")
                                            }

                                            val responseEfSumeSelect = cardReaderBackend.sendApdu(selectEfSumeCmd)
                                            if (responseEfSumeSelect == null || !(responseEfSumeSelect.last() == 0x00.toByte() && responseEfSumeSelect[responseEfSumeSelect.size - 2] == 0x90.toByte())) {
                                                // Допускаем 62xx или 61xx как "успех с предупреждением/FCP" для мока
                                                val sw1 = responseEfSumeSelect?.getOrNull(responseEfSumeSelect.size - 2)
                                                if (sw1 != 0x62.toByte() && sw1 != 0x61.toByte() && sw1 != 0x90.toByte() ){ // 90 добавлено для полноты
                                                    throw Exception("Ошибка выбора EF.SUME: ${formatApduResponseForDisplay(responseEfSumeSelect)}")
                                                }
                                                println("Select EF.SUME response: ${formatApduResponseForDisplay(responseEfSumeSelect)}")
                                            }


                                            val responseReadBinary = cardReaderBackend.sendApdu(readBinaryEfSumeCmd)
                                            if (responseReadBinary == null || !(responseReadBinary.last() == 0x00.toByte() && responseReadBinary[responseReadBinary.size - 2] == 0x90.toByte())) {
                                                throw Exception("Ошибка чтения EF.SUME: ${formatApduResponseForDisplay(responseReadBinary)}")
                                            }
                                            resultMessage = "EF.SUME:\n${formatApduResponseForDisplay(responseReadBinary)}"

                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            resultMessage = "Ошибка: ${e.message}"
                                        }

                                        withContext(Dispatchers.Main) {
                                            updateState {
                                                copy(
                                                    isProcessingCard = false,
                                                    cardStatusMessage = resultMessage
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = appState.isCardConnected && !appState.isProcessingCard,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "Прочитать EF.SUME", modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Прочитать данные EF.SUME")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main() = application {
    val cardReaderBackend = remember { CardReaderBackend() } // Один экземпляр на все приложение
    Window(onCloseRequest = ::exitApplication, title = "Card Reader App - Improved UI") {
        App(cardReaderBackend)
    }
}
