package cc.huevia.community;
import java.util.Map;
public final class CommentReplyTargetTest {
    public static void main(String[] args) {
        Map<String,Object> root=Map.of("id","root","author",Map.of("nickname","小林"));
        Map<String,Object> target=Map.of("id","child","available",true,"author",Map.of("nickname","小周"));
        equal("小周",CommentReplyTarget.recipientName(Map.of("parentId","root","replyTo",target),root));
        equal("小周",CommentReplyTarget.recipientName(Map.of("parentId","root","replyTo",target),null));
        equal("小林",CommentReplyTarget.recipientName(Map.of("parentId","root"),root));
        equal("原评论",CommentReplyTarget.recipientName(Map.of("parentId","another-thread"),root));
        equal("原评论",CommentReplyTarget.recipientName(Map.of("parentId","root"),null));
        equal("已不可用的评论",CommentReplyTarget.recipientName(Map.of("parentId","root","replyTo",Map.of("available",false)),root));
        equal("用户",CommentReplyTarget.recipientName(Map.of("replyTo",Map.of("available",true)),root));
        equal("用户",CommentReplyTarget.authorName(Map.of("author",Map.of("nickname",""))));
        equal("小周",CommentReplyTarget.authorName(Map.of("author",Map.of("nickname","小周"))));
        System.out.println("PASS: 9 comment reply-recipient checks");
    }
    private static void equal(String expected,String actual){if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);}
}
