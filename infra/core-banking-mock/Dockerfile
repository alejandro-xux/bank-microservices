# El mainframe emulado con estado (BANK-90): WireMock más su extensión de estado, que recuerda cada asiento por id-op.
# El jar no está en el repo: se baja de Maven Central y, si no coincide el sha256 publicado, la imagen no se construye.
# RUN y no "ADD --checksum": Testcontainers construye con el builder clásico de Docker, que no lo entiende
FROM wiremock/wiremock:3.13.2
ARG STATE_EXTENSION=wiremock-state-extension-standalone-0.10.1.jar
ARG STATE_EXTENSION_SHA256=8898a2700ca16f2d235fa42253e3f400cc08117f9190fdfe1686014c31619ae7
RUN curl -sSfL -o /var/wiremock/extensions/${STATE_EXTENSION} \
      https://repo1.maven.org/maven2/org/wiremock/extensions/wiremock-state-extension-standalone/0.10.1/${STATE_EXTENSION} \
 && echo "${STATE_EXTENSION_SHA256}  /var/wiremock/extensions/${STATE_EXTENSION}" | sha256sum -c -
# La extensión se registra sola (ServiceLoader: StandaloneStateExtension); WireMock 3 la carga desde el classpath
CMD ["--global-response-templating", "--disable-banner"]
