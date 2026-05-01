#!/bin/bash
# DEBUG=* shows every category. Set DEBUG=queue,model for a quieter stream.
PATH="$HOME/.elan/bin:$PATH" \
TMPDIR=/tmp \
  HARNESS_MODEL_PATH=${HARNESS_MODEL_PATH:-models/Qwen3.6-35B-A3B-Q8_0.gguf} \
  HARNESS_MAX_TOKENS=${HARNESS_MAX_TOKENS:-49152} \
  HARNESS_TIMEOUT_MS=${HARNESS_TIMEOUT_MS:-600000} \
  HARNESS_PORT=${HARNESS_PORT:-3001} \
  DEBUG=${DEBUG:-queue} \
  npm start
