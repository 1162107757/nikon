-- Local/isolated PostgreSQL acceptance only. All synthetic users and reservations roll back.
BEGIN;
SET LOCAL ROLE service_role;
DO $$
DECLARE q jsonb; p jsonb; other_user jsonb; result jsonb; updated jsonb; metadata jsonb; saved_custom text;
 asset_id uuid:=gen_random_uuid(); second_asset uuid:=gen_random_uuid(); password_asset uuid:=gen_random_uuid();
 stale_asset uuid:=gen_random_uuid(); expired_asset uuid:=gen_random_uuid(); qid uuid; pid uuid; oid uuid;
 expected_time timestamptz; first_time timestamptz; previous_budget bigint; after_budget bigint; denied_count integer; i integer;
BEGIN
 IF has_table_privilege('anon','public.nikon_avatar_uploads','SELECT')
 OR has_table_privilege('authenticated','public.nikon_avatar_storage_budget','UPDATE')
 OR has_function_privilege('anon','public.nikon_avatar_set(uuid,uuid,timestamp with time zone,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated','public.nikon_avatar_reserve(uuid,uuid,timestamp with time zone,text,text)','EXECUTE')
 OR has_function_privilege('anon','public.nikon_avatar_asset(uuid)','EXECUTE') THEN RAISE EXCEPTION 'avatar data accessible to client roles'; END IF;
 q:=nikon_sign_in_qq('123456789',repeat('B',32),'QQ avatar fixture','https://q.qlogo.cn/first',repeat('b',64),now()+interval '1 day');
 qid:=(q->>'id')::uuid; first_time:=(q->>'updatedAt')::timestamptz;
 IF q->>'avatarSource'<>'qq' OR q->>'avatarUrl'<>'https://q.qlogo.cn/first' THEN RAISE EXCEPTION 'QQ default missing'; END IF;
 p:=nikon_register_password('avatar_fixture','scrypt:32768:8:1:'||repeat('1',32)||':'||repeat('1',128),repeat('c',64),now()+interval '1 day');
 pid:=(p->>'id')::uuid;
 other_user:=nikon_register_password('avatar_other','scrypt:32768:8:1:'||repeat('2',32)||':'||repeat('2',128),repeat('d',64),now()+interval '1 day');
 oid:=(other_user->>'id')::uuid;
 IF p->>'avatarSource'<>'default' OR p->>'avatarUrl'<>'' THEN RAISE EXCEPTION 'password default missing'; END IF;
 result:=nikon_avatar_reserve(qid,asset_id,first_time,repeat('1',64),repeat('2',64));
 IF result->>'allowed'<>'true' THEN RAISE EXCEPTION 'first reservation failed'; END IF;
 SELECT reserved_bytes INTO previous_budget FROM nikon_avatar_storage_budget;
 IF previous_budget<>1048576 THEN RAISE EXCEPTION 'full bytes not durably reserved'; END IF;
 IF nikon_avatar_asset(asset_id) IS NOT NULL THEN RAISE EXCEPTION 'unfinished avatar exposed'; END IF;
 metadata:=jsonb_build_object('byteLength',2048,'width',512,'height',512,'mimeType','image/jpeg','extension','jpg','sha256',repeat('e',64),
 'storagePath','avatars/'||asset_id::text||'.jpg','url','https://avatar.example/preset-library/avatars/'||asset_id::text||'.jpg');
 BEGIN
  PERFORM nikon_avatar_set(oid,asset_id,(other_user->>'updatedAt')::timestamptz,metadata);
  RAISE EXCEPTION 'another user claimed reserved avatar';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'AVATAR_RESERVATION_EXPIRED' THEN RAISE; END IF; END;
 BEGIN
  PERFORM nikon_avatar_set(qid,asset_id,first_time,metadata||'{"storagePath":"preview/private.jpg"}');
  RAISE EXCEPTION 'private preset object accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'INVALID_AVATAR' THEN RAISE; END IF; END;
 BEGIN
  PERFORM nikon_avatar_set(qid,asset_id,first_time,metadata||'{"mimeType":"image/svg+xml"}');
  RAISE EXCEPTION 'SVG avatar accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'INVALID_AVATAR' THEN RAISE; END IF; END;
 BEGIN
  PERFORM nikon_avatar_set(qid,asset_id,first_time,metadata||'{"url":"https://evil.invalid/avatar.svg?token=secret"}');
  RAISE EXCEPTION 'external JSON avatar URL accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'INVALID_AVATAR' THEN RAISE; END IF; END;
 updated:=nikon_avatar_set(qid,asset_id,first_time,metadata); saved_custom:=updated->>'avatarUrl';
 IF updated->>'avatarSource'<>'custom' OR (updated->>'updatedAt')::timestamptz<=first_time
 OR nikon_avatar_asset(asset_id)->>'sha256'<>repeat('e',64) THEN RAISE EXCEPTION 'custom commit failed'; END IF;
 IF nikon_community_author(qid)->>'avatarUrl'<>saved_custom
 OR (nikon_user_session(repeat('b',64))->'user')->>'avatarUrl'<>saved_custom THEN RAISE EXCEPTION 'community/auth stale avatar'; END IF;
 -- Rerunning the migration must not reset custom state or overwrite provider snapshot.
 q:=nikon_sign_in_qq('123456789',repeat('B',32),'QQ refreshed fixture','https://q.qlogo.cn/latest',repeat('f',64),now()+interval '1 day');
 IF q->>'avatarSource'<>'custom' OR q->>'avatarUrl'<>saved_custom THEN RAISE EXCEPTION 'QQ login overwrote custom avatar'; END IF;
 q:=nikon_sign_in_qq('123456789',repeat('B',32),'QQ missing avatar fixture','',repeat('9',64),now()+interval '1 day');
 IF q->>'avatarSource'<>'custom' OR q->>'avatarUrl'<>saved_custom THEN RAISE EXCEPTION 'missing QQ photo overwrote custom avatar'; END IF;
 BEGIN
  PERFORM nikon_avatar_reset(qid,(updated->>'updatedAt')::timestamptz); RAISE EXCEPTION 'stale reset accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'USER_CONFLICT' THEN RAISE; END IF; END;
 updated:=nikon_avatar_reset(qid,(q->>'updatedAt')::timestamptz);
 IF updated->>'avatarSource'<>'qq' OR updated->>'avatarUrl'<>'https://q.qlogo.cn/latest'
 OR nikon_avatar_asset(asset_id) IS NOT NULL THEN RAISE EXCEPTION 'QQ reset did not select newest provider avatar'; END IF;
 -- Password reset after a real custom avatar returns to the native default.
 expected_time:=(p->>'updatedAt')::timestamptz;
 result:=nikon_avatar_reserve(pid,password_asset,expected_time,repeat('3',64),repeat('4',64));
 metadata:=metadata||jsonb_build_object('storagePath','avatars/'||password_asset::text||'.jpg','url','https://avatar.example/preset-library/avatars/'||password_asset::text||'.jpg');
 p:=nikon_avatar_set(pid,password_asset,expected_time,metadata);
 p:=nikon_avatar_reset(pid,(p->>'updatedAt')::timestamptz);
 IF p->>'avatarSource'<>'default' OR p->>'avatarUrl'<>'' OR nikon_avatar_asset(password_asset) IS NOT NULL THEN RAISE EXCEPTION 'password reset failed'; END IF;
 -- Login/profile edit while bytes are being transferred must win over stale commit.
 expected_time:=(updated->>'updatedAt')::timestamptz;
 PERFORM nikon_avatar_reserve(qid,stale_asset,expected_time,repeat('1',64),repeat('2',64));
 q:=nikon_sign_in_qq('123456789',repeat('B',32),'QQ concurrent fixture','https://q.qlogo.cn/current',repeat('0',64),now()+interval '1 day');
 BEGIN
  PERFORM nikon_avatar_set(qid,stale_asset,expected_time,metadata); RAISE EXCEPTION 'stale upload accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'USER_CONFLICT' THEN RAISE; END IF; END;
 IF nikon_avatar_asset(stale_asset) IS NOT NULL THEN RAISE EXCEPTION 'conflicted upload public'; END IF;
 expected_time:=(q->>'updatedAt')::timestamptz;
 PERFORM nikon_avatar_reserve(qid,expired_asset,expected_time,repeat('1',64),repeat('2',64));
 UPDATE nikon_avatar_uploads SET created_at=clock_timestamp()-interval '11 minutes' WHERE id=expired_asset;
 BEGIN
  PERFORM nikon_avatar_set(qid,expired_asset,expected_time,metadata); RAISE EXCEPTION 'expired upload accepted';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'AVATAR_RESERVATION_EXPIRED' THEN RAISE; END IF; END;
 -- Returned denials commit their counters, rather than throwing and rolling them back.
 DELETE FROM nikon_abuse_windows WHERE key LIKE 'avatar:%';
 FOR i IN 1..12 LOOP
  result:=nikon_avatar_reserve(qid,gen_random_uuid(),expected_time,repeat('5',64),repeat('6',64));
  IF result->>'allowed'<>'true' THEN RAISE EXCEPTION 'account budget denied early'; END IF;
 END LOOP;
 SELECT reserved_bytes INTO previous_budget FROM nikon_avatar_storage_budget;
 result:=nikon_avatar_reserve(qid,gen_random_uuid(),expected_time,repeat('5',64),repeat('6',64));
 SELECT reserved_bytes INTO after_budget FROM nikon_avatar_storage_budget;
 SELECT count INTO denied_count FROM nikon_abuse_windows WHERE key='avatar:account:'||qid::text;
 IF result->>'allowed'<>'false' OR after_budget<>previous_budget OR denied_count<>13 THEN RAISE EXCEPTION 'account rejection/refund incorrect'; END IF;
 -- Each shared source/installation/environment dimension independently denies uploads.
 FOR i IN 1..3 LOOP
  DELETE FROM nikon_abuse_windows WHERE key LIKE 'avatar:%';
  INSERT INTO nikon_abuse_windows(key,count) VALUES(CASE i WHEN 1 THEN 'avatar:global' WHEN 2 THEN 'avatar:source:'||repeat('5',64) ELSE 'avatar:installation:'||repeat('6',64) END,CASE i WHEN 1 THEN 512 WHEN 2 THEN 120 ELSE 60 END);
  result:=nikon_avatar_reserve(qid,gen_random_uuid(),expected_time,repeat('5',64),repeat('6',64));
  IF result->>'allowed'<>'false' THEN RAISE EXCEPTION 'shared dimension bypass %',i; END IF;
 END LOOP;
 DELETE FROM nikon_abuse_windows WHERE key LIKE 'avatar:%';
 UPDATE nikon_avatar_storage_budget SET reserved_bytes=8589934592;
 result:=nikon_avatar_reserve(qid,gen_random_uuid(),expected_time,repeat('5',64),repeat('6',64));
 IF result->>'allowed'<>'false' THEN RAISE EXCEPTION 'lifetime byte budget bypass'; END IF;
 UPDATE nikon_users SET status='disabled' WHERE id=qid;
 BEGIN
  PERFORM nikon_avatar_reserve(qid,second_asset,expected_time,repeat('5',64),repeat('6',64)); RAISE EXCEPTION 'disabled account reserved upload';
 EXCEPTION WHEN OTHERS THEN IF SQLERRM<>'USER_LOGIN_REQUIRED' THEN RAISE; END IF; END;
END; $$;
ROLLBACK;
