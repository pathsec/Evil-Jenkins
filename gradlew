#!/bin/sh
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)

# Use SDKMAN Java if available, otherwise fall back to system java
if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    . "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
fi

JAVA_EXEC="${JAVA_HOME:-$(dirname $(command -v java 2>/dev/null || echo /usr/bin/java))/../..}/bin/java"
JAVA_EXEC=$(command -v java 2>/dev/null || echo java)

exec "$JAVA_EXEC" \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
