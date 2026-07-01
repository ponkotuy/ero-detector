
name := "ero-detector"

scalaVersion := "3.8.4"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.4.9",
  "com.monovore" %% "decline" % "2.6.1",
  "com.microsoft.onnxruntime" % "onnxruntime" % "1.17.0",
  "com.google.cloud" % "google-cloud-vision" % "3.91.0"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

run / fork := true
connectInput := true

turbo := true
