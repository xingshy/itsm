-- Move incident navigation under ticket management without changing incident permissions.
-- V2 seeds ticket management as menu 3; retain this ID and existing role grants.
INSERT INTO sys_menu(parent_id,name,type,path,component,perm,icon,sort)
SELECT id,'工单列表','C','/tickets','ticket/index','itsm:ticket:list','Tickets',1
FROM sys_menu WHERE id=3 AND deleted=false;

-- Preserve existing ticket-list permissions before converting its old page to a directory.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT old.role_id,child.id FROM sys_role_menu old
JOIN sys_menu child ON child.parent_id=3 AND child.perm='itsm:ticket:list' AND child.deleted=false
WHERE old.menu_id=3
ON CONFLICT DO NOTHING;

UPDATE sys_menu SET parent_id=0,type='M',path='/tickets',component=NULL,perm=NULL,
    icon='Tickets',sort=2,updated_at=now()
WHERE id=3 AND deleted=false;

UPDATE sys_menu SET parent_id=3,sort=2,updated_at=now()
WHERE perm='itsm:incident:list' AND deleted=false;

-- Grant the directory to incident participants so their menu tree has the same hierarchy.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,3 FROM sys_role_menu rm
JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perm='itsm:incident:list' AND m.deleted=false
ON CONFLICT DO NOTHING;
