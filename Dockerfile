# 第一阶段：构建阶段 (使用包含 Maven 的镜像以简化构建)
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 1. 首先复制父项目的 pom.xml
COPY pom.xml .

# 2. 复制所有子模块的代码 (多模块项目必须复制整个文件夹)
COPY r1-web ./r1-web
COPY r1-server ./r1-server

# 3. 构建项目
# 注意：mvn package 会先在 r1-web 中编译前端，然后在 r1-server 中编译后端并打包成 Fat JAR
RUN mvn clean package -DskipTests

# 第二阶段：运行阶段
FROM eclipse-temurin:17.0.14_7-jdk

WORKDIR /app

# 安装基础工具和 ffmpeg
RUN apt-get update && \
    apt-get install -y wget ffmpeg ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 根据系统架构下载对应的 yt-dlp 和 cloudflared 二进制
RUN ARCH=$(uname -m) && \
    echo "检测到系统架构: $ARCH" && \
    if [ "$ARCH" = "x86_64" ]; then \
        YT_URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"; \
        CF_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb"; \
    elif [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then \
        YT_URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64"; \
        CF_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64.deb"; \
    else \
        echo "不支持的架构: $ARCH"; exit 1; \
    fi && \
    echo "下载 yt-dlp URL: $YT_URL" && \
    wget "$YT_URL" -O /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp && \
    echo "下载 cloudflared URL: $CF_URL" && \
    wget "$CF_URL" -O /tmp/cloudflared.deb && \
    dpkg -i /tmp/cloudflared.deb || apt-get install -f -y && \
    rm -f /tmp/cloudflared.deb && \
    # 验证安装
    yt-dlp --version && \
    cloudflared --version

# 从构建阶段复制 jar 文件
# 注意路径：在多模块中，最终的可运行 jar 位于 r1-server 模块的 target 目录下
# 我们使用排除法只拷贝真正的可执行 jar (排除以 .original 结尾的文件)
COPY --from=builder /app/r1-server/target/r1-server-*.jar app.jar

# 复制脚本 (路径根据你 manage_cloudflared.sh 所在的实际模块位置而定，假设在项目根目录或 r1-server 下)
# 如果脚本在 r1-server 目录下，请改为 COPY --from=builder /app/r1-server/manage_cloudflared.sh /manage_cloudflared.sh
COPY --from=builder /app/r1-server/manage_cloudflared.sh /manage_cloudflared.sh
RUN chmod +x /manage_cloudflared.sh

# 暴露端口 (根据你的 Spring Boot 配置修改，默认 8080)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
