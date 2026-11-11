# 前端镜像。
#
# 构建阶段在容器里跑 vue-tsc 类型检查 + vite build，而不是把本地 dist 拷进去：
# 让镜像构建本身承担「类型不过就不出镜像」这个门槛，否则本地先 build 一次再拷 dist，
# 就绕过了检查。
#
# 运行阶段用 nginx 提供静态文件，并由它反向代理三个后端服务——与开发期 vite 的代理规则
# 保持同一套路径前缀（/core、/ingest、/query）。开发与生产走不同的代理实现但不走不同的
# 路径约定，这样前端代码里的 /core 前缀在两个环境里指向同一件事。

# ------------------------------ 构建阶段 ------------------------------
FROM node:22-alpine AS build

WORKDIR /app

COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./

# --cache 必须显式覆盖：仓库里的 .npmrc 把缓存指向 D:/dev-tools/npm-cache（开发机在 Windows
# 的 D 盘），那条路径在 Linux 容器里不存在。让开发机的路径规约泄漏进镜像，构建会在
# 「写缓存目录」这一步报出一个与依赖无关的错误。
RUN npm ci --no-audit --no-fund --cache /root/.npm

COPY frontend/ ./

# 构建期必须给出三个后端地址：vite 在构建时会把代理配置写进产物之外，但若这里是
# 纯静态部署（无 nginx 代理），则需要在构建时确定 VITE_API_BASE。本项目采用同源代理，
# 故此处只做类型检查与打包。
RUN npx vue-tsc --noEmit && npx vite build

# ------------------------------ 运行阶段 ------------------------------
FROM nginx:1.27-alpine

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=4 \
  CMD ["sh", "-c", "wget -q -O /dev/null http://127.0.0.1/ || exit 1"]

CMD ["nginx", "-g", "daemon off;"]
