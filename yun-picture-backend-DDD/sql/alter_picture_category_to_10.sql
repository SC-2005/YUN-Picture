-- 分类现在 11 个：易烊千玺、壁纸、二次元、动漫、表情包、海报、风景、二次元壁纸、摄影、电商、模板
-- （「动漫」后来从二次元里独立出来，不再归并到二次元壁纸）
-- 线上由 PictureFillRunner 在补图前自动执行同样的归并；这个脚本用于手工执行/备查。
-- 注意：原「头像」分类涉及版权风险，已整体删除（先删库再删 COS 文件），
--       这里不要再用 SQL 把头像图片改成二次元，否则文件会留在对象存储里。

-- 1. 名字或标签里带「易烊千玺」的图片，全部归到易烊千玺分类（最先执行）
UPDATE picture
SET category = '易烊千玺'
WHERE (name LIKE '%易烊千玺%' OR tags LIKE '%易烊千玺%')
  AND category <> '易烊千玺';

-- 2. 被删掉的旧分类，归到最接近的保留分类
UPDATE picture SET category = '模板' WHERE category IN ('素材', '简约', '渐变');
UPDATE picture SET category = '壁纸' WHERE category = '背景';
UPDATE picture SET category = '摄影' WHERE category IN ('人物', '动物', '美食', '建筑', '科技', '运动', '其他');
UPDATE picture SET category = '二次元壁纸' WHERE category IN ('游戏', '国风');
-- 「插画」分类已取消，旧数据挪到替换它的「二次元壁纸」
UPDATE picture SET category = '二次元壁纸' WHERE category = '插画';
-- 追加任务曾把动漫图抓进二次元（名字从「二次元501」开始），上线新版后由
-- PictureFillRunner.moveExtraAnimeToAnimeCategory 自动挪到「动漫」；手工等价 SQL：
-- UPDATE picture SET category = '动漫' WHERE category = '二次元' AND name REGEXP '^二次元[5-9][0-9][0-9]$';
UPDATE picture SET category = '海报' WHERE category = '节日';
