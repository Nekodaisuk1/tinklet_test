# TINKLET Operational Commands

## 0. Variables

```bash
DEVICE_IP=172.16.50.230
DEVICE_SERIAL="$DEVICE_IP:5555"
APP_ID=com.example.tinkletmjpeg
APP_ACTIVITY=com.example.tinkletmjpeg/.MainActivity
STREAM_URL=http://127.0.0.1:18080/stream
PYTHON_BIN=/Users/tanna.iori/tinklet_test/.venv/bin/python
```

## 1. Wireless ADB setup (first time after USB connect)

```bash
adb devices -l
adb -s P16M116D5252231 tcpip 5555
adb connect "$DEVICE_IP:5555"
adb devices -l
```

## 2. Reconnect wireless ADB (daily)

```bash
adb connect "$DEVICE_IP:5555"
adb devices -l
```

## 3. Restart Android app

```bash
adb -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID"
adb -s "$DEVICE_SERIAL" shell am start -n "$APP_ACTIVITY"
```

## 4. Restart viewer on Mac

```bash
pkill -f "app.py --source" || true
adb -s "$DEVICE_SERIAL" forward tcp:18080 tcp:8080
"$PYTHON_BIN" /Users/tanna.iori/tinklet_test/app.py \
  --source "$STREAM_URL" \
  --status-interval 2 \
  --window-name "TINKLET Live"
```

## 5. One-shot full restart (device + viewer)

```bash
DEVICE_IP=172.16.50.230
DEVICE_SERIAL="$DEVICE_IP:5555"
APP_ID=com.example.tinkletmjpeg
APP_ACTIVITY=com.example.tinkletmjpeg/.MainActivity
PYTHON_BIN=/Users/tanna.iori/tinklet_test/.venv/bin/python

adb connect "$DEVICE_SERIAL"
adb -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID"
adb -s "$DEVICE_SERIAL" shell am start -n "$APP_ACTIVITY"
pkill -f "app.py --source" || true
adb -s "$DEVICE_SERIAL" forward tcp:18080 tcp:8080
"$PYTHON_BIN" /Users/tanna.iori/tinklet_test/app.py \
  --source http://127.0.0.1:18080/stream \
  --status-interval 2 \
  --window-name "TINKLET Live"
```

## 6. Quick health checks

```bash
adb -s "$DEVICE_SERIAL" shell getprop ro.product.model
"$PYTHON_BIN" -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:18080/health', timeout=5).read().decode())"
"$PYTHON_BIN" -c "import urllib.request; r=urllib.request.urlopen('http://127.0.0.1:18080/stream', timeout=8); print(len(r.read(128)))"
```
