#!/bin/bash
# Local harness for the pure-Kotlin feed layer files (no Gradle, no Android SDK).
# usage: run-tests.sh <path to Signal-Android tree> [work dir]
# Needs (default location /workspace/work/android-harness): JDK 21, kotlinc 2.2.20, junit 4.13.2, hamcrest 1.3, assertk-jvm 0.28.1,
# kotlinx-serialization-core/json-jvm 1.4.1 (~/.m2; only parseToJsonElement/JsonObject/... are used, no plugin) and kotlinx-coroutines-core-jvm (shipped in kotlinc/lib).
# The REAL source files are compiled. Only types that need Android/serialization-plugin/okhttp are stubbed, and the stubs for
# FeedEventPayload, FeedApiException and FeedTokenSource are cut out of the real FeedModels.kt / FeedApi.kt (annotations removed),
# so they cannot drift from the app. FeedTab is stubbed (real one has @StringRes); its apiName values are checked against the real file.
set -euo pipefail
SRC=${1:?path to Signal-Android tree}
H=${HARNESS:-/workspace/work/android-harness}
W=${2:-$H/build-feed-layer}
rm -rf "$W"; mkdir -p "$W/src"
export JAVA_HOME=$H/jdk-21.0.12.1+1 PATH=$H/jdk-21.0.12.1+1/bin:$H/kotlinc/bin:$PATH
M=$SRC/app/src/main/java/org/thoughtcrime/securesms/feed
T=$SRC/app/src/test/java/org/thoughtcrime/securesms/feed

grep -q 'apiName = "recommend"' "$M/FeedTab.kt" && grep -q 'apiName = "following"' "$M/FeedTab.kt"
cat > "$W/src/FeedTabStub.kt" <<EOK
package org.thoughtcrime.securesms.feed
enum class FeedTab(val apiName: String) { RECOMMEND("recommend"), FOLLOWING("following") }
EOK
{
  echo 'package org.thoughtcrime.securesms.feed.net'
  echo 'import java.io.IOException'
  # data class FeedEventPayload ... closing paren line
  awk '/^data class FeedEventPayload\(/{p=1} p{print} p&&/^\)/{exit}' "$M/net/FeedModels.kt" | sed -E 's/@SerialName\("[a-z_]+"\) //'
  # class FeedApiException ... first closing brace at column 0
  awk '/^class FeedApiException\(/{p=1} p{print} p&&/^}/{exit}' "$M/net/FeedApi.kt"
  awk '/^interface FeedTokenSource \{/{p=1} p{print} p&&/^}/{exit}' "$M/net/FeedApi.kt"
  # MAX_EVENTS constant used by FeedEventBuffer
  echo 'class FeedApi { companion object {'
  grep -E '^\s*const val MAX_EVENTS = ' "$M/net/FeedApi.kt"
  echo '} }'
} > "$W/src/Stubs.kt"

CP=$H/junit-4.13.2.jar:$H/hamcrest-core-1.3.jar:$H/assertk-jvm-0.28.1.jar
CP=$CP:$HOME/.m2/repository/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.4.1/kotlinx-serialization-core-jvm-1.4.1.jar
CP=$CP:$HOME/.m2/repository/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.4.1/kotlinx-serialization-json-jvm-1.4.1.jar
CP=$CP:$H/kotlinc/lib/kotlinx-coroutines-core-jvm.jar:$H/kotlinc/lib/kotlin-stdlib.jar

# main + test files of the feed layer; only the ones that exist in this commit are used
MAIN=(FeedEvents.kt FeedDetailEvents.kt net/FeedCapabilities.kt net/FeedAuthBackoff.kt net/FeedRetryAfter.kt net/FeedCooldown.kt)
TESTS=(FeedEventsTest:feed FeedDetailEventsTest:feed net/FeedCapabilitiesTest:feed net/FeedAuthBackoffTest:feed)
CLASSES=()
for f in "${MAIN[@]}"; do [ -f "$M/$f" ] && cp "$M/$f" "$W/src/"; done
for t in "${TESTS[@]}"; do
  f=${t%%:*}
  if [ -f "$T/$f.kt" ]; then
    cp "$T/$f.kt" "$W/src/"
    pkg=org.thoughtcrime.securesms.feed; [[ $f == net/* ]] && pkg=$pkg.net
    CLASSES+=("$pkg.$(basename "$f")")
  fi
done
if ! kotlinc -nowarn -cp "$CP" "$W"/src/*.kt -d "$W/out" > "$W/compile.log" 2>&1; then
  grep -v '^warning' "$W/compile.log"; echo "COMPILE FAILED"; exit 1
fi
java -cp "$W/out:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
