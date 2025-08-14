package com.sexysalve.cardreaderapp;

import javax.smartcardio.*;
import apdu4j.TerminalManager;
import jnasmartcardio.Smartcardio;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HexFormat;
import java.nio.ByteBuffer;


// --- Начало заглушки для Card интерфейса (MockCard) ---
class MockCard extends Card {
    private final String terminalName;
    private final ATR atr;
    private final String protocol;
    private boolean connected = true;
    private final CardChannel mockChannel;


    private boolean usimAdfSelected = false;
    private boolean dfTelecomSelected = false;
    private boolean efSumeSelected = false;

    static final byte[] EF_SUME_MOCK_DATA = {
            (byte)0x01, (byte)0x23, (byte)0x45, (byte)0x67, (byte)0x89,
            (byte)0xAB, (byte)0xCD, (byte)0xEF,
            (byte)0x11, (byte)0x22,
            (byte)0xFE, (byte)0xDC, (byte)0xBA, (byte)0x98, (byte)0x76,
            (byte)0x54, (byte)0x32, (byte)0x10,
            (byte)0x33, (byte)0x44
    };

    private static final byte[] SELECT_DF_TELECOM = HexFormat.of().parseHex("00A40000027F10");
    private static final byte[] SELECT_EF_SUME = HexFormat.of().parseHex("00A40000026F54");


    public MockCard(String terminalName, String protocol) {
        this.terminalName = terminalName;
        this.protocol = protocol;
        this.atr = new ATR(new byte[]{(byte) 0x3B, (byte) 0xFF, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x81, (byte) 0x31, (byte) 0xFE, (byte) 0x45, (byte) 0x4A, (byte) 0x43, (byte) 0x4F, (byte) 0x50, (byte) 0x76, (byte) 0x32, (byte) 0x34, (byte) 0x31, (byte) 0xB7});
        this.mockChannel = new MockCardChannel(this);
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
        return new byte[0];
    }

    @Override
    public void disconnect(boolean reset) {
        System.out.println("MOCK: Card in " + terminalName + " disconnected (reset=" + reset + ")");
        this.connected = false;
        this.dfTelecomSelected = false;
        this.efSumeSelected = false;
    }

    public boolean isMockConnected() {
        return connected;
    }

    public String getTerminalName() {
        return terminalName;
    }

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

        if (cla == 0x00 && ins == (byte)0xA4) {
            if (p1 == 0x00 && p2 == 0x00 && nc == 2 && Arrays.equals(data, Arrays.copyOfRange(SELECT_DF_TELECOM, 5, 7))) {
                dfTelecomSelected = true;
                efSumeSelected = false;
                usimAdfSelected = false;
                System.out.println("MOCK: DF.TELECOM Selected.");
                return new ResponseAPDU(new byte[]{(byte)0x90, (byte)0x00});
            } else if (dfTelecomSelected && p1 == 0x00 && p2 == 0x00 && nc == 2 && Arrays.equals(data, Arrays.copyOfRange(SELECT_EF_SUME, 5, 7))) {
                efSumeSelected = true;
                System.out.println("MOCK: EF.SUME Selected under DF.TELECOM.");
                byte[] mockFcpSume = HexFormat.of().parseHex("620F8202000083026F548A01059000");
                mockFcpSume[4] = (byte) (EF_SUME_MOCK_DATA.length >> 8);
                mockFcpSume[5] = (byte) (EF_SUME_MOCK_DATA.length & 0xFF);
                return new ResponseAPDU(mockFcpSume);
            }
        } else if (dfTelecomSelected && efSumeSelected && cla == 0x00 && ins == (byte)0xB0) {
            int offset = (p1 & 0xFF) << 8 | (p2 & 0xFF);
            int bytesToRead = (ne == 0) ? 256 : ne;
            if (ne == 0 && (EF_SUME_MOCK_DATA.length - offset) < bytesToRead ) {
                 bytesToRead = EF_SUME_MOCK_DATA.length - offset;
            }


            System.out.println("MOCK: READ BINARY for EF.SUME. Offset: " + offset + ", Length: " + bytesToRead);
            if (offset >= EF_SUME_MOCK_DATA.length) {
                System.err.println("MOCK: READ BINARY offset out of bounds.");
                return new ResponseAPDU(new byte[]{(byte)0x6B, (byte)0x00});
            }
            int actualLength = Math.min(bytesToRead, EF_SUME_MOCK_DATA.length - offset);
             if (actualLength < 0) actualLength = 0; // Защита от отрицательной длины
            byte[] responseData = Arrays.copyOfRange(EF_SUME_MOCK_DATA, offset, offset + actualLength);
            byte[] fullResponse = new byte[responseData.length + 2];
            System.arraycopy(responseData, 0, fullResponse, 0, responseData.length);
            fullResponse[responseData.length] = (byte)0x90;
            fullResponse[responseData.length + 1] = (byte)0x00;
            System.out.println("MOCK: Returning " + actualLength + " bytes from EF.SUME.");
            return new ResponseAPDU(fullResponse);
        }

        System.err.println("MOCK: Unknown APDU or incorrect state for APDU: " + HexFormat.of().formatHex(apdu));
        return new ResponseAPDU(new byte[]{(byte)0x6A, (byte)0x82});
    }
}
// --- Конец заглушки MockCard ---

// --- Начало заглушки для CardChannel ---
class MockCardChannel extends CardChannel {
    private final MockCard card;

    public MockCardChannel(MockCard card) {
        this.card = card;
    }

    @Override
    public Card getCard() {
        return card;
    }

    @Override
    public int getChannelNumber() {
        return 0;
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardException {
        if (!card.isMockConnected()) {
            throw new CardException("Card not connected");
        }
        return card.processApdu(command);
    }

    @Override
    public int transmit(ByteBuffer command, ByteBuffer response) throws CardException {
        if (!card.isMockConnected()) {
            throw new CardException("Card not connected");
        }

        System.out.println("MOCK CardChannel: transmit(ByteBuffer, ByteBuffer) called. Converting to CommandAPDU.");
        byte[] cmdBytes = new byte[command.remaining()];
        command.get(cmdBytes);
        CommandAPDU cmdAPDU = new CommandAPDU(cmdBytes);
        ResponseAPDU rspAPDU = transmit(cmdAPDU);
        response.put(rspAPDU.getBytes());
        response.flip();
        return rspAPDU.getBytes().length;
    }


    @Override
    public void close() {
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
                this.factory = TerminalManager.getTerminalFactory(null); //CONTEXT
                System.out.println("TerminalFactory initialized successfully (REAL MODE with apdu4j).");
            }   catch (Exception e) { // Fallback for other exceptions
                System.err.println("CRITICAL UNEXPECTED ERROR: Could not initialize apdu4j TerminalFactory (REAL MODE).");
                System.err.println("Details: " + e.getMessage());
                this.factory = null;

                e.printStackTrace();
            }
        } else {
            System.out.println("CardReaderBackend initialized in MOCK_MODE.");
        }
    }

    private void handleJnaPCSCException(Smartcardio.JnaPCSCException pcscException, String contextMessage) {
        if (pcscException.code == 0x8010001d) { // SCARD_E_NO_SERVICE
            System.err.println("CRITICAL ERROR: The Smart Card Resource Manager service is not running.");
            System.err.println("Please ensure the 'Smart Card' service is started in Windows Services (services.msc) and try again.");
        } else {
            System.err.println("CRITICAL ERROR: PC/SC error while initializing TerminalFactory. PCSC Error Code: " + String.format("0x%08X", pcscException.code));
            String details = contextMessage != null ? contextMessage : pcscException.getMessage();
            if (details != null && !details.trim().isEmpty()){
                System.err.println("Details: " + details);
            }
        }
    }

    public List<String> listTerminalNames() {
        if (MOCK_MODE) {
            System.out.println("MOCK: Returning list of mock terminals.");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            return new ArrayList<>(mockTerminalNamesList);
        }

        if (factory == null) {
            System.err.println("TerminalFactory (apdu4j) not initialized, cannot get list of terminals (REAL MODE).");
            return Collections.emptyList();
        }
        try {
            List<CardTerminal> terminals = factory.terminals().list();
            if (terminals.isEmpty()) {
                System.out.println("No card readers found (REAL MODE with apdu4j).");
                return Collections.emptyList();
            }
            System.out.println("Found card readers (REAL MODE with apdu4j):");
            List<String> terminalNames = new ArrayList<>();
            for (CardTerminal terminal : terminals) {
                terminalNames.add(terminal.getName());
                System.out.println("- " + terminal.getName());
            }
            return terminalNames;
        } catch (jnasmartcardio.Smartcardio.JnaPCSCException pcscEx) {
            if (pcscEx.code == 0x8010001d) { // SCARD_E_NO_SERVICE
                System.err.println("CRITICAL ERROR: Smart Card Resource Manager service is not running (SCARD_E_NO_SERVICE).\nПроверьте, что служба 'Smart Card' (SCardSvr) запущена в Windows Services (services.msc).");
            } else {
                System.err.println("PCSC error while obtaining list of terminals: " + pcscEx.getMessage());
            }
            return Collections.emptyList();
        } catch (Smartcardio.EstablishContextException e) {
            System.err.println("Error while obtaining list of terminals (REAL MODE with apdu4j): " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        } catch (CardException e) {
            throw new RuntimeException(e);
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
            this.connectedCard = new MockCard(terminalName, mockProtocol);
            System.out.println("MOCK: Successfully connected to " + terminalName + ". Protocol: " + mockProtocol);
            return true;
        }

        if (factory == null) { System.err.println("Factory (apdu4j) is null in REAL MODE connect"); return false; }
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

            if (terminalToConnect == null) {
                System.err.println("Terminal not found in REAL MODE (apdu4j): " + terminalName);
                return false;
            }
            if (!terminalToConnect.isCardPresent()) {
                System.out.println("No card present in REAL MODE (apdu4j): " + terminalName);
                return false;
            }
            this.connectedCard = terminalToConnect.connect("*");
            System.out.println("Successfully connected to the card in terminal " + terminalName + " (REAL MODE with apdu4j). Protocol: " + this.connectedCard.getProtocol());
            return true;
        } catch (CardException e) {
            System.err.println("Connection error in REAL MODE (apdu4j) to " + terminalName + ": " + e.getMessage());
            this.connectedCard = null;
            return false;
        }
    }

    public void disconnectCard() {
        if (MOCK_MODE) {
            if (this.connectedCard != null && this.connectedCard instanceof MockCard) {
                System.out.println("MOCK: Disconnecting from " + ((MockCard)this.connectedCard).getTerminalName() + "...");
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                ((MockCard)this.connectedCard).disconnect(true);
                this.connectedCard = null;
                this.currentMockTerminalName = null;
                System.out.println("MOCK: Card successfully disconnected.");
            } else {
                System.out.println("MOCK: No active mock connection to disconnect or card is not a MockCard.");
            }
            return;
        }

        if (this.connectedCard != null) {
            try {
                System.out.println("Disconnecting from the card (REAL MODE with apdu4j)...");
                this.connectedCard.disconnect(true);
                System.out.println("Card successfully disconnected (REAL MODE with apdu4j).");
            } catch (CardException e) {
                System.err.println("Error when disconnecting the card (REAL MODE with apdu4j): " + e.getMessage());
                // TODO: Заменить e.printStackTrace()
                e.printStackTrace();
            } finally {
                this.connectedCard = null;
            }
        } else {
            System.out.println("No active connection to disconnect (REAL MODE with apdu4j).");
        }
    }

    public boolean isCardConnected() {
        if (MOCK_MODE) {
            return this.connectedCard != null && (this.connectedCard instanceof MockCard) && ((MockCard)this.connectedCard).isMockConnected();
        }
        return this.connectedCard != null;
    }

    public String getConnectedCardProtocol() {
        if (this.connectedCard != null) {
            try {
                return this.connectedCard.getProtocol();
            } catch (IllegalStateException e) {
                System.err.println("Error getting protocol (likely mock card was disconnected unexpectedly): " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public String getConnectedTerminalName() {
        if (MOCK_MODE) {
            if (this.connectedCard != null && this.connectedCard instanceof MockCard) {
                 return ((MockCard)this.connectedCard).getTerminalName();
            }
            return null;
        }
        if (this.connectedCard != null) {
            return "Real Terminal (Name not stored after connect)";
        }
        return null;
    }

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
            if (channel == null) {
                System.err.println((MOCK_MODE ? "MOCK" : "REAL") + ": Basic channel is null. Cannot send APDU.");
                return MOCK_MODE ? new byte[]{(byte)0x6F, (byte)0x00} : null;
            }

            CommandAPDU commandAPDU = new CommandAPDU(commandBytes);
            ResponseAPDU responseAPDU = channel.transmit(commandAPDU);
            byte[] responseBytes = responseAPDU.getBytes();

            System.out.println((MOCK_MODE ? "MOCK" : "REAL") + ": Received APDU Response: " + HexFormat.of().formatHex(responseBytes));
            return responseBytes;

        } catch (CardException e) {
            System.err.println((MOCK_MODE ? "MOCK" : "REAL") + ": CardException while sending APDU: " + e.getMessage());
            // TODO: Заменить e.printStackTrace()
            e.printStackTrace();
            return MOCK_MODE ? new byte[]{(byte)0x6F, (byte)0x01} : null;
        } catch (Exception e) {
            System.err.println((MOCK_MODE ? "MOCK" : "REAL") + ": General Exception while sending APDU: " + e.getMessage());
            // TODO: Заменить e.printStackTrace()
            e.printStackTrace();
            return MOCK_MODE ? new byte[]{(byte)0x6F, (byte)0x02} : null;
        }
    }

    public byte[] readEfSume() {
        if (!isCardConnected()) {
            System.err.println("Cannot read EF.SUME: Card not connected.");
            return null;
        }

        System.out.println("Attempting to read EF.SUME...");

        byte[] selectDfTelecomCmd = HexFormat.of().parseHex("00A40000027F10");
        byte[] responseSelectDf = sendApdu(selectDfTelecomCmd);

        if (responseSelectDf == null || responseSelectDf.length < 2 || !(responseSelectDf[responseSelectDf.length-2] == (byte)0x90 && responseSelectDf[responseSelectDf.length-1] == (byte)0x00)) {
            System.err.println("Failed to SELECT DF.TELECOM. Response: " + (responseSelectDf != null ? HexFormat.of().formatHex(responseSelectDf) : "null"));
            return null;
        }
        System.out.println("DF.TELECOM selected successfully.");

        byte[] selectEfSumeCmd = HexFormat.of().parseHex("00A40000026F54");
        byte[] responseSelectEfSume = sendApdu(selectEfSumeCmd);

        if (responseSelectEfSume == null || responseSelectEfSume.length < 2 || !(responseSelectEfSume[responseSelectEfSume.length-2] == (byte)0x90 && responseSelectEfSume[responseSelectEfSume.length-1] == (byte)0x00)) {
            System.err.println("Failed to SELECT EF.SUME. Response: " + (responseSelectEfSume != null ? HexFormat.of().formatHex(responseSelectEfSume) : "null"));
            if (responseSelectEfSume != null && responseSelectEfSume.length >=2 && responseSelectEfSume[responseSelectEfSume.length-2] == (byte)0x6A && responseSelectEfSume[responseSelectEfSume.length-1] == (byte)0x82) {
                System.err.println("Error 6A82: File not found. Check if EF.SUME (6F54) exists under DF.TELECOM (7F10) on this card.");
            }
            return null;
        }
        System.out.println("EF.SUME selected successfully. FCP: " + HexFormat.of().formatHex(Arrays.copyOfRange(responseSelectEfSume, 0, responseSelectEfSume.length -2)));

        int fileSize = -1;
        byte[] fcpData = Arrays.copyOfRange(responseSelectEfSume, 0, responseSelectEfSume.length - 2);
        for (int i = 0; i < fcpData.length - 3; i++) {
            if (fcpData[i] == (byte)0x82 && fcpData[i+1] == (byte)0x02) {
                fileSize = ((fcpData[i+2] & 0xFF) << 8) | (fcpData[i+3] & 0xFF);
                System.out.println("Parsed EF.SUME file size from FCP: " + fileSize + " bytes.");
                break;
            }
        }

        if (fileSize <= 0) {
             System.err.println("Could not determine EF.SUME file size from FCP or size is 0. FCP was: " + HexFormat.of().formatHex(fcpData));
             if (MOCK_MODE) {
                 fileSize = MockCard.EF_SUME_MOCK_DATA.length;
                 System.out.println("Using known MOCK_MODE file size: " + fileSize);
             } else {
                 System.err.println("Cannot proceed without file size in REAL_MODE.");
                 return null;
             }
        }

        int le;
        if (fileSize == 0) {
            le = 0;
        } else if (fileSize > 255) {
            le = 0x00; 
        } else {
            le = fileSize;
        }

        if (fileSize > 255 && !MOCK_MODE) {
            System.out.println("File size (" + fileSize + ") > 255. Attempting to read up to 256 bytes (Le=0x00). Multiple reads might be needed for full file.");
        }


        byte[] readBinaryCmd = new byte[]{(byte)0x00, (byte)0xB0, (byte)0x00, (byte)0x00, (byte)le};
        System.out.println("Attempting READ BINARY with Le = " + String.format("%02X", le) + " (decimal " + le + ")");
        byte[] responseReadBinary = sendApdu(readBinaryCmd);

        if (responseReadBinary == null || responseReadBinary.length < 2 || !(responseReadBinary[responseReadBinary.length-2] == (byte)0x90 && responseReadBinary[responseReadBinary.length-1] == (byte)0x00)) {
            System.err.println("Failed to READ BINARY EF.SUME. Response: " + (responseReadBinary != null ? HexFormat.of().formatHex(responseReadBinary) : "null"));
            return null;
        }

        byte[] efSumeData = Arrays.copyOfRange(responseReadBinary, 0, responseReadBinary.length - 2);
        System.out.println("Successfully read EF.SUME data (" + efSumeData.length + " bytes): " + HexFormat.of().formatHex(efSumeData));
        return efSumeData;
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

                System.out.println("\n--- Attempting to read EF.SUME ---");
                byte[] efSumeContent = backend.readEfSume();

                if (efSumeContent != null) {
                    System.out.println("\nSuccessfully retrieved EF.SUME content ("+efSumeContent.length+" bytes):");
                    System.out.println(HexFormat.of().formatHex(efSumeContent));
                    if (efSumeContent.length % 10 == 0 && efSumeContent.length > 0) {
                        System.out.println("EF.SUME seems to contain " + (efSumeContent.length / 10) + " records of 10 bytes each.");
                        for (int i = 0; i < efSumeContent.length; i+=10) {
                            byte[] record = Arrays.copyOfRange(efSumeContent, i, i+10);
                            System.out.println("Record " + (i/10 + 1) + ": " + HexFormat.of().formatHex(record));
                        }
                    } else {
                         System.out.println("EF.SUME content length (" + efSumeContent.length + ") is not a multiple of 10. Raw hex data printed above.");
                    }

                } else {
                    System.out.println("\nFailed to retrieve EF.SUME content.");
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
