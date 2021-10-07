
name := "ero-detector"

scalaVersion := "3.0.2"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.4.1",
  "com.google.cloud" % "google-cloud-vision" % "2.0.13",
  "com.google.api" % "gax-grpc" % "2.5.0"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

run / fork := true
connectInput := true

turbo := true
