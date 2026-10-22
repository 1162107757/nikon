package cc.huevia.community;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static cc.huevia.community.CommunityRepository.*;

public final class CommentReplyPagesTest {
    private static final String ROOT="10000000-0000-4000-8000-000000000001",OTHER="10000000-0000-4000-8000-000000000002";
    private static int checks;
    public static void main(String[] args)throws Exception {
        CommentReplyPages pages=new CommentReplyPages();List<Map<String,Object>> initial=rows(0,3);
        check(pages.nextPage(ROOT)==1,"three inline previews do not skip first full page");
        List<Map<String,Object>> merged=pages.accept(ROOT,1,1,40,initial,rows(0,20));
        check(merged.size()==20&&unique(merged)==20,"initial3 and first20 deduplicate by ID");
        check(pages.nextPage(ROOT)==2,"first successful page advances explicit cursor");
        merged=pages.accept(ROOT,2,2,40,merged,rows(20,40));check(merged.size()==40&&unique(merged)==40,"second page expands to40 without losses");
        check(pages.nextPage(OTHER)==1,"root comment cursors are independent");
        pages.clear();List<Map<String,Object>> focused=new ArrayList<>(rows(0,2));focused.addAll(rows(79,80));
        check(pages.nextPage(ROOT)==1,"focused late reply does not advance page");
        merged=pages.accept(ROOT,1,1,80,focused,rows(0,20));check(merged.size()==21&&unique(merged)==21,"focus last reply plus first20 are unique");
        check(text(merged.get(20),"id").equals(replyId(79)),"late focused reply retains chronological location");
        merged=pages.accept(ROOT,2,2,80,merged,rows(20,40));check(merged.size()==41&&pages.nextPage(ROOT)==3,"focus overlap does not distort second page cursor");
        merged=pages.accept(ROOT,3,3,80,merged,rows(40,60));merged=pages.accept(ROOT,4,4,80,merged,rows(60,80));
        check(merged.size()==80&&unique(merged)==80,"focus last reply deduplicates when its final page arrives");
        for(int i=0;i<80;i++)check(text(merged.get(i),"id").equals(replyId(i)),"complete chronological reply sequence retained");
        int before=pages.nextPage(ROOT);try{pages.accept(ROOT,before,before+1,80,merged,List.of());throw new AssertionError();}catch(IOException expected){check(pages.nextPage(ROOT)==before,"invalid or stale page does not advance");}
        pages.clear();Map<String,Object> alien=body("id",replyId(0),"parentId",OTHER,"createdAt","2026-10-18T00:00:00Z");try{pages.accept(ROOT,1,1,1,List.of(),List.of(alien));throw new AssertionError();}catch(IOException expected){check(pages.nextPage(ROOT)==1,"foreign parent rejected without advancing");}
        List<Map<String,Object>> retry=pages.accept(ROOT,1,1,40,initial,rows(0,20));check(retry.size()==20&&pages.nextPage(ROOT)==2,"same page remains retryable after an error");
        pages.clear();check(pages.nextPage(ROOT)==1,"fresh comments reload resets cursors");
        System.out.println("Comment replies paging: "+checks+" checks passed (host-only).");
    }
    private static List<Map<String,Object>> rows(int from,int to){List<Map<String,Object>> result=new ArrayList<>();for(int i=from;i<to;i++)result.add(body("id",replyId(i),"parentId",ROOT,"text","回复"+i,"createdAt",Instant.parse("2026-10-18T00:00:00Z").plusSeconds(i).toString()));return result;}
    private static String replyId(int i){return String.format("20000000-0000-4000-8000-%012d",i);}
    private static int unique(List<Map<String,Object>> rows){HashSet<String> ids=new HashSet<>();for(Map<String,Object> row:rows)ids.add(text(row,"id"));return ids.size();}
    private static void check(boolean okay,String label){if(!okay)throw new AssertionError(label);checks++;}
}
