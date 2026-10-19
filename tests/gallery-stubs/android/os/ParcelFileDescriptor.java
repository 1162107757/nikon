package android.os;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
public final class ParcelFileDescriptor {
    private final File file;
    public ParcelFileDescriptor(File file) { this.file = file; }
    public static final class AutoCloseOutputStream extends FileOutputStream {
        public AutoCloseOutputStream(ParcelFileDescriptor descriptor) throws FileNotFoundException { super(descriptor.file); }
    }
}
