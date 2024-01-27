FROM openjdk:17-oracle

LABEL maintainer="rasmus.svensson@live.com"

ADD backend/target/elotracking.jar app.jar

CMD [ "sh", "-c", "java -XX:MaxRAM=200m --add-opens java.base/java.lang=ALL-UNNAMED -jar -Dserver.port=$PORT /app.jar" ]
