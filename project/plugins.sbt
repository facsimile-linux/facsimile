logLevel := Level.Warn

// sbt-pack plugin for collecting jars for deployment
addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.7.3")

// scalastyle plugin for style checking
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.7.0")

// scalariform plugin for autoformatting
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.1")

// sbt buildinfo plugin for putting git sha into file
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.5.0")

// coveralls.io code coverage plugin
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.3.5")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")
