FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21

ENV TZ="Europe/Oslo"
WORKDIR /app
COPY build/install/no.nav.please/lib ./lib
EXPOSE 8080
CMD ["-cp", "/app/lib/*", "no.nav.please.ApplicationKt"]
