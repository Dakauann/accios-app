#!/bin/bash
set -e

OPENCV_URL="https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip"
ZIP_FILE="opencv-4.12.0-android-sdk.zip"
TARGET_DIR="OpenCV-android-sdk"

# Download the OpenCV Android SDK
wget -O "$ZIP_FILE" "$OPENCV_URL"

# Unzip the SDK
unzip "$ZIP_FILE"

# Move the sdk directory to current location
if [ -d "OpenCV-android-sdk/sdk" ]; then
  mkdir -p ./opencv2
  mv OpenCV-android-sdk/sdk ./opencv2
  echo "SDK extracted to ./opencv2"
else
  echo "SDK directory not found in the archive."
  exit 1
fi

rm -rf "$ZIP_FILE" OpenCV-android-sdk

echo "Done."