# TINKLET x MediaPipe Pose MVP

`requirements.md` に基づき、TINKLET から配信された映像を PC 側で受信し、
MediaPipe Pose で上半身の骨格推定をリアルタイム表示する MVP を実装しています。

本実装の方針:

- 映像保存なし
- 画像保存なし
- JSON ファイル保存なし
- ログファイル保存なし
- メモリ上リングバッファのみ使用

## 1. 参照した TINKLET ドキュメント

実装・運用フローの前提として、以下を参照しています。

- THINKLET開発者ポータル  
	https://fairydevicesrd.github.io/thinklet.app.developer/
- Squid Run (THINKLET RTMP ストリーミングアプリ)  
	https://github.com/FairyDevicesRD/thinklet.squid.run
- RTMP/OBS連携記事 (公式Publication)  
	https://zenn.dev/fairydevices/articles/f7b4ceefa1f13d
- THINKLET Developer Console (CWS/API 関連)  
	https://console.thinklet.fd.ai/

補足:

- TINKLET は Android SDK 互換で、通常の Android アプリ資産を活用可能
- 無画面端末のため、adb/scrcpy/キー設定による操作が重要
- RTMP 配信は Squid Run + MediaMTX 構成が公開情報として確認可能

## 2. このリポジトリの実装範囲

このリポジトリは **PC 側推論ノード** を実装しています。

- ストリーム受信 (OpenCV)
- MediaPipe Pose 推論
- 上半身ランドマークのオーバーレイ表示
- ストリーム断時の再接続
- 終了時の平均 FPS / 検出率表示

ファイル:

- `app.py`: MVP本体
- `requirements.txt`: Python依存関係

## 3. テスト向け最短セットアップ

### 3.1 インストール（最短）

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 3.2 まずは動作確認（TINKLETなしでも可）

#### ローカルカメラで直接確認

```bash
python app.py --source 0
```

#### テスト用 HTTP MJPEG サーバー経由で確認（推奨）

ターミナル1: サーバー起動
```bash
python test_mjpeg_server.py
```

ターミナル2: 推論実行
```bash
python app.py --source "http://127.0.0.1:8080/stream"
```

これでHTTP MJPEG経由のフロー全体を検証できます。

### 3.3 TINKLET 側の準備（配信方式の選択）

2つの方式から選べます：

#### 方式A: RTMP（推奨・複雑）

| 項目 | 内容 |
|------|------|
| TINKLET側 | Squid Run アプリをビルド・インストール |
| PC側 | MediaMTX を起動してRTMPサーバーを立てる |
| 設定 | key_config.json で Squid Run の起動URLを指定 |
| 利点 | OBS等と組み合わせやすい、安定している |
| 欠点 | セットアップが複数ステップ |

ステップ:
1. TINKLET に Squid Run を導入 (上記ドキュメント参照)
2. PC 上で MediaMTX を起動し、RTMP 受け口を用意
3. Squid Run の `streamUrl` / `streamKey` を設定して送信開始

#### 方式B: HTTP MJPEG（シンプル）

| 項目 | 内容 |
|------|------|
| TINKLET側 | 標準 Android アプリでカメラを HTTP で配信 |
| PC側 | URL を指定してストリーム受信 |
| 設定 | TINKLET 上の HTTP サーバー設定のみ |
| 利点 | セットアップが簡単 |
| 欠点 | 独自実装が必要、遅延が増える可能性 |

ステップ:
1. TINKLET で HTTP MJPEG サーバーを起動
2. PC から `http://<TINKLET_IP>:PORT` にアクセス

### 3.4 TINKLET 側の詳細設定 (方式A: RTMP)

要件に沿って RTMP を使う場合、次の流れがシンプルです。

1. TINKLET に Squid Run を導入 (上記ドキュメント参照)
2. PC 上で MediaMTX を起動し、RTMP 受け口を用意
3. Squid Run の `streamUrl` / `streamKey` を設定して送信開始

例:

- `streamUrl`: `rtmp://<PC_IP>:1935`
- `streamKey`: `left`

PC 側受信 URL は通常次の形式になります。

- `rtmp://<PC_IP>:1935/left`

### 3.5 TINKLET 側の詳細設定 (方式B: HTTP MJPEG)

標準 Android アプリで HTTP MJPEG をホストする場合の実装例は、
[TINKLET_MJPEG_GUIDE.md](TINKLET_MJPEG_GUIDE.md) を参照してください。

**最小実装の流れ:**
1. CameraX でフレーム取得
2. nanohttpd でHTTPサーバーを実装
3. MJPEG形式（multipart/x-mixed-replace）でストリーム配信

PC 側の受信 URL例: `http://192.168.1.100:8080/stream`

**テスト手順:**
- ローカルのテスト用サーバー (`test_mjpeg_server.py`) でフロー全体を検証後、
- TINKLET側のアプリに置き換える流れがお勧めです。

## 4. 実行方法（TINKLET ストリーム）

### 方式 A (RTMP)

```bash
python app.py --source "rtmp://<PC_IP>:1935/left"
```

ローカルの標準構成（MediaMTXを同一PCで起動）なら、引数なしでも起動できます。

```bash
python app.py
```

### 方式 B (HTTP MJPEG)

```bash
python app.py --source "http://<TINKLET_IP>:8080/stream"
```

HTTP MJPEG の場合、`--source` に URL を指定すれば動作します。

## 5. 実行オプション

- `--reconnect-delay`: 再接続待ち秒数
- `--max-failures`: 連続フレーム失敗で再接続
- `--model-complexity`: MediaPipeモデル複雑度 (0/1/2)
- `--ring-buffer-size`: 一時データ保存数 (メモリ内のみ)

終了:

- ウィンドウをアクティブにして `q` または `Esc`

## 6. 要件との対応

- リアルタイム処理: フレーム単位推論 + 即時描画
- 上半身中心推定: 肩・肘・手首を描画
- 保存禁止: ファイル出力処理を未実装
- 一時データ: リングバッファのみ使用
- 安定性: ストリーム断を検知して再接続
- 終了時指標: 平均 FPS / 推定成功率を標準出力

## 7. 注意点

- 500ms 以下の遅延はネットワーク・エンコード設定に依存します
- 15fps 以上を安定化するには、解像度を `640x480` 付近にするのが無難です
- macOS では OpenCV ウィンドウ表示のため、ターミナルに画面制御権限が必要な場合があります
