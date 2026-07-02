# ero-detector

画像判定器を使って、画像を `negative` / `neutral` / `positive` のディレクトリに振り分ける Scala/sbt プロジェクトです。Google Cloud Vision API の SafeSearch 検出を使う `cv` 判定器と、SmilingWolf/wd-vit-tagger-v3 を使う `wd` 判定器を実装しています。

主に `sbt run` または `sbt "runMain Classify"` で使う想定です。実行すると対象画像ファイルはコピーではなく移動されます。

## 前提

- sbt
- Java 17(mise)
- `cv` 判定器を使う場合:
  - Google Cloud Vision API を使える Google Cloud プロジェクト
  - Application Default Credentials で読めるサービスアカウントキー
- `wd` 判定器を使う場合:
  - SmilingWolf/wd-vit-tagger-v3 の `model.onnx`
  - SmilingWolf/wd-vit-tagger-v3 の `selected_tags.csv`

## セットアップ

Google Cloud Vision API の認証情報を環境変数で指定します。

```sh
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
```

設定ファイルを使うサブコマンドを動かす場合は、サンプルをコピーして編集します。

```sh
cp src/main/resources/application.conf.sample src/main/resources/application.conf
```

`Classify --classifier cv` だけを使う場合、`application.conf` は不要です。

`wd` 判定器を使う場合は、次のsbtタスクで Hugging Face から `model.onnx` と `selected_tags.csv` を取得します。

```sh
sbt downloadWdModel
```

デフォルトでは次の場所に配置します。

```text
models/wd-vit-tagger-v3/model.onnx
models/wd-vit-tagger-v3/selected_tags.csv
```

別の場所に置く場合は、実行時に `--wd-model` と `--wd-tags` で指定します。

## 画像を分類する

標準入力または引数で画像ファイルのパスを渡します。標準入力は改行区切りと空白区切りの両方を受け付けます。通常は `find` とパイプで渡すのが扱いやすいです。判定器は `--classifier` で選択します。

```sh
find /path/to/images -maxdepth 1 -type f | sbt "runMain Classify --classifier cv"
```

WD判定器に切り替える場合は `--classifier wd` を指定します。

```sh
find /path/to/images -maxdepth 1 -type f | sbt "runMain Classify --classifier wd"
```

モデルファイルの場所を指定する例:

```sh
find /path/to/images -maxdepth 1 -type f | sbt "runMain Classify --classifier wd --wd-model /path/to/model.onnx --wd-tags /path/to/selected_tags.csv"
```

引数で渡す場合は、標準入力を待たずに指定ファイルだけを処理します。

```sh
sbt "runMain Classify --classifier cv /path/to/a.jpg /path/to/b.jpg"
```

引数と標準入力を同時に使いたい場合は `--stdin` を指定します。

```sh
find /path/to/more-images -maxdepth 1 -type f | sbt "runMain Classify --classifier cv --stdin /path/to/a.jpg"
```

バッチサイズとスレッド数はオプションで変更できます。

```sh
sbt "runMain Classify --classifier cv --batch-size 16 --threads 2 /path/to/a.jpg"
```

`sbt run` でも実行できます。複数の main クラスがあるため、sbt に候補を聞かれたら `Classify` を選んでください。

```sh
sbt run
```

処理結果は画像ファイルと同じディレクトリ配下に作られる分類ディレクトリへ移動されます。

`cv` 判定器の分類基準:

- `positive`: `racy` が `VERY_LIKELY` かつ `adult` が `VERY_LIKELY`
- `neutral`: `racy` が `VERY_LIKELY` かつ `adult` が `UNLIKELY` / `POSSIBLE` / `LIKELY`
- `negative`: 上記以外

`wd` 判定器の分類基準:

- `positive`: rating が `explicit`
- `neutral`: rating が `questionable`
- `negative`: rating が `general` / `sensitive`

`sensitive` と `questionable` の分類先はオプションで変更できます。

```sh
sbt "runMain Classify --classifier wd --wd-sensitive-rate neutral --wd-questionable-rate positive /path/to/a.jpg"
```

例:

```text
/path/to/images/a.jpg
/path/to/images/b.jpg
```

を処理すると、結果に応じて次のように移動されます。

```text
/path/to/images/negative/a.jpg
/path/to/images/positive/b.jpg
```

## その他の main クラス

### Registration

`application.conf` の `path.original_images_dir` 配下から、`nonh` / `ero` / `title` という名前のディレクトリを探して画像を整理します。

```sh
sbt "runMain Registration"
```

`path.dl4j_dir` と `path.images_dir` を結合したディレクトリの下に `nonh` / `ero` などの分類先を作ります。`ero` は元画像を親ディレクトリへ戻し、分類先にはシンボリックリンクを作ります。

### MergeDirRecursive

指定したディレクトリ配下のファイルを再帰的にたどり、相対パスを `_` でつないだ名前にして指定ディレクトリ直下へ移動します。

```sh
sbt "runMain MergeDirRecursive /path/to/base-dir"
```

例: `/path/to/base-dir/a/b.jpg` は `/path/to/base-dir/a_b.jpg` へ移動されます。

## Python 補助スクリプト

`image-processing/` には OpenCV を使った重複画像削除用の古い補助スクリプトがあります。

```sh
cd image-processing
pipenv install
pipenv run python main.py /path/to/images/*.jpg
```

Hu Moments の差分がほぼ同じ画像を重複とみなし、後続のファイルを削除します。こちらも破壊的な処理なので、必要なら事前にバックアップを取ってください。

## 注意

- 画像ファイルは分類先へ移動されます。元の場所には残りません。
- Google Cloud Vision API の利用料金が発生する場合があります。
- `Classify --classifier cv` はデフォルトで 16 枚ずつ API に投げ、2 スレッドで処理します。
- `Classify --classifier wd` はローカルの ONNX Runtime で推論します。WebP画像は `webp-imageio` のImageIO pluginで読み込みます。モデルファイルは大きいため、このリポジトリには含めません。
- `src/main/resources/application.conf` は `.gitignore` 対象です。
