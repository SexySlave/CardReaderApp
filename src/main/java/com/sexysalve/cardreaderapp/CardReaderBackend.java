package com.sexysalve.cardreaderapp;

import javax.smartcardio.*;
import java.util.ArrayList;
import java.util.Arrays; // Для сравнения массивов байт
import java.util.Collections;
import java.util.List;
import java.util.HexFormat; // Для удобного вывода байт в HEX (Java 17+)

// --- Начало заглушки для Card интерфейса (MockCard) ---
class MockCard extends Card {
    private final String terminalName;
    private final ATR atr;
    private final String protocol;
    private boolean connected = true;
    private CardChannel mockChannel; // Для мокирования CardChannel

    // Имитация файловой системы карты
    private boolean usimAdfSelected = false;
    private boolean efSumeSelected = false;

    // Примерное содержимое EF.SUME (Subscriber Usage Measurement Entry)
    // Это просто примерные байты
    // Здесь 2 записи по 10 байт
    static final byte[] EF_SUME_MOCK_DATA = {
            // Запись 1
            (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89, // Номер IMSI/MSISDN (часть)
            (byte)0xAB, (byte)0xCD, (byte)0xEF,                         // Дата/Время
            (byte)0x11, (byte)0x22,                                     // Счетчик
            // Запись 2
            (byte)0xFE, (byte)0xDC, (byte)0xBA, (byte)0x98, (byte)0x76,
            (byte)0x54, (byte)0x32, (byte)0x10,
            (byte)0x33, (byte)0x44
    };

    // APDU команды, которые мы будем "понимать"
    // SELECT USIM ADF (A0000000871002...)
    private static final byte[] SELECT_USIM_ADF = HexFormat.of().parseHex("00A4040008A0000000871002FF"); // FF - Le, ожидаем ответ
    // SELECT EF.SUME (файл ID 6F18 под USIM ADF)
    private static final byte[] SELECT_EF_SUME = HexFormat.of().parseHex("00A4020C026F18"); // 6F18 - это пример ID, он может быть другим
    // READ BINARY
    // Команда будет вида 00 B0 <offset_hi> <offset_lo> <Le>


    public MockCard(String terminalName, String protocol) {
        this.terminalName = terminalName;
        this.protocol = protocol;
        this.atr = new ATR(new byte[]{(byte) 0x3B, (byte) 0xFF, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x81, (byte) 0x31, (byte) 0xFE, (byte) 0x45, (byte) 0x4A, (byte) 0x43, (byte) 0x4F, (byte) 0x50, (byte) 0x76, (byte) 0x32, (byte) 0x34, (byte) 0x31, (byte) 0xB7});
        this.mockChannel = new MockCardChannel(this); // Передаем ссылку на MockCard
    }

    @Override
    public ATR getATR() {
        if (!connected) throw new IllegalStateException("Card not connected");
        return this.atr;
    }

    @Override
    public String getProtocol() {
        if (!connected) throw new IllegalStateException("Card not connected");
        return this.protocol;
    }

    @Override
    public CardChannel getBasicChannel() {
        if (!connected) throw new IllegalStateException("Card not connected");
        System.out.println("MOCK: getBasicChannel() called for " + terminalName);
        return this.mockChannel;
    }

    @Override
    public CardChannel openLogicalChannel() throws CardException {
        if (!connected) throw new CardException("Card not connected or logical channels not supported by mock");
        System.out.println("MOCK: openLogicalChannel() called for " + terminalName + ". Returning basic channel for simplicity.");
        // В простом моке можем вернуть тот же basic channel или null/ошибку, если не хотим это поддерживать.
        return this.mockChannel;
    }


    @Override
    public void beginExclusive() throws CardException {
        if (!connected) throw new CardException("Card not connected");
        System.out.println("MOCK: beginExclusive() for " + terminalName);
    }

    @Override
    public void endExclusive() throws CardException {
        if (!connected) throw new CardException("Card not connected");
        System.out.println("MOCK: endExclusive() for " + terminalName);
    }

    @Override
    public byte[] transmitControlCommand(int controlCode, byte[] command) throws CardException {
        if (!connected) throw new CardException("Card not connected");
        System.out.println("MOCK: transmitControlCommand() for " + terminalName + " (not implemented in mock)");
        return new byte[0]; // Empty response
    }

    @Override
    public void disconnect(boolean reset) throws CardException {
        System.out.println("MOCK: Card in " + terminalName + " disconnected (reset=" + reset + ")");
        this.connected = false;
        this.usimAdfSelected = false;
        this.efSumeSelected = false;
    }

    public boolean isMockConnected() {
        return connected;
    }

    // Внутренний метод для обработки APDU в MockCardChannel
    protected ResponseAPDU processApdu(CommandAPDU command) {
        byte[] apdu = command.getBytes();
        byte cla = (byte) command.getCLA();
        byte ins = (byte) command.getINS();
        byte p1 = (byte) command.getP1();
        byte p2 = (byte) command.getP2();
        int nc = command.getNc();
        byte[] data = command.getData();
        int ne = command.getNe();

        System.out.println("MOCK CardChannel: Processing APDU: " + HexFormat.of().formatHex(apdu));

        if (cla == 0x00 && ins == (byte)0xA4) { // SELECT command
            if (p1 == 0x04 && p2 == 0x00) { // Select by DF Name (AID)
                // Сравниваем только значащую часть AID, игнорируя Le
                byte[] aidToSelect = Arrays.copyOfRange(SELECT_USIM_ADF, 5, 5 + SELECT_USIM_ADF[4]);
                if (Arrays.equals(data, aidToSelect)) {
                    usimAdfSelected = true;
                    efSumeSelected = false; // Сбрасываем выбор EF при выборе ADF
                    System.out.println("MOCK: USIM ADF Selected.");
                    // Успешный ответ для SELECT (FCI может быть более сложным)
                    // Для простоты, просто 9000
                    return new ResponseAPDU(new byte[]{(byte)0x90, (byte)0x00});
                }
            } else if (usimAdfSelected && p1 == 0x02 && p2 == 0x0C) { // Select EF by File ID (под текущим DF)
                // Сравниваем только ID файла
                byte[] fileIdToSelect = Arrays.copyOfRange(SELECT_EF_SUME, 5, 5 + SELECT_EF_SUME[4]);
                if (Arrays.equals(data, fileIdToSelect)) {
                    efSumeSelected = true;
                    System.out.println("MOCK: EF.SUME Selected.");
                    // Ответ для SELECT EF обычно содержит информацию о файле (FCP)
                    // Для мока, просто 9000 или можно вернуть мок-FCP
                    // Пример мок-FCP с размером файла:
                    // 62 0F (FCP template)
                    //    82 02 78 21 (File descriptor: EF, Transparent, size 33)
                    //    83 02 6F 18 (File ID)
                    //    8A 01 05    (Life Cycle Status: Activated)
                    // 90 00
                    byte[] mockFcpSume = HexFormat.of().parseHex("620F8202001483026F188A01059000"); // 0014 hex = 20 dec (размер EF_SUME_MOCK_DATA)
                    // Заменяем байты размера файла на актуальный
                    mockFcpSume[4] = (byte) (EF_SUME_MOCK_DATA.length >> 8); // Старший байт размера
                    mockFcpSume[5] = (byte) (EF_SUME_MOCK_DATA.length & 0xFF); // Младший байт размера

                    return new ResponseAPDU(mockFcpSume);
                }
            }
        } else if (usimAdfSelected && efSumeSelected && cla == 0x00 && ins == (byte)0xB0) { // READ BINARY
            int offset = (p1 & 0xFF) << 8 | (p2 & 0xFF);
            int bytesToRead = (ne == 0) ? 256 : ne; // Если Le=00, значит 256
            if (ne == 0 && EF_SUME_MOCK_DATA.length - offset < 256) { // Если Le=00, но осталось меньше 256
                bytesToRead = EF_SUME_MOCK_DATA.length - offset;
            }


            System.out.println("MOCK: READ BINARY for EF.SUME. Offset: " + offset + ", Length: " + bytesToRead);

            if (offset >= EF_SUME_MOCK_DATA.length) {
                System.err.println("MOCK: READ BINARY offset out of bounds.");
                return new ResponseAPDU(new byte[]{(byte)0x6B, (byte)0x00}); // Wrong P1/P2 (Offset out of range)
            }

            int actualLength = Math.min(bytesToRead, EF_SUME_MOCK_DATA.length - offset);
            byte[] responseData = Arrays.copyOfRange(EF_SUME_MOCK_DATA, offset, offset + actualLength);
            byte[] fullResponse = new byte[responseData.length + 2];
            System.arraycopy(responseData, 0, fullResponse, 0, responseData.length);
            fullResponse[responseData.length] = (byte)0x90;
            fullResponse[responseData.length + 1] = (byte)0x00;

            System.out.println("MOCK: Returning " + actualLength + " bytes from EF.SUME.");
            return new ResponseAPDU(fullResponse);
        }

        System.err.println("MOCK: Unknown APDU or incorrect state for APDU: " + HexFormat.of().formatHex(apdu));
        return new ResponseAPDU(new byte[]{(byte)0x6A, (byte)0x82}); // File not found or Command not allowed
    }
}
// --- Конец заглушки MockCard ---

// --- Начало заглушки для CardChannel ---
class MockCardChannel extends CardChannel {
    private final MockCard card; // Ссылка на родительскую MockCard

    public MockCardChannel(MockCard card) {
        this.card = card;
    }

    @Override
    public Card getCard() {
        return card;
    }

    @Override
    public int getChannelNumber() {
        return 0; // Базовый канал
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardException {
        if (!card.isMockConnected()) {
            throw new CardException("Card not connected");
        }
        // Делегируем обработку APDU в MockCard
        return card.processApdu(command);
    }

    @Override
    public int transmit(java.nio.ByteBuffer command, java.nio.ByteBuffer response) throws CardException {
        if (!card.isMockConnected()) {
            throw new CardException("Card not connected");
        }

        System.out.println("MOCK CardChannel: transmit(ByteBuffer, ByteBuffer) called. Converting to CommandAPDU.");
        byte[] cmdBytes = new byte[command.remaining()];
        command.get(cmdBytes);
        CommandAPDU cmdAPDU = new CommandAPDU(cmdBytes);
        ResponseAPDU rspAPDU = transmit(cmdAPDU);
        response.put(rspAPDU.getBytes());
        response.flip(); // Подготовить буфер для чтения
        return rspAPDU.getBytes().length;
    }


    @Override
    public void close() throws CardException {
        System.out.println("MOCK CardChannel: close() called.");

    }
}
// --- Конец заглушки для CardChannel ---


public class CardReaderBackend {

    private final boolean MOCK_MODE = true;
    private TerminalFactory factory;
    private Card connectedCard;
    private String currentMockTerminalName;

    private final List<String> mockTerminalNamesList = List.of(
            "Mock USB Reader 0 (GemFake)",
            "Mock Bluetooth Reader (VirtuaBlue)",
            "Mock NFC Antenna (SimulaTap)"
    );

    public CardReaderBackend() {
        if (!MOCK_MODE) {
            try {
                this.factory = TerminalFactory.getDefault();
                System.out.println("TerminalFactory initialized successfully (REAL MODE).");
            } catch (Exception e) {
                System.err.println("CRITICAL ERROR: Could not initialize TerminalFactory (REAL MODE). Ensure PC/SC service is running.");
                e.printStackTrace();
                this.factory = null;
            }
        } else {
            System.out.println("CardReaderBackend initialized in MOCK_MODE.");
        }
    }

    public List<String> listTerminalNames() {
        if (MOCK_MODE) {
            System.out.println("MOCK: Returning list of mock terminals.");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            return new ArrayList<>(mockTerminalNamesList);
        }
        // ... (реальная логика без изменений)
        if (factory == null) {
            System.err.println("TerminalFactory not initialized, cannot get list of terminals (REAL MODE).");
            return Collections.emptyList();
        }
        try {
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) {
                System.out.println("No card readers found (REAL MODE).");
                return Collections.emptyList();
            }
            System.out.println("Found card readers (REAL MODE):");
            List<String> terminalNames = new ArrayList<>();
            for (CardTerminal terminal : terminals) {
                terminalNames.add(terminal.getName());
                System.out.println("- " + terminal.getName());
            }
            return terminalNames;
        } catch (CardException e) {
            System.err.println("Error while obtaining list of terminals (REAL MODE): " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public boolean connectToTerminalByName(String terminalName) {
        if (MOCK_MODE) {
            if (terminalName == null || terminalName.isEmpty() || !mockTerminalNamesList.contains(terminalName)) {
                System.err.println("MOCK: Invalid terminal name or not found in mock list: " + terminalName);
                return false;
            }
            if (this.connectedCard != null) {
                System.out.println("MOCK: Already connected to " + this.currentMockTerminalName + ". Please disconnect first.");
                return false;
            }
            System.out.println("MOCK: Attempting to connect to terminal: " + terminalName);
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            this.currentMockTerminalName = terminalName;
            String mockProtocol = terminalName.contains("USB") ? "T=0 (Mock)" : "T=1 (Mock)";
            this.connectedCard = new MockCard(terminalName, mockProtocol); // Используем MockCard
            System.out.println("MOCK: Successfully connected to " + terminalName + ". Protocol: " + mockProtocol);
            return true;
        }
        // ... (реальная логика без изменений, но надо убедиться, что this.connectedCard присваивается правильно)
        // не возможности проверить (нет оборудования)        if (factory == null) { System.err.println("Factory is null in REAL MODE connect"); return false; }
        if (terminalName == null || terminalName.isEmpty()) { System.err.println("Terminal name is null/empty in REAL MODE connect"); return false; }
        if (this.connectedCard != null) { System.err.println("Already connected in REAL MODE connect"); return false; }
        try {
            CardTerminal terminalToConnect = null;
            List<CardTerminal> terminals = factory.terminals().list();
            for (CardTerminal terminal : terminals) {
                if (terminal.getName().equals(terminalName)) {
                    terminalToConnect = terminal;
                    break;
                }
            }
            if (terminalToConnect == null) { System.err.println("Terminal not found in REAL MODE: " + terminalName); return false; }
            if (!terminalToConnect.isCardPresent()) { System.out.println("No card present in REAL MODE: " + terminalName);return false; }
            this.connectedCard = terminalToConnect.connect("*"); // Присваиваем реальную карту
            System.out.println("Successfully connected to the card in terminal " + terminalName + " (REAL MODE). Protocol: " + this.connectedCard.getProtocol());
            return true;
        } catch (CardException e) {
            System.err.println("Connection error in REAL MODE to " + terminalName + ": " + e.getMessage());
            this.connectedCard = null;
            return false;
        }
    }

    public void disconnectCard() {
        if (MOCK_MODE) {
            if (this.connectedCard != null) {
                System.out.println("MOCK: Disconnecting from " + this.currentMockTerminalName + "...");
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                try {
                    this.connectedCard.disconnect(true);
                } catch (CardException e) {
                    System.err.println("MOCK: Error calling disconnect on MockCard: " + e.getMessage());
                }
                this.connectedCard = null;
                this.currentMockTerminalName = null;
                System.out.println("MOCK: Card successfully disconnected.");
            } else {
                System.out.println("MOCK: No active connection to disconnect.");
            }
            return;
        }
        // ... (реальная логика без изменений)
        if (this.connectedCard != null) {
            try {
                System.out.println("Disconnecting from the card (REAL MODE)...");
                this.connectedCard.disconnect(true);
                System.out.println("Card successfully disconnected (REAL MODE).");
            } catch (CardException e) {
                System.err.println("Error when disconnecting the card (REAL MODE): " + e.getMessage());
                e.printStackTrace();
            } finally {
                this.connectedCard = null;
            }
        } else {
            System.out.println("No active connection to disconnect (REAL MODE).");
        }
    }

    public boolean isCardConnected() {
        if (MOCK_MODE) {
            return this.connectedCard != null && ((MockCard)this.connectedCard).isMockConnected();
        }
        return this.connectedCard != null;
    }

    public String getConnectedCardProtocol() {
        if (this.connectedCard != null) { // Логика одна для обоих режимов, так как getProtocol есть у Card и MockCard
            try {
                return this.connectedCard.getProtocol();
            } catch (IllegalStateException e) { // На случай, если MockCard бросает ошибку при отключении
                System.err.println("Error getting protocol (likely mock card was disconnected unexpectedly): " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public String getConnectedTerminalName() {
        if (MOCK_MODE) {
            if (this.connectedCard != null) {
                return this.currentMockTerminalName;
            }
            return null;
        }
        // В реальном режиме мы не храним отдельно CardTerminal после подключения,
        // но можно было бы. Для простоты, если карта подключена, можно вернуть общее имя.
        if (this.connectedCard != null) {
            // Если бы мы сохраняли CardTerminal currentRealTerminal, то вернули бы currentRealTerminal.getName()
            return "Real Terminal (Connected)"; // Заглушка для реального режима
        }
        return null;
    }

    /**
     * Отправляет APDU команду на подключенную карту и возвращает ответ.
     * В MOCK_MODE имитирует ответ для известных команд (SELECT USIM, SELECT EF.SUME, READ BINARY EF.SUME).
     * @param commandBytes APDU команда в виде массива байт.
     * @return Ответ от карты в виде массива байт (включая SW1SW2) или null при ошибке.
     */
    public byte[] sendApdu(byte[] commandBytes) {
        if (!isCardConnected()) {
            System.err.println("Cannot send APDU: Card not connected.");
            return null;
        }
        if (commandBytes == null || commandBytes.length == 0) {
            System.err.println("Cannot send APDU: Command is null or empty.");
            return null;
        }

        System.out.println((MOCK_MODE ? "MOCK" : "REAL") + ": Sending APDU: " + HexFormat.of().formatHex(commandBytes));

        try {
            CardChannel channel = this.connectedCard.getBasicChannel();
            if (channel == null && MOCK_MODE) { // MockCardChannel должен был быть создан
                System.err.println("MOCK: Basic channel is null in MockCard. This shouldn't happen.");
                return new byte[]{(byte)0x6F, (byte)0x00}; // Generic error
            } else if (channel == null) {
                System.err.println("REAL: Basic channel is null. Cannot send APDU.");
                return null;
            }

            CommandAPDU commandAPDU = new CommandAPDU(commandBytes);
            ResponseAPDU responseAPDU = channel.transmit(commandAPDU);
            byte[] responseBytes = responseAPDU.getBytes();

            System.out.println((MOCK_MODE ? "MOCK" : "REAL") + ": Received APDU Response: " + HexFormat.of().formatHex(responseBytes));
            return responseBytes;

        } catch (CardException e) {
            System.err.println((MOCK_MODE ? "MOCK" : "REAL") + ": CardException while sending APDU: " + e.getMessage());
            e.printStackTrace();
            return null; // Или можно вернуть байты ошибки, например, 6F00
        } catch (Exception e) { // Ловим другие возможные ошибки, особенно в моке
            System.err.println((MOCK_MODE ? "MOCK" : "REAL") + ": General Exception while sending APDU: " + e.getMessage());
            e.printStackTrace();
            return new byte[]{(byte)0x6F, (byte)0x01}; // Другая общая ошибка
        }
    }


    public static void main(String[] args) {
        CardReaderBackend backend = new CardReaderBackend();
        System.out.println("--- Testing CardReaderBackend in " + (backend.MOCK_MODE ? "MOCK_MODE" : "REAL_MODE") + " ---");

        List<String> terminalNames = backend.listTerminalNames();
        System.out.println("Found terminals: " + terminalNames);

        if (!terminalNames.isEmpty()) {
            String terminalToTest = terminalNames.get(0);
            System.out.println("\nAttempting to connect to: " + terminalToTest);
            if (backend.connectToTerminalByName(terminalToTest)) {
                System.out.println("Connection status: " + backend.isCardConnected());
                System.out.println("Protocol: " + backend.getConnectedCardProtocol());
                System.out.println("Terminal name: " + backend.getConnectedTerminalName());

                if (backend.MOCK_MODE) {
                    System.out.println("\n--- MOCK APDU Test for EF.SUME ---");
                    // 1. SELECT USIM ADF
                    byte[] selectUsimAdfCmd = HexFormat.of().parseHex("00A4040008A0000000871002FF"); // AID of USIM ADF + Le
                    byte[] response1 = backend.sendApdu(selectUsimAdfCmd);
                    System.out.println("Response to SELECT USIM ADF: " + (response1 != null ? HexFormat.of().formatHex(response1) : "null"));

                    // 2. SELECT EF.SUME (пример ID, может отличаться)
                    // Предположим, что USIM ADF был успешно выбран
                    if (response1 != null && response1[response1.length-2] == (byte)0x90 && response1[response1.length-1] == (byte)0x00) {
                        // Файл ID EF.SUME (6F18) - это пример, может быть другим
                        // На реальной карте нужно будет сначала узнать его из ответа на SELECT USIM ADF (FCI) или из спецификаций.
                        byte[] selectEfSumeCmd = HexFormat.of().parseHex("00A4020C026F18"); // Le=00, ожидаем FCP
                        byte[] response2 = backend.sendApdu(selectEfSumeCmd);
                        System.out.println("Response to SELECT EF.SUME: " + (response2 != null ? HexFormat.of().formatHex(response2) : "null"));

                        // 3. READ BINARY EF.SUME
                        // Предположим, что EF.SUME был успешно выбран, и из FCP мы знаем его размер.
                        // Для мока мы знаем размер MockCard.EF_SUME_MOCK_DATA.length = 20 (0x14)
                        if (response2 != null && response2[response2.length-2] == (byte)0x90 && response2[response2.length-1] == (byte)0x00) {
                            int fileSize = MockCard.EF_SUME_MOCK_DATA.length; // В реальном коде парсить из FCP
                            System.out.println("Mock EF.SUME size: " + fileSize);
                            byte[] readBinaryCmd = HexFormat.of().parseHex("00B00000" + String.format("%02X", fileSize)); // Offset 0000, Le = fileSize
                            byte[] response3 = backend.sendApdu(readBinaryCmd);
                            System.out.println("Response to READ BINARY EF.SUME: " + (response3 != null ? HexFormat.of().formatHex(response3) : "null"));

                            if (response3 != null && response3.length > 2) {
                                byte[] dataOnly = Arrays.copyOfRange(response3, 0, response3.length - 2);
                                System.out.println("EF.SUME Mock Data: " + HexFormat.of().formatHex(dataOnly));
                                // Здесь можно добавить логику для парсинга и отображения этих данных
                            }
                        }
                    }
                }

                System.out.println("\nAttempting to disconnect...");
                backend.disconnectCard();
                System.out.println("Connection status after disconnection: " + backend.isCardConnected());
            } else {
                System.out.println("Could not connect to " + terminalToTest);
            }
        } else {
            System.out.println("Test cannot be continued: no terminals found.");
        }
        System.out.println("\n--- Test CardReaderBackend finished ---");
    }
}
