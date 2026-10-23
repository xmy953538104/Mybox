package top.niunaijun.blackbox.core.system.pm.installer;

public final class InstallSessionState {
    private int writers;
    private boolean sealed;
    private boolean finished;

    public synchronized void requireMutable() {
        if (sealed || finished) throw new IllegalStateException("Session is sealed or finished");
    }

    public synchronized void openWriter() {
        requireMutable();
        writers++;
    }

    public synchronized void closeWriter() {
        if (writers <= 0) throw new IllegalStateException("No open writer");
        writers--;
        notifyAll();
    }

    public synchronized void seal() {
        requireMutable();
        if (writers != 0) throw new IllegalStateException("Close every write stream before commit");
        sealed = true;
    }

    public synchronized void finish() { finished = true; }
    public synchronized boolean isSealed() { return sealed; }
    public synchronized boolean isFinished() { return finished; }
}
