# WD Model

現状の `wd` classifier は、wd-vit-tagger-v3 のrating 4種の最大スコアを見て分類します。最大ratingが `sensitive` / `questionable` の場合は、さらに `general` と `explicit` を比較して `neutral-nega` / `neutral-posi` に分けます。

## 処理の流れ

1. `--classifier wd` を指定すると `src/main/scala/Classify.scala` の `ClassifierBackend.Wd` が選ばれ、`WdVitTaggerClassifier` が作られます。
2. モデルとタグCSVはデフォルトで以下を読みます。

   ```text
   models/wd-vit-tagger-v3/model.onnx
   models/wd-vit-tagger-v3/selected_tags.csv
   ```

   パスは `--wd-model` / `--wd-tags` で変更できます。

3. `selected_tags.csv` から `category == 9` の行だけをratingとして読みます。ここが `general`, `sensitive`, `questionable`, `explicit` に対応します。実装は `src/main/scala/Classify.scala` の `loadRatingIndexes` です。
4. 画像は白背景で正方形パディングし、モデル入力サイズへBICUBICリサイズし、BGR順の `Float` 配列にします。実装は `src/main/scala/Classify.scala` の `prepareImage` 以降です。
   WebP画像は `org.sejda.imageio:webp-imageio` のImageIO pluginで読み込みます。ImageIOで読めない画像は `Skipped unreadable image: ...` をstderrへ出してスキップします。
5. ONNX Runtimeでbatch推論します。入力shapeは `[batch, size, size, 3]` です。
6. 出力スコアからrating 4種だけを取り出し、`maxBy` で最大スコアのratingを採用します。最大ratingが `sensitive` / `questionable` の場合は、`general` と `explicit` の値を比較して分類します。

## ディレクトリ分類の写像

現在の分類ルールは `src/main/scala/Classify.scala` の `WdRateMapping` です。

```text
general                         -> negative
explicit                        -> positive
sensitive/questionable
  general > explicit            -> neutral-nega
  explicit >= general           -> neutral-posi
```

ログには `general` / `sensitive` / `questionable` / `explicit` の各スコアを表示します。

つまり、現状では例えば:

```text
general=0.20
sensitive=0.10
questionable=0.95
explicit=0.26
```

なら、最大ratingが `questionable` で、`explicit` が `general` 以上なので `neutral-posi` に移動します。逆に:

```text
general=0.40
sensitive=0.70
questionable=0.30
explicit=0.20
```

なら、最大ratingが `sensitive` で、`general` が `explicit` より高いので `neutral-nega` に移動します。

最大ratingが `general` の場合:

```text
general=0.80
explicit=0.05
```

なら `general` が最大なので `negative` です。

最大ratingが `explicit` の場合:

```text
general=0.20
explicit=0.80
```

なら `explicit` が最大なので `positive` です。

## モデル取得

`model.onnx` と `selected_tags.csv` は `project/WdModelDownload.scala` の `downloadWdModel` taskで取ります。

```sh
sbt downloadWdModel
```

## ログだけ確認する

`--dry-run` を指定すると、分類結果をログに出すだけでファイルは移動しません。

```sh
sbt "runMain Classify --classifier wd --dry-run /path/to/a.jpg"
```
