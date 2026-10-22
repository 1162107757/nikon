package cc.huevia.community;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static cc.huevia.community.CommunityRepository.*;

/** Inline previews and a focused late reply are not a page cursor. Only acknowledged pages advance it. */
public final class CommentReplyPages {
    private final Map<String,Integer> next = new HashMap<>();
    public int nextPage(String rootId) { return next.getOrDefault(rootId,1); }
    public void clear() { next.clear(); }

    public List<Map<String,Object>> accept(String rootId, int requestedPage, long responsePage, long total,
                                          List<Map<String,Object>> previous, List<Map<String,Object>> incoming) throws IOException {
        id(rootId);
        if(requestedPage!=nextPage(rootId)||responsePage!=requestedPage||requestedPage<1||requestedPage>=100000
                ||total<0||incoming.size()>20)throw new IOException("回复列表已变化，请重新加载后继续。");
        Map<String,Map<String,Object>> merged=new LinkedHashMap<>();
        for(List<Map<String,Object>> source:List.of(previous,incoming))for(Map<String,Object> reply:source){
            String replyId=id(text(reply,"id"));
            if(!rootId.equals(text(reply,"parentId")))throw new IOException("回复所属的评论不一致，请重新加载。");
            timestamp(reply);merged.put(replyId,reply);
        }
        if(merged.size()>total)throw new IOException("回复列表已变化，请重新加载后继续。");
        List<Map<String,Object>> result=new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(CommentReplyPages::timestampForSort).thenComparing(reply->text(reply,"id")));
        next.put(rootId,requestedPage+1);
        return result;
    }
    private static Instant timestamp(Map<String,Object> value) throws IOException {
        try{return Instant.parse(text(value,"createdAt"));}catch(DateTimeParseException invalid){throw new IOException("回复时间无效，请重新加载。");}
    }
    private static Instant timestampForSort(Map<String,Object> value) { return Instant.parse(text(value,"createdAt")); }
}
