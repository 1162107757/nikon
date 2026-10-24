package cc.huevia.album;
import java.util.HashMap;
import java.util.Map;
public final class PhotoLiveGallery {
    public enum PublicationStatus { PUBLISHED, EXISTING_SOURCE, MISSING, PENDING, UNAVAILABLE }
    public static final class PublicationState { public final PublicationStatus status;public final String uri,message;public PublicationState(PublicationStatus status,String uri){this.status=status;this.uri=uri;message="";} }
    public final Map<String,PublicationState> states=new HashMap<>();public int queries;
    public PublicationState publicationStatus(DownloadedPhoto photo){queries++;if("throws".equals(photo.id))throw new SecurityException("denied");return states.get(photo.id);}
}
