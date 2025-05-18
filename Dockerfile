FROM docker.io/openjdk:17-jdk-slim AS build

RUN apt-get update && \
    apt-get install -y curl && \
    apt-get install -y gnupg && \
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh
WORKDIR /usr/src/xhub
COPY . .
RUN clojure -T:build ci

FROM docker.io/gcr.io/distroless/java17-debian12
COPY --from=build /usr/src/xhub/target/net.clojars.xhub-team/xhub-0.1.0-SNAPSHOT.jar xhub.jar
CMD ["xhub.jar" ]
