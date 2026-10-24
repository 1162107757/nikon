package cc.huevia.album;
import java.io.IOException;
public final class PhotoPresetBatch {
    public static final class Source { final DownloadedPhoto result; public Source(DownloadedPhoto result){this.result=result;} }
    public static final class Draft {}
    public static final class Edit {
        public final DownloadedPhoto result; public final boolean failure;
        public final Source source;public final Draft draft;public final String owner,expectedSourceSha,accountScope;public final boolean accountBound;
        public final PhotoExportSpec exportSpec;public final PhotoSaveTarget saveTarget;public final boolean forceNewCopy;
        public Edit(DownloadedPhoto result,boolean failure){this(new Source(result),null,"",PhotoExportSpec.full(),"","",false,null,false,failure);}
        private Edit(Source source,Draft draft,String owner,PhotoExportSpec spec,String sha,String account,boolean bound,PhotoSaveTarget target,boolean force,boolean failure){this.source=source;this.draft=draft;this.owner=owner;this.expectedSourceSha=sha;this.accountScope=account;this.accountBound=bound;this.exportSpec=spec;this.saveTarget=target;this.forceNewCopy=force;this.result=source.result;this.failure=failure;}
        public static Edit saved(Source source,PhotoExportSpec spec,String sha){return new Edit(source,null,"",spec,sha,"",false,null,false,false);}
        public static Edit draft(Source source,Draft draft,String owner,PhotoExportSpec spec){return new Edit(source,draft,owner,spec,"","",false,null,false,false);}
        public Edit withAccount(String account){return new Edit(source,draft,owner,exportSpec,expectedSourceSha,account,true,saveTarget,forceNewCopy,failure);}
        public Edit withDestination(PhotoSaveTarget target,boolean force){return new Edit(source,draft,owner,exportSpec,expectedSourceSha,accountScope,accountBound,target,force,failure);}
    }
    public int lookups;
    public DownloadedPhoto findResult(Edit edit)throws IOException{lookups++;if(edit.failure)throw new IOException("source changed");return edit.result;}
}
