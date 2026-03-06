#!/bin/bash
set -euo pipefail

echo "Checking for remaining tasks in TESTING.md..."

# 1. Check if there are any unchecked boxes left
if ! grep -q "\[ \]" TESTING.md; then
  echo "Success: All tests in TESTING.md are checked off! Halting the Jules loop."
  exit 0
fi

echo "Unfinished tests found. Proceeding with loop..."

# 2. Rate Limiter Check (Fixed to look at the previous merge)
# Fetch the tip of main AND its parent
git fetch origin main --depth=2

# Get the timestamp of the PREVIOUS commit on main (before our brand new merge)
BASE_COMMIT_TS=$(git log -1 --format=%ct origin/main~1)
NOW=$(date +%s)

ELAPSED=$((NOW - BASE_COMMIT_TS))
MIN_WAIT=300 # 5 minutes in seconds

echo "Time elapsed since previous Jules cycle completed: $ELAPSED seconds."

if [ "$ELAPSED" -lt "$MIN_WAIT" ]; then
  WAIT_TIME=$((MIN_WAIT - ELAPSED))
  echo "Sleeping for $WAIT_TIME seconds to respect the 300/day quota..."
  sleep "$WAIT_TIME"
else
  echo "Cycle took longer than 5 minutes. No sleep required."
fi

# 3. Trigger the next Jules session
if [ -z "${JULES_API_KEY:-}" ]; then
  echo "Error: JULES_API_KEY environment variable is not set."
  exit 1
fi

echo "Building JSON payload from prompt.txt..."
PROMPT_CONTENT=$(cat prompt.txt)

# Use jq to safely construct the JSON payload and escape all newlines/quotes in the prompt
JSON_PAYLOAD=$(jq -n --arg prompt "$PROMPT_CONTENT" '{
  sourceContext: {
    source: "sources/github/alpeware/datachannel-clj",
    githubRepoContext: {
      startingBranch: "main"
    }
  },
  automationMode: "AUTO_CREATE_PR",
  prompt: $prompt
}')

echo "Triggering next Jules session..."
curl -X POST "https://jules.googleapis.com/v1alpha/sessions" \
  -H "x-goog-api-key: ${JULES_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "$JSON_PAYLOAD"
