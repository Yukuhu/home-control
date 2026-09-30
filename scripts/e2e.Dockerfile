# Tracked copy of .superpowers/e2e.Dockerfile (.superpowers/ is gitignored agent scratch space).
# Gradle + JDK 25 plus Playwright's Chromium, Firefox and WebKit with their OS packages, for scripts/e2e.sh.
# Browsers live in /ms-playwright so any uid can use them.
FROM gradle:jdk25
ARG PLAYWRIGHT_VERSION
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN set -eux; \
    test -n "$PLAYWRIGHT_VERSION"; \
    case "$(dpkg --print-architecture)" in \
      amd64) node_dir=linux ;; \
      arm64) node_dir=linux-arm64 ;; \
      *) echo "unsupported architecture" >&2; exit 1 ;; \
    esac; \
    repo=https://repo1.maven.org/maven2/com/microsoft/playwright; \
    work=$(mktemp -d); cd "$work"; \
    fetch() { curl --proto =https --proto-redir =https -fsSLo "$1" "$2"; }; \
    fetch driver.jar "$repo/driver/$PLAYWRIGHT_VERSION/driver-$PLAYWRIGHT_VERSION.jar"; \
    fetch bundle.jar "$repo/driver-bundle/$PLAYWRIGHT_VERSION/driver-bundle-$PLAYWRIGHT_VERSION.jar"; \
    unzip -q driver.jar 'driver/package/*'; \
    unzip -q bundle.jar "driver/$node_dir/node"; \
    chmod +x "driver/$node_dir/node"; \
    apt-get update; \
    (cd driver/package && "../$node_dir/node" cli.js install --with-deps chromium firefox webkit); \
    chmod -R a+rX /ms-playwright; \
    cd /; rm -rf "$work" /var/lib/apt/lists/*

# Installing OS packages needs root; running the browsers and tests does not.
USER gradle
