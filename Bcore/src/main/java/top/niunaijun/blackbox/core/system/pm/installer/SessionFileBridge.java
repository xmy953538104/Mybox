package top.niunaijun.blackbox.core.system.pm.installer;

import android.os.ParcelFileDescriptor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Implements the FileBridge stream protocol used by PackageInstaller.Session.openWrite. */
public final class SessionFileBridge extends Thread {
    private final ParcelFileDescriptor server;
    private final ParcelFileDescriptor client;
    private final RandomAccessFile output;
    private final Runnable onClose;
    private volatile boolean failed;

    public SessionFileBridge(File file, long offset, Runnable onClose) throws IOException {
        super("Mybox-apk-write");
        this.onClose = onClose;
        output = new RandomAccessFile(file, "rw");
        if (offset > output.length()) {
            output.close();
            throw new IOException("Write offset exceeds file size");
        }
        if (offset == 0) output.setLength(0);
        output.seek(offset);
        ParcelFileDescriptor[] sockets;
        try { sockets = ParcelFileDescriptor.createSocketPair(); }
        catch (IOException e) { output.close(); throw e; }
        server = sockets[0];
        client = sockets[1];
    }

    public ParcelFileDescriptor client() { return client; }
    public boolean failed() { return failed; }

    public void cancel() {
        try { server.close(); } catch (IOException ignored) { }
        try { client.close(); } catch (IOException ignored) { }
    }

    @Override public void run() {
        boolean closed = false;
        try {
            DataInputStream input = new DataInputStream(new java.io.FileInputStream(server.getFileDescriptor()));
            DataOutputStream reply = new DataOutputStream(new java.io.FileOutputStream(server.getFileDescriptor()));
            byte[] buffer = new byte[65536];
            while (!closed) {
                int command = input.readInt();
                int count = input.readInt();
                if (command == 1) {
                    if (count < 0) throw new IOException("Invalid FileBridge write length");
                    while (count > 0) {
                        int read = input.read(buffer, 0, Math.min(buffer.length, count));
                        if (read == -1) throw new IOException("Incomplete FileBridge write");
                        output.write(buffer, 0, read);
                        count -= read;
                    }
                } else if (command == 2 || command == 3) {
                    output.getFD().sync();
                    closed = command == 3;
                    // Signal state before the close acknowledgement can reach commit's caller.
                    if (closed) onClose.run();
                    reply.writeInt(command);
                    reply.writeInt(0);
                    reply.flush();
                } else throw new IOException("Unknown FileBridge command");
            }
        } catch (Exception e) {
            failed = true;
        } finally {
            try { output.close(); } catch (IOException ignored) { failed = true; }
            try { server.close(); } catch (IOException ignored) { }
            if (!closed) onClose.run();
        }
    }
}
