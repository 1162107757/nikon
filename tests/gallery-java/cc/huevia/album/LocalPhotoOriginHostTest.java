package cc.huevia.album;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Real origin-proof reader and publisher with a file-backed, fault-injecting provider boundary. */
public final class LocalPhotoOriginHostTest {
    private static final byte[] JPEG = {(byte)255,(byte)216,(byte)255,(byte)225,0,10,'E','x','i','f',0,0,31,42,(byte)255,(byte)217};
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("local-photo-origin-");
        try {
            matchingSourceIsReusedWithoutClaimingOwnership(root.resolve("existing"));
            equivalentOwnedPathStillFindsOrigin(root.resolve("path-alias"));
            deletedOwnedCopyStillReusesExistingForeignSource(root.resolve("deleted-owned"));
            revokedOrUnavailableProofCannotCreateImplicitCopies(root.resolve("unavailable"));
            missingAndChangedSourcesAllowExplicitSnapshotPublication(root.resolve("changed"));
            damagedProofAndPrivateWorkingCopiesFailClosed(root.resolve("proof"));
            pngAndUnimportedFilesRemainSeparateVersions(root.resolve("png"));
            System.out.println("PASS: " + checks + " imported photo origin/publication checks");
        } finally {
            try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    /** Harmless relative-segment alias checks canonical containment on hosts; the native probe covers Android's actual app-root alias. */
    private static void equivalentOwnedPathStillFindsOrigin(Path root) throws Exception {
        Fixture f = new Fixture(root); java.io.File alias = new java.io.File(f.context.getFilesDir(), "./pictures/camera-album/" + f.photo.file().getName());
        equal(alias.getCanonicalFile(), f.photo.file().getCanonicalFile()); yes(!alias.getAbsoluteFile().equals(alias.getCanonicalFile()));
        DownloadedPhoto photo = new DownloadedPhoto(alias, f.photo.filename());
        equal(LocalPhotoOrigin.check(f.context, photo).state, LocalPhotoOrigin.State.EXISTING_SOURCE);
        PhotoLiveGallery gallery = new PhotoLiveGallery(f.context); equal(gallery.publicationStatus(photo).status, PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE);
        equal(gallery.publish(photo), f.uri.toString()); equal(f.context.resolver.inserts, 1); equal(f.context.resolver.rows.size(), 1);
        java.io.File traversal = new java.io.File(f.context.getFilesDir(), "pictures/../pictures/camera-album/" + f.photo.file().getName());
        equal(traversal.getCanonicalFile(), f.photo.file().getCanonicalFile());
        equal(LocalPhotoOrigin.check(f.context, new DownloadedPhoto(traversal, f.photo.filename())).state, LocalPhotoOrigin.State.UNAVAILABLE);
        Path outside = root.resolve("elsewhere").resolve(f.photo.file().getName()); Files.createDirectories(outside.getParent()); Files.write(outside, JPEG);
        equal(LocalPhotoOrigin.check(f.context, new DownloadedPhoto(outside.toFile(), f.photo.filename())).state, LocalPhotoOrigin.State.UNAVAILABLE);
    }
    private static void deletedOwnedCopyStillReusesExistingForeignSource(Path root) throws Exception {
        Fixture f = new Fixture(root); PhotoLiveGallery gallery = new PhotoLiveGallery(f.context);
        String copy = gallery.publishNewCopy(f.photo); yes(!copy.equals(f.uri.toString()));
        yes(publicationReceipts(f.context) > 0); equal(f.context.resolver.rows.size(), 2);
        long ownedId = Long.parseLong(copy.substring(copy.lastIndexOf('/') + 1));
        yes(f.context.resolver.rows.remove(ownedId) != null); // User deletes the app-owned row, retaining its durable receipt.
        int inserts = f.context.resolver.inserts, writes = f.context.resolver.writes;
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.EXISTING_SOURCE);
        gallery = new PhotoLiveGallery(f.context); // Recreate to prove the retained receipt is read from disk.
        PhotoLiveGallery.PublicationState status = gallery.publicationStatus(f.photo);
        equal(status.status, PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE); equal(status.uri, f.uri.toString());
        equal(gallery.publish(f.photo), f.uri.toString()); equal(gallery.publish(f.photo), f.uri.toString());
        equal(f.context.resolver.inserts, inserts); equal(f.context.resolver.writes, writes);
        equal(f.context.resolver.rows.size(), 1); yes(f.context.resolver.rows.containsKey(f.row.id));
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.photo.file().toPath())));
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.row.file.toPath())));
    }
    private static void matchingSourceIsReusedWithoutClaimingOwnership(Path root) throws Exception {
        Fixture f = new Fixture(root); PhotoLiveGallery gallery = new PhotoLiveGallery(f.context);
        long modified = f.photo.file().lastModified(); int inserts = f.context.resolver.inserts, writes = f.context.resolver.writes;
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.EXISTING_SOURCE);
        equal(LocalPhotoOrigin.check(f.context, f.photo).uri, f.uri.toString());
        PhotoLiveGallery.PublicationState status = gallery.publicationStatus(f.photo);
        equal(status.status, PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE); equal(status.uri, f.uri.toString());
        equal(gallery.publish(f.photo), f.uri.toString()); equal(gallery.publish(f.photo), f.uri.toString());
        equal(f.context.resolver.inserts, inserts); equal(f.context.resolver.writes, writes); equal(publicationReceipts(f.context), 0L);
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) { gallery.deletePublished(f.photo); }
        yes(f.context.resolver.rows.containsKey(f.row.id)); equal(f.context.resolver.deletes, 0);
        String copy = gallery.publishNewCopy(f.photo); yes(!copy.equals(f.uri.toString())); equal(f.context.resolver.rows.size(), 2);
        equal(gallery.publicationStatus(f.photo).status, PhotoLiveGallery.PublicationStatus.PUBLISHED);
        equal(gallery.publish(f.photo), copy); equal(f.context.resolver.rows.size(), 2);
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) { gallery.deletePublished(f.photo); }
        equal(f.context.resolver.rows.size(), 1); yes(f.context.resolver.rows.containsKey(f.row.id)); equal(f.context.resolver.deletes, 1);
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.photo.file().toPath()))); equal(f.photo.file().lastModified(), modified);
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.row.file.toPath())));
    }
    private static void revokedOrUnavailableProofCannotCreateImplicitCopies(Path root) throws Exception {
        Fixture f = new Fixture(root); PhotoLiveGallery gallery = new PhotoLiveGallery(f.context); int inserts = f.context.resolver.inserts;
        f.context.resolver.unreadableUris.add(f.uri.toString());
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        equal(gallery.publicationStatus(f.photo).status, PhotoLiveGallery.PublicationStatus.UNAVAILABLE);
        fails(() -> gallery.publish(f.photo)); equal(f.context.resolver.inserts, inserts); equal(f.context.resolver.rows.size(), 1);
        String explicit = gallery.publishNewCopy(f.photo); yes(!explicit.equals(f.uri.toString())); equal(f.context.resolver.rows.size(), 2);
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.photo.file().toPath())));
        f.context.resolver.unreadableUris.clear(); equal(gallery.publish(f.photo), explicit); equal(f.context.resolver.rows.size(), 2);
        Fixture unavailable = new Fixture(root.resolve("provider")); unavailable.context.resolver.nullQuery = true;
        equal(LocalPhotoOrigin.check(unavailable.context, unavailable.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        fails(() -> new PhotoLiveGallery(unavailable.context).publish(unavailable.photo)); equal(unavailable.context.resolver.inserts, 1);
        unavailable.context.resolver.nullQuery = false; unavailable.context.resolver.failReadMemory = true;
        equal(LocalPhotoOrigin.check(unavailable.context, unavailable.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        equal(unavailable.context.resolver.inserts, 1);
    }
    private static void missingAndChangedSourcesAllowExplicitSnapshotPublication(Path root) throws Exception {
        for (boolean removed : new boolean[]{true, false}) {
            Fixture f = new Fixture(root.resolve(removed ? "missing" : "changed"));
            byte[] changed = JPEG.clone(); changed[12] ^= 55;
            if (removed) f.context.resolver.rows.remove(f.row.id); else Files.write(f.row.file.toPath(), changed);
            equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.MISSING_OR_CHANGED);
            PhotoLiveGallery gallery = new PhotoLiveGallery(f.context); String copy = gallery.publish(f.photo);
            yes(!copy.equals(f.uri.toString())); equal(f.context.resolver.rows.size(), removed ? 1 : 2);
            equal(gallery.publish(f.photo), copy); equal(gallery.publicationStatus(f.photo).status, PhotoLiveGallery.PublicationStatus.PUBLISHED);
            yes(Arrays.equals(JPEG, Files.readAllBytes(f.photo.file().toPath())));
            if (!removed) yes(Arrays.equals(changed, Files.readAllBytes(f.row.file.toPath())));
        }
    }
    private static void damagedProofAndPrivateWorkingCopiesFailClosed(Path root) throws Exception {
        Fixture f = new Fixture(root); byte[] proof = Files.readAllBytes(f.proof);
        Files.write(f.proof, new byte[]{1}); equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        fails(() -> new PhotoLiveGallery(f.context).publish(f.photo)); equal(f.context.resolver.inserts, 1);
        Files.write(f.proof, proof); f.writeProof("content://media/external_primary/images/media/99999", "image/jpeg", f.sha, f.sha, "");
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        Files.write(f.proof, proof); byte[] changed = JPEG.clone(); changed[13] ^= 7; Files.write(f.photo.file().toPath(), changed);
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
        yes(Arrays.equals(changed, Files.readAllBytes(f.photo.file().toPath()))); equal(f.context.resolver.inserts, 1);
        Files.write(f.photo.file().toPath(), JPEG); Files.delete(f.proof);
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.UNAVAILABLE);
    }
    private static void pngAndUnimportedFilesRemainSeparateVersions(Path root) throws Exception {
        Fixture f = new Fixture(root); f.writeProof(f.uri.toString(), "image/png", f.sha, f.sha, f.key + ".png");
        equal(LocalPhotoOrigin.check(f.context, f.photo).state, LocalPhotoOrigin.State.NOT_IMPORTED);
        Path ordinary = f.photo.file().toPath().resolveSibling("ordinary.JPG"); Files.write(ordinary, JPEG);
        equal(LocalPhotoOrigin.check(f.context, new DownloadedPhoto(ordinary.toFile(), "ordinary.JPG")).state, LocalPhotoOrigin.State.NOT_IMPORTED);
        yes(Arrays.equals(JPEG, Files.readAllBytes(f.row.file.toPath())));
    }
    private static long publicationReceipts(Context context) throws Exception {
        try (var files = Files.walk(context.getFilesDir().toPath())) { return files.filter(file -> file.toString().contains("gallery") && file.getFileName().toString().endsWith(".record")).count(); }
    }
    private static final class Fixture {
        final Context context; final Uri uri; final ContentResolver.Row row; final DownloadedPhoto photo; final String sha, key; final Path proof;
        Fixture(Path root) throws Exception {
            Build.VERSION.SDK_INT = 35; context = new Context(root.toFile()); ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "PHONE_SOURCE.JPG"); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/"); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            uri = context.resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            row = context.resolver.rows.values().iterator().next(); row.owner = "phone.camera.application"; Files.write(row.file.toPath(), JPEG);
            sha = PhotoEditStore.hash(JPEG); key = "phone-" + PhotoEditStore.hash(uri.toString().getBytes(StandardCharsets.UTF_8)) + "-" + sha;
            Path pictures = context.getFilesDir().toPath().resolve("pictures/camera-album"); Files.createDirectories(pictures);
            Path local = pictures.resolve(key + "__PHONE_SOURCE.jpg"); Files.write(local, JPEG); photo = new DownloadedPhoto(local.toFile(), "PHONE_SOURCE.jpg");
            Path sources = context.getFilesDir().toPath().resolve("local-photo-sources"); Files.createDirectories(sources); proof = sources.resolve(key + ".source");
            writeProof(uri.toString(), "image/jpeg", sha, sha, "");
        }
        void writeProof(String originUri, String mime, String sourceSha, String workingSha, String retained) throws Exception {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(proof))) {
                out.writeInt(0x4c505332); out.writeUTF(originUri); out.writeUTF("PHONE_SOURCE.JPG"); out.writeUTF(mime); out.writeUTF(sourceSha);
                out.writeUTF(retained); out.writeLong(JPEG.length); out.writeLong(1700000000000L); out.writeUTF(workingSha); out.writeLong(JPEG.length);
            }
        }
    }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception { try { action.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { checks++; } }
    private static void equal(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual); checks++; }
    private static void yes(boolean value) { if (!value) throw new AssertionError("Check " + checks + " failed"); checks++; }
}
