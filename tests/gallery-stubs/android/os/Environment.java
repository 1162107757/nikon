package android.os;
import java.io.File;
public final class Environment {
    public static final String DIRECTORY_PICTURES = "Pictures";
    private static File publicPictures;
    public static void setPublicPicturesForTest(File directory) { publicPictures = directory; }
    public static File getExternalStoragePublicDirectory(String type) { return publicPictures; }
}
