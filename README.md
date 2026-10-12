# ITSM IT 服务管理平台

统一服务门户 · ITIL 流程驱动 · SLA 全程度量。

- 前端:`itsm-ui`(Vue 3 + Vite + Element Plus + Pinia + Vue Flow)
- 后端:`itsm-server`(Spring Boot 3.2 + MyBatis-Plus + Flowable 7 + PostgreSQL + JWT)
- 设计文档见 `docs/`,各子模块说明见 `itsm-ui/README.md`、`itsm-server/README.md`

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | -- | --- |
| JDK | 17+ | 后端编译与运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端构建与开发 |
| PostgreSQL | 18+ | 业务库(需支持 `currentSchema`) |
| Redis | 6+ | 可选(启动不强依赖,按需安装) |

## 一、准备数据库(首次)

以超级用户连接 PostgreSQL 后执行(库:`itsdb`,用户:`itsm_app`,密码:`123456`,schema:`itsm`):

```sql
CREATE USER itsm_app WITH PASSWORD '123456';
CREATE DATABASE itsdb OWNER itsm_app ENCODING 'UTF8';

-- 切换到 itsdb 库执行(PSQL:\c itsdb)
CREATE SCHEMA IF NOT EXISTS itsm AUTHORIZATION itsm_app;
```

> PG 15+ 默认普通用户无 `public` schema 建表权限,本项目所有表统一放在 `itsm` schema 下,无需额外授权。

## 二、启动后端(端口 8080)

连接配置在 `itsm-server/itsm-boot/src/main/resources/application.yaml`,默认值如下,可用环境变量覆盖:

```yaml
spring.datasource.url:      jdbc:postgresql://localhost:5432/itsdb?currentSchema=itsm   # ITS_DB_URL
spring.datasource.username: itsm_app                                                     # ITS_DB_USER
spring.datasource.password: 123456                                                       # ITS_DB_PASSWORD
```

**方式一:命令行**

```bash
# 1. 先把依赖模块安装到本地仓库(首次或依赖变更后执行一次)
cd itsm-server
mvn -q -pl itsm-boot -am install -DskipTests

# 2. 单独启动 itsm-boot(spring-boot:run 不能直接在聚合根 pom 上执行)
cd itsm-boot
mvn spring-boot:run -DskipTests
```

**方式二:IDE(推荐)**

直接运行主类 `com.itsm.boot.ItsmApplication`(工作目录任意)。

首次启动 Flyway 会自动在 `itsm` schema 下完成建表与种子数据(同时初始化 Flowable 的 `ACT_*` 表),无需手工执行 SQL。

启动成功标志:`Started ItsmApplication`,验证:

```
GET http://localhost:8080/actuator/health   → {"status":"UP"}
```

## 三、启动前端(端口 5173)

```bash
cd itsm-ui
npm install        # 首次执行
npm run dev
```

Vite 已配置代理:`/api → http://localhost:8080`,前端无需处理跨域。

启动成功标志:`VITE ready`,访问 `http://localhost:5173/`。

## 四、访问与默认账号

| 入口 | 地址 |
| --- | --- |
| 前端门户 | http://localhost:5173/ |
| 后端 API | http://localhost:8080/api/v1/** |
| Swagger 文档 | http://localhost:8080/swagger-ui.html |
| 健康检查 | http://localhost:8080/actuator/health |

内置管理员:`admin / admin123`(登录后可在「系统管理 → 用户管理」维护人员;删除人员为逻辑删除,仅标记不删记录)。

## 五、常见问题

- **后端启动报 `Unable to find a suitable main class`**:`mvn spring-boot:run` 跑到了父 POM 上,请按上文先 `install` 再进入 `itsm-boot` 目录启动。
- **登录 401 / 接口 403**:确认已用 `admin` 登录;权限点由 RBAC 控制(`sys:user:*` 等)。
- **逻辑删除相关 SQL 报错**:`application.yaml` 中 MyBatis-Plus 逻辑删除值必须是 `true/false`(PostgreSQL boolean),不要改回 `1/0`。
- **端口冲突**:前端改 `itsm-ui/vite.config.ts` 的 `server.port`;后端改 `application.yaml` 的 `server.port`(同步调整前端代理 target)。


## 事件管理

事件管理使用真实后端及数据库，入口为“工单管理 → 事件管理”。功能、草案冲突处理、配置及测试方式见 [事件管理实现说明](docs/事件管理实现说明.md)。
