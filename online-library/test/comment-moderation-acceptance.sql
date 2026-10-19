BEGIN;
CREATE OR REPLACE FUNCTION pg_temp.check_true(value boolean,label text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF value IS DISTINCT FROM true THEN RAISE EXCEPTION 'FAIL: %',label; END IF; END $$;
DO $$
DECLARE a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); preset uuid:=gen_random_uuid(); root_id uuid:=gen_random_uuid();
 r jsonb; request jsonb; cid uuid; first_message uuid; foreign_message uuid; unseen_message uuid; caught boolean;
BEGIN
 INSERT INTO nikon_users(id,nickname,created_at) VALUES(a,'Fresh',now()),(b,'Established',now()-interval '2 days');
 INSERT INTO nikon_presets(id,draft,status,published,published_revision,published_at) VALUES(preset,'{}','published','{"name":"Isolated fixture","file":{"url":"https://fixture.invalid/a.ncp","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","byteLength":52}}',1,now());
 INSERT INTO nikon_community_comments(id,preset_id,user_id,text,status,was_public) VALUES(root_id,preset,b,'root','visible',true);
 PERFORM nikon_community('adminSetSettings',NULL,NULL,'{"settings":{"commentIntervalSeconds":0}}');
 request:=jsonb_build_object('text','新用户正常交流','parentId',root_id,'idempotencyKey','normal-first-001');
 r:=nikon_community('comment',a,preset,request||'{"moderation":{"decision":"allow","version":"rules-v1"}}'); cid:=(r->>'id')::uuid;
 PERFORM pg_temp.check_true(r->>'status'='visible','normal first comment is immediately visible');
 SELECT id INTO first_message FROM nikon_community_messages WHERE comment_id=cid;
 PERFORM pg_temp.check_true(first_message IS NOT NULL,'visible reply notifies recipient');
 -- Simulate a previously stored hash before moderation fields existed.
 UPDATE nikon_community_idempotency SET request_hash=md5(request::text) WHERE user_id=a AND key='normal-first-001';
 r:=nikon_community('comment',a,preset,request||'{"moderation":{"decision":"review","reason":"new rules"}}');
 PERFORM pg_temp.check_true((r->>'id')::uuid=cid AND r->>'status'='visible','replay uses original user payload and stored result');
 PERFORM pg_temp.check_true((SELECT count(*)=1 FROM nikon_community_messages WHERE comment_id=cid),'replay never duplicates message');
 r:=nikon_community('comment',b,preset,'{"text":"广告待核查","idempotencyKey":"old-user-review-001","moderation":{"decision":"review","reason":"疑似广告"}}'); cid:=(r->>'id')::uuid;
 PERFORM pg_temp.check_true(r->>'status'='pending' AND (SELECT reason='疑似广告' FROM nikon_community_comments WHERE id=cid),'established user is checked and reason retained');
 PERFORM pg_temp.check_true(NOT EXISTS(SELECT 1 FROM nikon_community_messages WHERE comment_id=cid),'pending never notifies');
 r:=nikon_community('comment',b,preset,'{"text":"缺少服务端审核","idempotencyKey":"missing-check-001"}');
 PERFORM pg_temp.check_true(r->>'status'='pending','missing moderation fails closed');
 INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(a,root_id,root_id) RETURNING id INTO foreign_message;
 r:=nikon_community('comment',a,preset,'{"text":"later arrival","parentId":null,"idempotencyKey":"later-comment-001","moderation":{"decision":"allow"}}');
 INSERT INTO nikon_community_messages(user_id,comment_id,parent_id) VALUES(b,(r->>'id')::uuid,root_id) RETURNING id INTO unseen_message;
 r:=nikon_community('readBatch',b,NULL,jsonb_build_object('ids',jsonb_build_array(first_message,foreign_message,gen_random_uuid())));
 PERFORM pg_temp.check_true((r->>'unread')::int=1 AND jsonb_array_length(r->'readIds')=1,'only own displayed IDs acknowledged');
 PERFORM pg_temp.check_true((SELECT read_at IS NULL FROM nikon_community_messages WHERE id=foreign_message),'foreign account untouched');
 PERFORM pg_temp.check_true((SELECT read_at IS NULL FROM nikon_community_messages WHERE id=unseen_message),'new unloaded arrival stays unread');
 r:=nikon_community('readBatch',b,NULL,jsonb_build_object('ids',jsonb_build_array(first_message)));
 PERFORM pg_temp.check_true((r->>'unread')::int=1,'read replay idempotent');
 caught:=false; BEGIN PERFORM nikon_community('readBatch',b,NULL,'{"ids":["invalid"]}'); EXCEPTION WHEN raise_exception THEN caught:=SQLERRM='INVALID_COMMUNITY'; END;
 PERFORM pg_temp.check_true(caught,'malformed IDs rejected');
 PERFORM pg_temp.check_true(NOT has_function_privilege('anon','nikon_community_read_messages(uuid,jsonb)','EXECUTE') AND NOT has_function_privilege('authenticated','nikon_community_read_messages(uuid,jsonb)','EXECUTE'),'read helper service-only');
END $$;
ROLLBACK;
