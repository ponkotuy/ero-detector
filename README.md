# ero-detector

Google Cloud Vision API の SafeSearch 検出を使って、画像を `negative` / `neutral` / `positive` のディレクトリに振り分ける Scala/sbt プロジェクトです。

主に `sbt run` または `sbt "runMain ExecutionCV"` で使う想定です。実行すると対象画像ファイルはコピーではなく移動されます。

## 前提

- sbt
- Java 8 / 11 / 17 あたりの LTS JDK
- Google Cloud Vision API を使える Google Cloud プロジェクト
- Application Default Credentials で読めるサービスアカウントキー

このリポジトリは `project/build.properties` で sbt `1.6.1` を使います。新しすぎる JDK では sbt/Scala 側が起動できないことがあります。手元の Java 26 では `bad constant pool index` で `sbt compile` が失敗しました。

## セットアップ

Google Cloud Vision API の認証情報を環境変数で指定します。

```sh
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
```

設定ファイルを使うサブコマンドを動かす場合は、サンプルをコピーして編集します。

```sh
cp src/main/resources/application.conf.sample src/main/resources/application.conf
```

`ExecutionCV` だけを使う場合、`application.conf` は不要です。

## SafeSearch で画像を分類する

標準入力または引数で画像ファイルのパスを渡します。通常は `find` とパイプで渡すのが扱いやすいです。

```sh
find /path/to/images -maxdepth 1 -type f | sbt "runMain ExecutionCV"
```

引数で渡す場合は、プログラムが標準入力の EOF を待つため、リダイレクトで stdin を閉じます。

```sh
sbt "runMain ExecutionCV /path/to/a.jpg /path/to/b.jpg" < /dev/null
```

`sbt run` でも実行できます。複数の main クラスがあるため、sbt に候補を聞かれたら `ExecutionCV` を選んでください。

```sh
sbt run
```

処理結果は画像ファイルと同じディレクトリ配下に作られる分類ディレクトリへ移動されます。

- `positive`: `racy` が `VERY_LIKELY` かつ `adult` が `VERY_LIKELY`
- `neutral`: `racy` が `VERY_LIKELY` かつ `adult` が `POSSIBLE` または `LIKELY`
- `negative`: 上記以外

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
- `ExecutionCV` は 16 枚ずつ API に投げ、2 スレッドで処理します。
- `src/main/resources/application.conf` は `.gitignore` 対象です。
