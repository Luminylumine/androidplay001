package com.sysmon.app.adb;

import com.sysmon.app.SysLog;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.net.ssl.SSLSocket;

import static com.sysmon.app.adb.AdbProtocol.*;

/** ADB 客户端：连接 + shell 执行（移植自 Shizuku AdbClient.kt）。 */
public class AdbClient {

    private final String host;
    private final int port;
    private final AdbKey key;

    private Socket socket;
    private DataInputStream plainIn;
    private DataOutputStream plainOut;
    private boolean useTls = false;
    private SSLSocket tlsSocket;
    private DataInputStream tlsIn;
    private DataOutputStream tlsOut;

    public AdbClient(String host, int port, AdbKey key) {
        this.host = host;
        this.port = port;
        this.key = key;
    }

    private DataInputStream in() { return useTls ? tlsIn : plainIn; }
    private DataOutputStream out() { return useTls ? tlsOut : plainOut; }

    /** 连接并完成握手（STLS/TLS 或 legacy AUTH）。失败抛异常。 */
    public void connect() throws Exception {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        plainIn = new DataInputStream(socket.getInputStream());
        plainOut = new DataOutputStream(socket.getOutputStream());

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::");
        SysLog.d("AdbClient sent CNXN, awaiting response");

        AdbMessage message = read();
        SysLog.d("AdbClient got response cmd=" + AdbMessage.cmdName(message.command)
                + " arg0=" + message.arg0 + " arg1=" + message.arg1);
        if (message.command == A_STLS) {
            write(A_STLS, A_STLS_VERSION, 0);
            SysLog.d("AdbClient sent STLS, starting TLS handshake");
            javax.net.ssl.SSLContext sslContext = key.sslContext();
            tlsSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(socket, host, port, true);
            tlsSocket.startHandshake();
            SysLog.i("AdbClient TLS handshake ok");
            tlsIn = new DataInputStream(tlsSocket.getInputStream());
            tlsOut = new DataOutputStream(tlsSocket.getOutputStream());
            useTls = true;
            message = read();
            SysLog.d("AdbClient post-TLS response cmd=" + AdbMessage.cmdName(message.command));
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) throw new IllegalStateException("not A_AUTH TOKEN arg0=" + message.arg0);
            SysLog.d("AdbClient got AUTH TOKEN, signing with " + message.dataLength + " bytes");
            byte[] sig = key.sign(message.data);
            SysLog.d("AdbClient signature length=" + sig.length);
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
            message = read();
            SysLog.d("AdbClient after SIGNATURE cmd=" + AdbMessage.cmdName(message.command));
            if (message.command != A_CNXN) {
                SysLog.d("AdbClient sending RSAPUBLICKEY");
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey());
                message = read();
                SysLog.d("AdbClient after RSAPUBLICKEY cmd=" + AdbMessage.cmdName(message.command));
            }
        }
        if (message.command != A_CNXN) throw new IllegalStateException("not A_CNXN, got=" + AdbMessage.cmdName(message.command));
        SysLog.i("AdbClient connected to " + host + ":" + port + " tls=" + useTls);
    }

    /** 执行 shell 命令，返回完整输出（不含退出码）。 */
    public String exec(String command) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int localId = 1;
        write(A_OPEN, localId, 0, "shell:" + command);
        AdbMessage message = read();
        if (message.command == A_OKAY) {
            while (true) {
                message = read();
                int remoteId = message.arg0;
                if (message.command == A_WRTE) {
                    if (message.data != null && message.dataLength > 0) {
                        out.write(message.data, 0, message.dataLength);
                    }
                    write(A_OKAY, localId, remoteId);
                } else if (message.command == A_CLSE) {
                    write(A_CLSE, localId, remoteId);
                    break;
                } else {
                    throw new IllegalStateException("not A_WRTE or A_CLSE");
                }
            }
        } else if (message.command == A_CLSE) {
            write(A_CLSE, localId, message.arg0);
        } else {
            throw new IllegalStateException("not A_OKAY or A_CLSE");
        }
        return out.toString("UTF-8");
    }

    /** 批量读文件（经 shell cat，带分隔符）。 */
    public String readFiles(String[] paths) throws Exception {
        StringBuilder cmd = new StringBuilder();
        for (String p : paths) {
            cmd.append("echo '=== ").append(p).append(" ==='; cat '").append(p).append("' 2>/dev/null; echo;");
        }
        return exec(cmd.toString());
    }

    private void write(int command, int arg0, int arg1) throws Exception {
        write(new AdbMessage(command, arg0, arg1, (byte[]) null));
    }

    private void write(int command, int arg0, int arg1, byte[] data) throws Exception {
        write(new AdbMessage(command, arg0, arg1, data));
    }

    private void write(int command, int arg0, int arg1, String data) throws Exception {
        write(new AdbMessage(command, arg0, arg1, data));
    }

    private void write(AdbMessage message) throws Exception {
        out().write(message.toByteArray());
        out().flush();
    }

    private AdbMessage read() throws Exception {
        byte[] header = new byte[AdbMessage.HEADER_LENGTH];
        in().readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buffer.getInt();
        int arg0 = buffer.getInt();
        int arg1 = buffer.getInt();
        int dataLength = buffer.getInt();
        int checksum = buffer.getInt();
        int magic = buffer.getInt();
        byte[] data = null;
        if (dataLength > 0) {
            data = new byte[dataLength];
            in().readFully(data, 0, dataLength);
        }
        AdbMessage msg = new AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data);
        if (!msg.validate()) throw new IllegalStateException("bad adb message");
        return msg;
    }

    public void close() {
        try { if (plainIn != null) plainIn.close(); } catch (Throwable ignored) {}
        try { if (plainOut != null) plainOut.close(); } catch (Throwable ignored) {}
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        if (useTls) {
            try { if (tlsIn != null) tlsIn.close(); } catch (Throwable ignored) {}
            try { if (tlsOut != null) tlsOut.close(); } catch (Throwable ignored) {}
            try { if (tlsSocket != null) tlsSocket.close(); } catch (Throwable ignored) {}
        }
    }
}
