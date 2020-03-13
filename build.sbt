
name := "ero-detector"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.3.3",
  "com.google.cloud" % "google-cloud-vision" % "1.99.2",
  "com.google.api" % "gax-grpc" % "1.54.0"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xlint")

run / fork := true
connectInput := true

turbo := true
