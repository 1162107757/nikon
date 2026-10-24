package cc.huevia.ui;
import cc.huevia.album.*;
import java.util.*;
public final class PhotoExportAvailabilityTest {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static PhotoPresetBatch.Edit edit(String id){return new PhotoPresetBatch.Edit(new DownloadedPhoto(id),false);}
    public static void main(String[] args){
        PhotoPresetBatch batch=new PhotoPresetBatch();PhotoLiveGallery gallery=new PhotoLiveGallery();
        for(PhotoLiveGallery.PublicationStatus status:PhotoLiveGallery.PublicationStatus.values())gallery.states.put(status.name(),new PhotoLiveGallery.PublicationState(status,"content://media/external_primary/images/media/1"));
        List<PhotoPresetBatch.Edit> edits=new ArrayList<>();for(PhotoLiveGallery.PublicationStatus status:PhotoLiveGallery.PublicationStatus.values())edits.add(edit(status.name()));
        edits.add(new PhotoPresetBatch.Edit(null,false));edits.add(new PhotoPresetBatch.Edit(null,true));edits.add(edit("null"));edits.add(edit("throws"));
        PhotoExportAvailability.Result result=PhotoExportAvailability.check(batch,gallery,edits,PhotoSaveTarget.PHONE_ALBUM);
        PhotoExportAvailability.Status[] expected={PhotoExportAvailability.Status.PHONE_SAVED,PhotoExportAvailability.Status.PHONE_SAVED,PhotoExportAvailability.Status.PHONE_MISSING,PhotoExportAvailability.Status.PENDING,PhotoExportAvailability.Status.UNKNOWN,PhotoExportAvailability.Status.NOT_GENERATED,PhotoExportAvailability.Status.UNKNOWN,PhotoExportAvailability.Status.UNKNOWN,PhotoExportAvailability.Status.UNKNOWN};
        for(int i=0;i<expected.length;i++){
            PhotoExportAvailability.Entry entry=result.entries.get(i);check(entry.status==expected[i],"mapped status "+i);
            check((entry.status==PhotoExportAvailability.Status.PHONE_SAVED)==!entry.uri.isEmpty(),"only verified published state exposes uri "+i);
        }
        check(batch.lookups==9,"each frozen edit is checked");check(gallery.queries==7,"only verified app results query phone");
        check(!result.allSaved(PhotoSaveTarget.PHONE_ALBUM),"mixed list is not all saved");
        check(result.summary(PhotoSaveTarget.PHONE_ALBUM).contains("暂无法核对"),"mixed unknown stays visible");
        check(result.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,false).equals("核对并继续保存"),"unknown default remains conservative");
        check(result.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,true).equals("另存 9 张新副本"),"extra copy explicit");
        for(PhotoLiveGallery.PublicationStatus status:PhotoLiveGallery.PublicationStatus.values()){
            PhotoExportAvailability.Result one=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(edit(status.name())),PhotoSaveTarget.PHONE_ALBUM);
            boolean saved=status==PhotoLiveGallery.PublicationStatus.PUBLISHED||status==PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE;
            check(one.allSaved(PhotoSaveTarget.PHONE_ALBUM)==saved,"saved exact status "+status);
            check(one.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,false).equals("查看已保存照片")==saved,"view only exact saved "+status);
            check(one.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,true).equals("另存新副本"),"explicit copy despite prior "+status);
            check(one.allSaved(PhotoSaveTarget.APP_ONLY),"verified app result remains viewable "+status);
        }
        int queries=gallery.queries;
        PhotoExportAvailability.Result app=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(edit("UNAVAILABLE")),PhotoSaveTarget.APP_ONLY);
        check(gallery.queries==queries,"app-only never queries MediaStore");check(app.entries.get(0).status==PhotoExportAvailability.Status.APP_RESULT,"app-only status");check(app.allSaved(PhotoSaveTarget.APP_ONLY),"app-only view");check(!app.allSaved(PhotoSaveTarget.PHONE_ALBUM),"app-only never proves phone exists");
        PhotoExportAvailability.Result missing=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(edit("MISSING")),PhotoSaveTarget.PHONE_ALBUM);
        check(missing.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,false).equals("重新保存到手机相册"),"missing copy can be republished");
        PhotoExportAvailability.Result unavailable=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(edit("UNAVAILABLE")),PhotoSaveTarget.PHONE_ALBUM);
        check(unavailable.count(PhotoExportAvailability.Status.PHONE_MISSING)==0,"unknown never means missing");check(unavailable.summary(PhotoSaveTarget.PHONE_ALBUM).contains("暂无法核对已保存照片"),"unknown copy");
        PhotoExportAvailability.Result none=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(new PhotoPresetBatch.Edit(null,false)),PhotoSaveTarget.PHONE_ALBUM);
        check(none.primaryLabel(PhotoSaveTarget.PHONE_ALBUM,false).equals("导出照片"),"new export label");check(!none.allSaved(PhotoSaveTarget.APP_ONLY),"missing result not viewable");
        gallery.states.put("bad-uri",new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.PUBLISHED,""));
        PhotoExportAvailability.Result bad=PhotoExportAvailability.check(batch,gallery,Collections.singletonList(edit("bad-uri")),PhotoSaveTarget.PHONE_ALBUM);
        check(bad.entries.get(0).status==PhotoExportAvailability.Status.UNKNOWN,"published without URI is not viewable");
        List<PhotoExportAvailability.Entry> mutable=new ArrayList<>();mutable.add(app.entries.get(0));PhotoExportAvailability.Result immutable=new PhotoExportAvailability.Result(mutable);mutable.clear();check(immutable.entries.size()==1,"snapshot immutable");
        try{immutable.entries.clear();throw new AssertionError("mutable result");}catch(UnsupportedOperationException expectedFailure){assertions++;}
        check(!PhotoExportAvailability.unknown(2).allSaved(PhotoSaveTarget.PHONE_ALBUM),"timeout unknown no fabricated saved");
        check(PhotoExportSpec.share().resolve(2400,1600).identity().equals(PhotoExportSpec.custom(2048,90).resolve(2400,1600).identity()),"mode names do not alter query identity");
        PhotoPresetBatch.Source source=new PhotoPresetBatch.Source(new DownloadedPhoto("frozen"));
        PhotoPresetBatch.Edit frozenSaved=PhotoPresetBatch.Edit.saved(source,PhotoExportSpec.full(),"expected-sha").withAccount("").withDestination(PhotoSaveTarget.PHONE_ALBUM,true);
        PhotoPresetBatch.Draft draft=new PhotoPresetBatch.Draft();
        PhotoPresetBatch.Edit frozenDraft=PhotoPresetBatch.Edit.draft(source,draft,"owner-a",PhotoExportSpec.full()).withAccount("account-a");
        PhotoPresetBatch.Edit legacy=PhotoPresetBatch.Edit.saved(source,PhotoExportSpec.full(),"legacy-sha");
        List<PhotoPresetBatch.Edit> changed=PhotoExportAvailability.withSpec(Arrays.asList(frozenSaved,frozenDraft,legacy),PhotoExportSpec.share());
        check(changed.get(0).source==source,"saved source frozen");check(changed.get(0).expectedSourceSha.equals("expected-sha"),"saved byte identity frozen");
        check(changed.get(0).accountBound&&changed.get(0).accountScope.isEmpty(),"bound guest is preserved");
        check(changed.get(1).draft==draft&&changed.get(1).owner.equals("owner-a"),"draft and owner frozen");
        check(changed.get(1).accountBound&&changed.get(1).accountScope.equals("account-a"),"bound account preserved");
        check(!changed.get(2).accountBound,"legacy lookup does not bind current account");
        for(PhotoPresetBatch.Edit changedEdit:changed){check(changedEdit.exportSpec==PhotoExportSpec.share(),"encoding changed");check(changedEdit.saveTarget==null&&!changedEdit.forceNewCopy,"lookup never copies publication choices");}
        try{changed.clear();throw new AssertionError("mutable spec list");}catch(UnsupportedOperationException expectedFailure){assertions++;}
        System.out.println("Export availability: "+assertions+" checks passed.");
    }
}
