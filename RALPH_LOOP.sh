#!/usr/bin/env bash

set -eu

# Configuration
MAX_ITERATIONS=20
PROMPT_FILE="RALPH_PROMPT.md"

for ((i=1; i<=MAX_ITERATIONS; i++)); do
    echo "=== Ralph Loop: Iteration $i of $MAX_ITERATIONS ==="
    
    # Run Claude Code with the prompt
    # --allow-all-tools is often needed for autonomous file/shell access
    # -p passes the content of our PROMPT.md
    # OUTPUT=$(claude --dangerously-skip-permissions -p "$(cat $PROMPT_FILE)"  2>&1)

     OUTPUT=$(claude -p "$(cat $PROMPT_FILE)" \
      --tools "default" \
      --verbose \
      --dangerously-skip-permissions \
      --output-format stream-json \
      | tee /tmp/claude-run.jsonl \
      | jq -r 'select(.type == "result") | .result')
    
    echo "$OUTPUT"

    # Check if Claude signaled that all tasks are finished
    if echo "$OUTPUT" | grep -q "RALPH_COMPLETE"; then
        echo "Checklist complete! Stopping loop."
        exit 0
    fi

    echo "Iteration $i finished. Restarting with fresh context..."
    sleep 2 # Brief pause to ensure file locks are released
done

echo "Reached max iterations."

