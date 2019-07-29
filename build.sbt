
name := "ero-detector"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.3.3",
  "com.google.cloud" % "google-cloud-vision" % "1.62.0"
)

run / fork := true
