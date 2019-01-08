FROM openjdk:8-jdk-alpine

COPY ./build/libs/refresh-flow-service-*.jar refresh-flow-service.jar

ENTRYPOINT ["java", "-jar", "/refresh-flow-service.jar"]