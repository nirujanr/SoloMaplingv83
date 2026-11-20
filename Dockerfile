# SoloMapling (Cosmic v83 + ArtificialPlayer framework) Docker image
# Based on the upstream Cosmic Dockerfile (xinyifly / wejrox), extended for SoloMapling's
# runtime data files, which are read from CWD-relative "src/main/java/..." paths.

#
# Stage 1: build the JAR
#
FROM maven:3.9.6-amazoncorretto-21 AS jar
WORKDIR /opt/cosmic

# pom first so dependency layers cache across source edits
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -f ./pom.xml clean package -Dmaven.test.skip -T 1C

# Collect every non-Java file under src/main/java (bot dialogue YAML, item pools,
# movement recordings, FM shop names, town pins, version tables...) with its path
# intact, because the bot framework loads them via "src/main/java/soloMapling/..."
RUN mkdir -p /opt/botdata \
 && find src/main/java -type f ! -name '*.java' -exec cp --parents {} /opt/botdata/ \;

#
# Stage 2: runtime
#
FROM amazoncorretto:21
WORKDIR /opt/server

# WZ data first: large and rarely changes (WzXmlPatcher edits Item.wz in place at startup)
COPY wz ./wz
COPY --from=jar /opt/cosmic/target/Cosmic.jar ./Server.jar
COPY --from=jar /opt/botdata/src ./src
COPY scripts ./scripts/
COPY config.yaml ./

# Runtime output: log4j -> logs/, nav graph cache -> cache/, BotLogger -> ./BotLog.txt
# BotLog.txt is symlinked into logs/ so a single volume captures all logs.
RUN mkdir -p logs cache \
 && ln -s logs/BotLog.txt BotLog.txt

# Same heap as launch.bat; override with JAVA_OPTS
ENV JAVA_OPTS="-Xmx3g"

# Login server + world 0 channels 1-3 (port = 7575 + (channel-1) + world*100)
EXPOSE 8484 7575 7576 7577

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dwz-path=wz -jar ./Server.jar"]
