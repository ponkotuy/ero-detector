# WD Model

現状の `wd` classifier は、wd-vit-tagger-v3 の全タグ分類結果のうち rating 4種だけを見て、最大スコアのratingを `negative` / `neutral` / `positive` に写像する仕組みです。閾値判定は入っていません。

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
6. 出力スコアからrating 4種だけを取り出し、`maxBy` で最大スコアのratingを採用します。ここが重要で、スコアが何%以上ならpositive、という閾値はありません。

## ディレクトリ分類の写像

現在の写像は `src/main/scala/Classify.scala` の `WdRateMapping` です。

```text
explicit     -> positive
questionable -> neutral
sensitive    -> neutral
general      -> negative
```

ただし `sensitive` と `questionable` はオプションで変更できます。

```sh
sbt "runMain Classify --classifier wd --wd-sensitive-rate neutral --wd-questionable-rate positive /path/to/a.jpg"
```

つまり、現状では例えば:

```text
general=0.20
sensitive=0.10
questionable=0.25
explicit=0.26
```

なら、絶対値が低くても最大が `explicit` なので `positive` に移動します。逆に:

```text
general=0.80
explicit=0.70
```

なら `general` が最大なので `negative` です。

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
