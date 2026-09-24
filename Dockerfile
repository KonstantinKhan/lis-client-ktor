### Build stage: gradlew installDist (тот же путь, что run.sh/run.cmd)
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

### Runtime stage: только JRE + собранный дистрибутив
FROM eclipse-temurin:17-jre-jammy AS runtime

# C.UTF-8 всегда доступна без locale-gen — нужна, чтобы консоль (readLine/readPassword)
# и вывод не превращались в кракозябры на кириллице (логин/пароль/сообщения на русском).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

COPY --from=build /src/build/install/lis-client-ktor /opt/lis-client-ktor

# Рабочая директория — сюда монтируются settings.json и исходный xlsx (см. docker-compose.yml).
# App.checkConfig() ищет settings.json относительно текущей директории процесса.
WORKDIR /data

ENTRYPOINT ["/opt/lis-client-ktor/bin/lis-client-ktor"]
