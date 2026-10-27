# 빌드 단계: 테스트는 CI에서 이미 돌렸으므로 건너뛴다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 소스보다 먼저 의존성을 받아 두면 소스만 바뀐 빌드에서 레이어 캐시가 재사용된다
RUN ./gradlew dependencies --no-daemon > /dev/null
COPY src src
RUN ./gradlew bootJar -x test --no-daemon && mv "$(ls build/libs/*.jar | grep -v plain)" app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
# root로 실행하지 않는다
RUN useradd --system --no-create-home zelo
COPY --from=build /app/app.jar app.jar
USER zelo
EXPOSE 8080 9292
ENTRYPOINT ["java", "-jar", "app.jar"]
