package com.nikonpreset.usbprobe.community;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static com.nikonpreset.usbprobe.community.CommunityRepository.*;

/** Durable upload/save/submit checkpoints. A lost submit response retries the same action directly. */
public final class SubmissionFlow {
    private SubmissionFlow() {}
    public static Map<String,Object> save(CommunityRepository api, SubmissionDraftStore store, String owner,
                                          Map<String,Object> original, boolean submit) throws IOException {
        Map<String,Object> snapshot = new LinkedHashMap<>(original);
        if (!Boolean.TRUE.equals(snapshot.get("rightsConfirmed"))) throw new IOException("请先确认你拥有预设和效果图的公开分享权利。");
        if (!(submit && Boolean.TRUE.equals(snapshot.get("submitPending")))) {
            for(String kind:new String[]{"ncp","preview"}) {
                String prefix="ncp".equals(kind)?"file":"preview",assetKey=prefix+"AssetId",path=text(snapshot,prefix+"Path");
                if(text(snapshot,assetKey).isEmpty()&&!path.isEmpty()) {
                    byte[] bytes=store.readFile(path,"ncp".equals(kind)?1024*1024:4*1024*1024);
                    Map<String,Object> uploaded=api.upload(kind,text(snapshot,prefix+"Name"),bytes,"ncp".equals(kind)?"application/octet-stream":mime(bytes));
                    Map<String,Object> asset=object(uploaded.get("asset"));snapshot.put(assetKey,text(asset,"id"));snapshot.put(prefix,asset);store.save(owner,snapshot);
                }
            }
            Map<String,Object> payload=body("name",text(snapshot,"name").trim(),"description",text(snapshot,"description").trim(),"category",text(snapshot,"category").trim(),
                    "compatibleModels",snapshot.getOrDefault("compatibleModels",new ArrayList<>()),"fileAssetId",text(snapshot,"fileAssetId"),
                    "previewAssetId",text(snapshot,"previewAssetId").isEmpty()?null:text(snapshot,"previewAssetId"),"rightsConfirmed",true);
            String serverId=text(snapshot,"id");boolean creating=serverId.isEmpty();
            payload.put("idempotencyKey",key(snapshot,creating?"createKey":"saveKey"));if(!creating)payload.put("expectedRevision",number(snapshot,"revision",1));
            Map<String,Object> saved=object(api.write(creating?"POST":"PUT","/community/submissions"+(creating?"":"/"+id(serverId)),payload).get("submission"));
            snapshot.putAll(saved);snapshot.put("saveKey",UUID.randomUUID().toString());snapshot.put("submitPending",submit);store.save(owner,snapshot);
        }
        if(submit) {
            Map<String,Object> result=api.write("POST","/community/submissions/"+id(text(snapshot,"id"))+"/submit",body("expectedRevision",number(snapshot,"revision",1),"idempotencyKey",key(snapshot,"submitKey")));
            snapshot.putAll(object(result.get("submission")));snapshot.put("submitPending",false);snapshot.put("submitKey",UUID.randomUUID().toString());store.save(owner,snapshot);
        }
        return snapshot;
    }
    private static String key(Map<String,Object> value,String key) { String result=text(value,key);if(result.isEmpty()){result=UUID.randomUUID().toString();value.put(key,result);}return result; }
    private static String mime(byte[] bytes){if(bytes.length>4&&bytes[0]==(byte)0x89&&bytes[1]=='P')return"image/png";if(bytes.length>3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8)return"image/jpeg";if(bytes.length>12&&bytes[8]=='W'&&bytes[9]=='E')return"image/webp";return"application/octet-stream";}
}
