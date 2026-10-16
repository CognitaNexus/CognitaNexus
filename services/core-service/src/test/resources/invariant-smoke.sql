\set ON_ERROR_STOP off
\pset pager off
\echo '=== 用例1: PAPER 成果挂 PATENT 扩展，应被拒绝 ==='
insert into core_achievement(id,type,title,title_norm,data_nature) values ('a1','PAPER','测试成果','ceshichengguo','REAL');
insert into core_patent(achievement_id) values ('a1');

\echo '=== 用例2: PUBLIC 成果缺类型扩展，提交时应被拒绝 ==='
begin;
insert into core_achievement(id,type,title,title_norm,data_nature,visibility,effective_status) values ('a2','PAPER','公开但缺扩展','gongkaiqueque','REAL','PUBLIC','EFFECTIVE');
commit;

\echo '=== 用例3: PUBLIC 成果配匹配扩展，应通过 ==='
begin;
insert into core_achievement(id,type,title,title_norm,data_nature,visibility,effective_status) values ('a3','PAPER','正常论文','zhengchanglunwen','REAL','PUBLIC','EFFECTIVE');
insert into core_paper(achievement_id,doi,doi_norm) values ('a3','10.1/x','10.1/x');
commit;
select 'a3 插入成功' as result;

\echo '=== 用例4: 同 DOI 再插一条成果，唯一约束应拒绝 ==='
insert into core_achievement(id,type,title,title_norm,data_nature) values ('a4','PAPER','重复DOI','chongfudoi','REAL');
insert into core_paper(achievement_id,doi,doi_norm) values ('a4','10.1/x','10.1/x');

\echo '=== 用例5: 同一学者对同一成果两条参与关系，应被拒绝 ==='
insert into core_scholar(id,display_name,name_norm,data_nature) values ('s1','张三','zhangsan','REAL');
insert into core_contribution(id,scholar_id,achievement_id,relation_state) values ('c1','s1','a3','AUTO');
insert into core_contribution(id,scholar_id,achievement_id,relation_state) values ('c2','s1','a3','PENDING_VERIFY');

\echo '=== 用例6: 同一门户两条生效授权，应被拒绝 ==='
insert into iam_account(id,username,username_norm,password_hash,display_name,status) values ('acc1','u1','u1','h','用户1','ACTIVE'),('acc2','u2','u2','h','用户2','ACTIVE');
insert into iam_portal_authority(id,account_id,scholar_id,status,granted_at) values ('pa1','acc1','s1','ACTIVE',now());
insert into iam_portal_authority(id,account_id,scholar_id,status,granted_at) values ('pa2','acc2','s1','ACTIVE',now());

\echo '=== 用例7: 同 DOI 重复导入来源记录是允许的（来源版本幂等键） ==='
insert into raw_source_config(id,source_code,display_name,enabled,license_scope,base_url) values ('src1','OPENALEX','OpenAlex',true,'CC0','https://api.openalex.org');
insert into raw_source_record(id,source_id,source_record_id,content_fingerprint,payload,data_nature,fetched_at,catalog_revision) values ('r1','src1','W1','fp1','{}','REAL',now(),1);
insert into raw_source_record(id,source_id,source_record_id,content_fingerprint,payload,data_nature,fetched_at,catalog_revision) values ('r2','src1','W2','fp1','{}','REAL',now(),1);
select '同一来源不同记录ID可共存' as result;

\echo '=== 最终状态 ==='
select (select count(*) from core_achievement) as achievements,
       (select count(*) from core_contribution) as contributions,
       (select count(*) from iam_portal_authority) as authorities;
