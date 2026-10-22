package cc.huevia.community;

import cc.huevia.account.AccountJson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One recoverable editor draft per account. No passwords, provider credentials or session tokens. */
public final class SubmissionDraftStore {
    private final File root;
    public SubmissionDraftStore(File files) throws IOException {
        root = new File(files, "community-drafts"); if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法保存投稿草稿。");
    }
    private File file(String user) throws IOException { return new File(root, CommunityRepository.id(user) + ".json"); }
    public synchronized Map<String,Object> load(String user) throws IOException {
        File source = file(user); if (!source.exists()) return fresh();
        if (!source.isFile() || source.length() > 32768) throw new IOException("投稿草稿无法读取，请重新选择文件。");
        // Files.readString is absent from some supported Android libcore versions.
        // Bound the stream as well as the initial length check, including a concurrently grown file.
        byte[] bytes;
        try(FileInputStream input=new FileInputStream(source);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){if(output.size()>32768-count)throw new IOException("投稿草稿过大。");output.write(buffer,0,count);}
            bytes=output.toByteArray();
        }
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        Map<String,Object> data = AccountJson.object(AccountJson.parse(json));
        for (String field : new String[]{"filePath", "previewPath"}) {
            String path = CommunityRepository.text(data,field); if (!path.isEmpty()) requireOwned(new File(path));
        }
        return new LinkedHashMap<>(data);
    }
    public synchronized void save(String user, Map<String,Object> draft) throws IOException {
        File destination = file(user); byte[] data = AccountJson.stringify(draft).getBytes(StandardCharsets.UTF_8);
        if (data.length > 32768) throw new IOException("投稿草稿过大。");
        File temporary = File.createTempFile("draft-", ".tmp", root);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(data); output.getFD().sync(); }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
    public synchronized File storeFile(String user, String kind, byte[] data) throws IOException {
        CommunityRepository.id(user);
        if (!"ncp".equals(kind) && !"preview".equals(kind)) throw new IOException("文件类型无效。");
        if (data == null || data.length == 0 || data.length > ("ncp".equals(kind) ? 1024*1024 : 4*1024*1024)) throw new IOException("文件大小超出限制。");
        File target = new File(root, user + "-" + UUID.randomUUID() + ("ncp".equals(kind) ? ".ncp" : ".image"));
        try (FileOutputStream output = new FileOutputStream(target)) { output.write(data); output.getFD().sync(); }
        return target;
    }
    public byte[] readFile(String path, int maximum) throws IOException {
        File file = new File(path); requireOwned(file);
        if (!file.isFile() || file.length() < 1 || file.length() > maximum) throw new IOException("草稿中的文件不可用，请重新选择。");
        byte[] value = Files.readAllBytes(file.toPath()); if (value.length > maximum) throw new IOException("文件过大。"); return value;
    }
    private void requireOwned(File file) throws IOException {
        if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("草稿文件路径无效。");
    }
    public static Map<String,Object> fresh() {
        Map<String,Object> data = new LinkedHashMap<>(); data.put("createKey", UUID.randomUUID().toString());
        data.put("saveKey",UUID.randomUUID().toString()); data.put("submitKey",UUID.randomUUID().toString()); return data;
    }
}
