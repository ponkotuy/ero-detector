
name := "ero-detector"

scalaVersion := "3.10.1"

val useOnnxRuntimeGpu =
  sys.props.get("onnxruntime.gpu").exists(value => Set("1", "true", "yes").contains(value.toLowerCase))

val onnxRuntimeArtifact =
  if(useOnnxRuntimeGpu) "onnxruntime_gpu" else "onnxruntime"

val onnxRuntimeVersion = "1.20.0"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.4.10",
  "com.monovore" %% "decline" % "2.6.2",
  "com.microsoft.onnxruntime" % onnxRuntimeArtifact % onnxRuntimeVersion,
  "org.sejda.imageio" % "webp-imageio" % "0.1.6",
  "com.google.cloud" % "google-cloud-vision" % "3.101.0"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

run / fork := true
connectInput := true

turbo := true

lazy val downloadWdModel = WdModelDownload.downloadWdModel

downloadWdModel := {
  WdModelDownload.run(baseDirectory.value, streams.value.log)
}
