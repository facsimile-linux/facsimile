// basic project settings
name := "facsimile"

organization := "info.raack.facsimile"

version := "1.0.x-SNAPSHOT"

scalaVersion := "2.11.7"

// activate scalastyle
lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")

compileScalastyle := org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value

(compile in Compile) <<= (compile in Compile) dependsOn compileScalastyle

lazy val testScalastyle = taskKey[Unit]("testScalastyle")

testScalastyle := org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Test).toTask("").value

(test in Test) <<= (test in Test) dependsOn testScalastyle

// activate scalariform, with some format tweaks
import scalariform.formatter.preferences._

scalariformPreferences := scalariformPreferences.value
  .setPreference(AlignParameters, false)
  .setPreference(FormatXml, true)
  .setPreference(PreserveDanglingCloseParenthesis, false)
  .setPreference(SpaceInsideBrackets, false)
  .setPreference(IndentWithTabs, false)
  .setPreference(SpaceInsideParentheses, false)
  .setPreference(MultilineScaladocCommentsStartOnFirstLine, false)
  .setPreference(AlignSingleLineCaseStatements, false)
  .setPreference(CompactStringConcatenation, false)
  .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, false)
  .setPreference(IndentPackageBlocks, true)
  .setPreference(CompactControlReadability, false)
  .setPreference(SpacesWithinPatternBinders, true)
  .setPreference(DoubleIndentClassDeclaration, true)
  .setPreference(PreserveSpaceBeforeArguments, false)
  .setPreference(SpaceBeforeColon, false)
  .setPreference(RewriteArrowSymbols, false)
  .setPreference(IndentLocalDefs, false)
  .setPreference(SpacesAroundMultiImports, false)

// linting (static code analysis) - https://github.com/HairyFotr/linter
addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.12")

// use pack plugin to pack all libraries for easy deployment with roller deploytool and no nasty uberjar file collissions
packAutoSettings

libraryDependencies ++= Seq(
  "org.json4s" %% "json4s-jackson" % "[3.4.0]",
  "org.scalatest" %% "scalatest" % "[2.2.6,3)" % "test",
  "org.scalactic" %% "scalactic" % "[2.2.6,3)" % "test"
)

// for specs2
scalacOptions in Test ++= Seq("-Yrangepos")

// need this so that tests which access the testing database don't happen at the same moment and interfere with each other
parallelExecution in Test := false
