# Estágio 1: build com JDK e Maven.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build

# Baixa as dependências antes de copiar o código, para o Docker reaproveitar
# essa camada enquanto o pom.xml não mudar.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# Estágio 2: execução só com JRE, sem Maven nem código-fonte.
FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app

# Usuário sem privilégios de root.
RUN useradd --system --no-create-home --shell /usr/sbin/nologin app

COPY --from=build /build/target/core-api.jar /app/core-api.jar

USER app
EXPOSE 8080
# Forma exec: o java é o PID 1 e recebe o SIGTERM do docker stop.
ENTRYPOINT ["java", "-jar", "/app/core-api.jar"]
