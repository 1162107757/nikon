BEGIN;
-- Read only the acknowledged page's IDs; newly arriving/unseen messages remain unread.
CREATE OR REPLACE FUNCTION public.nikon_community_read_messages(p_user uuid,p_ids jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE candidate jsonb; acknowledged jsonb;
BEGIN
 IF p_user IS NULL OR NOT EXISTS(SELECT 1 FROM nikon_users WHERE id=p_user AND status='active') THEN RAISE EXCEPTION 'USER_LOGIN_REQUIRED'; END IF;
 IF p_ids IS NULL OR jsonb_typeof(p_ids)<>'array' THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
 IF jsonb_array_length(p_ids) NOT BETWEEN 1 AND 50 THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
 FOR candidate IN SELECT value FROM jsonb_array_elements(p_ids) LOOP
  IF jsonb_typeof(candidate)<>'string' OR candidate#>>'{}' !~* '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' THEN RAISE EXCEPTION 'INVALID_COMMUNITY'; END IF;
 END LOOP;
 WITH updated AS (UPDATE nikon_community_messages SET read_at=coalesce(read_at,clock_timestamp())
  WHERE user_id=p_user AND id IN(SELECT (value#>>'{}')::uuid FROM jsonb_array_elements(p_ids)) RETURNING id)
 SELECT coalesce(jsonb_agg(id),'[]'::jsonb) INTO acknowledged FROM updated;
 RETURN jsonb_build_object('ok',true,'readIds',acknowledged,'unread',(SELECT count(*) FROM nikon_community_messages WHERE user_id=p_user AND read_at IS NULL));
END; $$;
REVOKE ALL ON FUNCTION public.nikon_community_read_messages(uuid,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.nikon_community_read_messages(uuid,jsonb) TO service_role;

DO $patch$
DECLARE source text; old_fragment text; new_fragment text;
BEGIN
 source:=pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure);
 FOR old_fragment,new_fragment IN SELECT * FROM (VALUES
 ($old$old_hash<>md5(p_body::text)$old$,$new$old_hash<>md5((CASE WHEN p_action='comment' THEN p_body-'moderation' ELSE p_body END)::text)$new$),
 ($old$md5(p_body::text),result);$old$,$new$md5((CASE WHEN p_action='comment' THEN p_body-'moderation' ELSE p_body END)::text),result);$new$),
 ($old$-- During the first 24 hours, posting remains moderated until this account has an approved visible comment.$old$,
  $new$-- Every comment requires a server-generated allow decision; missing checks go to human review.$new$),
 ($old$'deleteComment','read','readAll') THEN$old$,$new$'deleteComment','read','readAll','readBatch') THEN$new$),
 ($old$p_action IN('messages','unread','read','readAll') THEN$old$,$new$p_action IN('messages','unread','read','readAll','readBatch') THEN$new$),
 ($old$IF p_action IN('read','readAll') THEN UPDATE$old$,$new$IF p_action='readBatch' THEN RETURN nikon_community_read_messages(p_user_id,p_body->'ids'); END IF;
  IF p_action IN('read','readAll') THEN UPDATE$new$),
 ($old$new_comment_status:=CASE WHEN account.created_at>now()-interval '1 day' AND NOT EXISTS(SELECT 1 FROM nikon_community_comments WHERE user_id=p_user_id AND status='visible') THEN 'pending' ELSE 'visible' END;$old$,
  $new$new_comment_status:=CASE WHEN p_body->'moderation'->>'decision'='allow' THEN 'visible' ELSE 'pending' END;$new$),
 ($old$INSERT INTO nikon_community_comments(preset_id,user_id,parent_id,reply_to_id,text,status,was_public) VALUES(p_id,p_user_id,target_parent,target_reply,p_body->>'text',new_comment_status,new_comment_status='visible')$old$,
  $new$INSERT INTO nikon_community_comments(preset_id,user_id,parent_id,reply_to_id,text,status,was_public,reason) VALUES(p_id,p_user_id,target_parent,target_reply,p_body->>'text',new_comment_status,new_comment_status='visible',CASE WHEN new_comment_status='pending' THEN left(coalesce(nullif(p_body->'moderation'->>'reason',''),'自动审核未完成，请人工核查'),500) ELSE '' END)$new$)
 ) fragments(old_value,new_value) LOOP
  IF strpos(source,new_fragment)=0 THEN
   IF (length(source)-length(replace(source,old_fragment,'')))/length(old_fragment)<>1 THEN RAISE EXCEPTION 'COMMENT_MODERATION_READ_PATCH_MISMATCH'; END IF;
   source:=replace(source,old_fragment,new_fragment);
  END IF;
 END LOOP;
 EXECUTE source;
END;
$patch$;
NOTIFY pgrst,'reload schema';
COMMIT;
