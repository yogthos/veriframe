#!/bin/bash
# Run the harness against DeepSeek (api.deepseek.com).
# Requires DEEPSEEK_API_KEY in your environment.
#
# Override HARNESS_MODEL to use a different DeepSeek variant
# (default deepseek-chat; deepseek-reasoner is the thinking model).
PATH="$HOME/.elan/bin:$PATH" \
TMPDIR=/tmp \
  HARNESS_PROVIDER=deepseek \
  HARNESS_MODEL=${HARNESS_MODEL:-deepseek-reasoner} \
  HARNESS_MAX_TOKENS=${HARNESS_MAX_TOKENS:-16384} \
  HARNESS_TIMEOUT_MS=${HARNESS_TIMEOUT_MS:-300000} \
  HARNESS_PORT=${HARNESS_PORT:-3001} \
  DEBUG=${DEBUG:-queue} \
  npm start
