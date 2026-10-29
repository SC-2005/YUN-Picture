-- 短信验证码登录：给已有数据库的 user 表补手机号字段
use yu_picture;

ALTER TABLE user
    ADD COLUMN phone varchar(20) NULL COMMENT '手机号' AFTER userPassword,
    ADD INDEX idx_phone (phone);
