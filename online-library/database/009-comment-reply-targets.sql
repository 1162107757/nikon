-- Preserve a flat, paged thread while recording the actual comment being replied to.
-- Historical replies retain their recorded root target; no intent is inferred.
BEGIN;
ALTER TABLE public.nikon_community_comments ADD COLUMN IF NOT EXISTS reply_to_id uuid REFERENCES public.nikon_community_comments(id);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='nikon_comment_reply_target_shape' AND conrelid='public.nikon_community_comments'::regclass) THEN
  ALTER TABLE public.nikon_community_comments ADD CONSTRAINT nikon_comment_reply_target_shape
   CHECK((parent_id IS NOT NULL OR reply_to_id IS NULL) AND (reply_to_id IS NULL OR reply_to_id<>id));
 END IF;
END $$;

CREATE OR REPLACE FUNCTION public.nikon_community_comment_view(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path=public,pg_temp AS $$
 SELECT jsonb_build_object('id',c.id,'presetId',c.preset_id,'parentId',c.parent_id,'text',CASE WHEN c.status='visible' THEN c.text ELSE '' END,
 'author',nikon_community_author(c.user_id),'status',c.status,'createdAt',c.created_at,'updatedAt',c.updated_at,
 'replyTo',CASE WHEN c.parent_id IS NULL THEN NULL ELSE jsonb_build_object(
  'id',coalesce(c.reply_to_id,c.parent_id),
  'available',coalesce(target.status='visible' OR (target.status='deleted' AND target.was_public),false),
  'author',CASE WHEN target.status='visible' OR (target.status='deleted' AND target.was_public) THEN nikon_community_author(target.user_id) ELSE NULL END) END)
 FROM nikon_community_comments c LEFT JOIN nikon_community_comments target ON target.id=coalesce(c.reply_to_id,c.parent_id) WHERE c.id=p_id;
$$;
REVOKE ALL ON FUNCTION public.nikon_community_comment_view(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_community_comment_view(uuid) TO service_role;

DO $patch$
DECLARE source text; old_fragment text; new_fragment text;
BEGIN
 source:=pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure);
 FOR old_fragment,new_fragment IN SELECT * FROM (VALUES
 ($old$target_parent uuid; new_id uuid;$old$,
  $new$target_parent uuid; target_reply uuid; reply_target nikon_community_comments%ROWTYPE; new_id uuid;$new$),
 ($old$target_parent:=(p_body->>'parentId')::uuid;
  IF target_parent IS NOT NULL THEN SELECT * INTO parent FROM nikon_community_comments WHERE id=target_parent AND preset_id=p_id AND status='visible' AND parent_id IS NULL FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF; END IF;$old$,
  $new$target_parent:=(p_body->>'parentId')::uuid;
  target_reply:=coalesce((p_body->>'replyToId')::uuid,target_parent);
  IF target_parent IS NULL AND target_reply IS NOT NULL THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
  IF target_parent IS NOT NULL THEN
   SELECT * INTO parent FROM nikon_community_comments WHERE id=target_parent AND preset_id=p_id AND status='visible' AND parent_id IS NULL FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
   SELECT * INTO reply_target FROM nikon_community_comments WHERE id=target_reply AND preset_id=p_id AND status='visible' AND (id=target_parent OR parent_id=target_parent) FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
  END IF;$new$),
 ($old$INSERT INTO nikon_community_comments(preset_id,user_id,parent_id,text,status,was_public) VALUES(p_id,p_user_id,target_parent,p_body->>'text',new_comment_status,new_comment_status='visible')$old$,
  $new$INSERT INTO nikon_community_comments(preset_id,user_id,parent_id,reply_to_id,text,status,was_public) VALUES(p_id,p_user_id,target_parent,target_reply,p_body->>'text',new_comment_status,new_comment_status='visible')$new$),
 ($old$IF new_comment_status='visible' AND target_parent IS NOT NULL AND parent.user_id<>p_user_id THEN INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(parent.user_id,new_id,target_parent)$old$,
  $new$IF new_comment_status='visible' AND target_parent IS NOT NULL AND reply_target.user_id<>p_user_id THEN INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(reply_target.user_id,new_id,target_parent)$new$),
 ($old$SELECT * INTO parent FROM nikon_community_comments WHERE id=comment.parent_id AND status='visible' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;$old$,
  $new$SELECT * INTO parent FROM nikon_community_comments WHERE id=comment.parent_id AND status='visible' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;
    SELECT * INTO reply_target FROM nikon_community_comments WHERE id=coalesce(comment.reply_to_id,comment.parent_id) AND preset_id=comment.preset_id
     AND (status='visible' OR (status='deleted' AND was_public)) AND (id=comment.parent_id OR parent_id=comment.parent_id) FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'COMMENT_UNAVAILABLE'; END IF;$new$),
 ($old$SELECT * INTO comment FROM nikon_community_comments WHERE id=p_id AND status<>'deleted' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_body->>'decision'='restore' THEN$old$,
  $new$SELECT * INTO comment FROM nikon_community_comments WHERE id=p_id AND status<>'deleted';
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  -- Match reply creation's root-before-target lock order, including approval retries.
  IF p_body->>'decision'='restore' AND comment.parent_id IS NOT NULL THEN
   PERFORM 1 FROM nikon_community_comments WHERE id=comment.parent_id FOR UPDATE;
  END IF;
  SELECT * INTO comment FROM nikon_community_comments WHERE id=p_id AND status<>'deleted' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'NOT_FOUND'; END IF;
  IF p_body->>'decision'='restore' THEN$new$),
 ($old$IF p_body->>'decision'='restore' AND comment.parent_id IS NOT NULL AND parent.user_id<>comment.user_id THEN
   INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(parent.user_id,comment.id,comment.parent_id)$old$,
  $new$IF p_body->>'decision'='restore' AND comment.parent_id IS NOT NULL AND reply_target.user_id<>comment.user_id THEN
   INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(reply_target.user_id,comment.id,comment.parent_id)$new$)
 ) fragments(old_value,new_value) LOOP
  IF strpos(source,new_fragment)=0 THEN
   IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'COMMENT_REPLY_TARGET_PATCH_MISMATCH'; END IF;
   source:=replace(source,old_fragment,new_fragment);
  END IF;
 END LOOP;
 EXECUTE source;
END;
$patch$;
NOTIFY pgrst,'reload schema';
COMMIT;
