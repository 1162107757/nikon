package cc.huevia.community;

import java.util.Map;
import static cc.huevia.community.CommunityRepository.text;

/** A thread root is a paging container, not necessarily the person receiving a reply. */
public final class CommentReplyTarget {
    private CommentReplyTarget() { }
    public static String recipientName(Map<String,Object> reply, Map<String,Object> root) {
        Object target = reply.get("replyTo");
        if (target instanceof Map) {
            Map<String,Object> value = (Map<String,Object>)target;
            if (Boolean.FALSE.equals(value.get("available"))) return "已不可用的评论";
            return authorName(value);
        }
        // Old API/data only records the root. Do not infer a target from reply order or text.
        if (root != null && text(root,"id").equals(text(reply,"parentId"))) return authorName(root);
        return "原评论";
    }
    public static String authorName(Map<String,Object> comment) {
        Object person = comment.get("author");
        String name = person instanceof Map ? text((Map<String,Object>)person,"nickname") : "";
        return name.isEmpty() ? "用户" : name;
    }
}
