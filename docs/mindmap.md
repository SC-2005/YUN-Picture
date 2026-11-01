# YUN-Picture 思维导图

> GitHub / VS Code / Typora 等支持 Mermaid 的工具可以直接看到图形；不支持的平台可以看下面的文字版大纲。

## 功能与架构全景

```mermaid
mindmap
  root((YUN-Picture))
    用户端
      首页
        瀑布流布局
        滚动加载
        图片懒加载
        关键词搜索
        分类浏览
      图片
        本地上传
        URL 上传
        自动压缩
        缩略图
        图片详情
        分享链接
        AI 扩图
      空间
        个人空间
        团队空间
        邀请成员
        角色权限
        批量编辑
        实时协同
        空间分析
      用户中心
        账号注册
        密码登录
        短信验证码登录
        资料修改
    管理端
      图片审核
      用户管理
      空间管理
      批量编辑
    后端能力
      接口层
        Controller
        DTO 与 VO
        Assembler
      应用层
        图片应用服务
        空间应用服务
        用户应用服务
        一次性维护任务
      领域层
        图片领域
        空间领域
        用户领域
      基础设施层
        MyBatis-Plus
        COS 客户端
        DashScope 客户端
        短信客户端
        AOP 鉴权
      公共能力
        Sa-Token 鉴权
        WebSocket 协同
        Redis 缓存
        Spring Session
    外部服务
      MySQL
      Redis
      腾讯云 COS
      阿里云百炼
      阿里云短信
    工程与部署
      Knife4j 接口文档
      OpenAPI 前端代码生成
      Nginx 反向代理
      systemd 服务
      宝塔面板
      多环境配置
```

## 图片上传与处理流程

```mermaid
flowchart TD
    A[选择本地文件 / 输入图片 URL] --> B{来源}
    B -->|本地文件| C[校验格式与大小]
    B -->|URL| D[HEAD 校验 + 下载]
    C --> E[上传到腾讯云 COS]
    D --> E
    E --> F[COS 图片处理：压缩 / 缩略图]
    F --> G[写入图片表]
    G --> H[记录分类与标签]
    H --> I[首页 / 空间展示]
```

## 团队空间协同编辑流程

```mermaid
flowchart LR
    A[成员 A 编辑图片] --> B[WebSocket 发送编辑事件]
    B --> C[Disruptor 队列]
    C --> D[图片编辑事件处理器]
    D --> E[广播给同一空间的其他成员]
    E --> F[成员 B / C 实时看到修改]
```

## 文字版大纲

```text
YUN-Picture
├── 用户端
│   ├── 首页：瀑布流、滚动加载、图片懒加载、搜索、分类
│   ├── 图片：本地上传、URL 上传、压缩缩略图、详情、分享、AI 扩图
│   ├── 空间：个人/团队空间、邀请成员、角色权限、批量编辑、实时协同、空间分析
│   └── 用户中心：注册、密码登录、短信验证码登录、资料修改
├── 管理端
│   ├── 图片审核
│   ├── 批量编辑
│   ├── 用户管理
│   └── 空间管理
├── 后端能力（DDD）
│   ├── interfaces：Controller、DTO/VO、Assembler
│   ├── application：图片/空间/用户应用服务、维护任务
│   ├── domain：图片、空间、用户领域
│   ├── infrastructure：MyBatis-Plus、COS、DashScope、短信、AOP
│   └── shared：Sa-Token 鉴权、WebSocket 协同、缓存
├── 外部服务
│   ├── MySQL
│   ├── Redis
│   ├── 腾讯云 COS
│   ├── 阿里云百炼（Qwen3-VL、通义万相）
│   └── 阿里云短信
└── 工程与部署
    ├── Knife4j 接口文档
    ├── OpenAPI 前端代码生成
    ├── Nginx 反向代理
    ├── systemd / 宝塔面板
    └── local / prod 多环境配置
```
