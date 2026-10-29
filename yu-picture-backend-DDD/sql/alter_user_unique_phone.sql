-- 一个手机号只能绑定一个账号：把 phone 索引改成唯一索引
use yu_picture;

UPDATE user SET phone = NULL WHERE phone = '';

ALTER TABLE user DROP INDEX idx_phone;

ALTER TABLE user ADD UNIQUE INDEX uk_phone (phone);
