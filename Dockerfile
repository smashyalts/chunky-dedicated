FROM openjdk:17

ENV MINECRAFT_VERSION=1.20.4
ENV PAPER_BUILD=435
ENV CHUNKY_VERSION=1.3.136
ENV JAVA_ARGS="-Xmx4G"

ENV SEED=""

WORKDIR /srv/
COPY ./docker-data .
COPY ./chunky-dedicated/target/chunky-dedicated-*.jar ./plugins
RUN chmod 777 -R .
ENTRYPOINT ./entrypoint.sh
