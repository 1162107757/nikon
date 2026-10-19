package android.util;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
public final class AtomicFile {
    private final File base, backup;
    public AtomicFile(File file) { base = file; backup = new File(file + ".bak"); }
    public File getBaseFile() { return base; }
    public FileInputStream openRead() throws IOException {
        if (backup.exists()) { Files.deleteIfExists(base.toPath()); Files.move(backup.toPath(), base.toPath()); }
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        if (base.exists()) { if (backup.exists()) Files.delete(base.toPath()); else Files.move(base.toPath(), backup.toPath()); }
        return new FileOutputStream(base);
    }
    public void finishWrite(FileOutputStream stream) throws IOException { stream.close(); Files.deleteIfExists(backup.toPath()); }
    public void failWrite(FileOutputStream stream) { try { stream.close(); Files.deleteIfExists(base.toPath()); if (backup.exists()) Files.move(backup.toPath(), base.toPath()); } catch (IOException ignored) { } }
}
