package com.sysmon.app.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ADB 24 字节小端消息头 + data（移植自 Shizuku AdbMessage.kt）。 */
public class AdbMessage {

    public static final int HEADER_LENGTH = 24;

    public final int command;
    public final int arg0;
    public final int arg1;
    public final int dataLength;
    public final int dataCrc32;
    public final int magic;
    public final byte[] data;

    public AdbMessage(int command, int arg0, int arg1, int dataLength, int dataCrc32, int magic, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.dataLength = dataLength;
        this.dataCrc32 = dataCrc32;
        this.magic = magic;
        this.data = data;
    }

    public AdbMessage(int command, int arg0, int arg1, String data) {
        this(command, arg0, arg1, (data + "\u0000").getBytes());
    }

    public AdbMessage(int command, int arg0, int arg1, byte[] data) {
        this(command, arg0, arg1,
                data == null ? 0 : data.length,
                data == null ? 0 : crc32(data),
                (int) (command ^ 0xFFFFFFFFL),
                data);
    }

    public static String cmdName(int command) {
        switch (command) {
            case 0x4e584e43: return "CNXN";
            case 0x48545541: return "AUTH";
            case 0x4e45504f: return "OPEN";
            case 0x59414b4f: return "OKAY";
            case 0x45534c43: return "CLSE";
            case 0x45545257: return "WRTE";
            case 0x534c5453: return "STLS";
            default: return "0x" + Integer.toHexString(command);
        }
    }

    public boolean validate() {
        if (command != (magic ^ 0xFFFFFFFF)) return false;
        if (dataLength != 0 && crc32(data) != dataCrc32) return false;
        return true;
    }

    public byte[] toByteArray() {
        int length = HEADER_LENGTH + (data == null ? 0 : data.length);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        buffer.putInt(dataLength);
        buffer.putInt(dataCrc32);
        buffer.putInt(magic);
        if (data != null) buffer.put(data);
        return buffer.array();
    }

    private static int crc32(byte[] data) {
        if (data == null) return 0;
        int res = 0;
        for (byte b : data) {
            res += (b & 0xFF);
        }
        return res;
    }
}
