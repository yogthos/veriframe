#!/bin/bash
# Run the harness against GLM (Zhipu BigModel coding endpoint).
# Requires ZHIPU_API_KEY in your environment.
#
# Override HARNESS_MODEL to use a different GLM variant (default glm-4.6).
TMPDIR=/tmp \
  HARNESS_PROVIDER=glm \
  HARNESS_MODEL=${HARNESS_MODEL:-glm-5.1} \
  HARNESS_MAX_TOKENS=${HARNESS_MAX_TOKENS:-16384} \
  HARNESS_TIMEOUT_MS=${HARNESS_TIMEOUT_MS:-300000} \
  HARNESS_PORT=${HARNESS_PORT:-3001} \
  DEBUG=${DEBUG:-queue} \
  npm start
