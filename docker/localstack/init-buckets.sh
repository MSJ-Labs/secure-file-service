#!/bin/bash
# Runs once LocalStack is ready. Idempotent: LocalStack keeps no data across restarts in the community edition.
set -euo pipefail

awslocal s3api create-bucket --bucket "${S3_QUARANTINE_BUCKET:-quarantine}"
awslocal s3api create-bucket --bucket "${S3_CLEAN_BUCKET:-clean}"